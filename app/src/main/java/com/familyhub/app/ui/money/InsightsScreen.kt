package com.familyhub.app.ui.money

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.familyhub.app.core.theme.AccentCard
import com.familyhub.app.core.theme.GlassCard
import com.familyhub.app.data.remote.Rec
import com.familyhub.app.ui.AppEnv
import com.familyhub.app.ui.common.*
import com.familyhub.app.ui.modules.DAY_MS
import com.familyhub.app.ui.modules.lng
import com.familyhub.app.ui.modules.str
import java.io.File

/** A card chosen on the Card Bills screen, opened here with its billing cycle. */
object InsightsLink {
    var pendingCard: String? = null
}

private val MODES = listOf("This month", "Last month", "Pick month", "Custom", "Card cycle")

@Composable
fun InsightsScreen(env: AppEnv) {
    val context = LocalContext.current
    val now = remember { System.currentTimeMillis() }
    val allExpenses = env.data["expenses"].orEmpty()
    val cards = env.data["items"].orEmpty().filter { it.str("type") == "card" }

    val linkedCard = remember { InsightsLink.pendingCard.also { InsightsLink.pendingCard = null } }
    var mode by rememberSaveable { mutableStateOf(if (linkedCard != null) "Card cycle" else "This month") }
    var monthOffset by rememberSaveable { mutableStateOf(0) }
    var customFrom by rememberSaveable { mutableStateOf<Long?>(monthStart(now, 0)) }
    var customTo by rememberSaveable { mutableStateOf<Long?>(now) }
    var cardName by rememberSaveable { mutableStateOf(linkedCard ?: cards.firstOrNull()?.str("title").orEmpty()) }
    var billed by rememberSaveable { mutableStateOf(false) }
    var scope by rememberSaveable { mutableStateOf("Everyone") }
    var categoryFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    val startDay = monthStartDay(env.userDoc)
    val budgets = categoryBudgets(env.userDoc)
    fun bms(add: Int): Long = budgetMonthStart(now, startDay, add)

    // The period being looked at.
    val card = cards.firstOrNull { it.str("title") == cardName }
    val cycle = card?.let { CardCycle.of(it, now) }
    val (from, to) = when (mode) {
        "Last month" -> bms(-1) to bms(0) - 1
        "Pick month" -> bms(monthOffset) to bms(monthOffset + 1) - 1
        "Custom" -> {
            val f = customFrom ?: monthStart(now, 0)
            val t = customTo ?: now
            minOf(f, t) to endOfDay(maxOf(f, t))
        }
        "Card cycle" -> if (cycle == null) monthStart(now, 0) to now
        else if (billed) cycle.billedStart to cycle.billedEnd else cycle.currentStart to cycle.currentEnd
        else -> bms(0) to bms(1) - 1
    }
    val monthLike = mode == "This month" || mode == "Last month" || mode == "Pick month"
    val periodLabel = when {
        monthLike && startDay == 1 -> monthLabel(from)
        monthLike -> monthLabel(if (startDay > 15) to else from) + " (${shortDate(from)} – ${shortDate(to)})"
        else -> "${shortDate(from)} – ${shortDate(to)}"
    }

    fun inScope(r: Rec): Boolean = when (scope) {
        "Me" -> r.str("memberUid") == env.uid || (r.str("memberUid").isBlank() && r.str("memberName") == env.profile.name)
        else -> true
    }
    fun cardFilter(r: Rec): Boolean = mode != "Card cycle" || card == null || r.str("card") == card.str("title")

    val periodSpends = allExpenses.filter { isSpend(it) && inRange(it, from, to) && inScope(it) && cardFilter(it) }
    val periodIncome = allExpenses.filter { isIncome(it) && inRange(it, from, to) && inScope(it) }
    val spent = periodSpends.sumOf { amountOf(it) }
    val earned = periodIncome.sumOf { amountOf(it) }

    // Same length period just before, for comparison.
    val length = (to - from).coerceAtLeast(DAY_MS)
    val prevSpent = allExpenses.filter {
        isSpend(it) && inRange(it, from - length - 1, from - 1) && inScope(it) && cardFilter(it)
    }.sumOf { amountOf(it) }

    val byCategory = periodSpends.groupBy { it.str("category").ifBlank { "Other" } }
        .mapValues { (_, l) -> l.sumOf { amountOf(it) } }
        .toList().sortedByDescending { it.second }
    val byMethod = periodSpends.groupBy { it.str("paymentMethod").ifBlank { "Not set" } }
        .mapValues { (_, l) -> l.sumOf { amountOf(it) } }
        .toList().sortedByDescending { it.second }
    val byMember = periodSpends.groupBy { it.str("memberName").ifBlank { "Someone" } }
        .mapValues { (_, l) -> l.sumOf { amountOf(it) } }
        .toList().sortedByDescending { it.second }
    val topPlaces = periodSpends.groupBy { it.str("title").trim().lowercase() }
        .map { (_, l) -> l.first().str("title").trim() to l.sumOf { amountOf(it) } }
        .filter { it.first.isNotBlank() }
        .sortedByDescending { it.second }.take(5)

    val months = (11 downTo 0).map { bms(-it) }
    val trend = months.mapIndexed { i, m ->
        val end = (if (i + 1 < months.size) months[i + 1] else bms(1)) - 1
        allExpenses.filter { isSpend(it) && inRange(it, m, end) && inScope(it) }.sumOf { amountOf(it) }
    }
    val selectedMonthIndex = months.indexOfFirst { it == from }.takeIf { monthLike && it >= 0 }

    val shown = periodSpends.filter { categoryFilter == null || it.str("category").ifBlank { "Other" } == categoryFilter }
        .sortedByDescending { it.lng("dateMillis") ?: 0L }

    if (showSettings) {
        BudgetSettingsDialog(
            startDay = startDay,
            budgets = budgets,
            onDismiss = { showSettings = false },
            onSave = { d, map ->
                env.repo.updateUserDoc(env.uid, mapOf("monthStartDay" to d, "categoryBudgets" to map))
                showSettings = false
                monthOffset = 0
            }
        )
    }
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Money Insights", onBack = env.onBack) {
            IconButton(onClick = { showSettings = true }) {
                Icon(Icons.Rounded.Tune, contentDescription = "Month start and budgets")
            }
            IconButton(onClick = { exportCsv(context, periodSpends + periodIncome, periodLabel) }) {
                Icon(Icons.Rounded.Share, contentDescription = "Export as CSV")
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                ChoiceChips(MODES, mode, { mode = it.ifBlank { "This month" }; categoryFilter = null })
            }
            if (mode == "Pick month") {
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(6.dp)) {
                            IconButton(onClick = { monthOffset -= 1 }) { Icon(Icons.Rounded.ChevronLeft, "Previous month") }
                            Text(periodLabel, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            IconButton(onClick = { if (monthOffset < 0) monthOffset += 1 }, enabled = monthOffset < 0) {
                                Icon(Icons.Rounded.ChevronRight, "Next month")
                            }
                        }
                    }
                }
            }
            if (mode == "Custom") {
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DatePickField("From", customFrom, { customFrom = it }, modifier = Modifier.weight(1f))
                            DatePickField("To", customTo, { customTo = it }, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            if (mode == "Card cycle") {
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (cards.isEmpty()) {
                                Text("Add your credit cards with their statement date to see spends per billing cycle.")
                                FilledTonalButton(onClick = { env.navigate("card_list") }) { Text("Add a card") }
                            } else {
                                ChoiceChips(cards.map { it.str("title") }, cardName, { if (it.isNotBlank()) cardName = it })
                                ChoiceChips(listOf("Current cycle", "Last bill"), if (billed) "Last bill" else "Current cycle",
                                    { billed = it == "Last bill" })
                                if (cycle != null) {
                                    Text(
                                        if (billed) "Bill due ${formatDate(cycle.billDue)}" else "Next bill due ${formatDate(cycle.nextDue)}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item {
                ChoiceChips(listOf("Everyone", "Me"), scope, { scope = it.ifBlank { "Everyone" } })
            }

            // Summary
            item {
                AccentCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text("Spent • $periodLabel", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelLarge)
                        Text(inr(spent), color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        val change = if (prevSpent > 0) (spent - prevSpent) / prevSpent * 100 else null
                        Text(
                            when {
                                change == null -> "Nothing in the previous period to compare."
                                change >= 0 -> "▲ %.0f%% more than the previous period (%s)".format(change, inr(prevSpent))
                                else -> "▼ %.0f%% less than the previous period (%s)".format(-change, inr(prevSpent))
                            },
                            color = Color.White.copy(alpha = 0.9f),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row {
                            StatBlock("Income", inr(earned), Modifier.weight(1f))
                            StatBlock("Saved", inr(earned - spent), Modifier.weight(1f))
                            StatBlock("Entries", periodSpends.size.toString(), Modifier.weight(1f))
                        }
                        if (mode in listOf("This month", "Last month", "Pick month") && scope == "Everyone" && env.family.monthlyBudget > 0) {
                            Spacer(modifier = Modifier.height(10.dp))
                            val used = (spent / env.family.monthlyBudget).toFloat()
                            LinearProgressIndicator(
                                progress = { used.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                                color = if (used > 1f) Color(0xFFFF453A) else Color.White,
                                trackColor = Color.White.copy(alpha = 0.25f)
                            )
                            Text(
                                "%.0f%% of the %s monthly budget".format(used * 100, inr(env.family.monthlyBudget)),
                                color = Color.White.copy(alpha = 0.9f),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }

            // Categories
            if (byCategory.isNotEmpty()) {
                item { SectionTitle("By category") }
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            DonutChart(byCategory, inrShort(spent), "${byCategory.size} categories")
                            Spacer(modifier = Modifier.height(12.dp))
                            DonutLegend(byCategory, onClick = { c -> categoryFilter = if (categoryFilter == c) null else c }, selected = categoryFilter)
                        }
                    }
                }
            }

            // Category budgets
            if (monthLike) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle("Budgets", modifier = Modifier.weight(1f))
                        TextButton(onClick = { showSettings = true }) { Text(if (budgets.isEmpty()) "Set budgets" else "Edit") }
                    }
                }
                if (budgets.isNotEmpty()) {
                    item {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                val spentBy = byCategory.toMap()
                                budgets.entries.sortedByDescending { (spentBy[it.key] ?: 0.0) / it.value }.forEachIndexed { i, (cat, limit) ->
                                    val used = spentBy[cat] ?: 0.0
                                    val over = used > limit
                                    HBar(
                                        cat + if (over) "  • over by ${inr(used - limit)}" else "",
                                        used, limit,
                                        "${inr(used)} of ${inr(limit)}",
                                        if (over) Color(0xFFFF3B30) else if (used > limit * 0.8) Color(0xFFFF9500) else chartColor(i)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Trend
            item { SectionTitle("Monthly trend") }
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        BarChart(
                            labels = months.map { shortMonth(it) },
                            values = trend,
                            selected = selectedMonthIndex,
                            onClick = { i ->
                                mode = "Pick month"
                                monthOffset = i - (months.size - 1)
                                categoryFilter = null
                            }
                        )
                        val avg = trend.dropLast(1).filter { it > 0 }.let { if (it.isEmpty()) 0.0 else it.average() }
                        Text(
                            if (avg > 0) "Average month: ${inr(avg)}. Tap a bar to open that month." else "Tap a bar to open that month.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }

            if (byMember.size > 1 || byMethod.isNotEmpty()) {
                item { SectionTitle("Who and how") }
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (byMember.size > 1) {
                                Text("By family member", style = MaterialTheme.typography.labelLarge)
                                val max = byMember.first().second
                                byMember.forEachIndexed { i, (n, v) -> HBar(n, v, max, inr(v), chartColor(i)) }
                            }
                            if (byMethod.isNotEmpty()) {
                                Text("By payment method", style = MaterialTheme.typography.labelLarge)
                                val max = byMethod.first().second
                                byMethod.forEachIndexed { i, (n, v) -> HBar(n, v, max, inr(v), chartColor(i + 3)) }
                            }
                        }
                    }
                }
            }

            if (topPlaces.isNotEmpty()) {
                item { SectionTitle("Top places") }
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            topPlaces.forEachIndexed { i, (n, v) ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${i + 1}.", modifier = Modifier.width(24.dp), fontWeight = FontWeight.Bold)
                                    Text(n, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(inr(v), fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle(if (categoryFilter != null) "Transactions • $categoryFilter" else "Transactions", modifier = Modifier.weight(1f))
                    if (categoryFilter != null) TextButton(onClick = { categoryFilter = null }) { Text("Show all") }
                }
            }
            if (shown.isEmpty()) {
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "No spending in this period. Add expenses in Finance or import them from bank SMS and email.",
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
            items(shown.take(100), key = { (it["_private"] == true).toString() + it.str("id") }) { r ->
                GlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), onClick = { env.navigate("finance") }) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(r.str("title").ifBlank { "Expense" }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOf(formatDate(r.lng("dateMillis")), r.str("category"), r.str("paymentMethod"), r.str("memberName"))
                                    .filter { it.isNotBlank() }.joinToString(" • "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(inr(amountOf(r)), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun BudgetSettingsDialog(
    startDay: Int,
    budgets: Map<String, Double>,
    onDismiss: () -> Unit,
    onSave: (Int, Map<String, Double>) -> Unit
) {
    var day by remember { mutableStateOf(startDay) }
    val values = remember {
        mutableStateMapOf<String, String>().also { m ->
            budgets.forEach { (k, v) -> m[k] = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString() }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Month and budgets") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("My month starts on day", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Use your salary date or card statement date, for example 25 for a month from the 25th to the 24th.",
                    style = MaterialTheme.typography.bodySmall
                )
                ChoiceChips(listOf("1", "5", "7", "10", "15", "20", "25", "28"), day.toString(), { d -> d.toIntOrNull()?.let { day = it } })
                Text("Monthly budget per category (₹)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                com.familyhub.app.ui.modules.financeCategories.filter { it != "Salary" }.forEach { c ->
                    TextInput(
                        c, values[c].orEmpty(), { v -> values[c] = v.filter { ch -> ch.isDigit() || ch == '.' } },
                        keyboard = androidx.compose.ui.text.input.KeyboardType.Number
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val map = HashMap<String, Double>()
                com.familyhub.app.ui.modules.financeCategories.forEach { c -> map[c] = parseNumber(values[c].orEmpty()) ?: 0.0 }
                onSave(day, map)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun StatBlock(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
        Text(value, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun csvCell(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""

/** Saves the period's entries as a CSV file and opens the share sheet (Sheets, Excel, email, WhatsApp). */
fun exportCsv(context: Context, rows: List<Rec>, label: String) {
    try {
        val sb = StringBuilder("Date,Type,Title,Category,Amount (INR),Paid by,Card,Member,Notes\n")
        for (r in rows.sortedBy { it.lng("dateMillis") ?: 0L }) {
            sb.append(
                listOf(
                    formatDate(r.lng("dateMillis")), r.str("type"), r.str("title"), r.str("category"),
                    "%.2f".format(amountOf(r)), r.str("paymentMethod"), r.str("card"), r.str("memberName"), r.str("notes")
                ).joinToString(",") { csvCell(it) }
            ).append('\n')
        }
        val file = File(context.cacheDir, "family-hub-" + label.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-') + ".csv")
        file.writeText(sb.toString())
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Family Hub expenses – $label")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Export expenses").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(context, "Could not export: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
