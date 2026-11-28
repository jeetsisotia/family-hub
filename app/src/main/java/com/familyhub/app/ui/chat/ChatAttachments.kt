package com.familyhub.app.ui.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.familyhub.app.data.remote.ChatMedia
import com.familyhub.app.data.remote.ChatRepository
import com.familyhub.app.data.remote.ChatRepository.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/** Received attachments are kept in the app's cache (cleared by Android when space runs low). */
object ChatFileCache {
    fun file(ctx: Context, a: Attachment): File =
        File(File(ctx.cacheDir, "chat").apply { mkdirs() }, a.id + "_" + ChatMedia.safeName(a.name, a.kind, a.mime))

    /** The file on this phone, fetched from the chat once (Firestore also keeps the parts for offline use). */
    suspend fun get(ctx: Context, familyId: String, chatId: String, a: Attachment): File = withContext(Dispatchers.IO) {
        val f = file(ctx, a)
        if (!f.exists() || f.length() == 0L) {
            val bytes = ChatRepository.loadAttachment(familyId, chatId, a)
            val tmp = File(f.parentFile, f.name + ".part")
            tmp.writeBytes(bytes); tmp.renameTo(f)
        }
        f
    }

    fun uri(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
}

/** Reading what the person picked, and shrinking photos before they are sent. */
object ChatPick {
    data class Picked(val bytes: ByteArray, val name: String, val mime: String, val w: Int = 0, val h: Int = 0)

    fun nameAndSize(ctx: Context, uri: Uri): Pair<String, Long> {
        var name = ""; var size = -1L
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0).orEmpty()
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        return name to size
    }

    /** A photo, at most [ChatMedia.PHOTO_MAX_SIDE] pixels on its long side, as JPEG (turned the right way up). */
    fun photo(ctx: Context, uri: Uri): Picked {
        val bmp: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
                val s = info.size
                val scale = minOf(1.0, ChatMedia.PHOTO_MAX_SIDE.toDouble() / maxOf(s.width, s.height))
                d.setTargetSize((s.width * scale).toInt().coerceAtLeast(1), (s.height * scale).toInt().coerceAtLeast(1))
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= ChatMedia.PHOTO_MAX_SIDE) sample *= 2
            val raw = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                ?: error("Could not read this photo")
            val scale = minOf(1.0, ChatMedia.PHOTO_MAX_SIDE.toDouble() / maxOf(raw.width, raw.height))
            if (scale < 1.0) Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt(), (raw.height * scale).toInt(), true) else raw
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 78, out)
        return Picked(out.toByteArray(), "photo.jpg", "image/jpeg", bmp.width, bmp.height)
    }

    /** A document as it is; refused if larger than chat allows. */
    fun file(ctx: Context, uri: Uri): Picked {
        val (name, size) = nameAndSize(ctx, uri)
        if (size > ChatMedia.MAX_BYTES) error("This file is ${ChatMedia.sizeText(size)}. Chat can send files up to ${ChatMedia.sizeText(ChatMedia.MAX_BYTES.toLong())}.")
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Could not read this file")
        if (bytes.size > ChatMedia.MAX_BYTES) error("This file is ${ChatMedia.sizeText(bytes.size.toLong())}. Chat can send files up to ${ChatMedia.sizeText(ChatMedia.MAX_BYTES.toLong())}.")
        return Picked(bytes, name.ifBlank { "file" }, ctx.contentResolver.getType(uri) ?: "application/octet-stream")
    }
}

/** Records a voice message (AAC, small enough for chat) and samples its loudness for the waveform. */
class VoiceRecorder(private val ctx: Context) {
    private var rec: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    val samples = mutableStateListOf<Int>()

