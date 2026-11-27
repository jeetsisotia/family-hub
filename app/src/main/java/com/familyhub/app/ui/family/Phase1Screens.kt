package com.familyhub.app.ui.family

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.familyhub.app.core.theme.EmptyState
import com.familyhub.app.core.theme.GlassCard
import com.familyhub.app.core.theme.GroupedRow
import com.familyhub.app.core.theme.GroupedSection
import com.familyhub.app.family.DocExpiry
import com.familyhub.app.family.Timeline
import com.familyhub.app.money.RecurringFinder
import com.familyhub.app.ui.AppEnv
import com.familyhub.app.ui.common.ChoiceChips
import com.familyhub.app.ui.common.ScreenTopBar
import com.familyhub.app.ui.money.canEditMoney
import com.familyhub.app.ui.money.inr
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private fun tzOffset(now: Long = System.currentTimeMillis()) = TimeZone.getDefault().getOffset(now).toLong()

// ---------------------------------------------------------------------------------------------------------------
// 1. Family activity timeline
// ---------------------------------------------------------------------------------------------------------------

private fun kindIcon(k: Timeline.Kind): ImageVector = when (k) {
    Timeline.Kind.MONEY -> Icons.Rounded.CurrencyRupee
    Timeline.Kind.TASK -> Icons.Rounded.TaskAlt
    Timeline.Kind.EVENT -> Icons.Rounded.CalendarMonth
    Timeline.Kind.REMINDER -> Icons.Rounded.Alarm
    Timeline.Kind.DOCUMENT -> Icons.Rounded.FolderShared
    Timeline.Kind.OTHER -> Icons.Rounded.History
}

private fun dayLabel(t: Long, now: Long = System.currentTimeMillis()): String {
    val c = Calendar.getInstance().apply { timeInMillis = now }
    val d = Calendar.getInstance().apply { timeInMillis = t }
    fun key(x: Calendar) = x.get(Calendar.YEAR) * 1000 + x.get(Calendar.DAY_OF_YEAR)
    return when (key(c) - key(d)) {
        0 -> "Today"
        1 -> "Yesterday"
        else -> SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(Date(t))
    }
}

/**
 * Who added, changed or finished what across the family. Built from the records already on the phone (no extra
 * database reads); "Only me", private, health and ID records never appear, and documents show only name and type.
 */
