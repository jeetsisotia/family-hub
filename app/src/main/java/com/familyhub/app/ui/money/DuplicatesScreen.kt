package com.familyhub.app.ui.money

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.familyhub.app.core.theme.GlassCard
import com.familyhub.app.data.remote.PRIVATE_FLAG
import com.familyhub.app.data.remote.Rec
import com.familyhub.app.money.Duplicates
import com.familyhub.app.money.FinanceMonth
import com.familyhub.app.ui.AppEnv
import com.familyhub.app.ui.common.ScreenTopBar
import com.familyhub.app.ui.modules.lng
import com.familyhub.app.ui.modules.num
import com.familyhub.app.ui.modules.str
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "Not duplicates" choices and a log of removals (for Undo), kept on this phone. */
object DuplicatePrefs {
    private const val PREFS = "duplicates"

    fun excluded(ctx: Context): Set<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("excluded", emptySet()).orEmpty()

    fun exclude(ctx: Context, key: String) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putStringSet("excluded", excluded(ctx) + key).apply()
    }

    data class Removal(val at: Long, val label: String, val ids: List<Pair<String, Boolean>>, val keepId: String, val undone: Boolean)

    fun log(ctx: Context): List<Removal> = try {
        val arr = JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("log", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val ids = o.getJSONArray("ids")
            Removal(
                o.getLong("at"), o.getString("label"),
                (0 until ids.length()).map { ids.getJSONObject(it).let { x -> x.getString("id") to x.optBoolean("private") } },
                o.optString("keep"), o.optBoolean("undone")
            )
        }.sortedByDescending { it.at }
    } catch (_: Exception) { emptyList() }

    private fun saveLog(ctx: Context, list: List<Removal>) {
        val arr = JSONArray()
        list.sortedByDescending { it.at }.take(20).forEach { r ->
            arr.put(JSONObject().put("at", r.at).put("label", r.label).put("keep", r.keepId).put("undone", r.undone)
                .put("ids", JSONArray().apply { r.ids.forEach { (id, p) -> put(JSONObject().put("id", id).put("private", p)) } }))
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("log", arr.toString()).apply()
    }

    fun add(ctx: Context, r: Removal) = saveLog(ctx, log(ctx) + r)
    fun markUndone(ctx: Context, at: Long) = saveLog(ctx, log(ctx).map { if (it.at == at) it.copy(undone = true) else it })
}

/**
 * Finance → Duplicates: entries that look like one payment saved more than once, with why, how sure, and which
 * one to keep. Nothing is removed without a confirmation that says how many; removed entries go to Recently
 * deleted, remember which entry was kept, and can be put back from here.
 */
