package com.familyhub.app.ui.modules

import androidx.compose.foundation.horizontalScroll
import com.familyhub.app.core.theme.GlassDialog
import com.familyhub.app.core.theme.GlassFilterChip
import com.familyhub.app.core.theme.GlassFloatingActionButton
import com.familyhub.app.core.theme.GlassInputField
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.draw.clip
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
import com.familyhub.app.core.theme.groupedRowBackground
import com.familyhub.app.core.theme.glass
import com.familyhub.app.data.remote.PRIVATE_FLAG
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
        // Hidden fields are not checked.
        if (f.showIf?.invoke(draft) == false) continue
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
            FieldType.TIME -> {
                // Saved as "HH:mm"; empty means all day.
                val raw = (out[f.key] as? String).orEmpty().trim()
                val t = com.familyhub.app.work.ReminderRules.normalizeTime(raw)
                if (raw.isNotEmpty() && t.isEmpty()) return Pair(null, "${f.label}: pick a time or leave it empty")
                if (f.required && t.isEmpty()) return Pair(null, "${f.label} is required")
                out[f.key] = t
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
    onExport: (() -> Unit)? = null,
    onOpen: ((String) -> Unit)? = null,
    extraOptions: Map<String, List<String>> = emptyMap(),
    families: List<com.familyhub.app.data.remote.FamilyInfo> = emptyList(),
    onAskAi: ((String) -> Unit)? = null,
    /** Shown above the module's own summary (the Finance dashboard). */
    overview: (@Composable () -> Unit)? = null,
    /** Test screens only: show these records instead of the saved ones, and start in a given state. */
    preview: List<Rec>? = null,
    previewState: String? = null
) {
    // Section access set by a Family Admin (the security rules enforce the same levels).
    val access by com.familyhub.app.family.AccessState.flow.collectAsState()
    val section = com.familyhub.app.family.Permissions.sectionOf(def.collection, def.typeValue)
    if (!access.canSee(section)) {
        NoAccessScreen(def.title, onBack)
        return
    }
    val viewOnly = !access.canEdit(section)
    /** "Only me" records are always the person's own to change. */
    fun mayChange(rec: Rec?) = !viewOnly || rec?.get(com.familyhub.app.data.remote.PRIVATE_FLAG) == true
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Family records plus my own "Only me" records of this kind.
    val flow = remember(familyId, uid, def.collection) { repo.observeMerged(familyId, uid, def.collection) }
    val saved by flow.collectAsState(initial = emptyList<Rec>())
    val all = preview ?: saved
    val records = remember(all) { recordsFor(def, all) }
    val sorted = remember(records) { sortRecords(def, records) }
    var query by remember { mutableStateOf(com.familyhub.app.core.search.SearchSeed.take(def.route)) }
    val shown = remember(sorted, query) {
        val q = query.trim()
        if (q.isEmpty()) sorted else sorted.filter { r -> r.values.any { v -> v is String && v.contains(q, ignoreCase = true) } }
    }

    // previewState "form": the emulator test opens the add form directly, to photograph it.
    var showForm by remember { mutableStateOf(previewState == "form") }
    var editing by remember { mutableStateOf<Rec?>(null) }
    var toDelete by remember { mutableStateOf<Rec?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Finance: tap a transaction for actions, long-press to select several.
    var selected by remember {
        mutableStateOf(
            if (previewState == "select") sortRecords(def, recordsFor(def, all)).take(3).map { com.familyhub.app.ui.money.recKey(it) }.toSet()
            else emptySet<String>()
        )
    }
    // A detail screen's Edit button: open that record's form once it is in the list.
    val editId by EditRequest.id.collectAsState()
    LaunchedEffect(editId, records) {
        val id = editId ?: return@LaunchedEffect
        records.firstOrNull { it.str("id") == id }?.let { r -> EditRequest.id.value = null; if (mayChange(r)) { editing = r; showForm = true } }
    }
    var sheetKey by remember {
        mutableStateOf(
            if (previewState == "sheet") sortRecords(def, recordsFor(def, all)).firstOrNull()?.let { com.familyhub.app.ui.money.recKey(it) } else null
        )
    }
    val isFinance = def.kind == "finance"
    androidx.activity.compose.BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }

    fun save(map: Map<String, Any?>, existing: Rec?) {
        val data = HashMap<String, Any?>(map)
        for ((k, v) in def.constants) {
            if (!data.containsKey(k)) data[k] = v
        }
        val tv = def.typeValue
        if (tv != null) data["type"] = tv
        // A new family entry can go to any family the person belongs to.
        val chosen = (data.remove("targetFamilyId") as? String).orEmpty()
        val destFamily = if (existing == null && chosen.isNotBlank()) chosen else familyId
        data["familyId"] = destFamily
        data["updatedAt"] = System.currentTimeMillis()
        // Who changed it last, for the family activity timeline.
        data["updatedByUid"] = uid
        data["updatedByName"] = userName
        if (existing == null) {
            data["createdByUid"] = uid
            data["createdByName"] = userName
            if (def.autoCreatedAt) data["createdAtMillis"] = System.currentTimeMillis()
        }
        def.postProcess?.invoke(data, uid, userName)
        val private = data["visibility"] == VIS_PRIVATE
        scope.launch {
            try {
                repo.saveScoped(destFamily, uid, def.collection, existing, data, private)
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
                // Who ticked it and when, for the family activity timeline.
                val now = System.currentTimeMillis()
                repo.setFieldsScoped(familyId, uid, def.collection, rec,
                    if (checked) mapOf(key to true, "completedAt" to now, "completedByUid" to uid, "completedByName" to userName, "updatedAt" to now)
                    else mapOf(key to false, "completedAt" to null, "updatedAt" to now))
            } catch (e: Exception) {
                error = "Could not update: ${e.message}"
            }
        }
    }

    fun remove(rec: Rec) {
        if (!mayChange(rec)) { error = VIEW_ONLY_TEXT; return }
        scope.launch {
            try {
                val soft = def.softDeleteKey
                if (soft != null) repo.setFieldScoped(familyId, uid, def.collection, rec, soft, true)
                else repo.deleteScoped(familyId, uid, def.collection, rec)
            } catch (e: Exception) {
                error = "Could not delete: ${e.message}"
            }
        }
    }

    fun setFields(rec: Rec, fields: Map<String, Any?>) {
        if (!mayChange(rec)) { error = VIEW_ONLY_TEXT; return }
        scope.launch {
            try {
                repo.setFieldsScoped(familyId, uid, def.collection, rec, fields + ("updatedAt" to System.currentTimeMillis()))
            } catch (e: Exception) {
                error = "Could not update: ${e.message}"
            }
        }
    }

    fun setPrivate(rec: Rec, private: Boolean) {
        if ((rec[PRIVATE_FLAG] == true) == private) return
        scope.launch {
            try {
                repo.saveScoped(
                    familyId, uid, def.collection, rec,
                    mapOf("visibility" to (if (private) VIS_PRIVATE else VIS_FAMILY), "updatedAt" to System.currentTimeMillis()),
                    private
                )
            } catch (e: Exception) {
                error = "Could not move: ${e.message}"
            }
        }
    }

    fun toggleSelect(rec: Rec) {
        val k = com.familyhub.app.ui.money.recKey(rec)
        selected = if (k in selected) selected - k else selected + k
    }

    fun selectedRecs(): List<Rec> = records.filter { com.familyhub.app.ui.money.recKey(it) in selected }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (isFinance && selected.isNotEmpty()) {
                com.familyhub.app.ui.money.BulkActionBar(
                    count = selected.size,
                    total = shown.size,
                    onClose = { selected = emptySet() },
                    onSelectAll = { selected = shown.map { com.familyhub.app.ui.money.recKey(it) }.toSet() },
                    onCategory = { c -> selectedRecs().forEach { setFields(it, mapOf("category" to c)) }; selected = emptySet() },
                    onNotExpense = { b -> selectedRecs().forEach { setFields(it, mapOf("notExpense" to b)) }; selected = emptySet() },
                    onPrivate = { b -> selectedRecs().forEach { setPrivate(it, b) }; selected = emptySet() },
                    onDelete = { selectedRecs().forEach { remove(it) }; selected = emptySet() }
                )
            } else Row(
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
                if (onAskAi != null) {
                    com.familyhub.app.ui.ai.PageAiButton("Ask AI about ${def.title}") { onAskAi("") }
                }
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
                // No gap between list rows (they form one grouped block); other items add their own space below.
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                        overview?.invoke()
                        if (def.typeValue == "memory") MemoriesHeader(records, onOpen)
                        ModuleHeader(def, records, monthlyBudget, isOwner, memberNames, onSaveBudget,
                            onRecordPayment = if (viewOnly) null else { t, trip ->
                                save(
                                    mapOf(
                                        "title" to "Payment: ${t.from} → ${t.to}", "amountInr" to t.amount, "paidBy" to t.from,
                                        "splitWith" to t.to, "splitMode" to com.familyhub.app.money.Split.EXACT,
                                        "splitDetail" to "${t.to} ${t.amount}", "trip" to trip, "isPayment" to true
                                    ),
                                    null
                                )
                            })
                        if (def.kind == "finance" && onOpen != null) {
                            com.familyhub.app.ui.hub.HubTiles(com.familyhub.app.ui.hub.Hubs.money.items, onOpen)
                        }
                    }
                }
                if (records.size > 3 || query.isNotEmpty()) {
                    item {
                        GlassInputField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
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
                        GlassCard(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), shape = RoundedCornerShape(20.dp)) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 22.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    def.icon, contentDescription = null, modifier = Modifier.size(30.dp),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = def.emptyText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                }
                itemsIndexed(shown, key = { _, it -> (it[PRIVATE_FLAG] == true).toString() + it.str("id") }) { index, rec ->
                    Box(modifier = Modifier.animateItem().then(if (isFinance) Modifier.padding(bottom = 10.dp) else Modifier)) {
                    if (isFinance) {
                        com.familyhub.app.ui.money.TransactionRow(
                            rec = rec,
                            selected = com.familyhub.app.ui.money.recKey(rec) in selected,
                            selecting = selected.isNotEmpty(),
                            onClick = {
                                if (selected.isNotEmpty()) toggleSelect(rec)
                                else sheetKey = com.familyhub.app.ui.money.recKey(rec)
                            },
                            onLongClick = { toggleSelect(rec) }
                        )
                    } else RecordCard(
                        def = def,
                        rec = rec,
                        first = index == 0,
                        last = index == shown.lastIndex,
                        onClick = {
                            if (def.typeValue == "memory" && onOpen != null) onOpen("memory:" + rec.str("id"))
                            else if (mayChange(rec)) { editing = rec; showForm = true } else error = VIEW_ONLY_TEXT
                        },
                        onToggle = { checked -> if (mayChange(rec)) toggle(rec, checked) else error = VIEW_ONLY_TEXT },
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

        val sheetRec = sheetKey?.let { k -> records.firstOrNull { com.familyhub.app.ui.money.recKey(it) == k } }
        if (sheetRec != null) {
            com.familyhub.app.ui.money.TransactionSheet(
                rec = sheetRec,
                all = records,
                onDismiss = { sheetKey = null },
                onEdit = { sheetKey = null; if (mayChange(sheetRec)) { editing = sheetRec; showForm = true } else error = VIEW_ONLY_TEXT },
                onSet = { r, f -> setFields(r, f) },
                onDelete = { sheetKey = null; remove(sheetRec) },
                // Remembered merchant categories are the person's own private records (in their backup too).
                onRemember = if (!isFinance) null else { merchant, category ->
                    scope.launch {
                        try {
                            repo.saveScoped(
                                familyId, uid, "items",
                                mapOf("id" to com.familyhub.app.money.MerchantRules.ruleId(merchant), com.familyhub.app.data.remote.PRIVATE_FLAG to true),
                                com.familyhub.app.money.MerchantRules.ruleData(merchant, category, System.currentTimeMillis()),
                                true
                            )
                        } catch (e: Exception) {
                            error = "Could not remember this merchant's category: ${e.message}"
                        }
                    }
                },
                onAskAi = onAskAi?.let { ask ->
                    {
                        sheetKey = null
                        ask("About \"${sheetRec.str("title")}\" (${money(sheetRec.num("amountInr") ?: 0.0)}): ")
                    }
                }
            )
        }

        if (viewOnly) com.familyhub.app.core.theme.GlassNotificationCard(
            title = "View only",
            subtitle = "A Family Admin gave you view-only access to ${com.familyhub.app.family.Permissions.label(section ?: "")}.",
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
        )
        if (selected.isEmpty() && !viewOnly) GlassFloatingActionButton(
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
                    extraOptions = extraOptions,
                    families = families,
                    currentFamilyId = familyId,
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
            GlassDialog(
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
    first: Boolean = true,
    last: Boolean = true,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onCall: (String) -> Unit
) {
    val doneKey = def.doneKey
    val done = doneKey != null && rec.bool(doneKey)
    // "timeText" is worked out from the saved time ("All day" when there is none), also for older records.
    val sub = def.subtitleKeys.map { k ->
        if (k == "timeText") com.familyhub.app.work.ReminderRules.timeText(rec.str("time")) else rec.str(k)
    }.filter { it.isNotBlank() }.joinToString(" • ")
    val amount = amountText(def, rec)
    val phone = def.phoneKey?.let { rec.str(it) }.orEmpty()

    val corner = 14.dp
    val shape = RoundedCornerShape(
        topStart = if (first) corner else 0.dp, topEnd = if (first) corner else 0.dp,
        bottomStart = if (last) corner else 0.dp, bottomEnd = if (last) corner else 0.dp
    )
    Column(
        modifier = Modifier.fillMaxWidth()
            .padding(bottom = if (last) 10.dp else 0.dp)
            .groupedRowBackground(shape)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (doneKey != null) {
                Checkbox(checked = done, onCheckedChange = onToggle)
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (rec[PRIVATE_FLAG] == true) {
                        Icon(
                            Icons.Rounded.Lock,
                            contentDescription = "Only me",
                            modifier = Modifier.size(14.dp).padding(end = 2.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = rec.str(def.titleKey).ifBlank { "(untitled)" },
                        fontWeight = FontWeight.SemiBold,
                        textDecoration = if (done) TextDecoration.LineThrough else null,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
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
        if (!last) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 16.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

@Composable
private fun DateLines(def: ModuleDef, rec: Rec, done: Boolean) {
    val fmt = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
    val rep = def.repeatKey?.let { rec.str(it) }.orEmpty()
    for (dk in def.dateKeys) {
        val raw = rec.lng(dk.key) ?: continue
        val d = nextOccurrenceOf(rec, raw, rep)
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
            text = "${dk.label}: ${fmt.format(Date(d))}" +
                (if (def.fields.any { it.type == FieldType.TIME }) ", " + com.familyhub.app.work.ReminderRules.timeText(rec.str("time")) else "") +
                if (note.isNotEmpty()) " • $note" else "",
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
    onSaveBudget: (Double) -> Unit,
    onRecordPayment: ((com.familyhub.app.money.Split.Transfer, String) -> Unit)? = null
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
            // Same rule as the subscription manager: only active subscriptions count.
            val monthly = com.familyhub.app.family.Subscriptions.totals(records).monthly
            if (records.isNotEmpty()) {
                SummaryCard(listOf("Per month" to money(monthly), "Per year" to money(monthly * 12)))
            }
        }
        "settle" -> SettleHeader(records, memberNames, onRecordPayment)
        "travel" -> TripHeader(records)
        "food" -> if (records.isNotEmpty()) {
            val s = com.familyhub.app.health.HealthMath.foodSummary(
                records.mapNotNull { r -> r.lng("dateMillis")?.let { com.familyhub.app.health.HealthMath.FoodEntry(it, r.num("kcal")) } },
                System.currentTimeMillis()
            )
            SummaryCard(
                listOf("Today" to "${s.todayKcal} kcal", "7-day average" to (s.weekAverageKcal?.let { "$it kcal a day" } ?: "—")),
                footer = "Only the calories you entered" +
                    (if (s.todayWithoutKcal > 0) " (${s.todayWithoutKcal} item" + (if (s.todayWithoutKcal == 1) "" else "s") + " today without calories)" else "") +
                    ". Not medical or diet advice."
            )
        }
        "medicine" -> if (records.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val plans = records.map { r ->
                com.familyhub.app.health.HealthMath.MedPlan(
                    times = com.familyhub.app.ui.common.parseTimes(r.str("times")),
                    // Without a start date only today is counted, so earlier days are never shown as missed.
                    start = r.lng("startMillis") ?: r.lng("createdAtMillis") ?: now,
                    end = r.lng("endMillis"),
                    taken = (r["taken"] as? Map<*, *>).orEmpty().filter { it.value == true }.keys.mapNotNull { it as? String }.toSet()
                )
            }
            val a = com.familyhub.app.health.HealthMath.adherence(plans, now)
            if (a.scheduled > 0) SummaryCard(
                listOf("Taken, last 7 days" to "${a.percent}%", "Doses not marked" to "${a.missed}"),
                footer = "Counts doses marked Taken here or on the notification. A dose counts after an hour."
            )
        }
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

@OptIn(ExperimentalLayoutApi::class)
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

    // The same rules as the Finance dashboard: totals in paise, refunds lower spending and are never income.
    val L = com.familyhub.app.money.Ledger
    fun isSpend(r: Rec): Boolean = L.isSpend(r)
    fun amt(r: Rec): Double = L.spendAmount(r)
    fun sum(list: List<Rec>, pick: (Rec) -> Boolean, value: (Rec) -> Double = { amt(it) }): Double =
        L.rupees(list.filter(pick).sumOf { L.paise(value(it)) })

    val monthRecs = records.filter { inMonth(it) }
    val monthRefunds = sum(monthRecs, { L.isRefund(it) }, { L.amount(it) })
    val spent = (sum(monthRecs, { isSpend(it) }) - monthRefunds).coerceAtLeast(0.0)
    val income = sum(monthRecs, { L.isIncome(it) }, { L.amount(it) })
    val saved = sum(monthRecs, { L.live(it) && L.type(it) == "SAVING" }, { L.amount(it) })
    val dueBills = records.filter { L.live(it) && (L.type(it) == "BILL" || L.type(it) == "EMI") && !L.isPaidDue(it) }
    val yearRecs = records.filter { inYear(it) }
    val yearSpent = (sum(yearRecs, { isSpend(it) }) - sum(yearRecs, { L.isRefund(it) }, { L.amount(it) })).coerceAtLeast(0.0)
    val yearIncome = sum(yearRecs, { L.isIncome(it) }, { L.amount(it) })
    val topCategories = monthRecs.filter { isSpend(it) }
        .groupBy { it.str("category").ifBlank { "Other" } }
        .mapValues { e -> L.rupees(e.value.sumOf { L.paise(amt(it)) }) }
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
            FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
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
        GlassDialog(
            onDismissRequest = { showBudget = false },
            title = { Text("Monthly budget (₹)") },
            text = {
                GlassInputField(
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

/** Settle up: balances from every open shared expense (equal, exact, % or shares), the fewest payments, per trip. */
@Composable
private fun SettleHeader(records: List<Rec>, memberNames: List<String>, onRecord: ((com.familyhub.app.money.Split.Transfer, String) -> Unit)?) {
    val S = com.familyhub.app.money.Split
    val open = records.filter { !it.bool("settled") }
    val trips = open.map { it.str("trip").trim() }.filter { it.isNotBlank() }.distinct().sorted()
    var trip by remember { mutableStateOf("") }
    val shown = if (trip.isBlank()) open else open.filter { it.str("trip").trim().equals(trip, ignoreCase = true) }
    val people = (memberNames + shown.map { it.str("paidBy") } + shown.flatMap { S.names(it.str("splitWith")) })
        .map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
    if (records.isEmpty()) return
    val expenses = shown.map { r ->
        com.familyhub.app.money.Split.Expense(
            id = r.str("id"), amount = r.num("amountInr") ?: 0.0, paidBy = r.str("paidBy"),
            with = S.names(r.str("splitWith"), people), mode = r.str("splitMode").ifBlank { S.EQUAL },
            detail = r.str("splitDetail"), group = r.str("trip")
        )
    }
    val b = S.balances(expenses, people)
    val transfers = S.settlements(b.net)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (trips.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            com.familyhub.app.core.theme.GlassFilterChip(selected = trip.isBlank(), onClick = { trip = "" }, label = { Text("All") })
            trips.forEach { t -> com.familyhub.app.core.theme.GlassFilterChip(selected = trip == t, onClick = { trip = t }, label = { Text(t) }) }
        }
        SummaryCard(
            stats = listOf("Shared spending" to money(b.total), "Payments to settle" to "${transfers.size}"),
            footer = buildString {
                if (transfers.isEmpty()) append("Everyone is settled up.")
                b.net.entries.sortedByDescending { it.value }.forEach { (who, v) ->
                    if (isNotEmpty()) append("\n")
                    append(if (v > 0) "$who gets back ${money(v)}" else "$who owes ${money(-v)}")
                }
                if (b.skipped.isNotEmpty()) append("\n${b.skipped.size} expense(s) skipped: their amounts, % or shares don't add up.")
            }
        )
        transfers.forEach { t ->
            GlassCard(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${t.from} pays ${t.to} ${money(t.amount)}", modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    if (onRecord != null) TextButton(onClick = { onRecord(t, trip) }) { Text("Record payment") }
                }
            }
        }
    }
}

/** Travel: the next trip with a countdown, and how its shared spending compares with its budget. */
@Composable
private fun TripHeader(records: List<Rec>) {
    val now = System.currentTimeMillis()
    val day = 86_400_000L
    val next = records.filter { (it.lng("endDateMillis") ?: 0L) >= now - day }.minByOrNull { it.lng("startDateMillis") ?: Long.MAX_VALUE } ?: return
    val start = next.lng("startDateMillis") ?: return
    val end = next.lng("endDateMillis") ?: start
    val days = ((start - startOfDay(now)) / day).toInt()
    val status = when {
        now in start..(end + day) -> "Happening now · day ${((startOfDay(now) - startOfDay(start)) / day + 1)} of ${((startOfDay(end) - startOfDay(start)) / day + 1)}"
        days == 0 -> "Starts today"
        days == 1 -> "Starts tomorrow"
        else -> "Starts in $days days"
    }
    val budget = next.num("estimatedBudgetInr")
    val packing = next.str("packingNotes").lines().map { it.trim() }.filter { it.isNotBlank() }
    val packed = packing.count { it.startsWith("[x]", ignoreCase = true) || it.startsWith("✓") }
    SummaryCard(
        stats = listOfNotNull(
            "Next trip" to next.str("destination"),
            "When" to status,
            budget?.let { "Budget" to money(it) },
            if (packing.isNotEmpty()) "Packed" to "$packed of ${packing.size}" else null
        ),
        footer = "Add shared trip costs in Finance → Settle up with this trip's name to split them and see who owes whom."
    )
}

// ---------- add / edit form ----------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RecordForm(
    def: ModuleDef,
    initial: Rec?,
    memberNames: List<String>,
    userName: String,
    extraOptions: Map<String, List<String>>,
    families: List<com.familyhub.app.data.remote.FamilyInfo> = emptyList(),
    currentFamilyId: String = "",
    onDismiss: () -> Unit,
    onSave: (Map<String, Any?>) -> Unit
) {
    val draft = remember {
        mutableStateMapOf<String, Any?>().also {
            it.putAll(initialDraft(def, initial, userName))
            // Where the record lives decides who can see it.
            it["visibility"] = when {
                initial != null -> if (initial[PRIVATE_FLAG] == true) VIS_PRIVATE else VIS_FAMILY
                def.privateByDefault -> VIS_PRIVATE
                else -> VIS_FAMILY
            }
        }
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

    GlassDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add to ${def.title}" else "Edit ${def.title}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                VisibilityPicker(
                    value = (draft["visibility"] as? String) ?: VIS_FAMILY,
                    onChange = { draft["visibility"] = it }
                )
                // In more than one family: choose which family a new entry is shared with.
                val joined = families.filter { it.ownerUid.isNotBlank() }
                if (initial == null && draft["visibility"] != VIS_PRIVATE && joined.size > 1) {
                    val chosen = (draft["targetFamilyId"] as? String)?.takeIf { it.isNotBlank() } ?: currentFamilyId
                    Text("Share with", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        joined.forEach { f ->
                            GlassFilterChip(
                                selected = chosen == f.id,
                                onClick = { draft["targetFamilyId"] = f.id },
                                label = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = { Icon(Icons.Rounded.FamilyRestroom, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            )
                        }
                    }
                }
                def.fields.forEach { f ->
                    if (f.showIf?.invoke(draft) == false) return@forEach
                    val label = if (f.required) "${f.label} *" else f.label
                    when (f.type) {
                        FieldType.TEXT -> GlassInputField(
                            value = (draft[f.key] as? String).orEmpty(),
                            onValueChange = { draft[f.key] = it },
                            label = { Text(label) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        FieldType.MULTILINE -> GlassInputField(
                            value = (draft[f.key] as? String).orEmpty(),
                            onValueChange = { draft[f.key] = it },
                            label = { Text(label) },
                            minLines = 2,
                            maxLines = 6,
                            modifier = Modifier.fillMaxWidth()
                        )
                        FieldType.NUMBER -> GlassInputField(
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
                        FieldType.TIME -> TimeField(
                            label = label,
                            value = (draft[f.key] as? String).orEmpty(),
                            onChange = { draft[f.key] = it }
                        )
                        FieldType.CHOICE -> {
                            val cur = (draft[f.key] as? String).orEmpty()
                            val dyn = f.dynamic?.replace(Regex("\\{(\\w+)\\}")) { mr -> (draft[mr.groupValues[1]] as? String).orEmpty() }
                            val opts = if (dyn != null) {
                                (f.options + extraOptions[dyn].orEmpty() + cur).filter { it.isNotBlank() }.distinct()
                            } else f.options
                            ChoiceField(
                                label = label,
                                value = cur,
                                options = opts,
                                onChange = { draft[f.key] = it }
                            )
                        }
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
                        FieldType.PHOTO -> PhotoField(
                            label = label,
                            value = (draft[f.key] as? String)?.takeIf { it.isNotBlank() },
                            onChange = { draft[f.key] = it }
                        )
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

/** A time picked on a clock, saved as "HH:mm". Clearing it makes the item all day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeField(label: String, value: String, onChange: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    val parsed = com.familyhub.app.work.ReminderRules.parseTime(value)
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { show = true }, modifier = Modifier.weight(1f)) {
            Text(
                text = label.substringBefore(" (") + ": " + com.familyhub.app.work.ReminderRules.timeText(value),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (parsed != null) {
            IconButton(onClick = { onChange("") }) {
                Icon(Icons.Rounded.Close, contentDescription = "All day (no time)")
            }
        }
    }
    if (show) {
        val state = rememberTimePickerState(
            initialHour = parsed?.first ?: 9,
            initialMinute = parsed?.second ?: 0,
            is24Hour = android.text.format.DateFormat.is24HourFormat(androidx.compose.ui.platform.LocalContext.current)
        )
        GlassDialog(
            onDismissRequest = { show = false },
            title = { Text(label.substringBefore(" (")) },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    onChange(String.format(Locale.US, "%02d:%02d", state.hour, state.minute))
                    show = false
                }) { Text("OK") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { onChange(""); show = false }) { Text("All day") }
                    TextButton(onClick = { show = false }) { Text("Cancel") }
                }
            }
        )
    }
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

/** A picture kept with the record (a receipt or payment proof), saved on this phone. */
@Composable
internal fun PhotoField(label: String, value: String?, onChange: (String?) -> Unit) {
    val context = LocalContext.current
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            // Saved as a file on this phone; the record keeps only its reference.
            val ref = com.familyhub.app.ui.common.LocalPhotos.save(context, uri)
            if (ref != null) onChange(ref) else Toast.makeText(context, "Could not read that picture", Toast.LENGTH_SHORT).show()
        }
    }
    val img = com.familyhub.app.ui.common.rememberPhoto(value)
    val elsewhere = img == null && com.familyhub.app.ui.common.LocalPhotos.isLocal(value)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        if (elsewhere) Text(com.familyhub.app.ui.common.photoElsewhereText(), style = MaterialTheme.typography.bodySmall)
        Text("Pictures stay on this phone and are not uploaded. They are included in backup files.", style = MaterialTheme.typography.labelSmall)
        if (img != null) {
            androidx.compose.foundation.Image(
                bitmap = img, contentDescription = label,
                modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).clip(RoundedCornerShape(12.dp))
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pick.launch("image/*") }) { Text(if (img == null && !elsewhere) "Add picture" else "Replace") }
            if (img != null || elsewhere) TextButton(onClick = { onChange(null) }) { Text("Remove") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceField(label: String, value: String, options: List<String>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        GlassInputField(
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

// ---------- who can see a record ----------

const val VIS_FAMILY = "Family"
const val VIS_PRIVATE = "Only me"

@Composable
private fun VisibilityPicker(value: String, onChange: (String) -> Unit) {
    Column {
        Text("Who can see this", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassFilterChip(
                selected = value == VIS_FAMILY,
                onClick = { onChange(VIS_FAMILY) },
                label = { Text("Family") },
                leadingIcon = { Icon(Icons.Rounded.Groups, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )
            GlassFilterChip(
                selected = value == VIS_PRIVATE,
                onClick = { onChange(VIS_PRIVATE) },
                label = { Text("Only me") },
                leadingIcon = { Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )
        }
    }
}

/** Quick links under the Finance summary. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FinanceShortcuts(onOpen: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FinanceShortcutRegistry.items.forEach { (route, label) ->
            AssistChip(
                onClick = { onOpen(route) },
                label = { Text(label) },
                leadingIcon = {
                    Icon(
                        FinanceShortcutRegistry.icon(route),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            )
        }
    }
}


private const val VIEW_ONLY_TEXT = "You have view-only access here. Ask a Family Admin if you need to make changes."

/** Shown when a Family Admin set this section to None for the person. */
@Composable
fun NoAccessScreen(title: String, onBack: (() -> Unit)?) {
    Column(modifier = Modifier.fillMaxSize()) {
        com.familyhub.app.ui.common.ScreenTopBar(title = title, onBack = onBack)
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text("No access", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "A Family Admin has not given you access to $title. Ask them if you need it.",
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}
