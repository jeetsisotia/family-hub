@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.familyhub.app.ui.money

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.familyhub.app.core.theme.GlassCard
import com.familyhub.app.core.theme.SegmentedControl
import com.familyhub.app.data.remote.PRIVATE_FLAG
import com.familyhub.app.data.remote.Rec
import com.familyhub.app.money.FinanceMonth
import com.familyhub.app.money.Ledger
import com.familyhub.app.money.MerchantRules
import com.familyhub.app.ui.AppEnv
import com.familyhub.app.ui.common.ScreenTopBar
import com.familyhub.app.ui.modules.FinanceModule
import com.familyhub.app.ui.modules.bool
import com.familyhub.app.ui.modules.financeCategories
import com.familyhub.app.ui.modules.localNoon
import com.familyhub.app.ui.modules.lng
import com.familyhub.app.ui.modules.noonToday
import com.familyhub.app.ui.modules.num
import com.familyhub.app.ui.modules.str
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

// ---------- categories ----------

/** Built-in income categories (spending ones are [financeCategories] without Salary). */
val incomeCategories = listOf("Salary", "Business", "Interest", "Rent received", "Gift", "Other")
val spendingCategories: List<String> get() = financeCategories.filter { it != "Salary" }

/** A custom category: its name, emoji and whether it is for spending, income or both. */
data class CustomCategory(val rec: Rec, val name: String, val emoji: String, val kind: String)

fun customCategories(items: List<Rec>): List<CustomCategory> =
    items.filter { it.str("type") == "category" && !it.bool("isDeleted") && it.str("parent").let { p -> p.isBlank() || p == "None" } }
        .map { CustomCategory(it, it.str("title").trim(), it.str("emoji").trim(), it.str("kind").ifBlank { "Spending" }) }
        .filter { it.name.isNotBlank() }

/** Categories offered for a kind: built-in first, then the family's own. */
fun categoriesFor(items: List<Rec>, kind: FinanceMonth.Kind): List<String> {
    val builtIn = if (kind == FinanceMonth.Kind.INCOME) incomeCategories else spendingCategories
    val own = customCategories(items).filter {
        val k = it.kind.lowercase()
        k == "both" || (kind == FinanceMonth.Kind.INCOME && k == "income") || (kind == FinanceMonth.Kind.EXPENSE && k != "income")
    }.map { it.name }
    return (builtIn + own).distinctBy { FinanceMonth.normName(it) }
}

/** A round badge: the custom emoji if the category has one, otherwise the built-in icon. */
@Composable
fun CategoryBadge(name: String, items: List<Rec>, size: Int = 34) {
    val emoji = remember(name, items) {
        customCategories(items).firstOrNull { FinanceMonth.normName(it.name) == FinanceMonth.normName(name) }?.emoji.orEmpty()
    }
    Box(
        modifier = Modifier.size(size.dp).clip(CircleShape).background(categoryColor(name).copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center
    ) {
        if (emoji.isNotBlank()) Text(emoji, fontSize = (size * 0.5f).sp)
        else Icon(categoryIcon(name), contentDescription = null, tint = categoryColor(name), modifier = Modifier.size((size * 0.55f).dp))
    }
}

// ---------- shared pieces ----------

private val Green = Color(0xFF34C759)
private val Red = Color(0xFFFF453A)

private fun rupees(p: Long) = inr(Ledger.rupees(p))

private fun monthTitle(r: FinanceMonth.Range) = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(r.from))

@Composable
private fun MonthBar(offset: Int, onChange: (Int) -> Unit, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = { onChange(offset - 1) }) { Icon(Icons.Rounded.ChevronLeft, contentDescription = "Previous month") }
        Text(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(onClick = { onChange(offset + 1) }, enabled = offset < 0) { Icon(Icons.Rounded.ChevronRight, contentDescription = "Next month") }
    }
}

@Composable
private fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 4.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f), modifier = Modifier.weight(1f))
        if (action != null && onAction != null) TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(action) }
    }
}

