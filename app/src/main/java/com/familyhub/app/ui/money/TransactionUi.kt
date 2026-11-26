package com.familyhub.app.ui.money

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.familyhub.app.core.theme.GlassCard
import com.familyhub.app.data.remote.PRIVATE_FLAG
import com.familyhub.app.data.remote.Rec
import com.familyhub.app.ui.modules.bool
import com.familyhub.app.ui.modules.financeCategories
import com.familyhub.app.ui.modules.lng
import com.familyhub.app.ui.modules.num
import com.familyhub.app.ui.modules.str
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val rupees: NumberFormat = NumberFormat.getCurrencyInstance(Locale("en", "IN")).apply { maximumFractionDigits = 2 }
private fun rupee(v: Double): String = rupees.format(v).removeSuffix(".00")

/** Stable key for a record in a list: family and "Only me" records can share an id. */
fun recKey(r: Rec): String = (r[PRIVATE_FLAG] == true).toString() + r.str("id")

fun categoryIcon(category: String): ImageVector = when (category.lowercase()) {
    "groceries" -> Icons.Rounded.LocalGroceryStore
    "rent" -> Icons.Rounded.House
    "utilities" -> Icons.Rounded.Bolt
    "education" -> Icons.Rounded.School
    "health" -> Icons.Rounded.MedicalServices
    "transport" -> Icons.Rounded.DirectionsBus
    "travel" -> Icons.Rounded.FlightTakeoff
    "fuel" -> Icons.Rounded.LocalGasStation
    "dining" -> Icons.Rounded.Restaurant
    "shopping" -> Icons.Rounded.ShoppingBag
    "entertainment" -> Icons.Rounded.Movie
    "emi" -> Icons.Rounded.AccountBalance
    "insurance" -> Icons.Rounded.Shield
    "salary" -> Icons.Rounded.Payments
    else -> Icons.Rounded.ReceiptLong
}

private val categoryColors = listOf(
    Color(0xFF34C759), Color(0xFF0A84FF), Color(0xFFFF9F0A), Color(0xFFAF52DE),
    Color(0xFFFF375F), Color(0xFF30B0C7), Color(0xFF5E5CE6), Color(0xFFFF6B35)
)

fun categoryColor(category: String): Color =
    categoryColors[(category.lowercase().hashCode() and 0x7fffffff) % categoryColors.size]

