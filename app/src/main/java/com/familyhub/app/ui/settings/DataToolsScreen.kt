package com.familyhub.app.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.familyhub.app.core.theme.GlassCard
import com.familyhub.app.data.remote.PRIVATE_FLAG
import com.familyhub.app.data.remote.Rec
import com.familyhub.app.ui.AppEnv
import com.familyhub.app.ui.common.ScreenTopBar
import com.familyhub.app.ui.common.formatDate
import com.familyhub.app.ui.common.plural
import com.familyhub.app.ui.modules.str
import com.google.firebase.Timestamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val COLLECTIONS = listOf("items", "events", "expenses", "tasks", "trips")

/** Health records that belong to one person ("Only me"), cleared by "Reset my health records". */
private val HEALTH_TYPES = setOf("medicine", "vital", "period", "habit", "health", "workout", "food")

// ---------- backup file format ----------

private fun toJson(v: Any?): Any = when (v) {
    null -> JSONObject.NULL
    is Timestamp -> JSONObject().put("__ts", v.toDate().time)
    is Map<*, *> -> JSONObject().also { o -> v.forEach { (k, x) -> if (k is String) o.put(k, toJson(x)) } }
    is List<*> -> JSONArray().also { a -> v.forEach { a.put(toJson(it)) } }
    is Number, is String, is Boolean -> v
    else -> v.toString()
}

private fun fromJson(v: Any?): Any? = when (v) {
    null, JSONObject.NULL -> null
    is JSONObject -> if (v.length() == 1 && v.has("__ts")) Timestamp(Date(v.getLong("__ts")))
    else HashMap<String, Any?>().also { m -> v.keys().forEach { k -> m[k] = fromJson(v.get(k)) } }
    is JSONArray -> (0 until v.length()).map { fromJson(v.get(it)) }
    else -> v
}

data class Backup(val familyName: String, val createdAt: Long, val data: Map<String, List<Rec>>, val photos: Map<String, String> = emptyMap()) {
    val count get() = data.values.sumOf { it.size }
}

private fun parseBackup(text: String): Backup {
    val o = JSONObject(text)
    require(o.optString("app") == "FamilyHub") { "This is not a Family Hub backup file." }
    val cols = o.getJSONObject("collections")
    val data = COLLECTIONS.associateWith { c ->
        val arr = cols.optJSONArray(c) ?: JSONArray()
        (0 until arr.length()).mapNotNull { i -> @Suppress("UNCHECKED_CAST") (fromJson(arr.getJSONObject(i)) as? Map<String, Any?>) }
    }
    // Pictures kept on the phone (receipts, payment proofs), by their reference.
    val photos = HashMap<String, String>()
    o.optJSONObject("photos")?.let { p -> p.keys().forEach { k -> photos[k] = p.optString(k) } }
    return Backup(o.optString("familyName"), o.optLong("createdAt"), data, photos)
}

/**
 * Backup, restore, recently deleted and resets. Backups, restores, deleting for good and resets are for
 * the family's admins (the Super Admin who created it, and Family Admins), and always ask first.
 */