/** One compact transaction line: badge, title, category and date, amount (green for income). */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun TxLine(r: Rec, items: List<Rec>, selected: Boolean? = null, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val type = Ledger.type(r)
    val income = type == "INCOME"
    val date = r.lng("dateMillis")?.let { SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(it)) }.orEmpty()
    val cat = FinanceMonth.categoryOf(r)
    Row(
        modifier = Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(if (selected == true) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selected != null) {
            Icon(
                if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = if (selected) "Selected" else "Not selected",
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.padding(end = 8.dp)
            )
        }
        CategoryBadge(if (type in setOf("TRANSFER", "CARD_PAYMENT")) "Transfer" else cat, items)
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(r.str("title").ifBlank { cat }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(
                listOf(if (type in setOf("EXPENSE", "INCOME")) cat else type.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() },
                    r.str("card").takeIf { it.isNotBlank() && it != "None" } ?: r.str("account").takeIf { it.isNotBlank() && it != "None" }, date)
                    .filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        val amt = r.num("amountInr") ?: 0.0
        Text((if (income) "+" else "") + inr(amt), fontWeight = FontWeight.SemiBold, color = if (income) Green else Color.Unspecified,
            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"))
    }
}

/** Opens the existing detail sheet (refunds, merchant rules, mark paid) and the shared form for editing. */
@Composable
private fun TransactionDetails(env: AppEnv, rec: Rec, all: List<Rec>, onDismiss: () -> Unit, onEdit: (Rec) -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this entry?") },
            text = { Text("It moves to Recently deleted (Settings → Data & backup), where it can be restored.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDismiss()
                    env.scope.launch { try { env.repo.setFieldScoped(env.familyId, env.uid, "expenses", rec, "isDeleted", true) } catch (_: Exception) { } }
                }) { Text("Delete", color = Red) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
    TransactionSheet(
        rec = rec,
        all = all,
        onDismiss = onDismiss,
        onEdit = { onDismiss(); onEdit(rec) },
        onSet = { r, f -> env.scope.launch { try { env.repo.setFieldsScoped(env.familyId, env.uid, "expenses", r, f) } catch (_: Exception) { } } },
        onDelete = { confirmDelete = true },
        onRemember = { merchant, category ->
            env.scope.launch {
                try {
                    env.repo.saveScoped(
                        env.familyId, env.uid, "items",
                        mapOf("id" to MerchantRules.ruleId(merchant), PRIVATE_FLAG to true),
                        MerchantRules.ruleData(merchant, category, System.currentTimeMillis()), true
                    )
                } catch (_: Exception) { }
            }
        }
    )
}

// ---------- dashboard ----------

/**
 * The Finance tab: month, balance, income and spending against last month, budget, top 5 categories and the
 * latest 5 entries. Accounts, cards, loans, dues, imports and categories open from the tiles.
 */
@Composable
fun FinanceDashboard(env: AppEnv) {
    var familyView by rememberSaveable { mutableStateOf(false) }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Rec?>(null) }
    var open by remember { mutableStateOf<Rec?>(null) }
    val now = remember { System.currentTimeMillis() }
    val at = remember(offset) { FinanceMonth.month(now, offset).from + 1 }
    val range = remember(at) { FinanceMonth.month(at) }
    val data = remember(env.data, familyView) { viewData(env, familyView) }
    val expenses = data["expenses"].orEmpty()
    val items = env.data["items"].orEmpty()
    val v = remember(expenses, at) { FinanceMonth.view(expenses, at) }
    val budget = env.family.monthlyBudget

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenTopBar(title = "Finance", onBack = null, compact = true)
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    SegmentedControl(listOf("Mine", "Family (shared)"), if (familyView) "Family (shared)" else "Mine", { familyView = it != "Mine" })
                }
                item { MonthBar(offset, { offset = it }, monthTitle(range)) }
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth(), strong = true) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Balance this month", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            Text(rupees(v.balance), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                                color = if (v.balance < 0) Red else Color.Unspecified)
                            Row {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Income", style = MaterialTheme.typography.labelSmall)
                                    Text(rupees(v.income), fontWeight = FontWeight.SemiBold, color = Green)
                                    v.incomeChangePct?.let { Text(changeText(it, "last month"), style = MaterialTheme.typography.bodySmall) }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Expenses", style = MaterialTheme.typography.labelSmall)
                                    Text(rupees(v.spent), fontWeight = FontWeight.SemiBold)
                                    v.spentChangePct?.let { Text(changeText(it, "last month"), style = MaterialTheme.typography.bodySmall,
                                        color = if (it > 0) Red else Green) }
                                }
                            }
                            if (v.refunds > 0) Text("Refunds of ${rupees(v.refunds)} lowered spending.", style = MaterialTheme.typography.bodySmall)
                            if (budget > 0) {
                                val spentR = Ledger.rupees(v.spent)
                                val frac = (spentR / budget).toFloat().coerceIn(0f, 1f)
                                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                Row(modifier = Modifier.fillMaxWidth().clickable { env.navigate("insights") }) {
                                    Text("Budget ${inr(budget)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                    Text(if (spentR <= budget) "${inr(budget - spentR)} left" else "${inr(spentR - budget)} over",
                                        style = MaterialTheme.typography.bodySmall, color = if (spentR > budget) Red else Color.Unspecified)
                                }
                                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                    color = if (spentR > budget) Red else if (frac > 0.8f) Color(0xFFFF9F0A) else Green)
                            }
                        }
                    }
                }
                item {
                    Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add transaction")
                    }
                }
                item { SectionHeader("Top categories", "Insights") { env.navigate("insights") } }
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                            if (v.top.isEmpty()) Text("No spending this month.", modifier = Modifier.padding(12.dp))
                            val max = v.top.maxOfOrNull { it.second } ?: 1L
                            v.top.forEach { (cat, p) ->
                                Row(modifier = Modifier.fillMaxWidth().clickable { env.navigate("transactions") }.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    CategoryBadge(cat, items, 30)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row {
                                            Text(cat, modifier = Modifier.weight(1f), maxLines = 1)
                                            Text(rupees(p), fontWeight = FontWeight.SemiBold)
                                        }
                                        LinearProgressIndicator(progress = { (p.toFloat() / max).coerceIn(0f, 1f) },
                                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)), color = categoryColor(cat))
                                    }
                                }
                            }
                        }
                    }
                }
                item { SectionHeader("Latest", "View all") { env.navigate("transactions") } }
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            if (v.latest.isEmpty()) Text("No entries this month.", modifier = Modifier.padding(12.dp))
                            v.latest.forEach { r -> TxLine(r, items) { open = r } }
                        }
                    }
                }
                item { SectionHeader("Manage") }
                item { com.familyhub.app.ui.hub.HubTiles(com.familyhub.app.ui.hub.Hubs.money.items, env.navigate) }
                item {
                    TextButton(onClick = { env.navigate("finance_all") }, modifier = Modifier.fillMaxWidth()) {
                        Text("All entries, bills, EMIs and transfers")
                    }
                }
            }
        }
    }
    open?.let { r -> TransactionDetails(env, r, expenses, { open = null }) { editing = it } }
    if (adding) TransactionForm(env, null, FinanceMonth.Kind.EXPENSE) { adding = false }
    editing?.let { r -> TransactionForm(env, r, if (Ledger.type(r) == "INCOME") FinanceMonth.Kind.INCOME else FinanceMonth.Kind.EXPENSE) { editing = null } }
}

