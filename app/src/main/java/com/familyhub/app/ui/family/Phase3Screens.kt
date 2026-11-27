package com.familyhub.app.ui.family

import com.familyhub.app.core.theme.GlassDialog
import com.familyhub.app.core.theme.GlassInputField
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.familyhub.app.core.theme.EmptyState
import com.familyhub.app.core.theme.GlassCard
import com.familyhub.app.data.remote.Rec
import com.familyhub.app.money.BudgetPlan
import com.familyhub.app.money.FinanceMonth
import com.familyhub.app.money.Goals
import com.familyhub.app.money.Intel
import com.familyhub.app.money.Ledger
import com.familyhub.app.money.RecurringFinder
import com.familyhub.app.money.Reports
import com.familyhub.app.family.Subscriptions
import com.familyhub.app.ui.AppEnv
import com.familyhub.app.ui.common.ChoiceChips
import com.familyhub.app.ui.common.ScreenTopBar
import com.familyhub.app.ui.money.canEditMoney
import com.familyhub.app.ui.money.categoriesFor
import com.familyhub.app.ui.money.inr
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SHAPE = RoundedCornerShape(18.dp)
private val RED = Color(0xFFFF3B30)
private val ORANGE = Color(0xFFFF9500)
private val GREEN = Color(0xFF34C759)

@Composable
private fun Bar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(color))
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth(), shape = SHAPE) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// 1. Family savings goals (above the Savings goals list)
// ---------------------------------------------------------------------------------------------------------------

@Composable
fun GoalsOverview(env: AppEnv) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val goals = remember(env.data) { env.data["items"].orEmpty().filter { it["type"] == "goal" && it["isDeleted"] != true } }
    if (goals.isEmpty()) return
    val active = goals.filter { !Goals.archived(it) }
    val now = System.currentTimeMillis()
    var adding by remember { mutableStateOf<Rec?>(null) }
    GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = SHAPE) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Goal progress", fontWeight = FontWeight.SemiBold)
            active.forEach { g ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text((g["title"] as? String).orEmpty(), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${(Goals.progress(g) * 100).toInt()}%", fontWeight = FontWeight.SemiBold, color = if (Goals.reached(g)) GREEN else MaterialTheme.colorScheme.primary)
                    }
                    Bar(Goals.progress(g).toFloat(), if (Goals.reached(g)) GREEN else MaterialTheme.colorScheme.primary)
                    Text(
                        "${inr(Goals.saved(g))} of ${inr(Goals.target(g))}" + (if (Goals.reached(g)) " · reached 🎉" else " · ${inr(Goals.remaining(g))} to go") +
                            (Goals.monthlyNeeded(g, now)?.let { " · about ${inr(it)} a month to make it" } ?: ""),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val bm = Goals.byMember(g)
                    if (bm.isNotEmpty()) Text("Added by: " + bm.joinToString(", ") { "${it.first} ${inr(it.second)}" }, style = MaterialTheme.typography.labelSmall)
                    if (canEditMoney(env)) TextButton(onClick = { adding = g }, contentPadding = PaddingValues(0.dp)) { Text("Add or take out money") }
                }
            }
            if (goals.size > active.size) Text("${goals.size - active.size} archived goal(s) are hidden here.", style = MaterialTheme.typography.labelSmall)
            Text("This only records what you set aside; no money is moved.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
        }
    }
    adding?.let { g ->
        var amount by remember(g) { mutableStateOf("") }
        var takeOut by remember(g) { mutableStateOf(false) }
        var note by remember(g) { mutableStateOf("") }
        var saving by remember(g) { mutableStateOf(false) }
        GlassDialog(onDismissRequest = { if (!saving) adding = null }, title = { Text((g["title"] as? String).orEmpty()) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChips(listOf("Add", "Take out"), if (takeOut) "Take out" else "Add", { takeOut = it == "Take out" })
                    GlassInputField(amount, { amount = it.filter { c -> c.isDigit() || c == '.' }.take(9) }, label = { Text("Amount (₹)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    GlassInputField(note, { note = it.take(80) }, label = { Text("Note (optional)") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = !saving && (amount.toDoubleOrNull() ?: 0.0) > 0, onClick = {
                    saving = true
                    val a = amount.toDouble() * if (takeOut) -1 else 1
                    val fields = Goals.contributionFields(g, Goals.Contribution(System.currentTimeMillis(), env.profile.name, a, note.trim()))
                    scope.launch {
                        try {
                            env.repo.setFieldsScoped(env.familyId, env.uid, "items", g, fields)
                            if (fields["done"] == true) notifyGoal(ctx, (g["title"] as? String).orEmpty())
                            Toast.makeText(ctx, if (fields["done"] == true) "Goal reached! 🎉" else "Saved.", Toast.LENGTH_SHORT).show()
                            adding = null
                        } catch (e: Exception) { Toast.makeText(ctx, "Not saved: ${e.message?.take(60)}", Toast.LENGTH_LONG).show() }
                        saving = false
                    }
                }) { Text(if (saving) "Saving…" else "Save") }
            },
            dismissButton = { TextButton(enabled = !saving, onClick = { adding = null }) { Text("Cancel") } })
    }
}