/** A clean transaction line: icon, merchant, details, amount. Tap for actions, long-press to select. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TransactionRow(
    rec: Rec,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val type = rec.str("type").ifBlank { "EXPENSE" }
    val category = rec.str("category").ifBlank { if (type == "INCOME") "Salary" else "Other" }
    val excluded = rec.bool("notExpense")
    val refund = rec.num("refundAmount") ?: 0.0
    val amount = rec.num("amountInr") ?: 0.0
    val isDue = (type == "BILL" || type == "EMI") && !rec.bool("isPaid")
    val date = rec.lng("dateMillis")?.let { SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(it)) }.orEmpty()
    val details = listOf(category, rec.str("paymentMethod"), date, rec.str("memberName"))
        .filter { it.isNotBlank() }.joinToString(" • ")
    val shape = RoundedCornerShape(20.dp)

    GlassCard(
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(42.dp).clip(CircleShape).background(
                    if (selected) Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary))
                    else Brush.linearGradient(listOf(categoryColor(category).copy(alpha = 0.85f), categoryColor(category)))
                ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (selected) Icons.Rounded.Check else categoryIcon(category),
                    contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (rec[PRIVATE_FLAG] == true) {
                        Icon(Icons.Rounded.Lock, contentDescription = "Only me", modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        rec.str("title").ifBlank { "(untitled)" },
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val tags = buildList {
                    if (excluded) add("Not an expense" to Color(0xFF8E8E93))
                    if (refund > 0) add("Refund ${rupee(refund)} merged" to Color(0xFF1B8A3A))
                    if (rec.str("refundOf").isNotBlank()) add("Refund" to Color(0xFF1B8A3A))
                    if (isDue) add("Due" to Color(0xFFD97706))
                }
                if (tags.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 3.dp)) {
                        tags.forEach { (t, c) ->
                            Text(
                                t, style = MaterialTheme.typography.labelSmall, color = c,
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.12f))
                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    (if (type == "INCOME") "+" else "") + rupee(amount),
                    fontWeight = FontWeight.Bold,
                    color = when {
                        excluded -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                        type == "INCOME" -> Color(0xFF1B8A3A)
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    textDecoration = if (excluded) TextDecoration.LineThrough else null,
                    maxLines = 1
                )
                if (refund > 0) {
                    Text(
                        "net " + rupee((amount - refund).coerceAtLeast(0.0)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            if (selecting && !selected) {
                Spacer(modifier = Modifier.width(6.dp))
                Icon(Icons.Rounded.RadioButtonUnchecked, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
            }
        }
    }
}

/** Refund candidates for a spend: credits within 60 days after it, not already merged, not larger than it. */
fun refundCandidates(spend: Rec, all: List<Rec>): List<Rec> {
    val t = spend.lng("dateMillis") ?: 0L
    val amount = spend.num("amountInr") ?: 0.0
    val merchant = spend.str("title").lowercase().take(6)
    return all.filter { r ->
        recKey(r) != recKey(spend) && !r.bool("isDeleted") && r.str("type") == "INCOME" &&
            r.str("refundOf").isBlank() && (r.num("amountInr") ?: 0.0) <= amount + 0.5 &&
            (r.lng("dateMillis") ?: 0L) in (t - 86_400_000L)..(t + 60L * 86_400_000L)
    }.sortedWith(
        compareByDescending<Rec> { merchant.isNotBlank() && it.str("title").lowercase().contains(merchant) }
            .thenByDescending { "refund" in it.str("title").lowercase() || "reversal" in it.str("title").lowercase() }
            .thenBy { it.lng("dateMillis") ?: 0L }
    )
}

/** Spends a credit could be the refund of: within 60 days before it and at least as large. */
fun refundTargets(refund: Rec, all: List<Rec>): List<Rec> {
    val t = refund.lng("dateMillis") ?: 0L
    val amount = refund.num("amountInr") ?: 0.0
    val merchant = refund.str("title").lowercase().take(6)
    return all.filter { r ->
        recKey(r) != recKey(refund) && !r.bool("isDeleted") && r.str("type") != "INCOME" && r.str("type") != "SAVING" &&
            (r.num("amountInr") ?: 0.0) + 0.5 >= amount + (r.num("refundAmount") ?: 0.0) &&
            (r.lng("dateMillis") ?: 0L) in (t - 60L * 86_400_000L)..(t + 86_400_000L)
    }.sortedWith(
        compareByDescending<Rec> { merchant.isNotBlank() && it.str("title").lowercase().contains(merchant) }
            .thenByDescending { it.lng("dateMillis") ?: 0L }
    )
}

