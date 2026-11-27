package com.familyhub.app.ui.money

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.familyhub.app.core.theme.GlassCard
import com.familyhub.app.money.Ledger
import com.familyhub.app.ui.AppEnv
import com.familyhub.app.ui.modules.AlertItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One line in the Due Summary: a money due (opens its pay sheet) or another alert (opens its screen). */
data class DueLine(
    val title: String,
    val detail: String,
    val dueMillis: Long,
    val amount: Double?,
    val overdue: Boolean,
    val due: Ledger.Due? = null,
    val route: String? = null
)

/**
 * Builds the Due Summary list. Money dues (bills, card bills, EMIs, renewals, premiums) come from the ledger,
 * exactly as before; other alerts (documents, birthdays, medicines, period) are added unless they are money
 * alerts, so a card bill appears once. Nothing here changes reminders or notifications.
 */
fun dueLines(data: Map<String, List<com.familyhub.app.data.remote.Rec>>, alerts: List<AlertItem>, now: Long): List<DueLine> {
    val money = Ledger.dues(data, now - 60 * Ledger.DAY, now + 10 * Ledger.DAY, now)
        .filter { it.status != "PAID" && (it.snoozedUntil ?: 0L) <= now }
        .map { d ->
            val what = when (d.kind) { "card" -> "Card bill"; "emi" -> "EMI"; "subscription" -> "Renews"; "policy" -> "Premium"; else -> "Bill" }
            DueLine(d.title, what, d.dueMillis, d.amount, d.status == "OVERDUE", due = d)
        }
    // Money alerts open the due calendar; those are the same dues as above, so they are left out here.
    val other = alerts.filter { it.route != "due_calendar" }
        .map { a -> DueLine(a.title.ifBlank { "Untitled" }, a.label, a.dateMillis, null, a.overdue, route = a.route) }
    return (money + other).sortedWith(compareByDescending<DueLine> { it.overdue }.thenBy { it.dueMillis })
}

/**
 * Home's Due Summary: one compact line with how many things are due, the total to pay and how many are
 * overdue. It opens up near the top of Home and folds away as you scroll down (tap to open or close it
 * yourself). Overdue items stay visible even when folded.
 */
@Composable
fun DueSummary(env: AppEnv, alerts: List<AlertItem>, listState: LazyListState?, onSeeAll: () -> Unit) {
    val now = remember { System.currentTimeMillis() }
    val lines = remember(env.data, alerts) { dueLines(env.data, alerts, now) }
    if (lines.isEmpty()) return
    val payable = lines.sumOf { it.amount ?: 0.0 }
    val overdue = lines.filter { it.overdue }

    // Near the top: open. Scrolled down: folded. A small gap between the two limits stops it flickering.
    var nearTop by remember { mutableStateOf(true) }
    if (listState != null) {
        LaunchedEffect(listState) {
            snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                .collect { (i, off) ->
                    if (nearTop && (i > 0 || off > 120)) nearTop = false
                    else if (!nearTop && i == 0 && off < 24) nearTop = true
                }
        }
    }
    // A tap overrides the automatic choice until Home next returns to (or leaves) the top.
    var manual by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(nearTop) { manual = null }
    val open = manual ?: nearTop
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(220), label = "chevron")
    val fmt = remember { SimpleDateFormat("d MMM", Locale.getDefault()) }
    var sheetFor by remember { mutableStateOf<Ledger.Due?>(null) }

    GlassCard(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { manual = !open }
                    .semantics { contentDescription = if (open) "Due summary, open. Tap to fold." else "Due summary, folded. Tap to open." }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (overdue.isNotEmpty()) Icons.Rounded.WarningAmber else Icons.Rounded.EventNote, contentDescription = null,
                    tint = if (overdue.isNotEmpty()) Color(0xFFFF3B30) else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    // Non-breaking spaces + no soft wrap: the line used to wrap after "·" and hide the total.
                    ("${lines.size} due" + if (payable > 0) " · ${headerAmount(payable)}" else "")
                        .replace(' ', ' '),
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis
                )
                if (overdue.isNotEmpty()) {
                    Text(
                        "${overdue.size} overdue", color = Color.White, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFFFF3B30)).padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Icon(Icons.Rounded.ExpandMore, contentDescription = null, modifier = Modifier.rotate(turn))
            }
            // Folded: overdue items stay visible (at most two), so nothing urgent is hidden.
            AnimatedVisibility(visible = !open && overdue.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(Modifier.padding(bottom = 6.dp)) { overdue.take(2).forEach { l -> Line(l, fmt) { if (l.due != null) sheetFor = l.due else l.route?.let(env.navigate) } } }
            }
            AnimatedVisibility(visible = open, enter = expandVertically(tween(220)) + fadeIn(tween(220)), exit = shrinkVertically(tween(200)) + fadeOut(tween(160))) {
                Column(Modifier.padding(bottom = 4.dp)) {
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    lines.take(5).forEach { l -> Line(l, fmt) { if (l.due != null) sheetFor = l.due else l.route?.let(env.navigate) } }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.End) {
                        if (lines.size > 5) Text(
                            "+${lines.size - 5} more", style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f).padding(start = 10.dp).align(Alignment.CenterVertically)
                        )
                        TextButton(onClick = onSeeAll) { Text("All alerts") }
                        TextButton(onClick = { env.navigate("due_calendar") }) { Text("Due calendar") }
                    }
                }
            }
        }
    }
    sheetFor?.let { d -> DueSheet(env, d) { sheetFor = null } }
}

@Composable
private fun Line(l: DueLine, fmt: SimpleDateFormat, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (l.overdue) Color(0xFFFF3B30) else Color(0xFFFF9500)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(l.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(
                l.detail.substringBefore(" • ") + " · " + (if (l.overdue) "overdue since " else "") + fmt.format(Date(l.dueMillis)),
                style = MaterialTheme.typography.labelSmall, maxLines = 1,
                color = if (l.overdue) Color(0xFFFF3B30) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        l.amount?.takeIf { it > 0 }?.let { Text(inr(it), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium) }
    }
}

/** Short total for the one-line header (exact amounts are on each line): ₹950, ₹9,714, ₹37.4 k, ₹1.2 L. */
internal fun headerAmount(v: Double): String = when {
    v >= 100_000 -> inrShort(v)
    v >= 10_000 -> "₹%.1f k".format(v / 1_000)
    else -> inr(v)
}