private fun changeText(pct: Int, than: String): String = when {
    pct > 0 -> "▲ $pct% vs $than"
    pct < 0 -> "▼ ${-pct}% vs $than"
    else -> "Same as $than"
}

// ---------- transactions ----------

/**
 * One place for spending and income: Expenses | Income, This month / Last month / a custom range (or any month with
 * the arrows), search, a category filter, the category breakdown, and history grouped by day.
 */
@Composable
fun TransactionsScreen(env: AppEnv) {
    var kind by rememberSaveable { mutableStateOf(FinanceMonth.Kind.EXPENSE) }
    var familyView by rememberSaveable { mutableStateOf(false) }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var custom by remember { mutableStateOf<FinanceMonth.Range?>(null) }
    var pickRange by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var showBreakdown by rememberSaveable { mutableStateOf(true) }
    var open by remember { mutableStateOf<Rec?>(null) }
    var editing by remember { mutableStateOf<Rec?>(null) }
    var adding by remember { mutableStateOf(false) }
    var source by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var pickFor by remember { mutableStateOf(false) }
    var plan by remember { mutableStateOf<Pair<com.familyhub.app.money.Recategorize.Plan, String>?>(null) }
    var lastChange by remember { mutableStateOf<Long?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val selecting = selected.isNotEmpty()
    androidx.activity.compose.BackHandler(enabled = selecting) { selected = emptySet() }
    val now = remember { System.currentTimeMillis() }
    val range = custom ?: FinanceMonth.month(now, offset)
    val data = remember(env.data, familyView) { viewData(env, familyView) }
    val expenses = data["expenses"].orEmpty()
    val items = env.data["items"].orEmpty()
    val all = remember(expenses, kind, range) { FinanceMonth.list(expenses, kind, range.from, range.to) }
    val sources = remember(all) { com.familyhub.app.money.Recategorize.sources(all).take(8) }
    val list = remember(expenses, kind, range, query, category, source) {
        FinanceMonth.list(expenses, kind, range.from, range.to, query, category)
            .filter { source.isBlank() || com.familyhub.app.money.Recategorize.sourceOf(it).equals(source, true) }
    }
    val breakdown = remember(all, kind) { FinanceMonth.breakdown(all, kind) }
    val days = remember(list) { FinanceMonth.byDay(list) }
    val fmtDay = remember { SimpleDateFormat("EEE, d MMM", Locale.getDefault()) }
    val fmtShort = remember { SimpleDateFormat("d MMM", Locale.getDefault()) }
    val label = custom?.let { fmtShort.format(Date(it.from)) + " – " + fmtShort.format(Date(it.to)) } ?: monthTitle(range)

    Column(modifier = Modifier.fillMaxSize()) {
        if (selecting) {
            ScreenTopBar(title = "${selected.size} selected", onBack = { selected = emptySet() }, compact = true) {
                TextButton(onClick = { selected = selected + list.map { it.str("id") } }) { Text("All") }
                TextButton(onClick = { pickFor = true }) { Text("Category", fontWeight = FontWeight.SemiBold) }
            }
        } else ScreenTopBar(title = "Transactions", onBack = env.onBack, compact = true) {
            IconButton(onClick = { adding = true }) { Icon(Icons.Rounded.Add, contentDescription = "Add transaction") }
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                SegmentedControl(listOf("Expenses", "Income"), if (kind == FinanceMonth.Kind.EXPENSE) "Expenses" else "Income", {
                    kind = if (it == "Income") FinanceMonth.Kind.INCOME else FinanceMonth.Kind.EXPENSE
                    category = ""
                })
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = custom == null && offset == 0, onClick = { custom = null; offset = 0 }, label = { Text("This month") })
                    FilterChip(selected = custom == null && offset == -1, onClick = { custom = null; offset = -1 }, label = { Text("Last month") })
                    FilterChip(selected = custom != null, onClick = { pickRange = true }, label = { Text("Custom") })
                    Spacer(modifier = Modifier.weight(1f))
                    FilterChip(selected = familyView, onClick = { familyView = !familyView }, label = { Text("Family") })
                }
            }
            if (custom == null) item { MonthBar(offset, { offset = it }, label) }
            else item { Text(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp)) }
            item {
                GlassCard(modifier = Modifier.fillMaxWidth(), strong = true) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(if (kind == FinanceMonth.Kind.EXPENSE) "Total spent" else "Total income", style = MaterialTheme.typography.labelMedium)
                            Text(rupees(FinanceMonth.total(list, kind)), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                                color = if (kind == FinanceMonth.Kind.INCOME) Green else Color.Unspecified)
                            Text("${list.size} " + if (list.size == 1) "entry" else "entries", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { showBreakdown = !showBreakdown }) { Text(if (showBreakdown) "Hide breakdown" else "Breakdown") }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("Search name, shop, amount") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, contentDescription = "Clear") } },
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()
                )
            }
            if (sources.size > 1 || source.isNotBlank()) item {
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { FilterChip(selected = source.isBlank(), onClick = { source = "" }, label = { Text("All accounts") }) }
                    items(sources) { a -> FilterChip(selected = source.equals(a, true), onClick = { source = if (source.equals(a, true)) "" else a }, label = { Text(a, maxLines = 1) }) }
                }
            }
            lastChange?.let { at ->
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                        Text("Category changed.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            CategoryAudit.list(ctx).firstOrNull { it.at == at }?.let { undoCategoryChange(env, ctx, it) }
                            lastChange = null
                        }) { Text("Undo") }
                    }
                }
            }
            if (!selecting && list.isNotEmpty()) item {
                Text("Tip: touch and hold an entry to select several and change their category.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f))
            }
            if (showBreakdown && breakdown.isNotEmpty()) {
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            breakdown.forEach { (cat, p, pct) ->
                                val sel = category.equals(cat, true)
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable { category = if (sel) "" else cat }
                                        .background(if (sel) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent)
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CategoryBadge(cat, items, 28)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(cat, modifier = Modifier.weight(1f), maxLines = 1)
                                    Text("$pct%", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 10.dp))
                                    Text(rupees(p), fontWeight = FontWeight.SemiBold)
                                }
                            }
                            Text(if (category.isBlank()) "Tap a category to show only it." else "Showing $category. Tap it again to show all.",
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                        }
                    }
                }
            } else if (category.isNotBlank()) {
                item {
                    AssistChip(onClick = { category = "" }, label = { Text("$category  ✕") })
                }
            }
            if (days.isEmpty()) {
                item {
                    Text(
                        if (query.isNotBlank() || category.isNotBlank()) "Nothing matches." else "No entries for this period.",
                        modifier = Modifier.fillMaxWidth().padding(24.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
            days.forEach { (day, rows) ->
                item(key = "d$day") {
                    Row(modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp)) {
                        Text(fmtDay.format(Date(day)), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                        Text(rupees(FinanceMonth.total(rows, kind)), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                    }
                }
                item(key = "g$day") {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(vertical = 2.dp)) {
                            rows.forEach { r ->
                                val id = r.str("id")
                                TxLine(
                                    r, items,
                                    selected = if (selecting) id in selected else null,
                                    onLongClick = { selected = if (id in selected) selected - id else selected + id }
                                ) {
                                    if (selecting) selected = if (id in selected) selected - id else selected + id else open = r
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (pickRange) {
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = custom?.from, initialSelectedEndDateMillis = custom?.to
        )
        DatePickerDialog(
            onDismissRequest = { pickRange = false },
            confirmButton = {
                TextButton(enabled = state.selectedStartDateMillis != null, onClick = {
                    val s = state.selectedStartDateMillis
                    if (s != null) {
                        val e = state.selectedEndDateMillis ?: s
                        custom = FinanceMonth.Range(Ledger.startOfDay(localNoon(s)), Ledger.startOfDay(localNoon(e)) + Ledger.DAY - 1)
                    }
                    pickRange = false
                }) { Text("Show") }
            },
            dismissButton = { TextButton(onClick = { pickRange = false }) { Text("Cancel") } }
        ) {
            DateRangePicker(state = state, modifier = Modifier.weight(1f))
        }
    }
    if (pickFor) CategoryPickerDialog(env, kind, "Move ${selected.size} to…", { pickFor = false }) { cat ->
        pickFor = false
        plan = com.familyhub.app.money.Recategorize.forIds(expenses, selected, cat) to cat
    }
    plan?.let { (p, cat) ->
        ConfirmCategoryPlan(p, cat, { plan = null }) {
            plan = null
            applyCategoryPlan(env, ctx, p, "${p.count} to $cat") { at -> if (at != null) { lastChange = at; selected = emptySet() } }
        }
    }
    open?.let { r -> TransactionDetails(env, r, expenses, { open = null }) { editing = it } }
    if (adding) TransactionForm(env, null, kind) { adding = false }
    editing?.let { r -> TransactionForm(env, r, kind) { editing = null } }
}

// ---------- the shared add / edit form ----------

/**
 * Add or edit a transaction: type, amount, category (with icon), account or card, date, description, and an
 * optional receipt (collapsed). Entries of other types (bills, EMIs, transfers) keep their type when edited.
 * Saves the same fields as the full form, through the same rules, so balances and reports are unchanged.
 */
@Composable
fun TransactionForm(env: AppEnv, existing: Rec?, initialKind: FinanceMonth.Kind, onDismiss: () -> Unit) {
    val items = env.data["items"].orEmpty()
    val oldType = existing?.let { Ledger.type(it) }
    val simple = oldType == null || oldType == "EXPENSE" || oldType == "INCOME"
    var kind by remember { mutableStateOf(if (oldType == "INCOME") FinanceMonth.Kind.INCOME else if (oldType == null) initialKind else FinanceMonth.Kind.EXPENSE) }
    var amount by remember { mutableStateOf(existing?.num("amountInr")?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }.orEmpty()) }
    var category by remember { mutableStateOf(existing?.str("category").orEmpty()) }
    var title by remember { mutableStateOf(existing?.str("title").orEmpty()) }
    var dateMillis by remember { mutableLongStateOf(existing?.lng("dateMillis") ?: noonToday()) }
    val accounts = remember(items) { items.filter { it.str("type") == "account" && !it.bool("isDeleted") } }
    val cards = remember(items) { items.filter { (it.str("type") == "card" || it.str("type") == "debit_card") && !it.bool("isDeleted") } }
    var source by remember {
        mutableStateOf(
            existing?.str("card")?.takeIf { it.isNotBlank() && it != "None" }?.let { "card:$it" }
                ?: existing?.str("account")?.takeIf { it.isNotBlank() && it != "None" }?.let { "account:$it" } ?: ""
        )
    }
    var onlyMe by remember { mutableStateOf(existing?.get(PRIVATE_FLAG) == true) }
    var photo by remember { mutableStateOf(existing?.str("proofPhoto")?.takeIf { it.isNotBlank() }) }
    var showReceipt by remember { mutableStateOf(photo != null) }
    var pickDate by remember { mutableStateOf(false) }
    var pickSource by remember { mutableStateOf(false) }
    var newCategory by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val cats = remember(items, kind) { categoriesFor(items, kind) }
    val fmt = remember { SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()) }
    // A new entry gets its id now, so saving twice (or again while offline) updates the same record, never a copy.
    val newId = remember { UUID.randomUUID().toString().replace("-", "").take(20) }
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext

    fun save() {
        val amt = amount.replace(",", "").trim().toDoubleOrNull()
        if (amt == null || amt <= 0.0) { error = "Enter an amount above zero."; return }
        val cat = category.ifBlank { "Other" }
        val data = HashMap<String, Any?>()
        if (existing != null) data.putAll(existing.filterKeys { it != "id" && it != PRIVATE_FLAG })
        data["type"] = if (simple) (if (kind == FinanceMonth.Kind.INCOME) "INCOME" else "EXPENSE") else oldType
        data["amountInr"] = amt
        data["category"] = cat
        data["title"] = title.trim().ifBlank { cat }
        data["dateMillis"] = dateMillis
        data["isDeleted"] = false
        when {
            source.startsWith("card:") -> {
                val name = source.removePrefix("card:")
                val debit = cards.any { it.str("title") == name && it.str("type") == "debit_card" }
                data["card"] = name
                data["account"] = "None"
                data["paymentMethod"] = if (debit) "Debit card" else "Credit card"
            }
            source.startsWith("account:") -> {
                val name = source.removePrefix("account:")
                val cash = accounts.any { it.str("title") == name && it.str("category").equals("Cash", true) } || name.equals("Cash", true)
                data["account"] = name
                data["card"] = "None"
                val pm = (data["paymentMethod"] as? String).orEmpty()
                data["paymentMethod"] = if (cash) "Cash" else if (pm.isBlank() || pm == "Credit card" || pm == "Debit card" || pm == "Cash") "UPI" else pm
            }
            else -> {
                data["account"] = "None"; data["card"] = "None"
                if ((data["paymentMethod"] as? String).isNullOrBlank()) data["paymentMethod"] = "UPI"
            }
        }
        if (photo != null) data["proofPhoto"] = photo else data.remove("proofPhoto")
        data["familyId"] = env.familyId
        data["updatedAt"] = System.currentTimeMillis()
        if (existing == null) {
            data["createdByUid"] = env.uid
            data["createdByName"] = env.profile.name
            data["createdAt"] = System.currentTimeMillis()
        }
        FinanceModule.postProcess?.invoke(data, env.uid, env.profile.name)
        val target = existing ?: mapOf("id" to newId, PRIVATE_FLAG to onlyMe)
        // Firestore keeps the change on the phone at once and sends it when online, so the sheet closes now.
        onDismiss()
        env.scope.launch {
            try {
                env.repo.saveScoped(env.familyId, env.uid, "expenses", target, data, onlyMe)
            } catch (e: Exception) {
                android.widget.Toast.makeText(appContext, "Could not save the transaction: ${e.message?.take(80)}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(if (existing == null) "Add transaction" else "Edit transaction", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (simple) {
                SegmentedControl(listOf("Expense", "Income"), if (kind == FinanceMonth.Kind.INCOME) "Income" else "Expense", {
                    kind = if (it == "Income") FinanceMonth.Kind.INCOME else FinanceMonth.Kind.EXPENSE
                })
            } else {
                Text("Type: " + (oldType ?: "").lowercase().replace('_', ' ') + " (kept)", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = amount, onValueChange = { s -> amount = s.filter { it.isDigit() || it == '.' || it == ',' } },
                label = { Text("Amount (₹)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                textStyle = MaterialTheme.typography.headlineSmall,
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()
            )
            Text("Category", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (cats + listOfNotNull(category.takeIf { it.isNotBlank() && cats.none { c -> c.equals(it, true) } })).forEach { c ->
                    FilterChip(
                        selected = category.equals(c, true), onClick = { category = c },
                        leadingIcon = { CategoryBadge(c, items, 22) }, label = { Text(c) }
                    )
                }
                AssistChip(onClick = { newCategory = true }, label = { Text("New") }, leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) })
            }
            OutlinedTextField(
                value = title, onValueChange = { title = it.take(120) }, label = { Text("Description (optional)") },
                singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(fmt.format(Date(dateMillis)), maxLines = 1)
                }
            }
            Box {
                OutlinedButton(onClick = { pickSource = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(if (source.startsWith("card:")) Icons.Rounded.CreditCard else Icons.Rounded.AccountBalance, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(source.substringAfter(":").ifBlank { if (kind == FinanceMonth.Kind.INCOME) "Received in: not set" else "Paid from: not set" }, maxLines = 1)
                }
                DropdownMenu(expanded = pickSource, onDismissRequest = { pickSource = false }) {
                    DropdownMenuItem(text = { Text("Not set") }, onClick = { source = ""; pickSource = false })
                    accounts.forEach { a ->
                        DropdownMenuItem(text = { Text(a.str("title")) }, leadingIcon = { Icon(Icons.Rounded.AccountBalance, contentDescription = null) },
                            onClick = { source = "account:" + a.str("title"); pickSource = false })
                    }
                    if (kind == FinanceMonth.Kind.EXPENSE) cards.forEach { c ->
                        DropdownMenuItem(text = { Text(c.str("title") + (c.str("last4").takeIf { it.isNotBlank() }?.let { " ••$it" } ?: "")) },
                            leadingIcon = { Icon(Icons.Rounded.CreditCard, contentDescription = null) },
                            onClick = { source = "card:" + c.str("title"); pickSource = false })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onlyMe = !onlyMe }) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Only me")
                    Text("Off: the family can see it.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = onlyMe, onCheckedChange = { onlyMe = it })
            }
            if (!showReceipt) {
                TextButton(onClick = { showReceipt = true }) {
                    Icon(Icons.Rounded.AttachFile, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add a receipt (optional)")
                }
            } else {
                com.familyhub.app.ui.modules.PhotoField("Receipt or payment proof", photo) { photo = it }
            }
            error?.let { Text(it, color = Red) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(onClick = { save() }, modifier = Modifier.weight(1f)) { Text("Save") }
            }
        }
    }
    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = dateMillis)
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = { state.selectedDateMillis?.let { dateMillis = localNoon(it) }; pickDate = false }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } }
        ) { DatePicker(state = state) }
    }
    if (newCategory) {
        CategoryDialog(env, null, if (kind == FinanceMonth.Kind.INCOME) "Income" else "Spending", onDismiss = { newCategory = false }) { name ->
            category = name
            newCategory = false
        }
    }
}

// ---------- categories ----------

private val EMOJI_CHOICES = listOf(
    "🛒", "🍽️", "☕", "🍕", "🚗", "⛽", "🚌", "✈️", "🏠", "💡", "📱", "💻", "🎓", "📚", "💊", "🏥",
    "🎬", "🎮", "🎁", "👕", "💄", "🐶", "🧸", "👶", "🏋️", "⚽", "🎵", "💼", "💰", "🏦", "📈", "🧾",
    "🛠️", "🧹", "🌱", "🙏", "❤️", "🎉", "📦", "🔁"
)

/**
 * Add or edit a custom category: name, emoji (with a preview), and spending, income or both. Duplicates of a
 * built-in or existing category for the same type are refused. Saved as a family record, so every member sees it.
 */
@Composable
fun CategoryDialog(env: AppEnv, existing: CustomCategory?, defaultKind: String, onDismiss: () -> Unit, onSaved: (String) -> Unit) {
    val items = env.data["items"].orEmpty()
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var emoji by remember { mutableStateOf(existing?.emoji.orEmpty()) }
    var kind by remember { mutableStateOf(existing?.kind ?: defaultKind) }
    var error by remember { mutableStateOf<String?>(null) }
    val others = remember(items) { customCategories(items).filter { it.rec.str("id") != existing?.rec?.str("id") }.map { it.name to it.kind } }
    val dup = FinanceMonth.isDuplicateCategory(name, kind, others, spendingCategories, incomeCategories, ignoreName = existing?.name.orEmpty())
    val newId = remember { UUID.randomUUID().toString().replace("-", "").take(20) }
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New category" else "Edit category") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Preview, as it will look in lists.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(categoryColor(name.ifBlank { "Other" }).copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center) {
                        if (emoji.isNotBlank()) Text(emoji, fontSize = 20.sp)
                        else Icon(categoryIcon(name), contentDescription = null, tint = categoryColor(name.ifBlank { "Other" }))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(name.ifBlank { "Category name" }, fontWeight = FontWeight.SemiBold)
                }
                OutlinedTextField(value = name, onValueChange = { name = it.take(30) }, label = { Text("Name") }, singleLine = true,
                    isError = dup, supportingText = if (dup) ({ Text("Already exists for this type") }) else null,
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                SegmentedControl(listOf("Spending", "Income", "Both"), kind, { kind = it })
                Text("Icon", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    EMOJI_CHOICES.forEach { e ->
                        Box(
                            modifier = Modifier.size(36.dp).clip(CircleShape)
                                .background(if (e == emoji) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent)
                                .clickable { emoji = if (emoji == e) "" else e },
                            contentAlignment = Alignment.Center
                        ) { Text(e, fontSize = 18.sp) }
                    }
                }
                OutlinedTextField(value = emoji, onValueChange = { emoji = it.take(8) }, label = { Text("Or type any emoji") }, singleLine = true,
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = Red) }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && !dup, onClick = {
                val clean = name.trim().replace(Regex("\\s+"), " ")
                val data = HashMap<String, Any?>()
                if (existing != null) data.putAll(existing.rec.filterKeys { it != "id" && it != PRIVATE_FLAG })
                data["type"] = "category"
                data["title"] = clean
                data["emoji"] = emoji.trim()
                data["kind"] = kind
                data["parent"] = "None"
                data["isDeleted"] = false
                data["familyId"] = env.familyId
                data["updatedAt"] = System.currentTimeMillis()
                if (existing == null) data["createdByUid"] = env.uid
                val private = existing?.rec?.get(PRIVATE_FLAG) == true
                onSaved(clean)
                env.scope.launch {
                    try {
                        // Shared with the family (not "Only me"), so every member gets the same categories. A fixed id
                        // means a repeated save never creates a second copy.
                        env.repo.saveScoped(env.familyId, env.uid, "items", existing?.rec ?: mapOf("id" to newId), data, private)
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(appContext, "Could not save the category: ${e.message?.take(80)}", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Categories: built-in ones (always there) and the family's own, for spending or income. "Apply to existing"
 * moves chosen past transactions into a category. Renaming offers to rename it on past transactions too, and
 * deleting moves its transactions to another category (or leaves them). Every bulk change is confirmed with a
 * count and can be undone from Recent changes.
 */
@Composable
fun CategoriesScreen(env: AppEnv) {
    val items = env.data["items"].orEmpty()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var tab by rememberSaveable { mutableStateOf("Spending") }
    var editing by remember { mutableStateOf<CustomCategory?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CustomCategory?>(null) }
    var applyTo by remember { mutableStateOf<String?>(null) }
    var renamed by remember { mutableStateOf<Pair<String, String>?>(null) }
    var moveTo by remember { mutableStateOf("Other") }
    var pickMove by remember { mutableStateOf(false) }
    var changesTick by remember { mutableIntStateOf(0) }
    val expenses = env.data["expenses"].orEmpty()
    val tabKind = if (tab == "Income") FinanceMonth.Kind.INCOME else FinanceMonth.Kind.EXPENSE
    val own = remember(items, tab) {
        customCategories(items).filter { val k = it.kind.lowercase(); k == "both" || k == tab.lowercase() }
    }
    val builtIn = if (tab == "Income") incomeCategories else spendingCategories
    val used = remember(env.data) {
        env.data["expenses"].orEmpty().filter { Ledger.live(it) }.groupingBy { FinanceMonth.normName(FinanceMonth.categoryOf(it)) }.eachCount()
    }
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Categories", onBack = env.onBack, compact = true) {
            IconButton(onClick = { adding = true }) { Icon(Icons.Rounded.Add, contentDescription = "New category") }
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { SegmentedControl(listOf("Spending", "Income"), tab, { tab = it }) }
            item { SectionHeader("Your family's categories") }
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        if (own.isEmpty()) Text("None yet. Tap + to add one with its own icon.", modifier = Modifier.padding(12.dp))
                        own.forEach { c ->
                            Row(modifier = Modifier.fillMaxWidth().clickable { editing = c }.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                CategoryBadge(c.name, items, 32)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(c.name, fontWeight = FontWeight.Medium)
                                    Text(c.kind + " · used ${used[FinanceMonth.normName(c.name)] ?: 0} times", style = MaterialTheme.typography.bodySmall)
                                }
                                IconButton(onClick = { applyTo = c.name }) { Icon(Icons.Rounded.PlaylistAddCheck, contentDescription = "Apply ${c.name} to existing transactions") }
                                IconButton(onClick = { editing = c }) { Icon(Icons.Rounded.Edit, contentDescription = "Edit ${c.name}") }
                                IconButton(onClick = { deleting = c; moveTo = "Other" }) { Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete ${c.name}") }
                            }
                        }
                    }
                }
            }
            item { SectionHeader("Built in") }
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        builtIn.forEach { c ->
                            Row(modifier = Modifier.fillMaxWidth().clickable { applyTo = c }.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                CategoryBadge(c, items, 32)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(c, modifier = Modifier.weight(1f))
                                Text("used ${used[FinanceMonth.normName(c)] ?: 0}", style = MaterialTheme.typography.bodySmall)
                                IconButton(onClick = { applyTo = c }) { Icon(Icons.Rounded.PlaylistAddCheck, contentDescription = "Apply $c to existing transactions") }
                            }
                        }
                    }
                }
            }
            item { SectionHeader("Recent changes") }
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) { key(changesTick) { RecentCategoryChanges(env) } }
            }
            item {
                Text(
                    "Tap ✓ (Apply to existing) on a category to move past transactions into it. In Transactions you can also " +
                        "touch and hold entries to select several. Subcategories and category budgets are in the full list " +
                        "(Finance → All entries → Money categories). Recent changes are kept on this phone.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 6.dp)
                )
            }
        }
    }
    if (adding) CategoryDialog(env, null, tab, { adding = false }) { adding = false }
    editing?.let { c ->
        CategoryDialog(env, c, c.kind, { editing = null }) { newName ->
            editing = null
            if (newName != c.name && com.familyhub.app.money.Recategorize.usage(expenses, c.name) > 0) renamed = c.name to newName
        }
    }
    renamed?.let { (old, new) ->
        val p = remember(old, new, expenses) { com.familyhub.app.money.Recategorize.rename(expenses, old, new) }
        AlertDialog(
            onDismissRequest = { renamed = null },
            title = { Text("Rename on past transactions too?") },
            text = { Text("${p.count} ${if (p.count == 1) "transaction uses" else "transactions use"} \"$old\". Rename them to \"$new\" as well? Only the category changes, and you can undo it from Recent changes.") },
            confirmButton = {
                TextButton(onClick = {
                    renamed = null
                    applyCategoryPlan(env, ctx, p, "${p.count} from $old to $new") { changesTick++ }
                }) { Text("Rename ${p.count}", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { renamed = null }) { Text("Keep old name on them") } }
        )
    }
    deleting?.let { c ->
        val n = com.familyhub.app.money.Recategorize.usage(expenses, c.name)
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${c.name}\"?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (n > 0) {
                        Text("$n ${if (n == 1) "transaction uses" else "transactions use"} it. Move them to:")
                        OutlinedButton(onClick = { pickMove = true }) { Text(moveTo) }
                        Text("Or keep them as they are with \"Delete only\".", style = MaterialTheme.typography.bodySmall)
                    } else Text("It is not used by any transaction.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    if (n > 0) {
                        val p = com.familyhub.app.money.Recategorize.moveAll(expenses, c.name, moveTo)
                        applyCategoryPlan(env, ctx, p, "${p.count} from ${c.name} to $moveTo") { changesTick++ }
                    }
                    env.scope.launch { try { env.repo.setFieldScoped(env.familyId, env.uid, "items", c.rec, "isDeleted", true) } catch (_: Exception) { } }
                }) { Text(if (n > 0) "Move $n and delete" else "Delete", color = Red) }
            },
            dismissButton = {
                Row {
                    if (n > 0) TextButton(onClick = {
                        deleting = null
                        env.scope.launch { try { env.repo.setFieldScoped(env.familyId, env.uid, "items", c.rec, "isDeleted", true) } catch (_: Exception) { } }
                    }) { Text("Delete only") }
                    TextButton(onClick = { deleting = null }) { Text("Cancel") }
                }
            }
        )
        if (pickMove) CategoryPickerDialog(env, tabKind, "Move to…", { pickMove = false }) { moveTo = it; pickMove = false }
    }
    applyTo?.let { cat -> ApplyToExistingSheet(env, cat, tabKind) { applyTo = null; changesTick++ } }
}
