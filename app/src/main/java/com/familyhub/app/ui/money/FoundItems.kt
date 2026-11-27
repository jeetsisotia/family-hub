package com.familyhub.app.ui.money

import com.familyhub.app.core.theme.GlassInputField
import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.familyhub.app.core.theme.GlassCard
import com.familyhub.app.data.remote.Rec
import com.familyhub.app.money.ParsedAlert
import com.familyhub.app.money.PhoneEvent
import com.familyhub.app.ui.AppEnv
import com.familyhub.app.ui.common.formatDate
import com.familyhub.app.ui.modules.DAY_MS
import com.familyhub.app.ui.modules.localNoon
import com.familyhub.app.ui.modules.str
import java.util.Calendar

/** What the person changed on a found item before adding it. */
data class AlertEdit(val title: String, val amount: Double?, val dateMillis: Long?)

fun defaultEdit(a: ParsedAlert) = AlertEdit(a.title, a.amount?.takeIf { a.kind !in setOf("card", "account") }, a.dueMillis ?: a.expiryMillis)

fun alertKindLabel(kind: String): String = when (kind) {
    "card_due" -> "Card bill"
    "bill_due" -> "Bill due"
    "subscription" -> "Subscription"
    "policy" -> "Insurance"
    "action" -> "Needs action"
    "card" -> "Credit card"
    "account" -> "Bank account"
    else -> "Found"
}

fun alertIcon(kind: String): ImageVector = when (kind) {
    "card_due", "card" -> Icons.Rounded.CreditCard
    "bill_due" -> Icons.Rounded.ReceiptLong
    "subscription" -> Icons.Rounded.Subscriptions
    "policy" -> Icons.Rounded.Shield
    "action" -> Icons.Rounded.TaskAlt
    "account" -> Icons.Rounded.AccountBalance
    else -> Icons.Rounded.Notifications
}

private val kindColors = mapOf(
    "card_due" to Color(0xFFFF6B35), "bill_due" to Color(0xFFFF9F0A), "subscription" to Color(0xFFAF52DE),
    "policy" to Color(0xFF30B0C7), "action" to Color(0xFFFF375F), "card" to Color(0xFF0A84FF), "account" to Color(0xFF34C759)
)

/** Saves one found item where it belongs: bills to Finance, renewals and policies to their lists, actions as reminders. */
suspend fun saveFoundAlert(env: AppEnv, a: ParsedAlert, e: AlertEdit, familyVisible: Boolean, cards: List<Rec>, dues: List<ParsedAlert>) {
    val now = System.currentTimeMillis()
    val repo = env.repo
    val note = "Found in ${if (a.source == "SMS") "SMS" else "email"}" + (a.minDue?.let { " • minimum due ₹%,.0f".format(it) } ?: "")
    when (a.kind) {
        "card_due", "bill_due" -> {
            val card = if (a.last4.isNotBlank()) cards.firstOrNull { it.str("last4") == a.last4 } else null
            val due = e.dateMillis ?: localNoon(now)
            repo.saveScoped(
                env.familyId, env.uid, "expenses", null,
                mapOf(
                    "type" to "BILL", "title" to e.title, "amountInr" to (e.amount ?: 0.0),
                    "category" to a.category.ifBlank { "Utilities" }, "dateMillis" to due, "dueDateMillis" to due,
                    "isPaid" to false, "isDeleted" to false, "paymentMethod" to "",
                    // A card bill repeats spending already counted card swipe by swipe.
                    "notExpense" to (a.kind == "card_due"),
                    "card" to (card?.str("title") ?: "None"), "notes" to note, "sourceKey" to a.key,
                    "memberUid" to env.uid, "memberName" to env.profile.name, "createdAt" to now
                ),
                !familyVisible
            )
        }
        "subscription" -> repo.saveScoped(
            env.familyId, env.uid, "items", null,
            mapOf(
                "type" to "subscription", "title" to e.title, "category" to a.category.ifBlank { "Other" },
                "amountInr" to (e.amount ?: 0.0), "cycle" to "Monthly", "renewalMillis" to e.dateMillis,
                "notes" to note, "sourceKey" to a.key, "createdAt" to now
            ),
            !familyVisible
        )
        "policy" -> repo.saveScoped(
            env.familyId, env.uid, "items", null,
            mapOf(
                "type" to "policy", "title" to e.title, "insurer" to "", "category" to a.category.ifBlank { "Other" },
                "amountInr" to e.amount, "premiumDueMillis" to (if (a.dueMillis != null || a.expiryMillis == null) e.dateMillis else a.dueMillis),
                "expiryMillis" to (if (a.dueMillis == null && a.expiryMillis != null) e.dateMillis else a.expiryMillis),
                "notes" to note, "sourceKey" to a.key, "createdAt" to now
            ),
            !familyVisible
        )
        "action" -> repo.saveScoped(
            env.familyId, env.uid, "items", null,
            mapOf(
                "type" to "reminder", "title" to e.title, "dateMillis" to (e.dateMillis ?: localNoon(now + DAY_MS)),
                "time" to "10:00", "repeat" to "None", "done" to false, "notes" to note, "sourceKey" to a.key, "createdAt" to now
            ),
            true
        )
        "card" -> {
            // The due day comes from the newest bill reminder for the same card, if one was found.
            val dueDay = dues.filter { it.kind == "card_due" && it.last4 == a.last4 }.mapNotNull { it.dueMillis }.maxOrNull()
                ?.let { Calendar.getInstance().apply { timeInMillis = it }.get(Calendar.DAY_OF_MONTH).toDouble() }
            repo.saveScoped(
                env.familyId, env.uid, "items", null,
                mapOf(
                    "type" to "card", "title" to e.title, "bank" to a.bank, "last4" to a.last4,
                    "statementDay" to null, "dueDay" to dueDay, "notes" to "Found in bank alerts", "sourceKey" to a.key, "createdAt" to now
                ),
                true
            )
        }
        "account" -> repo.saveScoped(
            env.familyId, env.uid, "items", null,
            mapOf(
                "type" to "account", "title" to e.title, "bank" to a.bank, "last4" to a.last4, "category" to "Savings",
                "notes" to "Found in bank alerts", "sourceKey" to a.key, "createdAt" to now
            ),
            true
        )
    }
}

