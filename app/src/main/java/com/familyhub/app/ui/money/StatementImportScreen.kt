package com.familyhub.app.ui.money

import com.familyhub.app.core.theme.GlassFilterChip
import com.familyhub.app.core.theme.GlassInputField
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.familyhub.app.core.theme.GlassCard
import com.familyhub.app.data.remote.Rec
import com.familyhub.app.money.ColumnMap
import com.familyhub.app.money.Ledger
import com.familyhub.app.money.StatementImport
import com.familyhub.app.money.StatementParser
import com.familyhub.app.money.StatementRow
import com.familyhub.app.money.TxnParser
import com.familyhub.app.ui.AppEnv
import com.familyhub.app.ui.common.ChoiceChips
import com.familyhub.app.ui.common.ScreenTopBar
import com.familyhub.app.ui.common.formatDate
import com.familyhub.app.ui.common.plural
import com.familyhub.app.ui.modules.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val NONE = "—"

/**
 * Imports a bank or card statement, or an Axio / Walnut export, saved as CSV or Excel.
 * The file is read on the phone; columns can be changed before anything is saved; lines already in
 * Finance (typed in, or from SMS and email) are marked and left unticked.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatementImportScreen(env: AppEnv) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items = env.data["items"].orEmpty()
    val targets = remember(items) { items.filter { (it.str("type") == "account" || it.str("type") == "card") && it["isDeleted"] != true } }
    var fileName by remember { mutableStateOf("") }
    var table by remember { mutableStateOf<List<List<String>>>(emptyList()) }
    var header by remember { mutableStateOf(0) }
    var map by remember { mutableStateOf(ColumnMap()) }
    var dayFirst by remember { mutableStateOf(true) }
    var target by remember { mutableStateOf<Rec?>(null) }
    var visibility by remember { mutableStateOf("Only me") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val selected = remember { mutableStateMapOf<Int, Boolean>() }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        error = null
        scope.launch {
            try {
                val (name, bytes) = withContext(Dispatchers.IO) {
                    val n = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    }.orEmpty()
                    val b = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                    n to b
                }
                if (bytes.size > 15_000_000) throw StatementParser.UnsupportedFile("This file is too big. Export a shorter date range.")
                val t = withContext(Dispatchers.Default) { StatementParser.readTable(bytes, name) }
                if (t.isEmpty()) throw StatementParser.UnsupportedFile("No rows found in this file.")
                val h = StatementParser.headerRow(t)
                val m = StatementParser.guess(t[h])
                fileName = name
                table = t
                header = h
                map = m
                dayFirst = StatementParser.looksDayFirst(t.drop(h + 1).take(300).map { it.getOrNull(m.date).orEmpty() })
                selected.clear()
                // Link to the account or card whose last four digits appear near the top of the file.
                val top = t.take(h + 1).flatten().joinToString(" ") + " " + name
                target = targets.firstOrNull { a -> a.str("last4").length == 4 && top.contains(a.str("last4")) }
            } catch (e: StatementParser.UnsupportedFile) {
                error = e.message
            } catch (e: Exception) {
                error = "Could not read this file: ${e.message}"
            }
            busy = false
        }
    }

    val rows = remember(table, header, map, dayFirst) {
        if (table.isEmpty()) emptyList() else StatementParser.rows(table, header, map, dayFirst)
    }
    val existing = env.data["expenses"].orEmpty().filter { it["isDeleted"] != true }
    val knownKeys = remember(existing) { existing.mapNotNull { it["sourceKey"] as? String }.toSet() }
    val dup = remember(rows, existing, target) {
        rows.associate { r ->
            val key = StatementImport.rowKey(r, target)
            val cand = StatementImport.entryForRow(r, target, items, "Other") + mapOf("last4" to "")
            val hit = if (key in knownKeys) "Already imported" else existing.firstOrNull { Ledger.sameTransaction(cand, it) }?.let { "Already in Finance: " + it.str("title") }
            r.index to hit
        }
    }
    LaunchedEffect(rows, dup) { rows.forEach { r -> if (selected[r.index] == null) selected[r.index] = dup[r.index] == null } }
    val chosen = rows.filter { selected[it.index] == true }

    fun doImport() {
        busy = true
        scope.launch {
            var ok = 0
            for (r in chosen) {
                // A category remembered for this merchant comes first, then the statement's own, then a guess.
                val cat = com.familyhub.app.money.MerchantRules.categoryFor(StatementParser.merchantOf(r.description), items)
                    ?: r.category.takeIf { it.isNotBlank() && it.lowercase() !in setOf("income", "expense", "others", "uncategorised", "uncategorized") }
                    ?: TxnParser.guessCategory(r.description) ?: "Other"
                val data = StatementImport.entryForRow(r, target, items, cat)
                data["sourceKey"] = StatementImport.rowKey(r, target)
                data["memberUid"] = env.uid
                data["memberName"] = env.profile.name
                data["createdAt"] = System.currentTimeMillis()
                try {
                    env.repo.saveScoped(env.familyId, env.uid, "expenses", null, data, visibility == "Only me")
                    ok++
                } catch (e: Exception) {
                    // keep going
                }
            }
            // The statement's last balance, kept on the account so it can be matched later.
            val t = target
            val last = rows.filter { it.balance != null }.maxByOrNull { it.dateMillis * 100_000 + it.index }
            if (t != null && t.str("type") == "account" && last != null && ((t["lastSeenBalanceAt"] as? Number)?.toLong() ?: 0L) <= last.dateMillis) {
                try {
                    env.repo.setFieldsScoped(
                        env.familyId, env.uid, "items", t,
                        mapOf("lastSeenBalance" to last.balance, "lastSeenBalanceAt" to last.dateMillis, "lastSeenBalanceFrom" to "Statement")
                    )
                } catch (e: Exception) { }
            }
            busy = false
            Toast.makeText(context, "${plural(ok, "entry", "entries")} added", Toast.LENGTH_SHORT).show()
            rows.forEach { selected[it.index] = false }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Import statement", onBack = env.onBack, compact = true)
        LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Choose a bank or credit card statement, or an export from Axio (Walnut) or another money app, saved as CSV or Excel (.xlsx). " +
                                "The file is read on this phone. Download statements from your bank's website or app; never share passwords or OTPs.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(onClick = {
                            pick.launch(arrayOf("text/*", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel", "application/octet-stream"))
                        }, enabled = !busy) {
                            Icon(Icons.Rounded.UploadFile, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text(if (fileName.isBlank()) "Choose file" else "Choose another file")
                        }
                        if (fileName.isNotBlank()) Text("$fileName • ${plural(rows.size, "line")} read", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { e -> item { Text(e, color = MaterialTheme.colorScheme.error) } }
            if (table.isNotEmpty()) {
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Whose statement is this?", style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                GlassFilterChip(target == null, { target = null }, { Text("Not linked") })
                                targets.forEach { a ->
                                    GlassFilterChip(target?.get("id") == a["id"], { target = a }, { Text(a.str("title") + if (a.str("type") == "card") " (card)" else "") })
                                }
                            }
                            Text("Columns (tap to change)", style = MaterialTheme.typography.labelLarge)
                            val head = table.getOrNull(header).orEmpty()
                            val names = listOf(NONE) + head.mapIndexed { i, h -> h.ifBlank { "Column ${i + 1}" } }
                            fun nameOf(i: Int) = if (i < 0) NONE else names.getOrElse(i + 1) { NONE }
                            fun idxOf(n: String) = if (n == NONE) -1 else names.indexOf(n) - 1
                            ColumnPicker("Date", nameOf(map.date), names) { map = map.copy(date = idxOf(it)) }
                            ColumnPicker("Description", nameOf(map.description), names) { map = map.copy(description = idxOf(it)) }
                            ColumnPicker("Money out (debit)", nameOf(map.debit), names) { map = map.copy(debit = idxOf(it)) }
                            ColumnPicker("Money in (credit)", nameOf(map.credit), names) { map = map.copy(credit = idxOf(it)) }
                            ColumnPicker("Amount (one column)", nameOf(map.amount), names) { map = map.copy(amount = idxOf(it)) }
                            ColumnPicker("Dr / Cr", nameOf(map.drCr), names) { map = map.copy(drCr = idxOf(it)) }
                            ColumnPicker("Balance", nameOf(map.balance), names) { map = map.copy(balance = idxOf(it)) }
                            ColumnPicker("Category", nameOf(map.category), names) { map = map.copy(category = idxOf(it)) }
                            Text("Header is row ${header + 1}", style = MaterialTheme.typography.labelSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { if (header > 0) { header--; map = StatementParser.guess(table[header]) } }) { Text("Header up") }
                                TextButton(onClick = { if (header < table.size - 2) { header++; map = StatementParser.guess(table[header]) } }) { Text("Header down") }
                            }
                            Text("Dates are written", style = MaterialTheme.typography.labelLarge)
                            ChoiceChips(listOf("Day first (31/12)", "Month first (12/31)"), if (dayFirst) "Day first (31/12)" else "Month first (12/31)", { dayFirst = it.startsWith("Day") })
                            Text("Save entries for", style = MaterialTheme.typography.labelLarge)
                            ChoiceChips(listOf("Only me", "Family"), visibility, { visibility = it.ifBlank { "Only me" } })
                        }
                    }
                }
                item {
                    val dupCount = rows.count { dup[it.index] != null }
                    Text(
                        "${chosen.size} of ${rows.size} ticked" + (if (dupCount > 0) " • $dupCount already in Finance (unticked)" else "") +
                            " • card bill payments and own transfers are saved as money moved, not spending",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { doImport() }, enabled = !busy && chosen.isNotEmpty()) { Text("Add ${chosen.size} to Finance") }
                        TextButton(onClick = { rows.forEach { selected[it.index] = true } }) { Text("Tick all") }
                        TextButton(onClick = { rows.forEach { selected[it.index] = false } }) { Text("Untick all") }
                    }
                }
                if (rows.isEmpty()) item { Text("No lines with a date and an amount were found. Check the columns above.", style = MaterialTheme.typography.bodySmall) }
                items(rows.take(500)) { r -> StatementLine(r, selected[r.index] == true, dup[r.index]) { selected[r.index] = it } }
                if (rows.size > 500) item { Text("Showing the first 500 lines; all ticked lines are imported.", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnPicker(label: String, value: String, options: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        GlassInputField(
            value = value, onValueChange = {}, readOnly = true, label = { Text(label) }, singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(o, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = { onPick(o); open = false }) }
        }
    }
}

@Composable
private fun StatementLine(r: StatementRow, checked: Boolean, dup: String?, onCheck: (Boolean) -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), onClick = { onCheck(!checked) }) {
        Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = onCheck)
            Column(Modifier.weight(1f)) {
                Text(StatementParser.merchantOf(r.description).ifBlank { r.description }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(
                        formatDate(r.dateMillis),
                        when {
                            StatementParser.isCardPayment(r.description) -> "card bill payment"
                            StatementParser.isSelfTransfer(r.description) -> "own transfer"
                            StatementParser.isAtm(r.description) -> "ATM cash"
                            else -> r.category
                        }
                    ).filter { it.isNotBlank() }.joinToString(" • "),
                    style = MaterialTheme.typography.labelSmall, maxLines = 1
                )
                if (dup != null) Text(dup, style = MaterialTheme.typography.labelSmall, color = Color(0xFFD97706), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text((if (r.isCredit) "+" else "−") + inr(r.amount), fontWeight = FontWeight.Bold, color = if (r.isCredit) Color(0xFF34C759) else MaterialTheme.colorScheme.onSurface)
        }
    }
}
