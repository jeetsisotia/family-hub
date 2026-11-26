package com.familyhub.app.ui.money

import android.app.DatePickerDialog
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.familyhub.app.core.theme.GlassCard
import com.familyhub.app.data.remote.Rec
import com.familyhub.app.money.Ledger
import com.familyhub.app.ui.AppEnv
import com.familyhub.app.ui.common.PhotoCodec
import com.familyhub.app.ui.common.ScreenTopBar
import com.familyhub.app.ui.common.formatDate
import com.familyhub.app.ui.common.plural
import com.familyhub.app.ui.hub.homeViewFilter
import com.familyhub.app.ui.modules.str
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val Green = Color(0xFF1B8A3A)
private val Red = Color(0xFFD93025)
private val Amber = Color(0xFFD97706)

private fun monthRange(offset: Int = 0): Pair<Long, Long> {
    val c = Calendar.getInstance()
    c.add(Calendar.MONTH, offset)
    c.set(Calendar.DAY_OF_MONTH, 1)
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    val from = c.timeInMillis
    c.add(Calendar.MONTH, 1)
    return from to c.timeInMillis - 1
}

/** Family View counts shared entries; My View counts the person's own. */
fun viewData(env: AppEnv, familyView: Boolean): Map<String, List<Rec>> {
    val f = homeViewFilter(familyView, env.uid, env.profile.name)
    return env.data.mapValues { (_, list) -> list.filter(f) }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), maxLines = 1)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
    }
}

