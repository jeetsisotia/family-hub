package com.familyhub.app.ui.modules

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.familyhub.app.core.theme.GlassCard
import com.familyhub.app.core.theme.GlassInputField
import com.familyhub.app.data.remote.MemoryRepository
import com.familyhub.app.data.remote.PRIVATE_FLAG
import com.familyhub.app.data.remote.Rec
import com.familyhub.app.family.AccessState
import com.familyhub.app.family.MemoryMath
import com.familyhub.app.ui.AppEnv
import com.familyhub.app.ui.common.ScreenTopBar
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Asks the next record screen to open this record's edit form (used by detail screens' Edit button). */
object EditRequest { val id = kotlinx.coroutines.flow.MutableStateFlow<String?>(null) }

/** One memory: its story, a photo album, reactions and comments from the family. */
@Composable
fun MemoryDetailScreen(env: AppEnv, memoryId: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rec = env.data["items"].orEmpty().firstOrNull { it.str("id") == memoryId && it.str("type") == "memory" }
    val access by AccessState.flow.collectAsState()
    if (rec == null) {
        Column(Modifier.fillMaxSize()) {
            ScreenTopBar(title = "Memory", onBack = env.onBack)
            Text("This memory is no longer here.", modifier = Modifier.padding(24.dp))
        }
        return
    }
    val private = rec[PRIVATE_FLAG] == true
    val canEdit = private || access.canEdit("memories")
    val photos = MemoryRepository.photos(rec)
    val notes by remember(env.familyId, memoryId) { MemoryRepository.observeNotes(env.familyId, memoryId) }.collectAsState(initial = emptyList())
    val comments = notes.filter { it.kind == "comment" }
    val reactions = notes.filter { it.kind == "reaction" }.associate { it.uid to it.text }
    var viewing by remember { mutableStateOf<MemoryRepository.Photo?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var comment by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MemoryMath.MAX_PHOTOS)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        if (!MemoryMath.canAddPhotos(photos.size, uris.size)) { error = "A memory holds at most ${MemoryMath.MAX_PHOTOS} photos."; return@rememberLauncherForActivityResult }
        scope.launch {
            busy = true
            try {
                val added = uris.map { u ->
                    val p = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.familyhub.app.ui.chat.ChatPick.photo(ctx, u) }
                    MemoryRepository.savePhoto(env.familyId, env.uid, memoryId, private, p.bytes, p.w, p.h)
                }
                env.repo.setFieldsScoped(env.familyId, env.uid, "items", rec,
                    mapOf("photos" to com.google.firebase.firestore.FieldValue.arrayUnion(*added.toTypedArray()), "updatedAt" to System.currentTimeMillis()))
            } catch (e: Exception) { error = "Could not add the photo: ${e.message}" } finally { busy = false }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = rec.str("title").ifBlank { "Memory" }, onBack = env.onBack) {
            if (canEdit) TextButton(onClick = { EditRequest.id.value = memoryId; env.navigate("memories") }) { Text("Edit") }
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            item {
                val date = rec.lng("dateMillis")?.let { SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(it)) }
                Text(listOfNotNull(date, rec.str("album").takeIf { it.isNotBlank() }?.let { "Album: $it" }, rec.str("category").takeIf { it.isNotBlank() })
                    .joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                if (rec.str("notes").isNotBlank()) Text(rec.str("notes"), modifier = Modifier.padding(top = 8.dp))
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Photos (${photos.size})", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (canEdit) TextButton(enabled = !busy, onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Icon(Icons.Rounded.AddAPhoto, null); Spacer(Modifier.width(6.dp)); Text("Add photos") }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (photos.isEmpty()) Text(if (canEdit) "No photos yet. Add some to make this an album." else "No photos yet.",
                    style = MaterialTheme.typography.bodySmall)
                else Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    photos.forEach { p -> MemoryThumb(env, private, p) { viewing = p } }
                }
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val mine = reactions[env.uid]
                    MemoryMath.REACTIONS.forEach { e ->
                        val n = reactions.values.count { it == e }
                        com.familyhub.app.core.theme.GlassFilterChip(
                            selected = mine == e,
                            onClick = { scope.launch { runCatching { MemoryRepository.react(env.familyId, memoryId, env.uid, env.profile.name, e, mine) }
                                .onFailure { error = "Could not react: ${it.message}" } } },
                            label = { Text(if (n > 0) "$e $n" else e) }
                        )
                    }
                }
                if (reactions.isNotEmpty()) Text(
                    reactions.entries.joinToString(", ") { (u, e) -> (env.members.firstOrNull { it.uid == u }?.name ?: notes.firstOrNull { it.uid == u }?.name ?: "Someone") + " " + e },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            item { Text("Comments (${comments.size})", fontWeight = FontWeight.SemiBold) }
            items(comments, key = { it.id }) { c ->
                GlassCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name.ifBlank { "Someone" }, fontWeight = FontWeight.Medium)
                            Text(c.text)
                        }
                        if (c.uid == env.uid || env.isAdmin) IconButton(onClick = { scope.launch { runCatching { MemoryRepository.deleteNote(env.familyId, c.id) } } }) {
                            Icon(Icons.Rounded.DeleteOutline, "Delete comment")
                        }
                    }
                }
            }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        }
        if (!private) Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp), verticalAlignment = Alignment.Bottom) {
            GlassInputField(value = comment, onValueChange = { comment = it }, placeholder = { Text("Add a comment") },
                maxLines = 4, shape = RoundedCornerShape(22.dp), modifier = Modifier.weight(1f))
            IconButton(onClick = {
                val t = MemoryMath.cleanComment(comment) ?: return@IconButton
                comment = ""
                scope.launch { runCatching { MemoryRepository.addComment(env.familyId, memoryId, env.uid, env.profile.name, t) }.onFailure { error = "Could not post: ${it.message}" } }
            }) { Icon(Icons.Rounded.Send, "Post comment") }
        }
    }
    viewing?.let { p ->
        Dialog(onDismissRequest = { viewing = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                val img = rememberMemoryImage(env, private, p)
                if (img != null) Image(img, "Photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                else CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp)) {
                    IconButton(onClick = { viewing = null }) { Icon(Icons.Rounded.Close, "Close", tint = Color.White) }
                    Spacer(Modifier.weight(1f))
                    if (canEdit && (p.byUid == env.uid || env.isAdmin)) IconButton(onClick = {
                        viewing = null
                        scope.launch {
                            runCatching {
                                env.repo.setFieldsScoped(env.familyId, env.uid, "items", rec,
                                    mapOf("photos" to (rec["photos"] as? List<*>).orEmpty().filterNot { (it as? Map<*, *>)?.get("id") == p.id },
                                        "updatedAt" to System.currentTimeMillis()))
                                MemoryRepository.deletePhoto(env.familyId, env.uid, private, p)
                            }.onFailure { error = "Could not remove the photo: ${it.message}" }
                        }
                    }) { Icon(Icons.Rounded.Delete, "Remove photo", tint = Color.White) }
                }
            }
        }
    }
}