suspend fun saveFoundEvent(env: AppEnv, ev: PhoneEvent, title: String, familyVisible: Boolean) {
    env.repo.saveScoped(
        env.familyId, env.uid, "events", null,
        mapOf(
            "title" to title, "category" to "Other", "startTimeMillis" to ev.begin,
            "endTimeMillis" to (if (ev.end > ev.begin) ev.end else ev.begin + 3_600_000L),
            "recurrence" to "NONE", "isDeleted" to false, "reminderMinutesBefore" to 30L,
            // The event's time from the phone calendar ("All day" events keep none).
            "time" to (if (ev.allDay) "" else java.util.Calendar.getInstance().apply { timeInMillis = ev.begin }.let {
                String.format(java.util.Locale.US, "%02d:%02d", it.get(java.util.Calendar.HOUR_OF_DAY), it.get(java.util.Calendar.MINUTE))
            }),
            "notes" to listOf(ev.location, "From phone calendar: ${ev.calendar}").filter { it.isNotBlank() }.joinToString("\n"),
            "sourceKey" to ev.key, "createdByUid" to env.uid, "createdAt" to System.currentTimeMillis()
        ),
        !familyVisible
    )
}

/** One found bill, renewal, policy, card, account or action, with a tick box; tap to edit before adding. */
@Composable
fun FoundAlertRow(
    a: ParsedAlert,
    edit: AlertEdit,
    checked: Boolean,
    onCheck: (Boolean) -> Unit,
    onEdit: (AlertEdit) -> Unit
) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var title by remember(edit.title) { mutableStateOf(edit.title) }
    var amount by remember(edit.amount) { mutableStateOf(edit.amount?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }.orEmpty()) }
    val color = kindColors[a.kind] ?: Color(0xFF8E8E93)
    GlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), onClick = { open = !open }) {
        Column(modifier = Modifier.padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = checked, onCheckedChange = onCheck)
                Box(
                    modifier = Modifier.size(34.dp).clip(CircleShape).background(color),
                    contentAlignment = Alignment.Center
                ) { Icon(alertIcon(a.kind), contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp)) }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(edit.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            alertKindLabel(a.kind),
                            edit.dateMillis?.let {
                                (when (a.kind) { "subscription" -> "renews "; "policy" -> if (a.dueMillis == null) "expires " else "due "; "action" -> "by "; else -> "due " }) + formatDate(it)
                            },
                            a.minDue?.let { "min ₹%,.0f".format(it) },
                            if (a.kind == "card" || a.kind == "account") "seen in ${a.amount?.toInt() ?: 1} alert(s)" else null,
                            a.source
                        ).joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
                if (edit.amount != null) Text(inr(edit.amount), fontWeight = FontWeight.Bold)
            }
            if (open) {
                Column(modifier = Modifier.padding(start = 12.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassInputField(
                        value = title, onValueChange = { title = it }, label = { Text("Name") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (a.kind !in setOf("card", "account", "action")) {
                        GlassInputField(
                            value = amount, onValueChange = { s -> amount = s.filter { it.isDigit() || it == '.' } },
                            label = { Text("Amount (₹)") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (a.kind !in setOf("card", "account")) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Date: " + (edit.dateMillis?.let { formatDate(it) } ?: "not set"),
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                val c = Calendar.getInstance().apply { timeInMillis = edit.dateMillis ?: System.currentTimeMillis() }
                                DatePickerDialog(context, { _, y, m, d ->
                                    val t = Calendar.getInstance().apply { clear(); set(y, m, d, 12, 0, 0) }.timeInMillis
                                    onEdit(edit.copy(dateMillis = t))
                                }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
                            }) { Text("Change") }
                        }
                    }
                    Row {
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            onEdit(edit.copy(title = title.trim().ifBlank { edit.title }, amount = amount.toDoubleOrNull() ?: edit.amount))
                            open = false
                        }) { Text("Save changes", fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}

@Composable
fun FoundEventRow(ev: PhoneEvent, checked: Boolean, onCheck: (Boolean) -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Row(modifier = Modifier.padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = onCheck)
            Box(
                modifier = Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF5E5CE6)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.CalendarMonth, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp)) }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(ev.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(
                        formatDate(ev.begin) + if (!ev.allDay) " " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ev.begin)) else "",
                        ev.calendar.ifBlank { ev.account }
                    ).filter { it.isNotBlank() }.joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