/** The top of the Finance tab: balances, this month, cards, loans and what is due, for the family or just you. */
@Composable
fun FinanceOverview(env: AppEnv) {
    // Accounts and cards start as "Only me", so the person's own view comes first.
    var familyView by rememberSaveable { mutableStateOf(false) }
    val data = remember(env.data, familyView) { viewData(env, familyView) }
    val (from, to) = remember { monthRange() }
    val now = remember { System.currentTimeMillis() }
    // The view picks whose accounts, cards, loans and spending count; balances use every entry on those accounts.
    val all = env.data["expenses"].orEmpty()
    val s = remember(data) { Ledger.summary(data, from, to, now, all) }
    val dues = remember(data) {
        Ledger.dues(mapOf("items" to data["items"].orEmpty(), "expenses" to all), from - 60 * Ledger.DAY, to + 7 * Ledger.DAY, now)
            .filter { it.status != "PAID" && (it.sourceCollection != "expenses" || data["expenses"].orEmpty().any { e -> e.str("id") == it.sourceId }) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(selected = !familyView, onClick = { familyView = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Mine") }
            SegmentedButton(selected = familyView, onClick = { familyView = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Family (shared)") }
        }
        GlassCard(modifier = Modifier.fillMaxWidth(), strong = true) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Available in bank", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Text(inr(s.bank), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Row { Stat("Cash", inr(s.cash), Modifier.weight(1f)); Stat("Savings rate", "${s.savingsPct}%", Modifier.weight(1f), if (s.savingsPct >= 0) Green else Red) }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                Row { Stat("Income", inr(s.income), Modifier.weight(1f), Green); Stat("Spent", inr(s.spent), Modifier.weight(1f)); Stat("Saved", inr(s.savings), Modifier.weight(1f), if (s.savings >= 0) Green else Red) }
                Row { Stat("Card outstanding", inr(s.cardOutstanding), Modifier.weight(1f), if (s.cardOutstanding > 0) Amber else Color.Unspecified); Stat("Loans left", inr(s.loanOutstanding), Modifier.weight(1f)) }
                Row { Stat("EMIs due", inr(s.emisDue), Modifier.weight(1f)); Stat("Card bills due", inr(s.cardBillsDue), Modifier.weight(1f)); Stat("All dues", inr(s.liabilities), Modifier.weight(1f), if (s.liabilities > 0) Red else Color.Unspecified) }
            }
        }
        if (dues.isNotEmpty()) {
            Text("NEXT DUE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f))
            dues.take(3).forEach { d -> DueRow(env, d) }
            if (dues.size > 3) TextButton(onClick = { env.navigate("due_calendar") }) { Text("All ${dues.size} dues") }
        }
    }
}

// ---------- dues ----------

/** Home: what needs paying now (overdue and the next 10 days). Tap one to pay, snooze or move it. */
@Composable
fun HomeDues(env: AppEnv) {
    val now = remember { System.currentTimeMillis() }
    val dues = remember(env.data) {
        Ledger.dues(env.data, now - 60 * Ledger.DAY, now + 10 * Ledger.DAY, now)
            .filter { it.status != "PAID" && (it.snoozedUntil ?: 0L) <= now }
    }
    if (dues.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "TO PAY • ${inr(dues.sumOf { it.amount })}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { env.navigate("due_calendar") }) { Text("Due calendar") }
        }
        dues.take(4).forEach { d -> DueRow(env, d) }
        if (dues.size > 4) Text("+${dues.size - 4} more in the due calendar", style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Home's at-a-glance strip: money in accounts, spending this month and card dues for the chosen view, then
 * today's activity from this phone (steps, sleep, active calories). Health numbers are the person's own and
 * stay on the phone; a dash means there is no reading.
 */
@Composable
fun HomeGlance(env: AppEnv, familyView: Boolean) {
    val context = LocalContext.current
    val data = remember(env.data, familyView) { viewData(env, familyView) }
    val (from, to) = remember { monthRange() }
    val now = remember { System.currentTimeMillis() }
    val s = remember(data) { Ledger.summary(data, from, to, now, env.data["expenses"].orEmpty()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), onClick = { env.navigate("finance") }) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (familyView) "FAMILY MONEY THIS MONTH" else "MY MONEY THIS MONTH",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("In accounts", inrShort(s.bank + s.cash), Modifier.weight(1f))
                    Stat("Spent", inrShort(s.spent), Modifier.weight(1f))
                    Stat("Card dues", inrShort(s.cardOutstanding), Modifier.weight(1f), if (s.cardOutstanding > 0) Red else Color.Unspecified)
                }
                if (s.income > 0) Text(
                    "Income ${inr(s.income)} • saved ${s.savingsPct}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
        val steps = remember { com.familyhub.app.health.StepTracker.today(context) }
        val hc = remember { com.familyhub.app.health.HealthConnectSync.day(context) }
        GlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), onClick = { env.navigate("health_hub") }) {
            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("Steps today", java.text.NumberFormat.getIntegerInstance().format(steps), Modifier.weight(1f), Color(0xFF34C759))
                Stat("Sleep", hc.sleepMin?.let { "${it / 60}h ${it % 60}m" } ?: "—", Modifier.weight(1f))
                Stat("Active", hc.activeKcal?.let { "${it.toInt()} kcal" } ?: "—", Modifier.weight(1f))
            }
        }
    }
}

private fun dueIcon(kind: String) = when (kind) {
    "card" -> Icons.Rounded.CreditCard
    "emi" -> Icons.Rounded.RequestQuote
    "subscription" -> Icons.Rounded.Subscriptions
    "policy" -> Icons.Rounded.Shield
    else -> Icons.Rounded.ReceiptLong
}

private fun statusColor(s: String) = when (s) { "PAID" -> Green; "OVERDUE" -> Red; else -> Amber }

/** One due with its status; tap for Mark paid, Snooze, Reschedule, Edit, Delete, proof and the payment. */
@Composable
fun DueRow(env: AppEnv, d: Ledger.Due) {
    var open by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val snoozed = (d.snoozedUntil ?: 0L) > now && d.status != "PAID"
    GlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), onClick = { open = true }) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(38.dp).clip(CircleShape).background(statusColor(d.status).copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(dueIcon(d.kind), contentDescription = null, tint = statusColor(d.status), modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(d.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        formatDate(d.dueMillis),
                        when { d.status == "PAID" -> "Paid"; d.status == "OVERDUE" -> "Overdue"; snoozed -> "Snoozed till " + formatDate(d.snoozedUntil); else -> "Unpaid" },
                        d.minAmount?.takeIf { it > 0 && d.status != "PAID" }?.let { "min " + inr(it) }
                    ).joinToString(" • "),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (d.status == "OVERDUE") Red else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1
                )
            }
            Text(inr(d.amount), fontWeight = FontWeight.Bold, color = if (d.status == "PAID") Green else Color.Unspecified)
        }
    }
    if (open) DueSheet(env, d) { open = false }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DueSheet(env: AppEnv, d: Ledger.Due, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accounts = env.data["items"].orEmpty().filter { it.str("type") == "account" }.map { it.str("title") }
    var amount by remember { mutableStateOf(Ledger.round2(d.amount).let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }) }
    var account by remember { mutableStateOf(accounts.firstOrNull().orEmpty()) }
    var proof by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val enc = PhotoCodec.encode(context, uri, 900, 70)
            if (enc != null) {
                proof = enc
                if (d.status == "PAID") scope.launch { DueActions.attachProof(env, d, enc); onDismiss() }
            }
        }
    }
    fun act(block: suspend () -> Unit) {
        busy = true
        scope.launch {
            try { block() } catch (e: Exception) { android.widget.Toast.makeText(context, "Could not save: ${e.message}", android.widget.Toast.LENGTH_LONG).show() }
            busy = false
            onDismiss()
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (d.sourceCollection == "expenses") "Delete this due?" else "Skip this one?") },
            text = { Text(if (d.sourceCollection == "expenses") "${d.title} will be removed." else "Only ${formatDate(d.dueMillis)} is skipped; later dues stay.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; act { DueActions.delete(env, d) } }) { Text("Delete", color = Red) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(d.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Due ${formatDate(d.dueMillis)} • ${inr(d.amount)}" + (d.minAmount?.let { " • minimum ${inr(it)}" } ?: ""), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            if (d.status != "PAID") {
                OutlinedTextField(
                    value = amount, onValueChange = { s -> amount = s.filter { it.isDigit() || it == '.' } }, label = { Text("Amount paid (₹)") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth()
                )
                if (d.minAmount != null && d.minAmount > 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = { amount = Ledger.round2(d.amount).toString() }, label = { Text("Full") })
                        AssistChip(onClick = { amount = Ledger.round2(d.minAmount).toString() }, label = { Text("Minimum") })
                    }
                }
                if (accounts.isNotEmpty()) {
                    Text("Paid from", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        accounts.forEach { a -> FilterChip(selected = account == a, onClick = { account = a }, label = { Text(a) }) }
                    }
                } else Text("Add a bank account in Accounts to track which account paid.", style = MaterialTheme.typography.labelSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { pick.launch("image/*") }) { Icon(Icons.Rounded.AttachFile, null); Text(if (proof == null) "Add payment proof" else "Proof added ✓") }
                }
                Button(
                    onClick = { val a = amount.toDoubleOrNull() ?: d.amount; act { DueActions.markPaid(env, d, a, account, proof) } },
                    enabled = !busy && (amount.toDoubleOrNull() ?: 0.0) > 0, modifier = Modifier.fillMaxWidth()
                ) { Icon(Icons.Rounded.TaskAlt, null); Spacer(Modifier.width(6.dp)); Text("Mark as paid") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { act { DueActions.snooze(env, d, System.currentTimeMillis() + Ledger.DAY) } }, enabled = !busy) { Text("Snooze 1 day") }
                    OutlinedButton(onClick = { act { DueActions.snooze(env, d, System.currentTimeMillis() + 3 * Ledger.DAY) } }, enabled = !busy) { Text("3 days") }
                }
                OutlinedButton(onClick = {
                    val c = Calendar.getInstance().apply { timeInMillis = d.dueMillis }
                    DatePickerDialog(context, { _, y, m, day ->
                        val t = Calendar.getInstance().apply { clear(); set(y, m, day, 12, 0, 0) }.timeInMillis
                        act { DueActions.reschedule(env, d, t) }
                    }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.EditCalendar, null); Spacer(Modifier.width(6.dp)); Text("Reschedule") }
            } else {
                Text("Paid ✓", color = Green, fontWeight = FontWeight.Bold)
                if (d.paidTxnId != null) {
                    val rec = env.data["expenses"].orEmpty().firstOrNull { it.str("id") == d.paidTxnId }
                    val proofImg = com.familyhub.app.ui.common.rememberPhoto(rec?.str("proofPhoto"))
                    if (proofImg != null) androidx.compose.foundation.Image(proofImg, contentDescription = "Payment proof", modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(14.dp)))
                    Text(listOfNotNull(rec?.str("account")?.takeIf { it.isNotBlank() }?.let { "From $it" }, rec?.let { formatDate((it["paidOnMillis"] as? Number)?.toLong()) }).joinToString(" • "))
                    TextButton(onClick = { pick.launch("image/*") }) { Icon(Icons.Rounded.AttachFile, null); Text(if (proofImg == null) "Add payment proof" else "Replace proof") }
                }
                TextButton(onClick = { onDismiss(); env.navigate("finance") }) { Text("View in Finance") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    onDismiss()
                    val src = if (d.sourceCollection == "expenses") env.data["expenses"].orEmpty().firstOrNull { it.str("id") == d.sourceId } else null
                    val cardId = if (src == null) d.sourceId else src.str("cardId")
                    val loanId = if (src == null) d.sourceId else src.str("loanId")
                    env.navigate(
                        when (d.kind) {
                            "card" -> if (cardId.isNotBlank()) "ccard:$cardId" else "cards"
                            "emi" -> if (loanId.isNotBlank()) "loan:$loanId" else "finance"
                            "subscription" -> "subscriptions"
                            "policy" -> "policies"
                            else -> "finance"
                        }
                    )
                }) { Icon(Icons.Rounded.Edit, null); Text("Edit") }
                TextButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, null, tint = Red); Text(if (d.sourceCollection == "expenses") "Delete" else "Skip this month", color = Red) }
            }
        }
    }
}

