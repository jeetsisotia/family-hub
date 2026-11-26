package com.familyhub.app.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.familyhub.app.core.theme.tileBrush
import java.io.ByteArrayOutputStream

/**
 * Reads pictures that older versions stored as small JPEG text inside Firestore records. New pictures are
 * no longer stored that way (see [LocalPhotos]); [encode] is kept only for code that still needs it.
 */
object PhotoCodec {

    fun encode(context: Context, uri: Uri, maxSize: Int, quality: Int = 80): String? {
        return try {
            val bitmap: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                    val w = info.size.width
                    val h = info.size.height
                    val scale = (maxSize * 2f) / maxOf(w, h)
                    if (scale < 1f) {
                        decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                    }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
            }
            // Crop to a centred square, then shrink.
            val side = minOf(bitmap.width, bitmap.height)
            val square = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
            val target = minOf(maxSize, side)
            val scaled = Bitmap.createScaledBitmap(square, target, target, true)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Throwable) {
            // Includes running out of memory on a very large photo.
            null
        }
    }

    fun decode(data: String?): ImageBitmap? {
        if (data.isNullOrBlank()) return null
        return try {
            val bytes = Base64.decode(data, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Pictures added from now on (receipts, payment proofs, profile photos) are saved as files in the app's own
 * private storage on this phone. Firestore keeps only a short reference ("local:<id>"), never the picture itself,
 * so nothing is uploaded and no paid storage is used. Older pictures saved inside records still show.
 * Another phone sees the reference but not the picture; the screen says the photo is on the phone that added it.
 * The pictures are included in a backup file, so they move with a backup to a new phone.
 */
object LocalPhotos {
    const val PREFIX = "local:"
    private val ID = Regex("[A-Za-z0-9-]{8,64}")

    fun isLocal(v: String?): Boolean = v?.startsWith(PREFIX) == true

    private fun dir(ctx: Context): java.io.File = java.io.File(ctx.filesDir, "photos").apply { mkdirs() }

    fun file(ctx: Context, ref: String?): java.io.File? {
        if (!isLocal(ref)) return null
        val id = ref!!.removePrefix(PREFIX)
        return if (ID.matches(id)) java.io.File(dir(ctx), "$id.jpg") else null
    }

    fun exists(ctx: Context, ref: String?): Boolean = file(ctx, ref)?.exists() == true

    /** Shrinks the picture (longest side [maxSide], aspect kept), saves it and returns its reference, or null. */
    fun save(ctx: Context, uri: Uri, maxSide: Int = 1600, quality: Int = 80): String? = try {
        val bitmap: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { decoder, info, _ ->
                val scale = maxSide.toFloat() / maxOf(info.size.width, info.size.height)
                if (scale < 1f) decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val raw = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: throw IllegalStateException("unreadable")
            val scale = maxSide.toFloat() / maxOf(raw.width, raw.height)
            if (scale < 1f) Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt().coerceAtLeast(1), (raw.height * scale).toInt().coerceAtLeast(1), true) else raw
        }
        val id = java.util.UUID.randomUUID().toString()
        java.io.File(dir(ctx), "$id.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        PREFIX + id
    } catch (e: Throwable) {
        // Includes running out of memory on a very large photo.
        null
    }

    fun load(ctx: Context, ref: String?): ImageBitmap? = try {
        file(ctx, ref)?.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
    } catch (e: Throwable) {
        null
    }

    fun delete(ctx: Context, ref: String?) {
        try { file(ctx, ref)?.delete() } catch (e: Exception) { }
    }

    /** The pictures behind [refs] that are on this phone, for a backup file (reference to Base64 JPEG). */
    fun export(ctx: Context, refs: Collection<String>): Map<String, String> = refs.distinct().mapNotNull { ref ->
        val f = file(ctx, ref)?.takeIf { it.exists() } ?: return@mapNotNull null
        try { ref to Base64.encodeToString(f.readBytes(), Base64.NO_WRAP) } catch (e: Exception) { null }
    }.toMap()

    /** Puts pictures from a backup file back on this phone; pictures already here are kept. Returns how many. */
    fun import(ctx: Context, photos: Map<String, String>): Int = photos.count { (ref, data) ->
        val f = file(ctx, ref) ?: return@count false
        if (f.exists()) return@count false
        try { f.writeBytes(Base64.decode(data, Base64.DEFAULT)); true } catch (e: Exception) { false }
    }
}

/** A picture saved with a record: a local file reference (new) or an older picture stored in the record. */
@Composable
fun rememberPhoto(data: String?): ImageBitmap? {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    return remember(data) { if (LocalPhotos.isLocal(data)) LocalPhotos.load(ctx, data) else PhotoCodec.decode(data) }
}

/** Shown where a record's picture was saved on another phone. */
fun photoElsewhereText(): String = "The picture is saved on the phone that added it."

/** A round picture of a person: their uploaded photo, else their Google photo, else their initials. */
@Composable
fun PersonAvatar(
    name: String,
    photoData: String?,
    photoUrl: String?,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val img = rememberPhoto(photoData)
    val initials = name.trim().split(" ").filter { it.isNotBlank() }.take(2)
        .joinToString("") { it.first().uppercase() }.ifBlank { "?" }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(tileBrush(name.length)),
        contentAlignment = Alignment.Center
    ) {
        when {
            img != null -> Image(
                bitmap = img,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            !photoUrl.isNullOrBlank() -> AsyncImage(
                model = photoUrl,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            else -> Text(
                text = initials,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.36f).sp
            )
        }
    }
}