private fun notifyGoal(ctx: Context, title: String) {
    try {
        if (!com.familyhub.app.work.Notifier.canPost(ctx)) return
        com.familyhub.app.work.Notifier.ensureChannels(ctx)
        val n = androidx.core.app.NotificationCompat.Builder(ctx, com.familyhub.app.work.Notifier.CH_GENERAL)
            .setSmallIcon(android.R.drawable.star_on).setContentTitle("Savings goal reached")
            .setContentText("$title: target reached. Well done!").setAutoCancel(true).build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).notify(("goal" + title).hashCode(), n)
    } catch (_: Exception) { }
}

// ---------------------------------------------------------------------------------------------------------------
// 2. Family budget planner (Finance → Budget planner)
// ---------------------------------------------------------------------------------------------------------------

private val MONTH = SimpleDateFormat("MMMM yyyy", Locale.getDefault())

@Composable
fun BudgetPlannerScreen(env: AppEnv) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var offset by rememberSaveable { mutableStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val from = BudgetPlan.monthStart(now, offset)
    val to = if (offset == 0) now else BudgetPlan.monthStart(now, offset + 1) - 1
    val expenses = env.data["expenses"].orEmpty()
    val items = env.data["items"].orEmpty()
    val limitRecs = items.filter { it["type"] == "budget_limit" && it["isDeleted"] != true }
    val lines = remember(limitRecs, expenses, from, to) { BudgetPlan.lines(BudgetPlan.limits(items), expenses, from, to, env.uid) }
    val total = remember(expenses, from, to) { Ledger.totals(expenses, from, to) }
    val trend = remember(expenses) { BudgetPlan.trend(expenses, now) }
    val mom = remember(expenses) { BudgetPlan.monthOnMonth(expenses, now) }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Budget planner", onBack = env.onBack)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { offset-- }) { Icon(Icons.Rounded.ChevronLeft, "Previous month") }
                    Text(MONTH.format(Date(from)), Modifier.weight(1f), fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    IconButton(onClick = { if (offset < 0) offset++ }, enabled = offset < 0) { Icon(Icons.Rounded.ChevronRight, "Next month") }
                }
            }
            item {
                Section("Family budget") {
                    val b = env.family.monthlyBudget
                    if (b > 0) {
                        Bar((total.spentRupees / b).toFloat(), if (total.spentRupees > b) RED else if (total.spentRupees > b * 0.8) ORANGE else GREEN)
                        Text("${inr(total.spentRupees)} spent of ${inr(b)} · " + if (total.spentRupees > b) "${inr(total.spentRupees - b)} over" else "${inr(b - total.spentRupees)} left",
                            style = MaterialTheme.typography.bodySmall)
                    } else Text("${inr(total.spentRupees)} spent. A family admin can set the monthly family budget in Finance.", style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                Section("Category limits") {
                    if (lines.isEmpty()) Text("No limits yet. Add one for the categories you want to watch.", style = MaterialTheme.typography.bodySmall)
                    lines.forEach { l ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(l.category + if (l.mine) " (only me)" else "", Modifier.weight(1f))
                            Text("${inr(l.actual)} / ${inr(l.planned)}", style = MaterialTheme.typography.labelLarge,
                                color = when (l.state) { BudgetPlan.State.OVER -> RED; BudgetPlan.State.NEAR -> ORANGE; else -> MaterialTheme.colorScheme.onSurface })
                        }
                        Bar((l.actual / l.planned.coerceAtLeast(1.0)).toFloat(), when (l.state) { BudgetPlan.State.OVER -> RED; BudgetPlan.State.NEAR -> ORANGE; else -> GREEN })
                        Text(if (l.remaining >= 0) "${inr(l.remaining)} left" else "${inr(-l.remaining)} over", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (canEditMoney(env)) TextButton(onClick = { adding = true }) { Text("Add a limit") }
                    val alerts = lines.filter { it.state != BudgetPlan.State.OK }
                    if (alerts.isNotEmpty()) Text("Alerts: " + alerts.joinToString("; ") { "${it.category} " + if (it.state == BudgetPlan.State.OVER) "over" else "near its limit" },
                        color = RED, style = MaterialTheme.typography.labelMedium)
                }
            }
            item {
                Section("Last 6 months") {
                    val max = trend.maxOfOrNull { it.second }?.coerceAtLeast(1.0) ?: 1.0
                    trend.forEach { (m, v) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(SimpleDateFormat("MMM", Locale.getDefault()).format(Date(m)), Modifier.width(40.dp), style = MaterialTheme.typography.labelMedium)
                            Bar((v / max).toFloat(), MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                            Text(inr(v), Modifier.width(86.dp), style = MaterialTheme.typography.labelMedium, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                        }
                    }
                }
            }
            item {
                Section("This month against last month") {
                    if (mom.isEmpty()) Text("No spending yet.", style = MaterialTheme.typography.bodySmall)
                    mom.take(6).forEach { c ->
                        Row {
                            Text(c.category, Modifier.weight(1f))
                            Text((if (c.diff >= 0) "+" else "−") + inr(kotlin.math.abs(c.diff)) + (c.pct?.let { " (${it.toInt()}%)" } ?: ""),
                                color = if (c.diff > 0) RED else GREEN, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = { shareText(ctx, "budget-${SimpleDateFormat("yyyy-MM", Locale.US).format(Date(from))}.csv", Reports.budgetCsv(lines)) },
                    modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.IosShare, null); Spacer(Modifier.width(6.dp)); Text("Export budget report (CSV)") }
            }
        }
    }
    if (adding) {
        val cats = remember(items) { categoriesFor(items, FinanceMonth.Kind.EXPENSE) }
        var cat by remember { mutableStateOf("") }
        var amount by remember { mutableStateOf("") }
        var yearly by remember { mutableStateOf(false) }
        var onlyMe by remember { mutableStateOf(false) }
        var saving by remember { mutableStateOf(false) }
        GlassDialog(onDismissRequest = { if (!saving) adding = false }, title = { Text("Category limit") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChips(cats, cat, { cat = it }, scroll = true)
                    GlassInputField(amount, { amount = it.filter { c -> c.isDigit() || c == '.' }.take(9) }, label = { Text("Limit (₹)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    ChoiceChips(listOf("Monthly", "Yearly"), if (yearly) "Yearly" else "Monthly", { yearly = it == "Yearly" })
                    Row(verticalAlignment = Alignment.CenterVertically) { Switch(onlyMe, { onlyMe = it }); Spacer(Modifier.width(8.dp)); Text("Only me (counts only my spending)", style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(enabled = !saving && cat.isNotBlank() && (amount.toDoubleOrNull() ?: 0.0) > 0, onClick = {
                    saving = true
                    val now2 = System.currentTimeMillis()
                    // One limit per category and scope: an existing one is updated, never duplicated.
                    val existing = limitRecs.firstOrNull { (it["category"] as? String).equals(cat, true) && (it["_private"] == true) == onlyMe }
                    scope.launch {
                        try {
                            env.repo.saveScoped(env.familyId, env.uid, "items", existing, mapOf("type" to "budget_limit", "category" to cat, "amountInr" to amount.toDouble(),
                                "period" to if (yearly) "Yearly" else "Monthly", "isDeleted" to false, "updatedAt" to now2, "createdByUid" to env.uid, "createdByName" to env.profile.name), onlyMe)
                            adding = false
                        } catch (e: Exception) { Toast.makeText(ctx, "Not saved: ${e.message?.take(60)}", Toast.LENGTH_LONG).show() }
                        saving = false
                    }
                }) { Text(if (saving) "Saving…" else "Save") }
            },
            dismissButton = { TextButton(enabled = !saving, onClick = { adding = false }) { Text("Cancel") } })
    }
}

// ---------------------------------------------------------------------------------------------------------------
// 3. Expense intelligence (Finance → Money insights)
// ---------------------------------------------------------------------------------------------------------------

@Composable
fun MoneyIntelScreen(env: AppEnv) {
    val now = System.currentTimeMillis()
    val expenses = env.data["expenses"].orEmpty()
    val items = env.data["items"].orEmpty()
    val from = BudgetPlan.monthStart(now, 0)
    val t = remember(expenses) { Ledger.totals(expenses, from, now) }
    val mom = remember(expenses) { BudgetPlan.monthOnMonth(expenses, now) }
    val unusual = remember(expenses) { Intel.unusual(expenses, now) }
    val lines = remember(items, expenses) { BudgetPlan.lines(BudgetPlan.limits(items), expenses, from, now, env.uid) }
    val subs = items.filter { it["type"] == "subscription" }
    val recurringMonthly = Subscriptions.totals(subs).monthly
    val found = remember(expenses, items) { RecurringFinder.find(expenses, items.filter { it["type"] == "subscription" }.map { RecurringFinder.payeeKey((it["title"] as? String).orEmpty()) }.toSet()) }
    // Member split from shared entries only (never someone else's "Only me" records).
    val byMember = remember(t) { t.spends.filter { it["_private"] != true }.groupBy { (it["memberName"] as? String).orEmpty().ifBlank { "Not set" } }
        .map { (k, v) -> k to v.sumOf { Ledger.spendAmount(it) } }.sortedByDescending { it.second } }
    val tips = Intel.suggestions(lines, mom, recurringMonthly, unusual)
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Money insights", onBack = env.onBack)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Section("${MONTH.format(Date(from))} so far") {
                    Text("Spent ${inr(t.spentRupees)} · income ${inr(t.incomeRupees)}" + if (t.incomeRupees > 0) " · kept ${inr(t.incomeRupees - t.spentRupees)}" else "",
                        style = MaterialTheme.typography.bodyMedium)
                    t.byCategory.take(5).forEach { (c, p) -> Row { Text(c, Modifier.weight(1f)); Text(inr(Ledger.rupees(p)), fontWeight = FontWeight.SemiBold) } }
                }
            }
            item {
                Section("Biggest changes from last month") {
                    val up = mom.filter { it.diff > 0 }.take(3)
                    if (up.isEmpty()) Text("No category is up on last month.", style = MaterialTheme.typography.bodySmall)
                    up.forEach { c -> Text("${c.category}: +${inr(c.diff)}" + (c.pct?.let { " (${it.toInt()}%)" } ?: " (new)"), style = MaterialTheme.typography.bodyMedium) }
                }
            }
            item {
                Section("Unusual spending") {
                    if (unusual.isEmpty()) Text("Nothing unusual this month.", style = MaterialTheme.typography.bodySmall)
                    unusual.take(5).forEach { u -> Text("${u.rec["title"]}: ${inr(u.amount)} (usually about ${inr(u.typical)} in ${u.rec["category"] ?: "Other"})", style = MaterialTheme.typography.bodyMedium) }
                }
            }
            item {
                Section("Repeating payments") {
                    Text("Subscriptions: about ${inr(recurringMonthly)} a month (${inr(recurringMonthly * 12)} a year).", style = MaterialTheme.typography.bodyMedium)
                    if (found.isNotEmpty()) Text("${found.size} more look recurring in your transactions: " + found.take(3).joinToString(", ") { it.payee } + ". See Finance → Recurring payments.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (byMember.size > 1) item {
                Section("By family member (shared entries)") { byMember.forEach { (m, v) -> Row { Text(m, Modifier.weight(1f)); Text(inr(v)) } } }
            }
            item {
                Section("Suggestions") {
                    if (tips.isEmpty()) Text("Nothing to flag right now.", style = MaterialTheme.typography.bodySmall)
                    tips.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    Text("Suggestions from your own numbers, not financial advice or a guarantee.", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// 4. Financial reports (Finance → Reports): CSV and PDF, shared with the system share sheet
// ---------------------------------------------------------------------------------------------------------------

private fun shareFile(ctx: Context, f: File, mime: String) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = mime; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }, "Share report"))
}

fun shareText(ctx: Context, name: String, text: String) {
    try { val f = File(ctx.cacheDir, name); f.writeText(text); shareFile(ctx, f, "text/csv") }
    catch (e: Exception) { Toast.makeText(ctx, "Could not export: ${e.message?.take(60)}", Toast.LENGTH_LONG).show() }
}

@Composable
fun ReportsScreen(env: AppEnv) {
    val ctx = LocalContext.current
    var month by rememberSaveable { mutableStateOf("This month") }
    val now = System.currentTimeMillis()
    val off = if (month == "Last month") -1 else 0
    val from = BudgetPlan.monthStart(now, off)
    val to = if (off == 0) now else BudgetPlan.monthStart(now, 0) - 1
    val tag = SimpleDateFormat("yyyy-MM", Locale.US).format(Date(from))
    val ex = env.data["expenses"].orEmpty()
    val items = env.data["items"].orEmpty()
    val lines = BudgetPlan.lines(BudgetPlan.limits(items), ex, from, to, env.uid)
    val goals = items.filter { it["type"] == "goal" && it["isDeleted"] != true }
    val subs = items.filter { it["type"] == "subscription" && it["isDeleted"] != true }
    val reports = listOf(
        "Monthly expenses" to { Reports.monthlyCsv(ex, from, to) },
        "Category breakdown" to { Reports.categoryCsv(ex, from, to) },
        "By family member" to { Reports.memberCsv(ex, from, to) },
        "Recurring payments" to { Reports.recurringCsv(RecurringFinder.find(ex, subs.map { RecurringFinder.payeeKey((it["title"] as? String).orEmpty()) }.toSet()), subs) },
        "Savings goals" to { Reports.goalsCsv(goals) },
        "Budget performance" to { Reports.budgetCsv(lines) }
    )
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Reports", onBack = env.onBack)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ChoiceChips(listOf("This month", "Last month"), month, { if (it.isNotBlank()) month = it })
            Text("Files are made on this phone and shared only where you choose. Money entries marked \"Only me\" by others are never included.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Section("CSV (opens in Excel or Google Sheets)") {
                reports.forEach { (name, make) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, Modifier.weight(1f))
                        TextButton(onClick = { shareText(ctx, name.lowercase(Locale.ROOT).replace(' ', '-') + "-$tag.csv", make()) }) { Text("Share") }
                    }
                }
            }
            Button(onClick = { sharePdf(ctx, env, from, to, lines, goals, subs) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.PictureAsPdf, null); Spacer(Modifier.width(6.dp)); Text("PDF summary for ${MONTH.format(Date(from))}")
            }
        }
    }
}

private fun sharePdf(ctx: Context, env: AppEnv, from: Long, to: Long, lines: List<BudgetPlan.Line>, goals: List<Rec>, subs: List<Rec>) {
    try {
        val ex = env.data["expenses"].orEmpty()
        val t = Ledger.totals(ex, from, to)
        val text = ArrayList<Pair<String, Boolean>>()
        text += "${env.family.name.ifBlank { "Family" }}: money report, ${MONTH.format(Date(from))}" to true
        text += "Spent ${inr(t.spentRupees)}, income ${inr(t.incomeRupees)}, refunds ${inr(Ledger.rupees(t.refunds))}" to false
        text += "" to false; text += "By category" to true
        t.byCategory.forEach { (c, p) -> text += "  $c: ${inr(Ledger.rupees(p))}" to false }
        if (lines.isNotEmpty()) { text += "" to false; text += "Budget" to true; lines.forEach { text += "  ${it.category}: ${inr(it.actual)} of ${inr(it.planned)} (${it.state.name.lowercase()})" to false } }
        val active = subs.filter { Subscriptions.isLive(it) }
        if (active.isNotEmpty()) { text += "" to false; text += "Subscriptions: ${inr(Subscriptions.totals(subs).monthly)} a month" to true; active.forEach { text += "  ${it["title"]}: ${inr((it["amountInr"] as? Number)?.toDouble() ?: 0.0)} ${(it["cycle"] as? String).orEmpty()}" to false } }
        if (goals.isNotEmpty()) { text += "" to false; text += "Savings goals" to true; goals.forEach { text += "  ${it["title"]}: ${inr(Goals.saved(it))} of ${inr(Goals.target(it))} (${(Goals.progress(it) * 100).toInt()}%)" to false } }
        val pdf = PdfDocument()
        val paint = Paint().apply { textSize = 11f }
        val bold = Paint().apply { textSize = 12f; isFakeBoldText = true }
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        var y = 48f; var n = 1
        for ((line, b) in text) {
            if (y > 800) { pdf.finishPage(page); n++; page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, n).create()); y = 48f }
            page.canvas.drawText(line, 40f, y, if (b) bold else paint); y += if (line.isEmpty()) 8f else 17f
        }
        pdf.finishPage(page)
        val f = File(ctx.cacheDir, "family-report-${SimpleDateFormat("yyyy-MM", Locale.US).format(Date(from))}.pdf")
        f.outputStream().use { pdf.writeTo(it) }; pdf.close()
        shareFile(ctx, f, "application/pdf")
    } catch (e: Exception) { Toast.makeText(ctx, "Could not make the PDF: ${e.message?.take(60)}", Toast.LENGTH_LONG).show() }
}