@Composable
fun DuplicatesScreen(env: AppEnv) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val expenses = env.data["expenses"].orEmpty()
    val excluded = remember(tick) { DuplicatePrefs.excluded(ctx) }
    val groups = remember(expenses, excluded) { Duplicates.find(expenses, excluded) }
    // Per group: which entry to keep and which to remove (the suggestion until changed).
    val keepChoice = remember { mutableStateMapOf<String, String>() }
    val removeChoice = remember { mutableStateMapOf<String, Set<String>>() }
    var confirm by remember { mutableStateOf<Pair<Duplicates.Group, List<Rec>>?>(null) }
    val log = remember(tick) { DuplicatePrefs.log(ctx) }
    val fmt = remember { SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()) }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Duplicates", onBack = env.onBack, compact = true)
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    "Entries with the same amount and direction within a day and a half, plus the same reference, card ending " +
                        "or shop. Amount alone is never enough. Check each one: nothing is removed until you confirm, and removed " +
                        "entries go to Recently deleted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f)
                )
            }
            if (groups.isEmpty()) item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = Color(0xFF34C759)); Spacer(Modifier.width(10.dp))
                        Text("No suspected duplicates.")
                    }
                }
            }
            items(groups, key = { it.key }) { g ->
                val keepId = keepChoice[g.key] ?: g.keep.str("id")
                val removeIds = removeChoice[g.key] ?: g.records.map { it.str("id") }.filter { it != keepId }.toSet()
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val high = g.confidence == Duplicates.Confidence.HIGH
                            Text(
                                if (high) "Very likely" else "Possible",
                                color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                    .background(if (high) Color(0xFFFF9500) else Color(0xFF8E8E93)).padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("${g.records.size} entries", style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(6.dp))
                        g.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        g.cautions.forEach {
                            Text("! $it", style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF3B30))
                        }
                        Spacer(Modifier.height(6.dp))
                        g.records.forEach { r ->
                            val id = r.str("id")
                            val isKeep = id == keepId
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(r.str("title").ifBlank { r.str("merchant") }.ifBlank { "Entry" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        fontWeight = if (isKeep) FontWeight.SemiBold else FontWeight.Normal)
                                    Text(
                                        listOf(Duplicates.origin(r), FinanceMonth.categoryOf(r), r.lng("dateMillis")?.let { fmt.format(Date(it)) })
                                            .filterNotNull().joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(inr(r.num("amountInr") ?: 0.0), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 6.dp))
                                if (isKeep) {
                                    AssistChip(onClick = {}, label = { Text("Keep") }, leadingIcon = { Icon(Icons.Rounded.Check, null) })
                                } else {
                                    TextButton(onClick = {
                                        keepChoice[g.key] = id
                                        removeChoice[g.key] = g.records.map { it.str("id") }.filter { it != id }.toSet()
                                    }) { Text("Keep this") }
                                    Checkbox(checked = id in removeIds, onCheckedChange = { on ->
                                        removeChoice[g.key] = if (on) removeIds + id else removeIds - id
                                    })
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { DuplicatePrefs.exclude(ctx, g.key); tick++ }) { Text("Not duplicates") }
                            Spacer(Modifier.width(6.dp))
                            Button(enabled = removeIds.isNotEmpty(), onClick = {
                                confirm = g to g.records.filter { it.str("id") in removeIds && it.str("id") != keepId }
                            }) { Text("Remove ${removeIds.size}") }
                        }
                    }
                }
            }
            if (log.isNotEmpty()) {
                item { Text("Removed recently", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 4.dp, top = 8.dp)) }
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            log.take(8).forEach { r ->
                                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(r.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(fmt.format(Date(r.at)) + if (r.undone) " · put back" else "", style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (!r.undone) TextButton(onClick = { undoRemoval(env, ctx, r); tick++ }) { Text("Undo") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    confirm?.let { (g, remove) ->
        val keep = g.records.firstOrNull { it.str("id") == (keepChoice[g.key] ?: g.keep.str("id")) } ?: g.keep
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Remove ${remove.size} ${if (remove.size == 1) "entry" else "entries"}?") },
            text = {
                Text(
                    "Keeps \"${keep.str("title").ifBlank { "Entry" }}\" (${inr(keep.num("amountInr") ?: 0.0)}). The other " +
                        "${remove.size} move to Recently deleted, where they can be restored, and you can undo it here. " +
                        "Totals, budgets and Insights update at once."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    removeDuplicates(env, ctx, keep, remove) { tick++ }
                }) { Text("Remove ${remove.size}", color = Color(0xFFFF3B30), fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } }
        )
    }
}

/** Removes confirmed duplicates (to Recently deleted), with permission re-checked and a log for Undo. */
fun removeDuplicates(env: AppEnv, ctx: Context, keep: Rec, remove: List<Rec>, onDone: () -> Unit = {}) {
    if (remove.isEmpty()) return
    if (!canEditMoney(env)) {
        Toast.makeText(ctx, "Finance editing is turned off for you. Ask a family admin.", Toast.LENGTH_LONG).show(); return
    }
    val now = System.currentTimeMillis()
    DuplicatePrefs.add(
        ctx, DuplicatePrefs.Removal(
            now, "${remove.size} duplicate${if (remove.size == 1) "" else "s"} of ${keep.str("title").ifBlank { "an entry" }}",
            remove.map { it.str("id") to (it[PRIVATE_FLAG] == true) }, keep.str("id"), false
        )
    )
    onDone()
    val app = ctx.applicationContext
    env.scope.launch {
        try {
            val n = env.repo.setFieldsMany(env.familyId, env.uid, "expenses", remove.map { it to Duplicates.removeFields(keep, now) })
            Toast.makeText(app, "Moved $n to Recently deleted.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(app, "Could not remove: ${e.message?.take(80)}", Toast.LENGTH_LONG).show()
        }
    }
}

private fun undoRemoval(env: AppEnv, ctx: Context, r: DuplicatePrefs.Removal) {
    DuplicatePrefs.markUndone(ctx, r.at)
    val now = System.currentTimeMillis()
    val recs = r.ids.map { (id, p) -> mapOf<String, Any?>("id" to id, PRIVATE_FLAG to p) }
    val app = ctx.applicationContext
    env.scope.launch {
        try {
            val n = env.repo.setFieldsMany(env.familyId, env.uid, "expenses", recs.map { it to Duplicates.restoreFields(now) })
            Toast.makeText(app, "Put back $n.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(app, "Undo failed: ${e.message?.take(80)}", Toast.LENGTH_LONG).show()
        }
    }
}
