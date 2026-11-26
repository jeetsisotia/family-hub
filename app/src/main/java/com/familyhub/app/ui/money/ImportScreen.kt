package com.familyhub.app.ui.money

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.familyhub.app.core.theme.AccentCard
import com.familyhub.app.core.theme.GlassCard
import com.familyhub.app.money.GmailAuthException
import com.familyhub.app.money.GmailReader
import com.familyhub.app.money.AlertParser
import com.familyhub.app.money.CalendarReader
import com.familyhub.app.money.ParsedAlert
import com.familyhub.app.money.ParsedTxn
import com.familyhub.app.money.PhoneEvent
import com.familyhub.app.money.SmsReader
import com.familyhub.app.ui.AppEnv
import com.familyhub.app.ui.common.*
import com.familyhub.app.ui.modules.DAY_MS
import com.familyhub.app.ui.modules.PAYMENT_METHODS
import com.familyhub.app.ui.modules.financeCategories
import com.familyhub.app.ui.modules.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PREFS = "family_hub_import"
private const val KEY_IGNORED = "ignored"

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private fun ignoredKeys(ctx: Context): Set<String> =
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_IGNORED, emptySet()).orEmpty()

private fun addIgnored(ctx: Context, keys: Collection<String>) {
    val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val all = (p.getStringSet(KEY_IGNORED, emptySet()).orEmpty() + keys).toList().takeLast(3000).toSet()
    p.edit().putStringSet(KEY_IGNORED, all).apply()
}

/**
 * Finds bank and card transactions in SMS or Gmail and adds the ones you pick to Finance.
 * Nothing is added until you tap Add. Already-added transactions are recognised and skipped.
 */