/** Month view of everything due: card bills, EMIs, premiums, rent, utilities, phone, subscriptions. */
@Composable
fun DueCalendarScreen(env: AppEnv) {
    var offset by rememberSaveable { mutableStateOf(0) }
    var selectedDay by remember { mutableStateOf<Int?>(null) }
    val (from, to) = remember(offset) { monthRange(offset) }
    val now = remember { System.currentTimeMillis() }
    val dues = remember(env.data, offset) { Ledger.dues(env.data, from, to, now) }
    val cal = remember(offset) { Calendar.getInstance().apply { timeInMillis = from } }
    val daysIn = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val firstDow = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Monday first
    fun dayOf(t: Long) = Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.DAY_OF_MONTH)
    val byDay = dues.groupBy { dayOf(it.dueMillis) }
    val shown = selectedDay?.let { byDay[it].orEmpty() } ?: dues
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Due calendar", onBack = env.onBack)
        LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { offset--; selectedDay = null }) { Icon(Icons.Rounded.ChevronLeft, "Previous month") }
                            Text(SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(from)), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            IconButton(onClick = { offset++; selectedDay = null }) { Icon(Icons.Rounded.ChevronRight, "Next month") }
                        }
                        Row { listOf("M", "T", "W", "T", "F", "S", "S").forEach { Text(it, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelSmall) } }
                        val cells = firstDow + daysIn
                        for (row in 0 until (cells + 6) / 7) {
                            Row {
                                for (col in 0 until 7) {
                                    val day = row * 7 + col - firstDow + 1
                                    Box(modifier = Modifier.weight(1f).height(44.dp).padding(2.dp), contentAlignment = Alignment.Center) {
                                        if (day in 1..daysIn) {
                                            val ds = byDay[day].orEmpty()
                                            val sel = selectedDay == day
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
                                                    .background(if (sel) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                                                    .clickable { selectedDay = if (sel) null else day }
                                            ) {
                                                Text("$day", style = MaterialTheme.typography.bodySmall, fontWeight = if (ds.isNotEmpty()) FontWeight.Bold else FontWeight.Normal)
                                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                                    ds.take(3).forEach { d -> Box(Modifier.size(6.dp).clip(CircleShape).background(statusColor(d.status))) }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        val unpaid = dues.filter { it.status != "PAID" }
                        Text(
                            "${plural(unpaid.size, "unpaid due")} • ${inr(unpaid.sumOf { it.amount })} • paid ${inr(dues.filter { it.status == "PAID" }.sumOf { it.amount })}",
                            style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
            if (shown.isEmpty()) item { Text(if (selectedDay != null) "Nothing due on this day." else "Nothing due this month.", style = MaterialTheme.typography.bodySmall) }
            items(shown) { d -> DueRow(env, d) }
        }
    }
}

// ---------- accounts ----------

@Composable
fun AccountsScreen(env: AppEnv) {
    val items = env.data["items"].orEmpty()
    val debit = items.filter { it.str("type") == "debit_card" && Ledger.live(it) }
    val accounts = items.filter { it.str("type") == "account" && Ledger.live(it) }
    val balances = remember(env.data) { accounts.map { it to Ledger.accountBalance(it, env.data["expenses"].orEmpty(), debit) } }
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Accounts", onBack = env.onBack) {
            TextButton(onClick = { env.navigate("accounts") }) { Text("Add / edit") }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                GlassCard(modifier = Modifier.fillMaxWidth(), strong = true) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Total in accounts", style = MaterialTheme.typography.labelMedium)
                        Text(inr(balances.sumOf { it.second.balance }), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            Button(onClick = { env.navigate("transfer") }) { Icon(Icons.Rounded.SwapHoriz, null); Spacer(Modifier.width(4.dp)); Text("Transfer") }
                            OutlinedButton(onClick = { env.navigate("debit_cards") }) { Text("Debit cards") }
                        }
                    }
                }
            }
            if (accounts.isEmpty()) item {
                Text("Add your bank accounts and cash with an opening balance. Balances then follow every entry paid from them; transfers between your accounts are not counted as spending.", style = MaterialTheme.typography.bodySmall)
            }
            items(balances) { (a, b) ->
                GlassCard(modifier = Modifier.fillMaxWidth(), onClick = { env.navigate("acct:" + a.str("id")) }) {
                    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(if (b.kind == "Cash" || b.kind == "Wallet") Color(0xFFFF9F0A) else Color(0xFF34C759)), contentAlignment = Alignment.Center) {
                            Icon(if (b.kind == "Cash" || b.kind == "Wallet") Icons.Rounded.Payments else Icons.Rounded.AccountBalance, null, tint = Color.White)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.str("title"), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOf(b.kind, a.str("last4").takeIf { it.isNotBlank() }?.let { "••$it" }.orEmpty(), plural(debit.count { it.str("accountId") == a.str("id") || it.str("account") == a.str("title") }, "debit card")).filter { it.isNotBlank() }.joinToString(" • "), style = MaterialTheme.typography.labelSmall)
                        }
                        Text(inr(b.balance), fontWeight = FontWeight.Bold, color = if (b.balance < 0) Red else Color.Unspecified)
                    }
                }
            }
        }
    }
}