/** What a tap on a transaction can do. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TransactionSheet(
    rec: Rec,
    all: List<Rec>,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onSet: (Rec, Map<String, Any?>) -> Unit,
    onDelete: () -> Unit,
    onAskAi: (() -> Unit)? = null
) {
    val type = rec.str("type").ifBlank { "EXPENSE" }
    val amount = rec.num("amountInr") ?: 0.0
    val refund = rec.num("refundAmount") ?: 0.0
    var showCategories by remember { mutableStateOf(false) }
    var showRefunds by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val date = rec.lng("dateMillis")?.let { SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault()).format(Date(it)) }.orEmpty()

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this entry?") },
            text = { Text("${rec.str("title")} • ${rupee(amount)} will be removed from Finance and all totals.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val cat = rec.str("category").ifBlank { "Other" }
                Box(
                    modifier = Modifier.size(48.dp).clip(CircleShape).background(categoryColor(cat)),
                    contentAlignment = Alignment.Center
                ) { Icon(categoryIcon(cat), contentDescription = null, tint = Color.White) }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(rec.str("title").ifBlank { "(untitled)" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        listOf(cat, rec.str("paymentMethod"), rec.str("card").takeIf { it != "None" }.orEmpty())
                            .filter { it.isNotBlank() }.joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Text(
                    (if (type == "INCOME") "+" else "") + rupee(amount),
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    color = if (type == "INCOME") Color(0xFF1B8A3A) else MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                listOf(date, rec.str("memberName").let { if (it.isBlank()) "" else "by $it" }, if (rec[PRIVATE_FLAG] == true) "Only me" else "Family")
                    .filter { it.isNotBlank() }.joinToString(" • "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp)
            )
            val notes = rec.str("notes")
            if (notes.isNotBlank()) Text(notes, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            Spacer(modifier = Modifier.height(12.dp))

            SheetAction(Icons.Rounded.Edit, "Edit details") { onEdit() }
            SheetAction(Icons.Rounded.Category, "Change category") { showCategories = !showCategories }
            if (showCategories) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(start = 40.dp, bottom = 8.dp)
                ) {
                    financeCategories.forEach { c ->
                        FilterChip(
                            selected = rec.str("category") == c,
                            onClick = { onSet(rec, mapOf("category" to c)); showCategories = false },
                            label = { Text(c) },
                            leadingIcon = { Icon(categoryIcon(c), contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                    }
                }
            }
            if (type == "BILL" || type == "EMI") {
                val paid = rec.bool("isPaid")
                SheetAction(if (paid) Icons.Rounded.Undo else Icons.Rounded.TaskAlt, if (paid) "Mark as not paid" else "Mark as paid") {
                    onSet(rec, mapOf("isPaid" to !paid))
                }
            }
            val excluded = rec.bool("notExpense")
            SheetAction(
                if (excluded) Icons.Rounded.AddCircleOutline else Icons.Rounded.RemoveCircleOutline,
                if (excluded) "Count it again" else if (type == "INCOME") "Not income (exclude from totals)" else "Not an expense",
                note = if (excluded) "It's left out of spending and budgets now" else "Self transfers, card bill payments, money moved between your accounts"
            ) { onSet(rec, mapOf("notExpense" to !excluded)) }

            if (type == "INCOME" && rec.str("refundOf").isBlank()) {
                SheetAction(Icons.Rounded.CallMerge, "This is a refund…", note = "Merge it into the purchase it refunds") { showRefunds = !showRefunds }
                if (showRefunds) {
                    val targets = remember(all) { refundTargets(rec, all).take(8) }
                    RefundPicker(targets, empty = "No earlier purchase of at least ${rupee(amount)} in the last 60 days.") { target ->
                        onSet(target, mapOf("refundAmount" to (target.num("refundAmount") ?: 0.0) + amount))
                        onSet(rec, mapOf("refundOf" to target.str("id"), "notExpense" to true))
                        onDismiss()
                    }
                }
            } else if (type != "INCOME") {
                SheetAction(Icons.Rounded.CallMerge, "Merge a refund", note = "Pick the refund credit; only the net amount counts") { showRefunds = !showRefunds }
                if (showRefunds) {
                    val candidates = remember(all) { refundCandidates(rec, all).take(8) }
                    RefundPicker(candidates, empty = "No credits found within 60 days after this purchase.") { r ->
                        onSet(rec, mapOf("refundAmount" to refund + (r.num("amountInr") ?: 0.0)))
                        onSet(r, mapOf("refundOf" to rec.str("id"), "notExpense" to true))
                        onDismiss()
                    }
                }
            }
            if (refund > 0) {
                SheetAction(Icons.Rounded.CallSplit, "Undo refund merge", note = "Counts the full ${rupee(amount)} again") {
                    all.filter { it.str("refundOf") == rec.str("id") }.forEach { r ->
                        onSet(r, mapOf("refundOf" to "", "notExpense" to false))
                    }
                    onSet(rec, mapOf("refundAmount" to 0.0))
                    onDismiss()
                }
            }
            if (rec.str("refundOf").isNotBlank()) {
                SheetAction(Icons.Rounded.CallSplit, "Unmerge this refund") {
                    all.firstOrNull { it.str("id") == rec.str("refundOf") }?.let { t ->
                        onSet(t, mapOf("refundAmount" to ((t.num("refundAmount") ?: 0.0) - amount).coerceAtLeast(0.0)))
                    }
                    onSet(rec, mapOf("refundOf" to "", "notExpense" to false))
                    onDismiss()
                }
            }
            if (onAskAi != null) {
                SheetAction(Icons.Rounded.AutoAwesome, "Ask AI about this") { onAskAi() }
            }
            SheetAction(Icons.Rounded.Delete, "Delete", tint = MaterialTheme.colorScheme.error) { confirmDelete = true }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefundPicker(options: List<Rec>, empty: String, onPick: (Rec) -> Unit) {
    Column(modifier = Modifier.padding(start = 40.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (options.isEmpty()) {
            Text(empty, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        options.forEach { o ->
            val d = o.lng("dateMillis")?.let { SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(it)) }.orEmpty()
            OutlinedCard(onClick = { onPick(o) }, modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(o.str("title"), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        Text(d, style = MaterialTheme.typography.labelSmall)
                    }
                    Text(rupee(o.num("amountInr") ?: 0.0), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    note: String? = null,
    tint: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 11.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(14.dp))
        Column {
            Text(label, fontWeight = FontWeight.Medium, color = if (tint == MaterialTheme.colorScheme.error) tint else MaterialTheme.colorScheme.onSurface)
            if (note != null) Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
        }
    }
}

/** Shown instead of the title bar while transactions are selected. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BulkActionBar(
    count: Int,
    total: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onCategory: (String) -> Unit,
    onNotExpense: (Boolean) -> Unit,
    onPrivate: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    var pickCategory by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete $count entries?") },
            text = { Text("They will be removed from Finance and all totals.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
    if (pickCategory) {
        AlertDialog(
            onDismissRequest = { pickCategory = false },
            title = { Text("Category for $count entries") },
            text = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    financeCategories.forEach { c ->
                        AssistChip(onClick = { pickCategory = false; onCategory(c) }, label = { Text(c) },
                            leadingIcon = { Icon(categoryIcon(c), contentDescription = null, modifier = Modifier.size(16.dp)) })
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickCategory = false }) { Text("Cancel") } }
        )
    }
    GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), shape = RoundedCornerShape(22.dp), strong = true) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Stop selecting") }
                Text("$count selected", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (count < total) TextButton(onClick = onSelectAll) { Text("Select all") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)) {
                AssistChip(onClick = { pickCategory = true }, label = { Text("Category") }, leadingIcon = { Icon(Icons.Rounded.Category, null, Modifier.size(16.dp)) })
                AssistChip(onClick = { onNotExpense(true) }, label = { Text("Not an expense") }, leadingIcon = { Icon(Icons.Rounded.RemoveCircleOutline, null, Modifier.size(16.dp)) })
                AssistChip(onClick = { onNotExpense(false) }, label = { Text("Count again") }, leadingIcon = { Icon(Icons.Rounded.AddCircleOutline, null, Modifier.size(16.dp)) })
                AssistChip(onClick = { onPrivate(true) }, label = { Text("Only me") }, leadingIcon = { Icon(Icons.Rounded.Lock, null, Modifier.size(16.dp)) })
                AssistChip(onClick = { onPrivate(false) }, label = { Text("Family") }, leadingIcon = { Icon(Icons.Rounded.Groups, null, Modifier.size(16.dp)) })
                AssistChip(onClick = { confirmDelete = true }, label = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Rounded.Delete, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error) })
            }
        }
    }
}