    fun start() {
        val f = File(ctx.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioChannels(1)
        r.setAudioSamplingRate(22_050)
        r.setAudioEncodingBitRate(24_000)
        r.setMaxDuration(ChatMedia.MAX_VOICE_MS.toInt())
        r.setOutputFile(f.absolutePath)
        r.prepare(); r.start()
        rec = r; file = f; startedAt = System.currentTimeMillis(); samples.clear()
    }

    fun sample() { rec?.let { r -> runCatching { samples.add(r.maxAmplitude) } } }
    fun elapsed(): Long = if (rec == null) 0 else System.currentTimeMillis() - startedAt

    /** Stops and returns the recording (null if too short to keep). */
    fun stop(): Triple<ByteArray, Long, List<Int>>? {
        val r = rec ?: return null
        val ms = elapsed()
        rec = null
        runCatching { r.stop() }
        r.release()
        val f = file ?: return null
        file = null
        val bytes = runCatching { f.readBytes() }.getOrNull()
        f.delete()
        if (bytes == null || bytes.isEmpty() || ms < 700) return null
        return Triple(bytes, ms, ChatMedia.wave(samples.toList()))
    }

    fun cancel() {
        rec?.let { runCatching { it.stop() }; it.release() }
        rec = null; file?.delete(); file = null
    }
}

/** Loads an attachment (once) and gives its file, or the error. */
@Composable
private fun rememberAttachmentFile(familyId: String, chatId: String, a: Attachment): State<Result<File>?> {
    val ctx = LocalContext.current
    return produceState<Result<File>?>(initialValue = null, a.id) {
        value = runCatching { ChatFileCache.get(ctx, familyId, chatId, a) }
    }
}

private fun thumbnail(f: File, maxSide: Int): ImageBitmap? = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, o)
    var s = 1
    while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
    BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
}.getOrNull()

/** What a message shows for its attachment. */
@Composable
fun AttachmentView(familyId: String, chatId: String, a: Attachment, fg: Color, onOpenPhoto: (Attachment) -> Unit) {
    when (a.kind) {
        "photo" -> PhotoView(familyId, chatId, a, onOpenPhoto)
        "voice" -> VoicePlayer(familyId, chatId, a, fg)
        else -> FileCard(familyId, chatId, a, fg)
    }
}

@Composable
private fun PhotoView(familyId: String, chatId: String, a: Attachment, onOpen: (Attachment) -> Unit) {
    val file by rememberAttachmentFile(familyId, chatId, a)
    val img = remember(file) { file?.getOrNull()?.let { thumbnail(it, 720) } }
    val ratio = if (a.w > 0 && a.h > 0) (a.w.toFloat() / a.h).coerceIn(0.5f, 2f) else 4f / 3f
    Box(
        Modifier.width(230.dp).aspectRatio(ratio).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.08f))
            .clickable(enabled = img != null) { onOpen(a) },
        contentAlignment = Alignment.Center
    ) {
        when {
            img != null -> Image(img, contentDescription = "Photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            file?.isFailure == true -> Text("Photo not available", style = MaterialTheme.typography.labelMedium)
            else -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
}

/** Full-screen photo: pinch to zoom, drag to move, share. */
@Composable
fun PhotoViewer(familyId: String, chatId: String, a: Attachment, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val file by rememberAttachmentFile(familyId, chatId, a)
    val img = remember(file) { file?.getOrNull()?.let { thumbnail(it, 2048) } }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { z, pan, _ ->
        scale = (scale * z).coerceIn(1f, 5f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (img != null) Image(
                img, contentDescription = "Photo", contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().transformable(state)
                    .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
            ) else CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp)) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close", tint = Color.White) }
                Spacer(Modifier.weight(1f))
                file?.getOrNull()?.let { f ->
                    IconButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType(a.mime.ifBlank { "image/jpeg" })
                            .putExtra(Intent.EXTRA_STREAM, ChatFileCache.uri(ctx, f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        runCatching { ctx.startActivity(Intent.createChooser(send, "Share photo")) }
                    }) { Icon(Icons.Rounded.Share, "Share", tint = Color.White) }
                }
            }
        }
    }
}

