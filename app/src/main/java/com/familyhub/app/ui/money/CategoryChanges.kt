package com.familyhub.app.ui.money

import com.familyhub.app.core.theme.GlassBottomSheet
import com.familyhub.app.core.theme.GlassDialog
import com.familyhub.app.core.theme.GlassFilterChip
import com.familyhub.app.core.theme.GlassInputField
import android.content.Context
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.familyhub.app.data.remote.PRIVATE_FLAG
import com.familyhub.app.data.remote.Rec
import com.familyhub.app.money.FinanceMonth
import com.familyhub.app.money.Recategorize
import com.familyhub.app.ui.AppEnv
import com.familyhub.app.ui.modules.lng
import com.familyhub.app.ui.modules.num
import com.familyhub.app.ui.modules.str
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A record of each bulk category change, kept on this phone (last 20), so it can be checked and undone.
 * Undo only puts back entries that still have the category the change gave them, so a later edit is never
 * overwritten.
 */
object CategoryAudit {
    private const val PREFS = "category_audit"
    private const val KEY = "log"
    private const val KEEP = 20

    data class Entry(val at: Long, val label: String, val count: Int, val undone: Boolean, val json: JSONObject)

    fun list(ctx: Context): List<Entry> = try {
        val arr = JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(o.getLong("at"), o.getString("label"), o.getJSONArray("rows").length(), o.optBoolean("undone"), o)
        }.sortedByDescending { it.at }
    } catch (_: Exception) { emptyList() }

    private fun save(ctx: Context, entries: List<JSONObject>) {
        val arr = JSONArray()
        entries.sortedByDescending { it.getLong("at") }.take(KEEP).forEach { arr.put(it) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun add(ctx: Context, label: String, plan: Recategorize.Plan): Long {
        val at = System.currentTimeMillis()
        val rows = JSONArray()
        plan.changes.forEach { c ->
            rows.put(JSONObject().apply {
                put("id", c.rec["id"] as? String)
                put("private", c.rec[PRIVATE_FLAG] == true)
                put("set", c.fields["category"] as? String)
                put("oldCategory", c.undo["category"] ?: JSONObject.NULL)
                put("oldSub", c.undo["subcategory"] ?: JSONObject.NULL)
            })
        }
        val o = JSONObject().put("at", at).put("label", label).put("rows", rows)
        save(ctx, list(ctx).map { it.json } + o)
        return at
    }

    fun markUndone(ctx: Context, at: Long) {
        save(ctx, list(ctx).map { if (it.at == at) it.json.put("undone", true) else it.json })
    }

    /** The changes that put an entry back, for records that still carry the category this change set. */
    fun undoChanges(entry: Entry, current: List<Rec>): List<Pair<Rec, Map<String, Any?>>> {
        val byId = current.associateBy { it.str("id") }
        val rows = entry.json.getJSONArray("rows")
        val now = System.currentTimeMillis()
        return (0 until rows.length()).mapNotNull { i ->
            val row = rows.getJSONObject(i)
            val rec = byId[row.optString("id")] ?: return@mapNotNull null
            if (!FinanceMonth.normName(rec.str("category")).equals(FinanceMonth.normName(row.optString("set")))) return@mapNotNull null
            val old = if (row.isNull("oldCategory")) "" else row.optString("oldCategory")
            val sub = if (row.isNull("oldSub")) "None" else row.optString("oldSub")
            rec to mapOf("category" to old, "subcategory" to sub, "updatedAt" to now)
        }
    }
}

/** True if this person may change family money entries (Finance editing is on for them, and they are approved). */
fun canEditMoney(env: AppEnv): Boolean {
    val me = env.members.firstOrNull { it.uid == env.uid }
    return me != null && me.status == "ACTIVE" && me.canEditFinance
}

/**
 * Writes a category plan after the person confirmed it. Checks permission again just before writing, keeps a
 * record for Undo, and reports the real result. The screen updates at once from the phone's copy.
 */
fun applyCategoryPlan(env: AppEnv, ctx: Context, plan: Recategorize.Plan, label: String, onDone: (Long?) -> Unit = {}) {
    if (plan.count == 0) { Toast.makeText(ctx, "Nothing to change.", Toast.LENGTH_SHORT).show(); onDone(null); return }
    if (!canEditMoney(env)) {
        Toast.makeText(ctx, "Finance editing is turned off for you. Ask a family admin.", Toast.LENGTH_LONG).show()
        onDone(null); return
    }
    val at = CategoryAudit.add(ctx, label, plan)
    onDone(at)
    val app = ctx.applicationContext
    env.scope.launch {
        try {
            val n = env.repo.setFieldsMany(env.familyId, env.uid, "expenses", plan.changes.map { it.rec to it.fields })
            Toast.makeText(app, "Updated $n ${if (n == 1) "transaction" else "transactions"}.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(app, "Some changes could not be saved: ${e.message?.take(80)}", Toast.LENGTH_LONG).show()
        }
    }
}

/** Undoes one logged change. */
fun undoCategoryChange(env: AppEnv, ctx: Context, entry: CategoryAudit.Entry) {
    val changes = CategoryAudit.undoChanges(entry, env.data["expenses"].orEmpty())
    CategoryAudit.markUndone(ctx, entry.at)
    if (changes.isEmpty()) { Toast.makeText(ctx, "Nothing to undo: those entries were changed again since.", Toast.LENGTH_LONG).show(); return }
    val app = ctx.applicationContext
    env.scope.launch {
        try {
            val n = env.repo.setFieldsMany(env.familyId, env.uid, "expenses", changes)
            Toast.makeText(app, "Put back $n ${if (n == 1) "transaction" else "transactions"}.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(app, "Undo failed: ${e.message?.take(80)}", Toast.LENGTH_LONG).show()
        }
    }
}

/** Pick a category (built-in and the family's own) for a kind. */
@Composable
fun CategoryPickerDialog(env: AppEnv, kind: FinanceMonth.Kind, title: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val items = env.data["items"].orEmpty()
    val cats = remember(items, kind) { categoriesFor(items, kind) }
    var adding by remember { mutableStateOf(false) }
    GlassDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(cats) { c ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(c) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CategoryBadge(c, items, 30); Spacer(Modifier.width(10.dp)); Text(c)
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().clickable { adding = true }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(10.dp)); Text("New category", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
    if (adding) CategoryDialog(env, null, if (kind == FinanceMonth.Kind.INCOME) "Income" else "Spending", { adding = false }) { name ->
        adding = false; onPick(name)
    }
}

/** "Move 12 transactions to Dining?" with what is left alone. */
@Composable
fun ConfirmCategoryPlan(plan: Recategorize.Plan, target: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    GlassDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (plan.count == 0) "Nothing to change" else "Move ${plan.count} ${if (plan.count == 1) "transaction" else "transactions"} to $target?") },
        text = {
            Text(
                buildString {
                    if (plan.count > 0) append("Only the category changes. Amounts, dates, accounts, notes and receipts stay as they are. Totals, budgets and Insights update at once. You can undo this from Finance → Categories → Recent changes.")
                    if (plan.skipped > 0) append("\n\n${plan.skipped} left as they are (already in $target, deleted, or a transfer or card payment).")
                }
            )
        },
        confirmButton = { if (plan.count > 0) TextButton(onClick = onConfirm) { Text("Move ${plan.count}", fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (plan.count > 0) "Cancel" else "OK") } }
    )
}

/**
 * "Apply to existing transactions": choose past entries for [category], one by one or all shown, then confirm.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApplyToExistingSheet(env: AppEnv, category: String, kind: FinanceMonth.Kind, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val expenses = env.data["expenses"].orEmpty()
    var query by remember { mutableStateOf("") }
    var onlyUncat by remember { mutableStateOf(true) }
    var period by remember { mutableStateOf("All time") }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf<Recategorize.Plan?>(null) }
    val now = remember { System.currentTimeMillis() }
    val range = when (period) {
        "This month" -> FinanceMonth.month(now)
        "Last 3 months" -> FinanceMonth.Range(FinanceMonth.month(now, -2).from, FinanceMonth.month(now).to)
        else -> FinanceMonth.Range(0L, Long.MAX_VALUE)
    }
    val shown = remember(expenses, query, onlyUncat, period, kind, category) {
        Recategorize.eligible(expenses, kind, range.from, range.to, query, onlyUncat, exclude = category)
    }
    val fmt = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }
    GlassBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("Apply \"$category\" to existing transactions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            GlassInputField(
                value = query, onValueChange = { query = it }, singleLine = true, placeholder = { Text("Search name, shop, amount") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("All time", "Last 3 months", "This month").forEach { p ->
                    GlassFilterChip(selected = period == p, onClick = { period = p }, label = { Text(p) })
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Only uncategorised", modifier = Modifier.weight(1f))
                Switch(checked = onlyUncat, onCheckedChange = { onlyUncat = it })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${shown.size} shown · ${picked.size} selected", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { picked = picked + shown.map { it.str("id") } }, enabled = shown.isNotEmpty()) { Text("Select all shown") }
                if (picked.isNotEmpty()) TextButton(onClick = { picked = emptySet() }) { Text("Clear") }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                if (shown.isEmpty()) item { Text("No matching transactions.", Modifier.padding(16.dp)) }
                items(shown, key = { it.str("id") }) { r ->
                    val id = r.str("id")
                    Row(
                        Modifier.fillMaxWidth().clickable { picked = if (id in picked) picked - id else picked + id }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = id in picked, onCheckedChange = { picked = if (it) picked + id else picked - id })
                        Column(Modifier.weight(1f)) {
                            Text(r.str("title").ifBlank { r.str("merchant") }.ifBlank { "Entry" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOf(FinanceMonth.categoryOf(r), Recategorize.sourceOf(r), r.lng("dateMillis")?.let { fmt.format(Date(it)) })
                                    .filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, maxLines = 1
                            )
                        }
                        Text(inr(r.num("amountInr") ?: 0.0), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Button(
                onClick = { confirm = Recategorize.forIds(expenses, picked, category) },
                enabled = picked.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
            ) { Text(if (picked.isEmpty()) "Select transactions" else "Apply to ${picked.size}") }
        }
    }
    confirm?.let { p ->
        ConfirmCategoryPlan(p, category, { confirm = null }) {
            confirm = null
            applyCategoryPlan(env, ctx, p, "${p.count} to $category") { if (it != null) onDismiss() }
        }
    }
}

/** The last bulk category changes on this phone, each with Undo. */
@Composable
fun RecentCategoryChanges(env: AppEnv) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val log = remember(tick) { CategoryAudit.list(ctx) }
    val fmt = remember { SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()) }
    if (log.isEmpty()) {
        Text("No bulk changes yet.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp)); return
    }
    Column {
        log.take(8).forEach { e ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Moved " + e.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(fmt.format(Date(e.at)) + if (e.undone) " · undone" else "", style = MaterialTheme.typography.bodySmall)
                }
                if (!e.undone) TextButton(onClick = { undoCategoryChange(env, ctx, e); tick++ }) { Text("Undo") }
            }
        }
    }
}