@Composable
fun DataToolsScreen(env: AppEnv) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    // Everything you can see, deleted entries included (they are not in env.data).
    var raw by remember { mutableStateOf<Map<String, List<Rec>>>(emptyMap()) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(env.familyId, env.uid, reload) {
        raw = COLLECTIONS.associateWith { c ->
            try { env.repo.observeMerged(env.familyId, env.uid, c).first() } catch (e: Exception) { emptyList() }
        }
    }
    val deleted = remember(raw) {
        raw.flatMap { (c, l) -> l.filter { it["isDeleted"] == true && it["skipped"] != true && it["movedToPrivate"] != true }.map { c to it } }
            .sortedByDescending { (_, r) -> (r["deletedAt"] as? Number)?.toLong() ?: 0L }
    }

    var pendingReset by remember { mutableStateOf<String?>(null) }
    var typed by remember { mutableStateOf("") }
    var restoreFile by remember { mutableStateOf<Backup?>(null) }
    var confirmPurge by remember { mutableStateOf<Pair<String, Rec>?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }

    fun backupJson(): String {
        val cols = JSONObject()
        raw.forEach { (c, l) -> cols.put(c, JSONArray().also { a -> l.forEach { a.put(toJson(it)) } }) }
        // Pictures saved on this phone that the entries refer to go into the file too, so a backup moved to a
        // new phone brings them along. The file stays wherever you save it; nothing is uploaded by the app.
        val refs = raw.values.flatten().flatMap { r -> r.values.mapNotNull { v -> (v as? String)?.takeIf { com.familyhub.app.ui.common.LocalPhotos.isLocal(it) } } }
        val photos = JSONObject()
        com.familyhub.app.ui.common.LocalPhotos.export(context, refs).forEach { (k, v) -> photos.put(k, v) }
        return JSONObject()
            .put("app", "FamilyHub").put("version", 3).put("createdAt", System.currentTimeMillis())
            .put("familyId", env.familyId).put("familyName", env.family.name).put("byUid", env.uid)
            .put("collections", cols).put("photos", photos).toString()
    }

    /** Entries of one kind (one section of the app), deleted ones included. */
    fun ofKind(def: com.familyhub.app.ui.modules.ModuleDef): List<Rec> {
        val wanted = def.typeValue
        return raw[def.collection].orEmpty().filter { r ->
            wanted == null || (r.str("type").ifBlank { if (def.defaultType) wanted else "" }) == wanted
        }
    }

    fun recordsToReset(kind: String): Map<String, List<Rec>> = when {
        kind == "finance" -> mapOf("expenses" to raw["expenses"].orEmpty())
        kind == "health" -> mapOf("items" to raw["items"].orEmpty().filter { it[PRIVATE_FLAG] == true && it.str("type") in HEALTH_TYPES })
        kind.startsWith("kind:") -> com.familyhub.app.ui.modules.ModuleByRoute[kind.removePrefix("kind:")]
            ?.let { mapOf(it.collection to ofKind(it)) }.orEmpty()
        else -> raw.mapValues { (_, l) -> l.filter { it[PRIVATE_FLAG] != true } }
    }

    fun runReset(kind: String) {
        busy = true
        scope.launch {
            try {
                var n = 0
                for ((c, l) in recordsToReset(kind)) n += env.repo.purgeMany(env.familyId, env.uid, c, l)
                message = "Reset done: ${plural(n, "entry", "entries")} deleted. Your backup file has everything that was there."
                reload++
            } catch (e: Exception) {
                message = "Reset stopped: ${e.message}. Only family admins can reset shared data."
            }
            busy = false
        }
    }

    // Save a backup file; after a reset was asked for, the reset runs only once the backup is saved.
    val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        val reset = pendingReset
        pendingReset = null
        if (uri == null) { if (reset != null) message = "Reset cancelled: the backup was not saved."; return@rememberLauncherForActivityResult }
        busy = true
        scope.launch {
            try {
                val text = backupJson()
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("Could not open the file") }
                val n = raw.values.sumOf { it.size }
                message = "Backup saved (${plural(n, "entry", "entries")}). It includes your Only me records, so keep it somewhere safe."
                busy = false
                if (reset != null) runReset(reset)
            } catch (e: Exception) {
                busy = false
                message = "Could not save the backup: ${e.message}" + if (reset != null) ". Nothing was reset." else ""
            }
        }
    }

    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { String(it.readBytes()) } } ?: error("empty file")
                restoreFile = withContext(Dispatchers.Default) { parseBackup(text) }
            } catch (e: Exception) {
                message = "Could not read that backup: ${e.message}"
            }
        }
    }

    fun fileName() = "familyhub-backup-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date()) + ".json"

    restoreFile?.let { b ->
        val present = raw.mapValues { (_, l) -> l.mapNotNull { it["id"] as? String }.toSet() }
        val missing = b.data.mapValues { (c, l) -> l.filter { (it["id"] as? String) !in present[c].orEmpty() } }
        val shared = missing.values.sumOf { l -> l.count { it[PRIVATE_FLAG] != true } }
        val mine = missing.values.sumOf { l -> l.count { it[PRIVATE_FLAG] == true } }
        AlertDialog(
            onDismissRequest = { restoreFile = null },
            title = { Text("Restore from backup?") },
            text = {
                Text(
                    "Backup of ${b.familyName.ifBlank { "a family" }} from ${formatDate(b.createdAt)}, ${plural(b.count, "entry", "entries")}.\n\n" +
                        "Adds the ${plural(shared + mine, "entry", "entries")} that are missing now ($shared shared, $mine Only me). " +
                        "Entries already here are left as they are, so nothing you have is overwritten."
                )
            },
            confirmButton = {
                TextButton(enabled = shared + mine > 0 || b.photos.isNotEmpty(), onClick = {
                    restoreFile = null
                    busy = true
                    scope.launch {
                        val pics = withContext(Dispatchers.IO) { com.familyhub.app.ui.common.LocalPhotos.import(context, b.photos) }
                        var ok = 0
                        var failed = 0
                        for ((c, l) in missing) for (r in l) {
                            val id = r["id"] as? String ?: continue
                            try {
                                env.repo.putScoped(env.familyId, env.uid, c, id, r, r[PRIVATE_FLAG] == true); ok++
                            } catch (e: Exception) { failed++ }
                        }
                        busy = false
                        message = "Restored ${plural(ok, "entry", "entries")}" + (if (pics > 0) " and ${plural(pics, "picture", "pictures")}" else "") +
                            if (failed > 0) "; $failed could not be restored." else "."
                        reload++
                    }
                }) { Text("Restore", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { restoreFile = null }) { Text("Cancel") } }
        )
    }

    var askReset by remember { mutableStateOf<String?>(null) }
    // Choose one section to reset, such as Reminders, Credit cards or Shopping.
    var pickKind by remember { mutableStateOf(false) }
    if (pickKind) {
        val kinds = com.familyhub.app.ui.modules.AllModules
            .map { it to ofKind(it).size }
            .filter { (d, n) -> n > 0 && d.collection != "expenses" }
            .sortedBy { it.first.title }
        AlertDialog(
            onDismissRequest = { pickKind = false },
            title = { Text("Reset which kind?") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (kinds.isEmpty()) Text("There is nothing to reset.")
                    kinds.take(40).forEach { (d, n) ->
                        TextButton(onClick = { pickKind = false; askReset = "kind:" + d.route }, modifier = Modifier.fillMaxWidth()) {
                            Text(d.title, modifier = Modifier.weight(1f))
                            Text(plural(n, "entry", "entries"), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Text("Finance entries have their own reset above.", style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = { TextButton(onClick = { pickKind = false }) { Text("Cancel") } }
        )
    }
    askReset?.let { kind ->
        val what = when {
            kind == "finance" -> "all Finance entries (shared ones and your Only me ones)"
            kind == "health" -> "your own health records (medicines, vitals, periods, habits, workouts, doctor records)"
            kind.startsWith("kind:") -> "all ${com.familyhub.app.ui.modules.ModuleByRoute[kind.removePrefix("kind:")]?.title ?: "these"} entries (shared ones and your Only me ones)"
            else -> "everything shared in ${env.family.name} (your Only me records stay)"
        }
        val n = recordsToReset(kind).values.sumOf { it.size }
        AlertDialog(
            onDismissRequest = { askReset = null; typed = "" },
            title = { Text("Reset ${plural(n, "entry", "entries")}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This deletes $what for good, for everyone in the family. First you save a backup file; the reset runs only after it is saved.")
                    Text("Type RESET to continue.", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(typed, { typed = it }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = typed.trim() == "RESET" && n > 0, onClick = {
                    askReset = null
                    typed = ""
                    pendingReset = kind
                    saveBackup.launch(fileName())
                }) { Text("Back up and reset", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { askReset = null; typed = "" }) { Text("Cancel") } }
        )
    }
    confirmPurge?.let { (c, r) ->
        AlertDialog(
            onDismissRequest = { confirmPurge = null },
            title = { Text("Delete for good?") },
            text = { Text("\"${r.str("title").ifBlank { r.str("name") }.ifBlank { "This entry" }}\" will be removed for everyone and cannot be brought back.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmPurge = null
                    scope.launch {
                        try { env.repo.purgeScoped(env.familyId, env.uid, c, r); reload++ } catch (e: Exception) { message = "Could not delete: ${e.message}" }
                    }
                }) { Text("Delete for good", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmPurge = null }) { Text("Cancel") } }
        )
    }
    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Empty Recently deleted?") },
            text = { Text("${plural(deleted.size, "entry", "entries")} will be removed for everyone and cannot be brought back. Back up first if you might need them.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmEmpty = false
                    busy = true
                    scope.launch {
                        try {
                            deleted.groupBy({ it.first }, { it.second }).forEach { (c, l) -> env.repo.purgeMany(env.familyId, env.uid, c, l) }
                            message = "Recently deleted is empty."
                        } catch (e: Exception) { message = "Could not empty it: ${e.message}" }
                        busy = false
                        reload++
                    }
                }) { Text("Delete all for good", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Cancel") } }
        )
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Data & backup", onBack = env.onBack)
        LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(
                    if (env.isAdmin) "You are ${if (env.isOwner) "the Super Admin" else "a Family Admin"} of ${env.family.name}."
                    else "Backups, restores, resets and deleting for good are done by ${env.family.name}'s admins.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            message?.let { m -> item { Text(m, fontWeight = FontWeight.Medium) } }
            if (env.isAdmin) {
                item {
                    ToolCard(Icons.Rounded.CloudDownload, "Back up", "Save every entry you can see (shared and your Only me ones) to a file on this phone or Drive.") {
                        Button(onClick = { saveBackup.launch(fileName()) }, enabled = !busy && raw.isNotEmpty()) { Text("Save backup file") }
                    }
                }
                item {
                    ToolCard(Icons.Rounded.Restore, "Restore", "Add back entries from a backup file. Entries you already have are not changed.") {
                        OutlinedButton(onClick = { openBackup.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }, enabled = !busy) { Text("Choose backup file") }
                    }
                }
                item {
                    ToolCard(Icons.Rounded.DeleteSweep, "Reset", "Start fresh. A backup file is saved first, and you type RESET to confirm.") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { askReset = "finance" }, enabled = !busy) { Text("Reset Finance entries") }
                            OutlinedButton(onClick = { askReset = "health" }, enabled = !busy) { Text("Reset my health records") }
                            OutlinedButton(onClick = { pickKind = true }, enabled = !busy) { Text("Reset one kind (reminders, cards…)") }
                            OutlinedButton(onClick = { askReset = "all" }, enabled = !busy) { Text("Reset all shared family data", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("RECENTLY DELETED (${deleted.size})", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    if (env.isAdmin && deleted.isNotEmpty()) TextButton(onClick = { confirmEmpty = true }) { Text("Empty", color = MaterialTheme.colorScheme.error) }
                }
            }
            if (deleted.isEmpty()) item { Text("Nothing here. Shared entries anyone deletes wait here until an admin removes them for good.", style = MaterialTheme.typography.bodySmall) }
            items(deleted.take(300)) { (c, r) ->
                GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.str("title").ifBlank { r.str("name") }.ifBlank { r.str("destination") }.ifBlank { "Untitled" }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(
                                    when (c) { "expenses" -> "Finance"; "events" -> "Calendar"; "tasks" -> "Tasks"; "trips" -> "Trips"; else -> r.str("type").replaceFirstChar { it.uppercase() } },
                                    (r["deletedAt"] as? Number)?.toLong()?.let { "deleted " + formatDate(it) },
                                    if (r[PRIVATE_FLAG] == true) "Only me" else null
                                ).joinToString(" • "),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        TextButton(onClick = {
                            scope.launch {
                                try { env.repo.restoreScoped(env.familyId, env.uid, c, r); reload++; Toast.makeText(context, "Restored", Toast.LENGTH_SHORT).show() }
                                catch (e: Exception) { message = "Could not restore: ${e.message}" }
                            }
                        }) { Text("Restore") }
                        if (env.isAdmin) IconButton(onClick = { confirmPurge = c to r }) { Icon(Icons.Rounded.DeleteForever, "Delete for good", tint = Color(0xFFD93025)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, actions: @Composable () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null); Spacer(Modifier.width(8.dp)); Text(title, fontWeight = FontWeight.Bold)
            }
            Text(text, style = MaterialTheme.typography.bodySmall)
            actions()
        }
    }
}