@Composable
private fun VoicePlayer(familyId: String, chatId: String, a: Attachment, fg: Color) {
    val ctx = LocalContext.current
    val file by rememberAttachmentFile(familyId, chatId, a)
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var speed by remember { mutableFloatStateOf(1f) }
    DisposableEffect(a.id) { onDispose { player?.release(); player = null } }
    LaunchedEffect(playing) {
        while (playing) {
            val p = player ?: break
            runCatching { if (p.duration > 0) progress = p.currentPosition.toFloat() / p.duration }
            delay(80)
        }
    }
    fun applySpeed(p: MediaPlayer) { runCatching { p.playbackParams = p.playbackParams.setSpeed(speed) } }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.widthIn(min = 210.dp)) {
        IconButton(
            onClick = {
                val f = file?.getOrNull() ?: return@IconButton
                val p = player ?: runCatching {
                    MediaPlayer().apply {
                        setDataSource(f.path); prepare()
                        setOnCompletionListener { playing = false; progress = 0f; seekTo(0) }
                    }
                }.getOrElse { Toast.makeText(ctx, "Could not play this voice message", Toast.LENGTH_LONG).show(); null }.also { player = it }
                if (p != null) {
                    if (playing) { p.pause(); playing = false } else { applySpeed(p); p.start(); playing = true }
                }
            },
            enabled = file?.isSuccess == true,
            modifier = Modifier.size(40.dp).clip(CircleShape).background(fg.copy(alpha = 0.15f))
        ) {
            if (file == null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = fg)
            else Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play voice message", tint = fg)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            val bars = a.wave.ifEmpty { List(ChatMedia.WAVE_BARS) { 30 } }
            Canvas(Modifier.fillMaxWidth().height(28.dp)) {
                val gap = 2.dp.toPx()
                val w = ((size.width - gap * (bars.size - 1)) / bars.size).coerceAtLeast(1f)
                bars.forEachIndexed { i, v ->
                    val h = size.height * v / 100f
                    val done = (i + 0.5f) / bars.size <= progress
                    drawRoundRect(
                        color = if (done) fg else fg.copy(alpha = 0.4f),
                        topLeft = Offset(i * (w + gap), (size.height - h) / 2),
                        size = Size(w, h), cornerRadius = CornerRadius(w / 2, w / 2)
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ChatMedia.durationText(if (playing) (a.durationMs * progress).toLong() else a.durationMs), style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.8f))
                Spacer(Modifier.weight(1f))
                Text(
                    (if (speed == 1f) "1" else speed.toString()) + "×",
                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = fg,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(fg.copy(alpha = 0.15f))
                        .clickable { speed = when (speed) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }; player?.let { if (playing) applySpeed(it) } }
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                )
            }
        }
    }
}

@Composable
private fun FileCard(familyId: String, chatId: String, a: Attachment, fg: Color) {
    val ctx = LocalContext.current
    var opening by remember { mutableStateOf(false) }
    var asked by remember { mutableStateOf(false) }
    val file by if (asked) rememberAttachmentFile(familyId, chatId, a) else remember { mutableStateOf<Result<File>?>(null) }
    LaunchedEffect(file) {
        val f = file ?: return@LaunchedEffect
        opening = false
        f.onSuccess { open(ctx, it, a) }.onFailure { Toast.makeText(ctx, "Could not open: ${it.message}", Toast.LENGTH_LONG).show(); asked = false }
    }
    Row(
        Modifier.widthIn(min = 200.dp, max = 260.dp).clip(RoundedCornerShape(12.dp)).background(fg.copy(alpha = 0.12f))
            .clickable { if (file?.isSuccess == true) open(ctx, file!!.getOrThrow(), a) else { opening = true; asked = true } }.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(if (a.mime == "application/pdf") Icons.Rounded.PictureAsPdf else Icons.Rounded.Description, null, tint = fg, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(a.name.ifBlank { "File" }, color = fg, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(ChatMedia.sizeText(a.size) + if (opening) " · opening…" else " · tap to open", color = fg.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun open(ctx: Context, f: File, a: Attachment) {
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(ChatFileCache.uri(ctx, f), a.mime.ifBlank { "*/*" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try { ctx.startActivity(view) } catch (e: ActivityNotFoundException) { Toast.makeText(ctx, "No app on this phone can open this file", Toast.LENGTH_LONG).show() }
}

/** Shared media: every photo, voice message and file in the messages loaded on screen. */
@Composable
fun MediaGallery(familyId: String, chatId: String, messages: List<ChatRepository.Message>, onOpenPhoto: (Attachment) -> Unit) {
    val withFiles = messages.filter { !it.deletedForAll && it.attachment != null }.sortedByDescending { it.at }
    val photos = withFiles.mapNotNull { m -> m.attachment?.takeIf { it.kind == "photo" } }
    val others = withFiles.filter { it.attachment?.kind != "photo" }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        Text("Photos, voice messages and files", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("From the messages loaded on screen.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Spacer(Modifier.height(10.dp))
        if (withFiles.isEmpty()) Text("Nothing shared in this chat yet.", style = MaterialTheme.typography.bodyMedium)
        if (photos.isNotEmpty()) {
            LazyVerticalGrid(GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.heightIn(max = 360.dp)) {
                items(photos, key = { it.id }) { a ->
                    val file by rememberAttachmentFile(familyId, chatId, a)
                    val img = remember(file) { file?.getOrNull()?.let { thumbnail(it, 300) } }
                    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.08f)).clickable { onOpenPhoto(a) }) {
                        if (img != null) Image(img, "Photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        others.forEach { m ->
            val a = m.attachment!!
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                AttachmentView(familyId, chatId, a, MaterialTheme.colorScheme.onSurface, onOpenPhoto)
                Spacer(Modifier.width(8.dp))
                Text(m.senderName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
    }
}
