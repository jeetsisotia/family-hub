package com.familyhub.app.ui.ai

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.familyhub.app.ui.AppEnv
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A record of every action the assistant carried out (or tried to), kept on this phone: what, when, and
 * whether it really succeeded. Shown in AI settings. Nothing here is sent anywhere.
 */
object AiAudit {
    private const val PREFS = "ai_audit"
    private const val KEEP = 50

    data class Entry(val at: Long, val what: String, val ok: Boolean, val error: String)

    fun record(ctx: Context, a: AiAction, ok: Boolean, error: String = "") {
        try {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val arr = JSONArray(p.getString("log", "[]"))
            val what = actionLabel(a).lines().firstOrNull().orEmpty().take(120)
            arr.put(JSONObject().put("at", System.currentTimeMillis()).put("what", what).put("ok", ok).put("error", error.take(120)))
            val keep = JSONArray()
            val start = maxOf(0, arr.length() - KEEP)
            for (i in start until arr.length()) keep.put(arr.get(i))
            p.edit().putString("log", keep.toString()).apply()
        } catch (_: Exception) { }
    }

    fun list(ctx: Context): List<Entry> = try {
        val arr = JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("log", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(o.getLong("at"), o.optString("what"), o.optBoolean("ok"), o.optString("error"))
        }.sortedByDescending { it.at }
    } catch (_: Exception) { emptyList() }
}

/** Kinds of action that change money records: they need Finance editing to be on for the person. */
private val MONEY_KINDS = setOf("expense", "income", "bill", "merge_refund", "not_expense", "category", "budget", FinanceActions.KIND)

/**
 * Checked again just before an action runs (the card may have been shown minutes earlier): the person must
 * still be an approved member, and money actions need Finance editing.
 */
fun checkActionAllowed(env: AppEnv, a: AiAction) {
    val me = env.members.firstOrNull { it.uid == env.uid }
    if (me != null && me.status != "ACTIVE") throw IllegalStateException("Your membership is not active.")
    val money = a.kind in MONEY_KINDS || (a.kind == "delete_dups" && a.collection == "expenses")
    if (money && me != null && !me.canEditFinance) throw IllegalStateException("Finance editing is turned off for you. Ask a family admin.")
}

/** The last AI actions on this phone (AI settings). */
@Composable
fun AiActivityList() {
    val ctx = LocalContext.current
    val log = remember { AiAudit.list(ctx) }
    val fmt = remember { SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()) }
    Column(Modifier.fillMaxWidth()) {
        Text("Recent AI actions", style = MaterialTheme.typography.titleSmall)
        Text("Kept on this phone only. An action shows as done only after the save succeeded.",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))
        if (log.isEmpty()) Text("None yet.", style = MaterialTheme.typography.bodySmall)
        log.take(15).forEach { e ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (e.ok) "✓" else "✕", color = if (e.ok) Color(0xFF34C759) else Color(0xFFFF3B30), modifier = Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.what, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    Text(fmt.format(Date(e.at)) + if (!e.ok && e.error.isNotBlank()) " · ${e.error}" else "",
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
