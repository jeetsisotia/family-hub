package com.familyhub.app.ui.modules

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import com.familyhub.app.core.theme.AccentCard
import com.familyhub.app.core.theme.GlassCard
import com.familyhub.app.core.theme.glass
import com.familyhub.app.data.remote.Rec
import com.familyhub.app.data.remote.RecordRepository
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val inrFormat: NumberFormat by lazy { NumberFormat.getCurrencyInstance(Locale("en", "IN")) }

internal fun money(v: Double): String = inrFormat.format(v)

private fun initialDraft(def: ModuleDef, rec: Rec?, userName: String): Map<String, Any?> {
    val m = HashMap<String, Any?>()
    if (rec != null) {
        m.putAll(rec)
        m.remove("id")
        return m
    }
    for (f in def.fields) {
        when (f.type) {
            FieldType.CHOICE -> m[f.key] = f.options.firstOrNull() ?: ""
            FieldType.MEMBER -> m[f.key] = userName
            FieldType.DATE -> if (f.required) m[f.key] = noonToday()
            FieldType.BOOL -> m[f.key] = false
            else -> m[f.key] = ""
        }
    }
    return m
}

private fun buildRecord(
    def: ModuleDef,
    draft: Map<String, Any?>,
    nums: Map<String, String>
): Pair<MutableMap<String, Any?>?, String?> {
    val out = HashMap<String, Any?>(draft)
    for (f in def.fields) {
        when (f.type) {
            FieldType.NUMBER -> {
                val t = nums[f.key].orEmpty().trim()
                if (t.isEmpty()) {
                    if (f.required) return Pair(null, "${f.label} is required")
                    out[f.key] = null
                } else {
                    val v = t.toDoubleOrNull() ?: return Pair(null, "${f.label} is not a valid number")
                    out[f.key] = v
                }
            }
            FieldType.DATE -> {
                if (f.required && out[f.key] == null) return Pair(null, "${f.label} is required")
            }
            FieldType.BOOL -> {
            }
            else -> {
                val s = (out[f.key] as? String).orEmpty().trim()
                if (f.required && s.isEmpty()) return Pair(null, "${f.label} is required")
                out[f.key] = s
            }
        }
    }
    return Pair(out, null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleScreen(
    def: ModuleDef,
    familyId: String,
    uid: String,
    userName: String,
    memberNames: List<String>,
    monthlyBudget: Double,
    isOwner: Boolean,
    repo: RecordRepository,
    onBack: (() -> Unit)?,
    onSaveBudget: (Double) -> Unit = {},
    onExport: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val flow = remember(familyId, def.collection) { repo.observeRecords(familyId, def.collection) }
    val all by flow.collectAsState(initial = emptyList<Rec>())
    val records = remember(all) { recordsFor(def, all) }
    val sorted = remember(records) { sortRecords(def, records) }
    var query by remember { mutableStateOf("") }
    val shown = remember(sorted, query) {
        val q = query.trim()
        if (q.isEmpty()) sorted else sorted.filter { r -> r.values.any { v -> v is String && v.contains(q, ignoreCase = true) } }
    }

    var showForm by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Rec?>(null) }
    var toDelete by remember { mutableStateOf<Rec?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun save(map: Map<String, Any?>, existing: Rec?) {
        val data = HashMap<String, Any?>(map)
        for ((k, v) in def.constants) {
            if (!data.containsKey(k)) data[k] = v
        }
        val tv = def.typeValue
        if (tv != null) data["type"] = tv
        data["familyId"] = familyId
        data["updatedAt"] = System.currentTimeMillis()
        if (existing == null) {
            data["createdByUid"] = uid
            data["createdByName"] = userName
            if (def.autoCreatedAt) data["createdAtMillis"] = System.currentTimeMillis()
        }
        def.postProcess?.invoke(data, uid, userName)
        scope.launch {
            try {
                repo.save(familyId, def.collection, existing?.str("id"), data)
                error = null
            } catch (e: Exception) {
                error = "Could not save: ${e.message}"
            }
        }
    }

    fun toggle(rec: Rec, checked: Boolean) {
        val key = def.doneKey ?: return
        scope.launch {
            try {
                repo.setField(familyId, def.collection, rec.str("id"), key, checked)
            } catch (e: Exception) {
                error = "Could not update: ${e.message}"
            }
        }
    }

    fun remove(rec: Rec) {
        scope.launch {
            try {
                val soft = def.softDeleteKey
                if (soft != null) repo.setField(familyId, def.collection, rec.str("id"), soft, true)
                else repo.delete(familyId, def.collection, rec.str("id"))
            } catch (e: Exception) {
                error = "Could not delete: ${e.message}"
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onBack != null) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.padding(start = 4.dp, end = 8.dp).glass(CircleShape, strong = true)
                    ) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Back") }
                } else {
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Text(
                    text = def.title,
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.weight(1f)
                )
                if (onExport != null) {
                    IconButton(
                        onClick = onExport,
                        modifier = Modifier.padding(end = 4.dp).glass(CircleShape, strong = true)
                    ) { Icon(Icons.Rounded.FileDownload, contentDescription = "Export report") }
                }
            }
            val err = error
            if (err != null) {
                Text(
                    text = err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    ModuleHeader(def, records, monthlyBudget, isOwner, memberNames, onSaveBudget)
                }
                if (records.size > 3) {
                    item {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(18.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.6f)
                            ),
                            placeholder = { Text("Search") },
                            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) }
                        )
                    }
                }
                if (shown.isEmpty()) {
                    item {
                        Text(
                            text = def.emptyText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                            modifier = Modifier.padding(vertical = 24.dp)
                        )
                    }
                }
                items(shown, key = { it.str("id") }) { rec ->
                    Box(modifier = Modifier.animateItem()) {
                    RecordCard(
                        def = def,
                        rec = rec,
                        onClick = { editing = rec; showForm = true },
                        onToggle = { checked -> toggle(rec, checked) },
                        onDelete = { toDelete = rec },
                        onCall = { phone ->
                            try {
                                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
                            } catch (e: Exception) {
                                error = "Could not open the phone app."
                            }
                        }
                    )
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { editing = null; showForm = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            shape = RoundedCornerShape(22.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) { Icon(Icons.Rounded.Add, contentDescription = "Add") }

        if (showForm) {
            val current = editing
            key(current?.str("id") ?: "new") {
                RecordForm(
                    def = def,
                    initial = current,
                    memberNames = memberNames,
                    userName = userName,
                    onDismiss = { showForm = false },
                    onSave = { m ->
                        save(m, current)
                        showForm = false
                    }
                )
            }
        }

        val del = toDelete
        if (del != null) {
            AlertDialog(
                onDismissRequest = { toDelete = null },
                title = { Text("Delete this entry?") },
                text = { Text(del.str(def.titleKey)) },
                confirmButton = {
                    TextButton(onClick = { remove(del); toDelete = null }) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } }
            )
        }
    }
}