@Composable
fun ActivityTimelineScreen(env: AppEnv) {
    val names = remember(env.members) { env.members.associate { it.uid to it.name } }
    val all = remember(env.data, names) {
        Timeline.build(mapOf(
            "expenses" to env.data["expenses"].orEmpty(), "tasks" to env.data["tasks"].orEmpty(),
            "events" to env.data["events"].orEmpty(), "items" to env.data["items"].orEmpty()
        ), names)
    }
    var kindLabel by rememberSaveable { mutableStateOf("") }
    var member by rememberSaveable { mutableStateOf("") }
    val kind = Timeline.Kind.values().firstOrNull { it.label == kindLabel }
    val shown = remember(all, kind, member) { Timeline.filter(all, kind, member.ifBlank { null }) }
    val people = remember(all) { all.map { it.who }.filter { it != "Someone" }.distinct() }
    val time = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Family activity", onBack = env.onBack)
        ChoiceChips(Timeline.Kind.values().filter { it != Timeline.Kind.OTHER }.map { it.label }, kindLabel, { kindLabel = it },
            Modifier.padding(horizontal = 16.dp), scroll = true)
        if (people.size > 1) {
            Spacer(Modifier.height(6.dp))
            ChoiceChips(people, member, { member = it }, Modifier.padding(horizontal = 16.dp), scroll = true)
        }
        if (shown.isEmpty()) {
            EmptyState(
                Icons.Rounded.History,
                if (all.isEmpty()) "No family activity yet" else "Nothing for this filter",
                if (all.isEmpty()) "When anyone in the family adds an expense, finishes a task, adds an event or updates a document, it shows here. " +
                    "\"Only me\" and health records never appear."
                else "Try another type or person.",
                Modifier.padding(top = 32.dp)
            )
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val byDay = shown.groupBy { dayLabel(it.atMillis) }
            byDay.forEach { (day, list) ->
                item(key = day) {
                    GroupedSection(title = day) {
                        list.forEachIndexed { i, e ->
                            GroupedRow(
                                title = "${e.who} ${e.action} ${e.title}",
                                note = listOf(e.kind.label, e.detail, time.format(Date(e.atMillis))).filter { it.isNotBlank() }.joinToString(" · "),
                                icon = kindIcon(e.kind),
                                divider = i < list.size - 1,
                                onClick = { env.navigate(e.route) }
                            )
                        }
                    }
                }
            }
            item {
                Text("Shared records only. \"Only me\" records, health records and document numbers are never shown here.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// 2. Document expiry summary (top of Documents)
// ---------------------------------------------------------------------------------------------------------------

/** Expired / expiring soon / active counts and the next documents to renew. Nothing shows if no document has a date. */
@Composable
fun DocExpiryCard(env: AppEnv) {
    val docs = remember(env.data) { env.data["items"].orEmpty().filter { it["type"] == "document" } }
    val sum = remember(docs) { DocExpiry.summary(docs, System.currentTimeMillis(), tzOffset()) }
    if (docs.isEmpty() || sum.noDate == docs.count { it["isDeleted"] != true }) return
    GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Expiry tracker", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatPill("Expired", sum.expired, Color(0xFFFF3B30), Modifier.weight(1f))
                StatPill("Due soon", sum.dueSoon, Color(0xFFFF9500), Modifier.weight(1f))
                StatPill("Active", sum.active, Color(0xFF34C759), Modifier.weight(1f))
            }
            sum.next.forEach { (d, left) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text((d["title"] as? String).orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text(listOfNotNull((d["category"] as? String)?.takeIf { it.isNotBlank() }, (d["holder"] as? String)?.takeIf { it.isNotBlank() },
                            (d["renewalStatus"] as? String)?.takeIf { it.isNotBlank() && it != "Not started" }).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        when { left < 0 -> "Expired ${-left} d ago"; left == 0 -> "Today"; else -> "In $left d" },
                        color = if (left < 0) Color(0xFFFF3B30) else Color(0xFFFF9500), style = MaterialTheme.typography.labelLarge
                    )
                }
            }
            if (sum.next.isEmpty()) Text("Nothing to renew soon.", style = MaterialTheme.typography.bodySmall)
            Text("Each document can choose when to remind you (Remind me in its form). Notifications never show the number or notes.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
        }
    }
}

@Composable
private fun StatPill(label: String, n: Int, color: Color, modifier: Modifier) {
    Column(modifier.background(color.copy(alpha = 0.12f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$n", fontWeight = FontWeight.Bold, color = color, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------------------------------------------
// 3. Recurring payment suggestions (Finance → Recurring payments)
// ---------------------------------------------------------------------------------------------------------------

private object RecurringPrefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("recurring_suggestions", Context.MODE_PRIVATE)
    fun dismissed(ctx: Context, family: String): Set<String> = p(ctx).getStringSet("d_$family", emptySet()).orEmpty()
    fun dismiss(ctx: Context, family: String, key: String) = p(ctx).edit().putStringSet("d_$family", dismissed(ctx, family) + key).apply()
    fun restoreAll(ctx: Context, family: String) = p(ctx).edit().remove("d_$family").apply()
}

/**
 * Payments that look recurring in the existing transactions, as suggestions. Track saves a Subscription (or a weekly
 * reminder) only after the person confirms the name, amount and frequency; transactions are never created.
 */
@Composable
fun RecurringScreen(env: AppEnv) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableStateOf(0) }
    val items = env.data["items"].orEmpty()
    val tracked = remember(items) {
        items.filter { it["type"] == "subscription" || it["type"] == "reminder" || it["type"] == "bill" }
            .map { RecurringFinder.payeeKey((it["title"] as? String).orEmpty()) }.filter { it.isNotBlank() }.toSet() +
            items.mapNotNull { (it["sourceKey"] as? String)?.removePrefix("recurring:")?.substringBefore('|') }.toSet()
    }
    val dismissed = remember(refresh) { RecurringPrefs.dismissed(ctx, env.familyId) }
    var justTracked by remember { mutableStateOf(setOf<String>()) }
    val suggestions = remember(env.data["expenses"], tracked, dismissed, justTracked) {
        RecurringFinder.find(env.data["expenses"].orEmpty(), tracked, dismissed).filter { it.key !in justTracked }
    }
    var confirm by remember { mutableStateOf<RecurringFinder.Suggestion?>(null) }
    val fmt = remember { SimpleDateFormat("d MMM", Locale.getDefault()) }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Recurring payments", onBack = env.onBack)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Found in your transactions: the same payee, a similar amount and regular gaps. These are suggestions only. " +
                    "Track one to get renewal reminders; nothing is paid or added to Finance.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (suggestions.isEmpty()) item {
                EmptyState(Icons.Rounded.EventRepeat, "No new recurring payments found",
                    "Suggestions need at least 3 monthly, 4 weekly or 2 yearly payments to the same payee. Ones you already track or dismissed are left out.",
                    actionLabel = if (dismissed.isNotEmpty()) "Show dismissed again" else null,
                    onAction = if (dismissed.isNotEmpty()) ({ RecurringPrefs.restoreAll(ctx, env.familyId); refresh++ }) else null)
            }
            items(suggestions, key = { it.key }) { s ->
                GlassCard(modifier = Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.payee, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("~" + inr(s.amount), fontWeight = FontWeight.SemiBold)
                        }
                        Text("${s.frequency.label} · ${s.count} payments · last ${fmt.format(Date(s.lastMillis))} · next about ${fmt.format(Date(s.nextMillis))}",
                            style = MaterialTheme.typography.bodySmall)
                        Text(if (s.confidence >= 0.8) "Very regular" else "Fairly regular", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row {
                            Button(onClick = { confirm = s }, enabled = canEditMoney(env)) { Text("Track") }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { RecurringPrefs.dismiss(ctx, env.familyId, s.key); refresh++ }) { Text("Dismiss") }
                        }
                    }
                }
            }
        }
    }

    confirm?.let { s ->
        var name by remember(s) { mutableStateOf(s.payee) }
        var amount by remember(s) { mutableStateOf("%.0f".format(s.amount)) }
        var freq by remember(s) { mutableStateOf(s.frequency.label) }
        var saving by remember(s) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!saving) confirm = null },
            title = { Text("Track this payment?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it.take(60) }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(amount, { amount = it.filter { c -> c.isDigit() || c == '.' }.take(9) }, label = { Text("Amount (₹)") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    ChoiceChips(RecurringFinder.Frequency.values().map { it.label }, freq, { if (it.isNotBlank()) freq = it }, scroll = true)
                    Text(if (freq == "Weekly") "Saves a weekly reminder. Nothing is paid or added to Finance."
                        else "Saves it under Finance → Subscriptions with the next renewal on ${fmt.format(Date(s.nextMillis))}. Nothing is paid or added to Finance.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(enabled = !saving && name.isNotBlank() && (amount.toDoubleOrNull() ?: 0.0) > 0, onClick = {
                    if (!canEditMoney(env)) { Toast.makeText(ctx, "Finance editing is turned off for you.", Toast.LENGTH_LONG).show(); return@TextButton }
                    saving = true
                    val now = System.currentTimeMillis()
                    val amt = amount.toDouble()
                    val data: Map<String, Any?> = if (freq == "Weekly") mapOf(
                        "type" to "reminder", "title" to "Pay $name (about ${inr(amt)})", "dateMillis" to s.nextMillis, "time" to "",
                        "repeat" to "Weekly", "done" to false, "notes" to "From recurring payment suggestions", "sourceKey" to "recurring:${s.key}",
                        "createdByUid" to env.uid, "createdByName" to env.profile.name, "createdAt" to now, "updatedAt" to now
                    ) else mapOf(
                        "type" to "subscription", "title" to name, "category" to "Other", "amountInr" to amt, "cycle" to freq,
                        "renewalMillis" to s.nextMillis, "notes" to "Suggested from ${s.count} payments", "sourceKey" to "recurring:${s.key}",
                        "createdByUid" to env.uid, "createdByName" to env.profile.name, "createdAt" to now, "updatedAt" to now
                    )
                    scope.launch {
                        try {
                            env.repo.saveScoped(env.familyId, env.uid, "items", null, data, false)
                            justTracked = justTracked + s.key
                            Toast.makeText(ctx, "Saved: $name (${freq.lowercase()}).", Toast.LENGTH_SHORT).show()
                            confirm = null
                        } catch (e: Exception) {
                            Toast.makeText(ctx, "Not saved: ${e.message?.take(80)}", Toast.LENGTH_LONG).show()
                        }
                        saving = false
                    }
                }) { Text(if (saving) "Saving…" else "Save") }
            },
            dismissButton = { TextButton(enabled = !saving, onClick = { confirm = null }) { Text("Cancel") } }
        )
    }
}