@Composable
fun AccountDetailScreen(env: AppEnv, accountId: String) {
    val scope = rememberCoroutineScope()
    val items = env.data["items"].orEmpty()
    val account = items.firstOrNull { it.str("id") == accountId } ?: run { Text("Account not found"); return }
    val debit = items.filter { it.str("type") == "debit_card" }
    val tx = env.data["expenses"].orEmpty()
    val b = remember(env.data) { Ledger.accountBalance(account, tx, debit) }
    val (from, to) = remember { monthRange() }
    val month = remember(env.data) { Ledger.accountBalance(account, tx.filter { ((it["dateMillis"] as? Number)?.toLong() ?: 0L) in from..to }, debit) }
    val list = remember(env.data) {
        tx.filter { Ledger.live(it) && (Ledger.touchesAccount(it, account, debit) || it.str("toAccountId") == accountId || it.str("toAccount") == account.str("title")) }
            .sortedByDescending { (it["dateMillis"] as? Number)?.toLong() ?: 0L }.take(150)
    }
    var reconcile by remember { mutableStateOf(false) }
    var stmt by remember { mutableStateOf("") }
    if (reconcile) {
        AlertDialog(
            onDismissRequest = { reconcile = false },
            title = { Text("Match statement balance") },
            text = {
                Column {
                    Text("Family Hub shows ${inr(b.balance)}. Enter the balance on your bank statement or app; the difference is saved as an adjustment, not spending.")
                    OutlinedTextField(stmt, { s -> stmt = s.filter { it.isDigit() || it == '.' || it == '-' } }, label = { Text("Statement balance (₹)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = stmt.toDoubleOrNull()
                    reconcile = false
                    if (v != null) scope.launch { DueActions.reconcile(env, account, b.balance, v) }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { reconcile = false }) { Text("Cancel") } }
        )
    }
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenTopBar(title = account.str("title"), onBack = env.onBack)
        LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                GlassCard(modifier = Modifier.fillMaxWidth(), strong = true) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Balance", style = MaterialTheme.typography.labelMedium)
                        Text(inr(b.balance), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Row { Stat("Opening", inr(b.opening), Modifier.weight(1f)); Stat("In this month", inr(month.moneyIn), Modifier.weight(1f), Green); Stat("Out this month", inr(month.moneyOut), Modifier.weight(1f)) }
                        val seen = (account["lastSeenBalance"] as? Number)?.toDouble()
                        val seenAt = (account["lastSeenBalanceAt"] as? Number)?.toLong()
                        if (seen != null && seenAt != null) {
                            val off = Ledger.round2(seen - Ledger.accountBalance(account, tx, debit, Ledger.endOfDay(seenAt)).balance)
                            Text(
                                "Bank ${account.str("lastSeenBalanceFrom").ifBlank { "alert" }.lowercase()} said ${inr(seen)} on ${formatDate(seenAt)}" +
                                    if (kotlin.math.abs(off) < 1) " • matches" else " • differs by ${inr(off)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (kotlin.math.abs(off) < 1) Green else Amber
                            )
                            if (kotlin.math.abs(off) >= 1) {
                                TextButton(onClick = { scope.launch { DueActions.reconcile(env, account, b.balance, b.balance + off, seenAt) } }) {
                                    Text("Match to ${inr(seen)} (saves a ${inr(off)} adjustment)")
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { reconcile = true }) { Text("Match statement") }
                            OutlinedButton(onClick = { env.navigate("transfer") }) { Text("Transfer") }
                            OutlinedButton(onClick = { env.navigate("statement_import") }) { Text("Import") }
                        }
                    }
                }
            }
            items(list) { t -> MoneyLine(t, incoming = Ledger.type(t) == "INCOME" || (Ledger.type(t) == "TRANSFER" && (t.str("toAccountId") == accountId || t.str("toAccount") == account.str("title"))) || (Ledger.type(t) == "ADJUSTMENT" && Ledger.amount(t) >= 0)) }
        }
    }
}