@Composable
fun ImportScreen(env: AppEnv, demoTexts: List<String>? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val expenses = env.data["expenses"].orEmpty()
    val cards = env.data["items"].orEmpty().filter { it.str("type") == "card" }
    val known = remember(env.data) {
        (expenses + env.data["items"].orEmpty() + env.data["events"].orEmpty()).mapNotNull { it["sourceKey"] as? String }.toSet()
    }
    // Cards and accounts already saved are recognised by their last four digits.
    val knownLast4 = remember(env.data) {
        env.data["items"].orEmpty().filter { it.str("type") == "card" || it.str("type") == "account" }.map { it.str("last4") }.filter { it.isNotBlank() }.toSet()
    }

    var visibility by rememberSaveable { mutableStateOf("Only me") }
    var days by rememberSaveable { mutableStateOf(30) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<List<ParsedTxn>>(emptyList()) }
    var ignored by remember { mutableStateOf(ignoredKeys(context)) }
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    val category = remember { mutableStateMapOf<String, String>() }
    var expanded by remember { mutableStateOf<String?>(null) }
    var gmailToken by remember { mutableStateOf<String?>(null) }
    var smsAllowed by remember { mutableStateOf(SmsReader.canRead(context)) }
    var smsDenied by remember { mutableStateOf(false) }
    var alerts by remember { mutableStateOf<List<ParsedAlert>>(emptyList()) }
    var events by remember { mutableStateOf<List<PhoneEvent>>(emptyList()) }
    val alertSel = remember { mutableStateMapOf<String, Boolean>() }
    val alertEdit = remember { mutableStateMapOf<String, AlertEdit>() }
    val eventSel = remember { mutableStateMapOf<String, Boolean>() }
    var section by rememberSaveable { mutableStateOf("Bills & renewals") }
    var scanningAll by remember { mutableStateOf(false) }
    val freshAlerts = alerts.filter { a ->
        a.key !in known && a.key !in ignored && !((a.kind == "card" || a.kind == "account") && a.last4 in knownLast4)
    }
    val freshEvents = events.filter { it.key !in known && it.key !in ignored }

    fun acceptAlerts(list: List<ParsedAlert>, source: String) {
        alerts = (alerts.filter { it.source != source } + list).distinctBy { it.key }
            .sortedWith(compareBy<ParsedAlert> { listOf("card_due", "bill_due", "policy", "subscription", "action", "card", "account").indexOf(it.kind) }
                .thenBy { it.dueMillis ?: Long.MAX_VALUE })
        for (a in list) {
            if (alertSel[a.key] == null) alertSel[a.key] = a.kind != "action" || (a.dueMillis ?: 0L) >= System.currentTimeMillis() - DAY_MS
            if (alertEdit[a.key] == null) alertEdit[a.key] = defaultEdit(a)
        }
    }

    val fresh = found.filter { it.key !in known && it.key !in ignored }
    val alreadyAdded = found.count { it.key in known }

    fun accept(list: List<ParsedTxn>, source: String) {
        val merged = (found.filter { it.source != source } + list).distinctBy { it.key }.sortedByDescending { it.dateMillis }
        found = merged
        for (t in list) {
            if (selected[t.key] == null) selected[t.key] = !t.isTransfer
            if (category[t.key] == null) category[t.key] = if (t.isCredit && t.category == "Other") "Other" else t.category
        }
        val newCount = list.count { it.key !in known && it.key !in ignored }
        message = when {
            list.isEmpty() -> "No bank transactions found in the last $days days."
            newCount == 0 -> "Everything found is already in Finance."
            else -> "${com.familyhub.app.ui.common.plural(newCount, "new transaction")} found. Check them and tap Add."
        }
    }

    fun scanSms() {
        busy = true
        message = null
        scope.launch {
            val scan = withContext(Dispatchers.IO) { SmsReader.scan(context, System.currentTimeMillis() - days * DAY_MS) }
            accept(scan.txns, "SMS")
            acceptAlerts(scan.alerts, "SMS")
            if (scan.alerts.isNotEmpty()) {
                message = (message ?: "") + " Also found " + com.familyhub.app.ui.common.plural(scan.alerts.size, "bill, renewal or account", "bills, renewals or accounts") + "."
            }
            busy = false
        }
    }

    // Sample messages for the automatic emulator test (the emulator has no bank SMS).
    LaunchedEffect(demoTexts) {
        if (demoTexts != null) {
            val now = System.currentTimeMillis()
            val txns = demoTexts.mapIndexedNotNull { i, t ->
                com.familyhub.app.money.TxnParser.parse(t, "VM-HDFCBK", now - i * 3_600_000L, "SMS")
            }
            accept(txns, "SMS")
            acceptAlerts(demoTexts.mapIndexedNotNull { i, t ->
                if (com.familyhub.app.money.TxnParser.parse(t, "VM-HDFCBK", now, "SMS") != null) null
                else AlertParser.parse(t, "VM-HDFCBK", now - i * 3_600_000L, "SMS", "demo$i")
            } + AlertParser.accounts(txns), "SMS")
        }
    }

    val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        smsAllowed = ok
        smsDenied = !ok
        if (ok) scanSms()
    }

    fun scanGmail(token: String) {
        busy = true
        message = null
        scope.launch {
            try {
                val mailTxns = GmailReader.fetch(token, days, if (days > 90) 300 else 120)
                accept(mailTxns, "Email")
                acceptAlerts(GmailReader.fetchAlerts(token, days, if (days > 90) 300 else 150) + AlertParser.accounts(mailTxns), "Email")
                context.getSharedPreferences("family_hub_worker", Context.MODE_PRIVATE).edit().putBoolean("gmailConnected", true).apply()
            } catch (e: GmailAuthException) {
                gmailToken = null
                message = e.message
            } catch (e: Exception) {
                message = "Could not read Gmail: ${e.message ?: "please try again"}"
            }
            busy = false
        }
    }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val token = GmailReader.tokenFromConsent(context, res.data)
        if (token != null) {
            gmailToken = token
            scanGmail(token)
        } else {
            busy = false
            message = "Gmail access was not given."
        }
    }

    fun connectGmail() {
        val activity = context.findActivity() ?: return
        busy = true
        message = null
        scope.launch {
            try {
                when (val a = GmailReader.authorize(activity)) {
                    is GmailReader.Auth.Token -> {
                        gmailToken = a.token
                        scanGmail(a.token)
                    }
                    is GmailReader.Auth.NeedsConsent -> {
                        val pi = a.result.pendingIntent
                        if (pi != null) consent.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                        else {
                            busy = false
                            message = "Google did not offer the Gmail permission screen."
                        }
                    }
                }
            } catch (e: Exception) {
                busy = false
                message = "Could not connect Gmail: ${e.message ?: "please try again"}"
            }
        }
    }

    fun scanCalendar() {
        scope.launch {
            val now = System.currentTimeMillis()
            events = withContext(Dispatchers.IO) { CalendarReader.read(context, now - DAY_MS, now + 180 * DAY_MS) }
            events.forEach { if (eventSel[it.key] == null) eventSel[it.key] = true }
            if (events.isEmpty()) message = "No upcoming events in this phone's calendars."
        }
    }

    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) scanCalendar() else message = "Calendar access was not given."
    }

    // One tap: SMS, the phone's calendars (Google calendars synced from Gmail included) and Gmail if connected.
    val allPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        smsAllowed = SmsReader.canRead(context)
        if (smsAllowed) scanSms() else smsDenied = res[Manifest.permission.READ_SMS] == false
        if (CalendarReader.canRead(context)) scanCalendar()
        val t = gmailToken
        if (t != null) scanGmail(t) else scope.launch {
            GmailReader.silentToken(context)?.let { tok -> gmailToken = tok; scanGmail(tok) }
        }
    }

    fun scanEverything() {
        scanningAll = true
        section = "Bills & renewals"
        val need = listOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CALENDAR)
            .filter { context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) allPermissions.launch(need.toTypedArray())
        else {
            scanSms()
            scanCalendar()
            val t = gmailToken
            if (t != null) scanGmail(t) else scope.launch {
                GmailReader.silentToken(context)?.let { tok -> gmailToken = tok; scanGmail(tok) }
            }
        }
    }

    fun addAlerts() {
        val chosen = freshAlerts.filter { alertSel[it.key] == true }
        val chosenEvents = freshEvents.filter { eventSel[it.key] == true }
        if (chosen.isEmpty() && chosenEvents.isEmpty()) return
        busy = true
        scope.launch {
            var ok = 0
            for (a in chosen) {
                try {
                    saveFoundAlert(env, a, alertEdit[a.key] ?: defaultEdit(a), visibility == "Family", cards, alerts)
                    ok++
                } catch (e: Exception) {
                    // keep going
                }
            }
            for (ev in chosenEvents) {
                try {
                    saveFoundEvent(env, ev, ev.title, visibility == "Family")
                    ok++
                } catch (e: Exception) {
                }
            }
            busy = false
            Toast.makeText(context, "${com.familyhub.app.ui.common.plural(ok, "item")} added", Toast.LENGTH_SHORT).show()
            message = "$ok added. Bills show in Finance and Due soon; renewals and policies in Reminders; events in Calendar."
        }
    }

    fun addSelected() {
        val chosen = fresh.filter { selected[it.key] == true }
        if (chosen.isEmpty()) return
        busy = true
        scope.launch {
            var ok = 0
            for (t in chosen) {
                val card = if (t.last4.isNotBlank()) cards.firstOrNull { it.str("last4") == t.last4 } else null
                val method = when {
                    card != null -> "Credit card"
                    t.method in PAYMENT_METHODS -> t.method
                    else -> "Other"
                }
                val title = t.merchant.ifBlank { if (t.isCredit) "Money received" else "Payment" }
                val data = hashMapOf<String, Any?>(
                    "type" to if (t.isCredit && !t.isTransfer) "INCOME" else "EXPENSE",
                    "title" to title,
                    "amountInr" to t.amount,
                    "category" to (category[t.key] ?: t.category).ifBlank { "Other" },
                    "dateMillis" to t.dateMillis,
                    "paymentMethod" to method,
                    "card" to (card?.str("title") ?: "None"),
                    "notes" to "Added from bank ${if (t.source == "SMS") "SMS" else "email"}" +
                        (if (t.last4.isNotBlank()) " (account or card ending ${t.last4})" else ""),
                    "sourceKey" to t.key,
                    "isPaid" to true,
                    "isDeleted" to false,
                    "memberUid" to env.uid,
                    "memberName" to env.profile.name,
                    "createdAt" to System.currentTimeMillis()
                )
                try {
                    env.repo.saveScoped(env.familyId, env.uid, "expenses", null, data, visibility == "Only me")
                    ok++
                } catch (e: Exception) {
                    // keep going; the rest are still added
                }
            }
            busy = false
            Toast.makeText(context, "${com.familyhub.app.ui.common.plural(ok, "transaction")} added to Finance", Toast.LENGTH_SHORT).show()
            message = "$ok added. They now show in Finance and Money Insights."
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Scan SMS & Gmail", onBack = env.onBack)
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Family Hub reads bank and card alerts on this phone, finds the amounts and places, " +
                                "and lets you choose which to add. Messages are read on the phone and are not uploaded.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text("Look back", style = MaterialTheme.typography.labelLarge)
                        ChoiceChips(
                            listOf("30 days", "90 days", "1 year", "All"),
                            when (days) { 30 -> "30 days"; 90 -> "90 days"; 365 -> "1 year"; else -> "All" },
                            { days = when (it) { "90 days" -> 90; "1 year" -> 365; "All" -> 1825; else -> 30 } }
                        )
                        Text("Save new entries for", style = MaterialTheme.typography.labelLarge)
                        ChoiceChips(listOf("Only me", "Family"), visibility, { visibility = it.ifBlank { "Only me" } })
                    }
                }
            }
            item {
                Button(onClick = { scanEverything() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.TravelExplore, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Scan everything: SMS, Gmail and calendar")
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = {
                            if (CalendarReader.canRead(context)) scanCalendar() else calendarPermission.launch(Manifest.permission.READ_CALENDAR)
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Calendar")
                    }
                    FilledTonalButton(
                        onClick = {
                            if (SmsReader.canRead(context)) scanSms() else smsPermission.launch(Manifest.permission.READ_SMS)
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.Sms, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (smsAllowed) "SMS" else "Allow SMS")
                    }
                    FilledTonalButton(
                        onClick = { val t = gmailToken; if (t != null) scanGmail(t) else connectGmail() },
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.Email, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (gmailToken == null) "Gmail" else "Gmail ✓")
                    }
                }
            }
            if (smsDenied) {
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("SMS permission is off", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Open App info → Permissions → SMS → Allow. If Android says it is a restricted setting, " +
                                    "first tap the ⋮ menu on App info and choose \"Allow restricted settings\".",
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }) { Text("Open App info") }
                        }
                    }
                }
            }
            if (gmailToken == null) {
                item {
                    Text(
                        "Gmail: Google will show a warning that this app is not verified, because Family Hub is a private family app. " +
                            "Tap Advanced, then Go to Family Hub, to allow read-only access. Only alert emails are read.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                }
            }
            if (busy) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            message?.let { m -> item { Text(m, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) } }

            if (freshAlerts.isNotEmpty() || freshEvents.isNotEmpty() || fresh.isNotEmpty()) {
                item {
                    val bills = freshAlerts.count { it.kind !in setOf("card", "account") }
                    val accts = freshAlerts.count { it.kind in setOf("card", "account") }
                    ChoiceChips(
                        listOf("Bills & renewals ($bills)", "Transactions (${fresh.size})", "Cards & accounts ($accts)", "Calendar (${freshEvents.size})"),
                        listOf("Bills & renewals ($bills)", "Transactions (${fresh.size})", "Cards & accounts ($accts)", "Calendar (${freshEvents.size})")
                            .firstOrNull { it.startsWith(section) }.orEmpty(),
                        { c -> section = c.substringBefore(" (") }
                    )
                }
            }
            if (section != "Transactions") {
                val shownAlerts = when (section) {
                    "Cards & accounts" -> freshAlerts.filter { it.kind in setOf("card", "account") }
                    "Calendar" -> emptyList()
                    else -> freshAlerts.filter { it.kind !in setOf("card", "account") }
                }
                val shownEvents = if (section == "Calendar") freshEvents else emptyList()
                if (shownAlerts.isNotEmpty() || shownEvents.isNotEmpty()) {
                    item {
                        val n = shownAlerts.count { alertSel[it.key] == true } + shownEvents.count { eventSel[it.key] == true }
                        AccentCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "$n selected • tap any item to change its name, amount or date",
                                    color = Color.White, fontWeight = FontWeight.SemiBold
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                    Button(
                                        onClick = { addAlerts() },
                                        enabled = !busy && n > 0,
                                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF0062E0))
                                    ) { Text("Add $n") }
                                    TextButton(onClick = {
                                        val skip = shownAlerts.filter { alertSel[it.key] != true }.map { it.key } +
                                            shownEvents.filter { eventSel[it.key] != true }.map { it.key }
                                        addIgnored(context, skip)
                                        ignored = ignoredKeys(context)
                                    }) { Text("Hide unticked", color = Color.White) }
                                }
                            }
                        }
                    }
                    items(shownAlerts, key = { "a-" + it.key }) { a ->
                        FoundAlertRow(
                            a = a,
                            edit = alertEdit[a.key] ?: defaultEdit(a),
                            checked = alertSel[a.key] == true,
                            onCheck = { alertSel[a.key] = it },
                            onEdit = { alertEdit[a.key] = it }
                        )
                    }
                    items(shownEvents, key = { "e-" + it.key }) { ev ->
                        FoundEventRow(ev, eventSel[ev.key] == true) { eventSel[ev.key] = it }
                    }
                } else if (scanningAll || alerts.isNotEmpty() || events.isNotEmpty()) {
                    item {
                        Text(
                            when (section) {
                                "Calendar" -> "No new calendar events. Tap Calendar to read this phone's calendars, including Google calendars synced from Gmail."
                                "Cards & accounts" -> "No new cards or accounts found."
                                else -> "No new bills, renewals, policies or actions found."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                        )
                    }
                }
            }
            if (section == "Transactions" && fresh.isNotEmpty()) {
                item {
                    val n = fresh.count { selected[it.key] == true }
                    val total = fresh.filter { selected[it.key] == true && !it.isCredit }.sumOf { it.amount }
                    AccentCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("$n selected • ${inr(total)} spent", color = Color.White, fontWeight = FontWeight.SemiBold)
                            if (alreadyAdded > 0) {
                                Text("$alreadyAdded already in Finance (hidden)", color = Color.White.copy(alpha = 0.85f),
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                Button(
                                    onClick = { addSelected() },
                                    enabled = !busy && n > 0,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF0062E0))
                                ) { Text("Add $n to Finance") }
                                TextButton(onClick = {
                                    val skip = fresh.filter { selected[it.key] != true }.map { it.key }
                                    addIgnored(context, skip)
                                    ignored = ignoredKeys(context)
                                }) { Text("Hide unticked", color = Color.White) }
                            }
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { fresh.forEach { selected[it.key] = true } }) { Text("Tick all") }
                        TextButton(onClick = { fresh.forEach { selected[it.key] = false } }) { Text("Untick all") }
                    }
                }
            }
            if (section == "Transactions") items(fresh, key = { it.key }) { t ->
                val isOpen = expanded == t.key
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    onClick = { expanded = if (isOpen) null else t.key }
                ) {
                    Column(modifier = Modifier.padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = selected[t.key] == true, onCheckedChange = { selected[t.key] = it })
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    t.merchant.ifBlank { if (t.isCredit) "Money received" else "Payment" },
                                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    listOf(
                                        formatDate(t.dateMillis),
                                        category[t.key] ?: t.category,
                                        t.method,
                                        if (t.last4.isNotBlank()) "••${t.last4}" else "",
                                        if (t.isTransfer) "card bill / transfer" else "",
                                        t.source
                                    ).filter { it.isNotBlank() }.joinToString(" • "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    maxLines = 2, overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                (if (t.isCredit) "+" else "−") + inr(t.amount),
                                fontWeight = FontWeight.Bold,
                                color = if (t.isCredit) Color(0xFF34C759) else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        if (isOpen) {
                            Text("Category", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 12.dp, top = 4.dp))
                            ChoiceChips(
                                financeCategories,
                                category[t.key] ?: t.category,
                                { c -> category[t.key] = c.ifBlank { "Other" } },
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