// ---------- list card ----------

private fun amountText(def: ModuleDef, rec: Rec): String? {
    val key = def.amountKey ?: return null
    val v = rec.num(key) ?: return null
    if (def.kind == "finance") {
        val sign = when (rec.str("type")) {
            "INCOME" -> "+"
            "EXPENSE", "BILL", "EMI" -> "-"
            else -> ""
        }
        return sign + money(v)
    }
    return money(v)
}

@Composable
private fun RecordCard(
    def: ModuleDef,
    rec: Rec,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onCall: (String) -> Unit
) {
    val doneKey = def.doneKey
    val done = doneKey != null && rec.bool(doneKey)
    val sub = def.subtitleKeys.map { rec.str(it) }.filter { it.isNotBlank() }.joinToString(" • ")
    val amount = amountText(def, rec)
    val phone = def.phoneKey?.let { rec.str(it) }.orEmpty()

    GlassCard(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (doneKey != null) {
                Checkbox(checked = done, onCheckedChange = onToggle)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rec.str(def.titleKey).ifBlank { "(untitled)" },
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (sub.isNotBlank()) {
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                DateLines(def, rec, done)
            }
            Column(horizontalAlignment = Alignment.End) {
                if (amount != null) {
                    Text(
                        text = amount,
                        fontWeight = FontWeight.Bold,
                        color = if (def.kind == "finance" && rec.str("type") == "INCOME") Color(0xFF1B8A3A)
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
                Row {
                    if (phone.isNotBlank()) {
                        IconButton(onClick = { onCall(phone) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Rounded.Call, contentDescription = "Call", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DateLines(def: ModuleDef, rec: Rec, done: Boolean) {
    val fmt = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
    val rep = def.repeatKey?.let { rec.str(it) }.orEmpty()
    for (dk in def.dateKeys) {
        val raw = rec.lng(dk.key) ?: continue
        val d = nextOccurrence(raw, rep)
        val days = daysUntil(d)
        val note = when {
            done -> ""
            days < 0 && def.alertOverdue -> "overdue by ${-days}d"
            days < 0 -> ""
            days == 0 -> "today"
            days <= 30 -> "in ${days}d"
            else -> ""
        }
        val color = when {
            done -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            days < 0 && def.alertOverdue -> MaterialTheme.colorScheme.error
            days in 0..7 -> Color(0xFFD97706)
            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        }
        Text(
            text = "${dk.label}: ${fmt.format(Date(d))}" + if (note.isNotEmpty()) " • $note" else "",
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
    }
}

// ---------- headers (summaries) ----------

@Composable
private fun ModuleHeader(
    def: ModuleDef,
    records: List<Rec>,
    monthlyBudget: Double,
    isOwner: Boolean,
    memberNames: List<String>,
    onSaveBudget: (Double) -> Unit
) {
    when (def.kind) {
        "finance" -> FinanceHeader(records, monthlyBudget, isOwner, onSaveBudget)
        "loan" -> {
            val outstanding = records.sumOf { it.num("amountInr") ?: 0.0 }
            val emi = records.sumOf { it.num("emiAmount") ?: 0.0 }
            if (records.isNotEmpty()) {
                SummaryCard(listOf("Total outstanding" to money(outstanding), "EMIs per month" to money(emi)))
            }
        }
        "subscription" -> {
            val monthly = records.sumOf { r ->
                val a = r.num("amountInr") ?: 0.0
                when (r.str("cycle").uppercase()) {
                    "YEARLY" -> a / 12.0
                    "QUARTERLY" -> a / 3.0
                    else -> a
                }
            }
            if (records.isNotEmpty()) {
                SummaryCard(listOf("Per month" to money(monthly), "Per year" to money(monthly * 12)))
            }
        }
        "settle" -> SettleHeader(records, memberNames)
        else -> {
        }
    }
}

@Composable
private fun SummaryCard(stats: List<Pair<String, String>>, footer: String? = null) {
    AccentCard(
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                stats.forEach { (label, value) ->
                    Column {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                        )
                        Text(
                            text = value,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
            if (footer != null) {
                Text(
                    text = footer,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
                )
            }
        }
    }
}

@Composable
private fun FinanceHeader(
    records: List<Rec>,
    budget: Double,
    isOwner: Boolean,
    onSaveBudget: (Double) -> Unit
) {
    val now = Calendar.getInstance()
    val year = now.get(Calendar.YEAR)
    val month = now.get(Calendar.MONTH)

    fun inYear(r: Rec): Boolean {
        val t = r.lng("dateMillis") ?: return false
        val c = Calendar.getInstance()
        c.timeInMillis = t
        return c.get(Calendar.YEAR) == year
    }

    fun inMonth(r: Rec): Boolean {
        val t = r.lng("dateMillis") ?: return false
        val c = Calendar.getInstance()
        c.timeInMillis = t
        return c.get(Calendar.YEAR) == year && c.get(Calendar.MONTH) == month
    }

    fun isSpend(r: Rec): Boolean {
        val t = r.str("type")
        return t == "EXPENSE" || ((t == "BILL" || t == "EMI") && r.bool("isPaid"))
    }

    fun amt(r: Rec): Double = r.num("amountInr") ?: 0.0

    val monthRecs = records.filter { inMonth(it) }
    val spent = monthRecs.filter { isSpend(it) }.sumOf { amt(it) }
    val income = monthRecs.filter { it.str("type") == "INCOME" }.sumOf { amt(it) }
    val saved = monthRecs.filter { it.str("type") == "SAVING" }.sumOf { amt(it) }
    val dueBills = records.filter { (it.str("type") == "BILL" || it.str("type") == "EMI") && !it.bool("isPaid") }
    val yearRecs = records.filter { inYear(it) }
    val yearSpent = yearRecs.filter { isSpend(it) }.sumOf { amt(it) }
    val yearIncome = yearRecs.filter { it.str("type") == "INCOME" }.sumOf { amt(it) }
    val topCategories = monthRecs.filter { isSpend(it) }
        .groupBy { it.str("category").ifBlank { "Other" } }
        .mapValues { e -> e.value.sumOf { amt(it) } }
        .entries.sortedByDescending { it.value }.take(4)

    var showBudget by remember { mutableStateOf(false) }
    val fraction = if (budget > 0) (spent / budget).toFloat().coerceIn(0f, 1f) else 0f

    AccentCard(
        shape = RoundedCornerShape(26.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "This month",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
            )
            Text(
                text = "${money(spent)} spent of ${money(budget)}",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onPrimary
            )
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.3f)
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Income ${money(income)}", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.bodySmall)
                Text("Saved ${money(saved)}", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.bodySmall)
                Text(
                    "Left ${money((budget - spent).coerceAtLeast(0.0))}",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (dueBills.isNotEmpty()) {
                Text(
                    text = "${dueBills.size} unpaid bills/EMIs: ${money(dueBills.sumOf { amt(it) })}",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                )
            }
            if (topCategories.isNotEmpty()) {
                Text(
                    text = "Top: " + topCategories.joinToString("  ") { "${it.key} ${money(it.value)}" },
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                text = "This year: ${money(yearSpent)} spent, ${money(yearIncome)} income",
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodySmall
            )
            if (isOwner) {
                TextButton(onClick = { showBudget = true }) {
                    Text("Edit monthly budget", color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }

    if (showBudget) {
        var text by remember { mutableStateOf(if (budget % 1.0 == 0.0) budget.toLong().toString() else budget.toString()) }
        AlertDialog(
            onDismissRequest = { showBudget = false },
            title = { Text("Monthly budget (₹)") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { s -> text = s.filter { c -> c.isDigit() || c == '.' } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = text.toDoubleOrNull()
                    if (v != null && v > 0) onSaveBudget(v)
                    showBudget = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showBudget = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SettleHeader(records: List<Rec>, memberNames: List<String>) {
    val open = records.filter { !it.bool("settled") }
    val payers = open.map { it.str("paidBy") }.filter { it.isNotBlank() }
    val people = (memberNames + payers).filter { it.isNotBlank() }.distinct()
    if (open.isEmpty() || people.size < 2) {
        if (records.isNotEmpty()) {
            GlassCard(shape = RoundedCornerShape(22.dp)) {
                Text(
                    text = "Everyone is settled up.",
                    modifier = Modifier.padding(16.dp),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        return
    }
    val total = open.sumOf { it.num("amountInr") ?: 0.0 }
    val share = total / people.size
    val balance = HashMap<String, Double>()
    for (p in people) balance[p] = -share
    for (r in open) {
        val p = r.str("paidBy")
        if (p.isNotBlank()) balance[p] = (balance[p] ?: 0.0) + (r.num("amountInr") ?: 0.0)
    }
    val creditors = balance.filter { it.value > 0.5 }.map { it.key to it.value }.sortedByDescending { it.second }.toMutableList()
    val debtors = balance.filter { it.value < -0.5 }.map { it.key to -it.value }.sortedByDescending { it.second }.toMutableList()
    val lines = mutableListOf<String>()
    var i = 0
    var j = 0
    while (i < debtors.size && j < creditors.size) {
        val pay = minOf(debtors[i].second, creditors[j].second)
        lines.add("${debtors[i].first} owes ${creditors[j].first} ${money(pay)}")
        debtors[i] = debtors[i].first to (debtors[i].second - pay)
        creditors[j] = creditors[j].first to (creditors[j].second - pay)
        if (debtors[i].second < 0.5) i++
        if (creditors[j].second < 0.5) j++
    }
    SummaryCard(
        stats = listOf("Open expenses" to money(total), "Share each" to money(share)),
        footer = lines.joinToString("\n").ifBlank { "Everyone is settled up." }
    )
}

// ---------- add / edit form ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordForm(
    def: ModuleDef,
    initial: Rec?,
    memberNames: List<String>,
    userName: String,
    onDismiss: () -> Unit,
    onSave: (Map<String, Any?>) -> Unit
) {
    val draft = remember {
        mutableStateMapOf<String, Any?>().also { it.putAll(initialDraft(def, initial, userName)) }
    }
    val nums = remember {
        mutableStateMapOf<String, String>().also { m ->
            for (f in def.fields) {
                if (f.type == FieldType.NUMBER) {
                    val v = (draft[f.key] as? Number)?.toDouble()
                    if (v != null) m[f.key] = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
                }
            }
        }
    }
    var formError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add to ${def.title}" else "Edit ${def.title}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                def.fields.forEach { f ->
                    val label = if (f.required) "${f.label} *" else f.label
                    when (f.type) {
                        FieldType.TEXT -> OutlinedTextField(
                            value = (draft[f.key] as? String).orEmpty(),
                            onValueChange = { draft[f.key] = it },
                            label = { Text(label) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        FieldType.MULTILINE -> OutlinedTextField(
                            value = (draft[f.key] as? String).orEmpty(),
                            onValueChange = { draft[f.key] = it },
                            label = { Text(label) },
                            minLines = 2,
                            maxLines = 6,
                            modifier = Modifier.fillMaxWidth()
                        )
                        FieldType.NUMBER -> OutlinedTextField(
                            value = nums[f.key].orEmpty(),
                            onValueChange = { s -> nums[f.key] = s.filter { c -> c.isDigit() || c == '.' } },
                            label = { Text(label) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        FieldType.DATE -> DateField(
                            label = label,
                            value = (draft[f.key] as? Number)?.toLong(),
                            required = f.required,
                            onChange = { draft[f.key] = it }
                        )
                        FieldType.CHOICE -> ChoiceField(
                            label = label,
                            value = (draft[f.key] as? String).orEmpty(),
                            options = f.options,
                            onChange = { draft[f.key] = it }
                        )
                        FieldType.MEMBER -> {
                            val current = (draft[f.key] as? String).orEmpty()
                            val opts = (memberNames + current).filter { it.isNotBlank() }.distinct()
                            ChoiceField(
                                label = label,
                                value = current,
                                options = opts,
                                onChange = { draft[f.key] = it }
                            )
                        }
                        FieldType.BOOL -> Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(label)
                            Switch(
                                checked = (draft[f.key] as? Boolean) ?: false,
                                onCheckedChange = { draft[f.key] = it }
                            )
                        }
                    }
                }
                val fe = formError
                if (fe != null) {
                    Text(fe, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val (built, err) = buildRecord(def, draft, nums)
                if (built == null) formError = err else onSave(built)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, value: Long?, required: Boolean, onChange: (Long?) -> Unit) {
    var show by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { show = true }, modifier = Modifier.weight(1f)) {
            Text(
                text = if (value != null) "$label: ${fmt.format(Date(value))}" else "$label: not set",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!required && value != null) {
            IconButton(onClick = { onChange(null) }) {
                Icon(Icons.Rounded.Close, contentDescription = "Clear date")
            }
        }
    }
    if (show) {
        val state = rememberDatePickerState(initialSelectedDateMillis = value)
        DatePickerDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(onClick = {
                    val picked = state.selectedDateMillis
                    if (picked != null) onChange(localNoon(picked))
                    show = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("Cancel") } }
        ) {
            DatePicker(state = state)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceField(label: String, value: String, options: List<String>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(o) },
                    onClick = {
                        onChange(o)
                        expanded = false
                    }
                )
            }
        }
    }
}