@Composable
private fun MoneyLine(t: Rec, incoming: Boolean) {
    val ty = Ledger.type(t)
    val amt = kotlin.math.abs(Ledger.amount(t))
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(t.str("title"), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(listOf(formatDate((t["dateMillis"] as? Number)?.toLong()), when (ty) { "TRANSFER" -> "Transfer"; "CARD_PAYMENT" -> "Card bill"; "ADJUSTMENT" -> "Adjustment"; else -> t.str("category") }).filter { it.isNotBlank() }.joinToString(" • "), style = MaterialTheme.typography.labelSmall)
        }
        Text((if (incoming) "+" else "−") + inr(amt), color = if (incoming) Green else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
    }
}

// ---------- credit cards ----------

private val cardBrushes = listOf(
    listOf(Color(0xFF1C1C1E), Color(0xFF48484A)), listOf(Color(0xFF0A3D91), Color(0xFF2F7BFF)),
    listOf(Color(0xFF5B1F8C), Color(0xFFB04FE0)), listOf(Color(0xFF0B6E4F), Color(0xFF2FBF71)), listOf(Color(0xFF8C2F1F), Color(0xFFE5603B))
)

@Composable
private fun CardFace(c: Ledger.CardStatus, card: Rec, index: Int, onClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(cardBrushes[index % cardBrushes.size])).clickable(onClick = onClick).padding(18.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            Row {
                Text(card.str("bank").ifBlank { "Credit card" }, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.weight(1f))
                Text("•• " + card.str("last4"), color = Color.White.copy(alpha = 0.8f))
            }
            Text(c.title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Text("Outstanding", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
            Text(inr(c.outstanding), color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (c.limit != null && c.limit > 0) {
                LinearProgressIndicator(progress = { (c.outstanding / c.limit).toFloat().coerceIn(0f, 1f) }, color = Color.White, trackColor = Color.White.copy(alpha = 0.25f), modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                Text("${inr(c.available ?: 0.0)} available of ${inr(c.limit)}", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun CreditCardsScreen(env: AppEnv) {
    val now = remember { System.currentTimeMillis() }
    val cards = env.data["items"].orEmpty().filter { it.str("type") == "card" && Ledger.live(it) }.sortedBy { it.str("title") }
    val status = remember(env.data) { cards.map { Ledger.cardStatus(it, env.data["expenses"].orEmpty(), now) } }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Credit cards", onBack = env.onBack) { TextButton(onClick = { env.navigate("card_list") }) { Text("Add / edit") } }
        LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row { Stat("Total outstanding", inr(status.sumOf { it.outstanding }), Modifier.weight(1f), Amber); Stat("Due now", inr(status.sumOf { it.amountDue }), Modifier.weight(1f), Red) }
            }
            if (cards.isEmpty()) item { Text("Add a card with its statement and due dates. Purchases count as spending when you make them; paying the bill lowers what you owe and is not counted again.", style = MaterialTheme.typography.bodySmall) }
            items(cards.indices.toList()) { i ->
                val c = status[i]
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CardFace(c, cards[i], i) { env.navigate("ccard:" + cards[i].str("id")) }
                    Text("Statement ${formatDate(c.lastStatementMillis)} • due ${formatDate(c.dueMillis)} • " + DueActions.cardOutstandingNote(c), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
fun CardDetailScreen(env: AppEnv, cardId: String) {
    val now = remember { System.currentTimeMillis() }
    val card = env.data["items"].orEmpty().firstOrNull { it.str("id") == cardId } ?: run { Text("Card not found"); return }
    val tx = env.data["expenses"].orEmpty()
    val c = remember(env.data) { Ledger.cardStatus(card, tx, now) }
    var pay by remember { mutableStateOf(false) }
    val spends = remember(env.data) {
        tx.filter { Ledger.isSpend(it) && Ledger.touchesCard(it, card) && ((it["dateMillis"] as? Number)?.toLong() ?: 0L) > c.lastStatementMillis - 90 * Ledger.DAY }
    }
    val byCat = spends.groupBy { it.str("category").ifBlank { "Other" } }.mapValues { (_, l) -> l.sumOf { Ledger.spendAmount(it) } }.toList().sortedByDescending { it.second }
    if (pay) {
        val d = Ledger.Due("card:${card.str("id")}:pay", "card", c.title + " bill", c.amountDue.takeIf { it > 0 } ?: c.outstanding, c.minDue, c.dueMillis, "UNPAID", null, "items", card.str("id"), null, "Credit card")
        DueSheet(env, d) { pay = false }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = c.title, onBack = env.onBack) { TextButton(onClick = { env.navigate("card_list") }) { Text("Edit") } }
        LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { CardFace(c, card, 1) {} }
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row { Stat("Bill amount", inr(c.statementAmount), Modifier.weight(1f)); Stat("Due now", inr(c.amountDue), Modifier.weight(1f), if (c.amountDue > 0) Red else Green); Stat("Minimum", inr(c.minDue), Modifier.weight(1f)) }
                        Row { Stat("Statement", formatDate(c.lastStatementMillis), Modifier.weight(1f)); Stat("Due date", formatDate(c.dueMillis), Modifier.weight(1f)); Stat("Unbilled", inr(c.unbilled), Modifier.weight(1f)) }
                        Row { Stat("Paid since bill", inr(c.paidSinceStatement), Modifier.weight(1f), Green); Stat("Rewards", (card["rewardPoints"] as? Number)?.toLong()?.toString() ?: "—", Modifier.weight(1f)); Stat("Next statement", formatDate(c.nextStatementMillis), Modifier.weight(1f)) }
                        Button(onClick = { pay = true }, enabled = c.outstanding > 0, modifier = Modifier.fillMaxWidth()) { Text("Pay bill (full, minimum or part)") }
                    }
                }
            }
            if (byCat.isNotEmpty()) {
                item { Text("SPENDING BY CATEGORY (90 DAYS)", style = MaterialTheme.typography.labelMedium) }
                items(byCat) { (cat, v) ->
                    Row(Modifier.fillMaxWidth()) { Text(cat, Modifier.weight(1f)); Text(inr(v), fontWeight = FontWeight.SemiBold) }
                }
            }
            item { Text("STATEMENTS", style = MaterialTheme.typography.labelMedium) }
            items(c.history) { h ->
                Row(Modifier.fillMaxWidth()) {
                    Text(formatDate(h.statementMillis), Modifier.weight(1f))
                    Text("spent ${inr(h.spent)} • paid ${inr(h.paid)}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1.4f))
                    Text(inr(h.closing), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ---------- loans ----------

@Composable
fun LoansScreen(env: AppEnv) {
    val now = remember { System.currentTimeMillis() }
    val loans = env.data["items"].orEmpty().filter { it.str("type") == "loan" && Ledger.live(it) }
    val status = remember(env.data) { loans.map { Ledger.loanStatus(it, env.data["expenses"].orEmpty(), now) } }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Loans & EMI", onBack = env.onBack) { TextButton(onClick = { env.navigate("loans") }) { Text("Add / edit") } }
        LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Row { Stat("Principal left", inr(status.sumOf { it.outstandingPrincipal }), Modifier.weight(1f)); Stat("Interest still to pay", inr(status.sumOf { it.interestPayable }), Modifier.weight(1f), Amber); Stat("Monthly EMIs", inr(status.sumOf { it.emi }), Modifier.weight(1f)) } }
            if (loans.isEmpty()) item { Text("Add a loan with its amount, interest rate, months and first EMI date to get the full EMI calendar.", style = MaterialTheme.typography.bodySmall) }
            items(status.indices.toList()) { i ->
                val l = status[i]
                GlassCard(Modifier.fillMaxWidth(), onClick = { env.navigate("loan:" + loans[i].str("id")) }) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row { Text(l.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Text(loans[i].str("loanType"), style = MaterialTheme.typography.labelSmall) }
                        LinearProgressIndicator(progress = { if (l.tenure > 0) l.paidCount.toFloat() / l.tenure else 0f }, modifier = Modifier.fillMaxWidth())
                        Text("${l.paidCount} of ${l.tenure} EMIs paid • ${inr(l.emi)}/month • ${inr(l.outstandingPrincipal)} left" + (if (l.overdue > 0) " • ${l.overdue} overdue" else ""), style = MaterialTheme.typography.labelSmall, color = if (l.overdue > 0) Red else Color.Unspecified)
                        l.nextDue?.let { Text("Next: EMI ${it.no} on ${formatDate(it.dueMillis)}", style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }
        }
    }
}

@Composable
fun LoanDetailScreen(env: AppEnv, loanId: String) {
    val now = remember { System.currentTimeMillis() }
    val loan = env.data["items"].orEmpty().firstOrNull { it.str("id") == loanId } ?: run { Text("Loan not found"); return }
    val l = remember(env.data) { Ledger.loanStatus(loan, env.data["expenses"].orEmpty(), now) }
    var payFor by remember { mutableStateOf<Ledger.Installment?>(null) }
    payFor?.let { inst ->
        val d = Ledger.Due("loan:$loanId:${inst.no}", "emi", "${l.title} EMI ${inst.no}/${l.tenure}", inst.emi, null, inst.dueMillis, inst.status, null, "items", loanId, null, "Loan & EMI")
        DueSheet(env, d) { payFor = null }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = l.title, onBack = env.onBack) { TextButton(onClick = { env.navigate("loans") }) { Text("Edit") } }
        LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                GlassCard(Modifier.fillMaxWidth(), strong = true) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row { Stat("Loan amount", inr(l.principal), Modifier.weight(1f)); Stat("Rate", "${l.rate}%", Modifier.weight(1f)); Stat("EMI", inr(l.emi), Modifier.weight(1f)) }
                        Row { Stat("Principal left", inr(l.outstandingPrincipal), Modifier.weight(1f)); Stat("Interest left", inr(l.interestPayable), Modifier.weight(1f), Amber); Stat("EMIs left", "${l.remaining}", Modifier.weight(1f)) }
                        Text("Total interest over the loan: ${inr(l.totalInterest)}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            item { Text("EMI CALENDAR • tap an unpaid EMI to mark it paid", style = MaterialTheme.typography.labelMedium) }
            items(l.schedule, key = { it.no }) { s ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = s.status != "PAID") { payFor = s }.padding(vertical = 6.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${s.no}", Modifier.width(30.dp), style = MaterialTheme.typography.labelMedium)
                    Text(formatDate(s.dueMillis), Modifier.weight(1.3f), maxLines = 1)
                    Text(inr(s.emi), Modifier.weight(1f), maxLines = 1)
                    Text(
                        when (s.status) { "PAID" -> "Paid"; "OVERDUE" -> "Overdue"; "DUE" -> "Due soon"; else -> "Upcoming" },
                        color = when (s.status) { "PAID" -> Green; "OVERDUE" -> Red; "DUE" -> Amber; else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f) },
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

// ---------- transfer ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransferScreen(env: AppEnv) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val items = env.data["items"].orEmpty()
    val accounts = items.filter { it.str("type") == "account" && Ledger.live(it) }.map { it.str("title") }
    val cards = items.filter { it.str("type") == "card" && Ledger.live(it) }.map { it.str("title") }
    var from by remember { mutableStateOf(accounts.firstOrNull().orEmpty()) }
    var to by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var at by remember { mutableStateOf(System.currentTimeMillis()) }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Transfer", onBack = env.onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Moving money between your own accounts, or paying a credit card, is not counted as income or spending.", style = MaterialTheme.typography.bodySmall)
            if (accounts.isEmpty()) {
                Text("Add an account first.", fontWeight = FontWeight.SemiBold)
                Button(onClick = { env.navigate("accounts") }) { Text("Add account") }
                return@Column
            }
            Text("From", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { accounts.forEach { a -> FilterChip(from == a, { from = a }, { Text(a) }) } }
            Text("To", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                accounts.filter { it != from }.forEach { a -> FilterChip(to == a, { to = a }, { Text(a) }, leadingIcon = { Icon(Icons.Rounded.AccountBalance, null, Modifier.size(16.dp)) }) }
                cards.forEach { c -> FilterChip(to == "card:$c", { to = "card:$c" }, { Text(c) }, leadingIcon = { Icon(Icons.Rounded.CreditCard, null, Modifier.size(16.dp)) }) }
            }
            OutlinedTextField(amount, { s -> amount = s.filter { it.isDigit() || it == '.' } }, label = { Text("Amount (₹)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = {
                val c = Calendar.getInstance().apply { timeInMillis = at }
                DatePickerDialog(context, { _, y, m, d -> at = Calendar.getInstance().apply { clear(); set(y, m, d, 12, 0, 0) }.timeInMillis }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
            }) { Text("Date: " + formatDate(at)) }
            Button(
                onClick = {
                    val a = amount.toDoubleOrNull() ?: return@Button
                    scope.launch {
                        try {
                            DueActions.transfer(env, from, to.removePrefix("card:"), to.startsWith("card:"), a, at, note)
                            android.widget.Toast.makeText(context, "Saved", android.widget.Toast.LENGTH_SHORT).show()
                            env.onBack()
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(context, "Could not save: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                },
                enabled = from.isNotBlank() && to.isNotBlank() && (amount.toDoubleOrNull() ?: 0.0) > 0,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (to.startsWith("card:")) "Pay card" else "Transfer") }
        }
    }
}