@Composable
private fun rememberMemoryImage(env: AppEnv, private: Boolean, p: MemoryRepository.Photo): ImageBitmap? {
    val img by produceState<ImageBitmap?>(null, p.id) {
        value = runCatching {
            val b = MemoryRepository.loadPhoto(env.familyId, env.uid, private, p)
            BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap()
        }.getOrNull()
    }
    return img
}

@Composable
private fun MemoryThumb(env: AppEnv, private: Boolean, p: MemoryRepository.Photo, onClick: () -> Unit) {
    val img = rememberMemoryImage(env, private, p)
    Box(Modifier.size(120.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        if (img != null) Image(img, "Photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else CircularProgressIndicator(Modifier.size(24.dp))
    }
}

/** Memories list header: "On this day" from earlier years and the family's albums. */
@Composable
fun MemoriesHeader(records: List<Rec>, onOpen: ((String) -> Unit)?) {
    val mems = records.map { MemoryMath.Mem(it.str("id"), it.str("title"), it.lng("dateMillis"), it.str("album")) }
    val otd = remember(mems) { MemoryMath.onThisDay(mems, System.currentTimeMillis()) }
    val albums = remember(mems) { MemoryMath.albums(mems) }
    if (otd.isEmpty() && albums.isEmpty()) return
    GlassCard(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (otd.isNotEmpty()) {
                Text("On this day", fontWeight = FontWeight.SemiBold)
                otd.take(3).forEach { (m, y) ->
                    Text("${m.title} · ${MemoryMath.yearsAgoText(y)}", modifier = Modifier.clickable(enabled = onOpen != null) { onOpen?.invoke("memory:${m.id}") })
                }
            }
            if (albums.isNotEmpty()) {
                Text("Albums", fontWeight = FontWeight.SemiBold)
                Text(albums.joinToString("  ·  ") { "${it.first} (${it.second})" }, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
