@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.familyhub.app.ui.family

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.familyhub.app.core.theme.EmptyState
import com.familyhub.app.core.theme.GlassCard
import com.familyhub.app.data.remote.PRIVATE_FLAG
import com.familyhub.app.data.remote.Rec
import com.familyhub.app.family.CalendarMath
import com.familyhub.app.family.Maintenance
import com.familyhub.app.family.ShoppingLists
import com.familyhub.app.family.Subscriptions
import com.familyhub.app.ui.AppEnv
import com.familyhub.app.ui.common.ChoiceChips
import com.familyhub.app.ui.common.ScreenTopBar
import com.familyhub.app.ui.money.inr
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val CARD_SHAPE = RoundedCornerShape(18.dp)
private fun Rec.s(k: String) = (this[k] as? String).orEmpty()
private fun Rec.l(k: String) = (this[k] as? Number)?.toLong()

/** The overview shown above a module's own list (Documents, Calendar, Subscriptions, Maintenance, Vehicles), or nothing. */
@Composable
fun ModuleOverview(route: String, env: AppEnv) {
    when (route) {
        "documents" -> DocExpiryCard(env)
        "calendar" -> CalendarOverview(env)
        "subscriptions" -> SubscriptionsOverview(env)
        "maintenance", "vehicles" -> ServiceOverview(env, route)
        "goals" -> GoalsOverview(env)
    }
}

val ModuleOverviewRoutes = setOf("documents", "calendar", "subscriptions", "maintenance", "vehicles", "goals")

// ---------------------------------------------------------------------------------------------------------------
// Smart shopping and household lists
// ---------------------------------------------------------------------------------------------------------------

/**
 * Several lists (Groceries, Household, a party…), each item with quantity, category, price and who buys it.
 * Pending, unavailable and bought (history), search, and an estimate of what is still to buy. "Only me" items stay
 * private. Uses the same shopping records as before, so older items show up in "Main list".
 */
@Composable
fun ShoppingListsScreen(env: AppEnv) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val all = remember(env.data) { env.data["tasks"].orEmpty().filter { it["type"] == "SHOPPING" && it["isDeleted"] != true } }
    val lists = remember(all) { ShoppingLists.summaries(all) }
    var list by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var showBought by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Rec?>(null) }
    var adding by remember { mutableStateOf(false) }
    val inList = remember(all, list, query) {
        ShoppingLists.search(all.filter { list.isBlank() || ShoppingLists.listName(it) == list }, query)
    }
    val pending = inList.filter { ShoppingLists.state(it) == ShoppingLists.State.PENDING }
    val unavailable = inList.filter { ShoppingLists.state(it) == ShoppingLists.State.UNAVAILABLE }
    val bought = inList.filter { ShoppingLists.state(it) == ShoppingLists.State.BOUGHT }.sortedByDescending { it.l("completedAt") ?: it.l("updatedAt") ?: 0L }
    val estimate = ShoppingLists.estimate(inList)

    fun setFields(r: Rec, fields: Map<String, Any?>) = scope.launch {
        try { env.repo.setFieldsScoped(env.familyId, env.uid, "tasks", r, fields + ("updatedAt" to System.currentTimeMillis())) }
        catch (e: Exception) { Toast.makeText(ctx, "Could not update: ${e.message?.take(60)}", Toast.LENGTH_SHORT).show() }
    }
    fun toggle(r: Rec, done: Boolean) {
        val now = System.currentTimeMillis()
        setFields(r, if (done) mapOf("isCompleted" to true, "status" to "Pending", "completedAt" to now, "completedByUid" to env.uid, "completedByName" to env.profile.name)
            else mapOf("isCompleted" to false, "completedAt" to null))
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenTopBar(title = "Shopping lists", onBack = env.onBack)
            if (lists.size > 1) ChoiceChips(lists.map { it.name }, list, { list = it }, Modifier.padding(horizontal = 16.dp), scroll = true)
            OutlinedTextField(query, { query = it }, placeholder = { Text("Search items, lists or people") }, singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
            if (all.isEmpty()) {
                EmptyState(Icons.Rounded.ShoppingCart, "No shopping lists yet", "Add milk, groceries or anything the family needs. Make separate lists for groceries, the house or a party.",
                    Modifier.padding(top = 24.dp), actionLabel = "Add an item", onAction = { adding = true })
                return@Column
            }
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text(
                        "${pending.size} to buy" + (if (unavailable.isNotEmpty()) " · ${unavailable.size} not available" else "") +
                            (if (estimate > 0) " · about ${inr(estimate)}" else ""),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (pending.isEmpty() && unavailable.isEmpty()) item { Text("Everything here is bought. 🎉", style = MaterialTheme.typography.bodyMedium) }
                items(pending, key = { "p" + it.s("id") + it[PRIVATE_FLAG] }) { r -> ShopRow(r, list.isBlank(), { toggle(r, it) }, { editing = r }) { setFields(r, mapOf("status" to "Unavailable")) } }
                if (unavailable.isNotEmpty()) {
                    item { Text("Not available", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp)) }
                    items(unavailable, key = { "u" + it.s("id") + it[PRIVATE_FLAG] }) { r -> ShopRow(r, list.isBlank(), { toggle(r, it) }, { editing = r }) { setFields(r, mapOf("status" to "Pending")) } }
                }
                if (bought.isNotEmpty()) item {
                    TextButton(onClick = { showBought = !showBought }) { Text(if (showBought) "Hide bought (${bought.size})" else "Bought history (${bought.size})") }
                }
                if (showBought) items(bought.take(60), key = { "b" + it.s("id") + it[PRIVATE_FLAG] }) { r -> ShopRow(r, list.isBlank(), { toggle(r, it) }, { editing = r }, null) }
            }
        }
        FloatingActionButton(onClick = { adding = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)) { Icon(Icons.Rounded.Add, "Add item") }
    }
    if (adding || editing != null) ShopItemDialog(env, editing, list.ifBlank { lists.firstOrNull()?.name ?: ShoppingLists.DEFAULT_LIST }, lists.map { it.name }) { adding = false; editing = null }
}

@Composable
private fun ShopRow(r: Rec, showList: Boolean, onCheck: (Boolean) -> Unit, onEdit: () -> Unit, onToggleUnavailable: (() -> Unit)?) {
    val st = ShoppingLists.state(r)
    GlassCard(modifier = Modifier.fillMaxWidth(), shape = CARD_SHAPE, onClick = onEdit) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = st == ShoppingLists.State.BOUGHT, onCheckedChange = onCheck)
            Column(Modifier.weight(1f)) {
                Text(r.s("title"), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (st == ShoppingLists.State.PENDING) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                val price = (r["estPrice"] as? Number)?.toDouble()?.takeIf { it > 0 }?.let { inr(it) }
                val sub = listOfNotNull(r.s("qty").ifBlank { r.s("subtitle") }.takeIf { it.isNotBlank() }, r.s("category").takeIf { it.isNotBlank() },
                    if (showList) ShoppingLists.listName(r) else null, r.s("assignedToName").takeIf { it.isNotBlank() }?.let { "for $it" }, price,
                    if (r[PRIVATE_FLAG] == true) "Only me" else null).joinToString(" · ")
                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onToggleUnavailable != null) TextButton(onClick = onToggleUnavailable) {
                Text(if (st == ShoppingLists.State.UNAVAILABLE) "Available" else "Not in shop", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun ShopItemDialog(env: AppEnv, existing: Rec?, defaultList: String, lists: List<String>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(existing?.s("title").orEmpty()) }
    var qty by remember { mutableStateOf(existing?.let { it.s("qty").ifBlank { it.s("subtitle") } }.orEmpty()) }
    var listName by remember { mutableStateOf(existing?.let { ShoppingLists.listName(it) } ?: defaultList) }
    var category by remember { mutableStateOf(existing?.s("category").orEmpty()) }
    var price by remember { mutableStateOf((existing?.get("estPrice") as? Number)?.toDouble()?.let { "%.0f".format(it) }.orEmpty()) }
    var who by remember { mutableStateOf(existing?.s("assignedToName").orEmpty()) }
    var onlyMe by remember { mutableStateOf(existing?.get(PRIVATE_FLAG) == true) }
    var saving by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!saving) onClose() },
        title = { Text(if (existing == null) "Add to shopping" else "Edit item") },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it.take(80) }, label = { Text("Item") }, singleLine = true)
                OutlinedTextField(qty, { qty = it.take(30) }, label = { Text("Quantity (for example 2 kg)") }, singleLine = true)
                OutlinedTextField(listName, { listName = it.take(30) }, label = { Text("List") }, singleLine = true)
                if (lists.size > 1) ChoiceChips(lists, listName, { if (it.isNotBlank()) listName = it }, scroll = true)
                ChoiceChips(ShoppingLists.CATEGORIES, category, { category = it }, scroll = true)
                OutlinedTextField(price, { price = it.filter { c -> c.isDigit() || c == '.' }.take(8) }, label = { Text("Estimated price each (₹, optional)") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                if (env.memberNames.isNotEmpty()) { Text("Who buys it", style = MaterialTheme.typography.labelMedium); ChoiceChips(env.memberNames, who, { who = it }, scroll = true) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(onlyMe, { onlyMe = it }, enabled = existing == null)
                    Spacer(Modifier.width(8.dp)); Text("Only me (private list item)", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && title.isNotBlank(), onClick = {
                saving = true
                val now = System.currentTimeMillis()
                val data = hashMapOf<String, Any?>(
                    "type" to "SHOPPING", "title" to title.trim(), "qty" to qty.trim(), "subtitle" to qty.trim(), "listName" to listName.trim().ifBlank { ShoppingLists.DEFAULT_LIST },
                    "category" to category, "estPrice" to price.toDoubleOrNull(), "assignedToName" to who, "updatedAt" to now,
                    "updatedByUid" to env.uid, "updatedByName" to env.profile.name
                )
                if (existing == null) data.putAll(mapOf("isCompleted" to false, "status" to "Pending", "createdAt" to now, "createdByUid" to env.uid, "createdByName" to env.profile.name))
                scope.launch {
                    try {
                        env.repo.saveScoped(env.familyId, env.uid, "tasks", existing, data, if (existing != null) existing[PRIVATE_FLAG] == true else onlyMe)
                        onClose()
                    } catch (e: Exception) { Toast.makeText(ctx, "Not saved: ${e.message?.take(60)}", Toast.LENGTH_LONG).show() }
                    saving = false
                }
            }) { Text(if (saving) "Saving…" else "Save") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onClose) { Text("Cancel") } }
    )
}


// ---------------------------------------------------------------------------------------------------------------
// Family Calendar 2.0 (above the calendar list)
// ---------------------------------------------------------------------------------------------------------------

/** Month, week and agenda views with member and category filters and clash warnings. Uses the existing events. */
@Composable
fun CalendarOverview(env: AppEnv) {
    var view by rememberSaveable { mutableStateOf("Month") }
    var who by rememberSaveable { mutableStateOf("") }
    var cat by rememberSaveable { mutableStateOf("") }
    var monthOffset by rememberSaveable { mutableStateOf(0) }
    var picked by rememberSaveable { mutableStateOf<Long?>(null) }
    val cal = remember { Calendar.getInstance() }
    val (from, to) = remember(view, monthOffset) {
        val c = Calendar.getInstance(); c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        when (view) {
            "Week" -> { c.add(Calendar.DAY_OF_YEAR, 7 * monthOffset); val s = c.timeInMillis; s to s + 7 * 86_400_000L - 1 }
            "Agenda" -> { val s = c.timeInMillis; s to s + 30 * 86_400_000L - 1 }
            else -> { c.set(Calendar.DAY_OF_MONTH, 1); c.add(Calendar.MONTH, monthOffset); val s = c.timeInMillis; c.add(Calendar.MONTH, 1); s to c.timeInMillis - 1 }
        }
    }
    val events = env.data["events"].orEmpty()
    val birthdays = remember(env.data) {
        env.data["items"].orEmpty().filter { it["type"] == "birthday" }.mapNotNull { b ->
            val d = b.l("dateMillis") ?: b.l("dobMillis") ?: return@mapNotNull null
            mapOf("title" to b.s("title").ifBlank { b.s("name") }, "startTimeMillis" to d, "recurrence" to "YEARLY", "category" to "Birthday", "ownerName" to b.s("title"))
        }
    }
    val occ = remember(events, birthdays, from, to) { CalendarMath.occurrences(events + birthdays, from, to) }
    val shown = occ.filter { o ->
        (cat.isBlank() || o.rec.s("category").equals(cat, true)) &&
            (who.isBlank() || who.lowercase(Locale.ROOT) in CalendarMath.people(o.rec) || o.rec.s("createdByName").equals(who, true))
    }
    val clashes = remember(shown) { CalendarMath.conflicts(shown) }
    val clashIds = clashes.flatMap { listOf(it.first, it.second) }.toSet()
    val dayFmt = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
    val timeFmt = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val cats = remember(occ) { occ.map { it.rec.s("category") }.filter { it.isNotBlank() }.distinct() }

    GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).animateContentSize(), shape = CARD_SHAPE) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Month", "Week", "Agenda").forEachIndexed { i, v ->
                    SegmentedButton(selected = view == v, onClick = { view = v; monthOffset = 0; picked = null }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(v, maxLines = 1) }
                }
            }
            if (view != "Agenda") Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { monthOffset--; picked = null }) { Icon(Icons.Rounded.ChevronLeft, "Previous") }
                Text(if (view == "Month") SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(from)) else dayFmt.format(Date(from)) + " – " + dayFmt.format(Date(to)),
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = { monthOffset++; picked = null }) { Icon(Icons.Rounded.ChevronRight, "Next") }
            }
            if (env.memberNames.size > 1) ChoiceChips(env.memberNames, who, { who = it }, scroll = true)
            if (cats.size > 1) ChoiceChips(cats, cat, { cat = it }, scroll = true)
            if (clashes.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0x22FF3B30)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.WarningAmber, null, tint = Color(0xFFFF3B30), modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text("${clashes.size} clash${if (clashes.size > 1) "es" else ""}: " + clashes.take(2).joinToString("; ") {
                        "${it.first.rec.s("title")} and ${it.second.rec.s("title")} (${dayFmt.format(Date(it.first.start))})" }, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (view == "Month") {
                MonthGrid(from, shown, clashIds, picked) { picked = if (picked == it) null else it }
                val dayList = picked?.let { p -> shown.filter { it.start >= p && it.start < p + 86_400_000L } }
                if (dayList != null) {
                    Text(dayFmt.format(Date(picked!!)), fontWeight = FontWeight.SemiBold)
                    if (dayList.isEmpty()) Text("Nothing planned.", style = MaterialTheme.typography.bodySmall)
                    dayList.forEach { o -> OccRow(o, o in clashIds, timeFmt, null) }
                }
            } else {
                if (shown.isEmpty()) Text(if (view == "Week") "Nothing this week." else "Nothing in the next 30 days.", style = MaterialTheme.typography.bodySmall)
                shown.groupBy { o -> cal.timeInMillis = o.start; cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR) }.values.take(if (view == "Week") 7 else 20).forEach { day ->
                    Text(dayFmt.format(Date(day.first().start)), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                    day.forEach { o -> OccRow(o, o in clashIds, timeFmt, null) }
                }
            }
        }
    }
}

@Composable
private fun OccRow(o: CalendarMath.Occurrence, clash: Boolean, timeFmt: SimpleDateFormat, onClick: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (clash) Color(0xFFFF3B30) else MaterialTheme.colorScheme.primary))
        Spacer(Modifier.width(8.dp))
        Text(if (o.allDay) "All day" else timeFmt.format(Date(o.start)), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp))
        Column(Modifier.weight(1f)) {
            Text(o.rec.s("title"), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            val who = listOf(o.rec.s("ownerName"), o.rec.s("participants")).filter { it.isNotBlank() }.joinToString(", ")
            val sub = listOfNotNull(o.rec.s("category").takeIf { it.isNotBlank() }, who.takeIf { it.isNotBlank() }, if (clash) "clashes" else null).joinToString(" · ")
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.labelSmall, color = if (clash) Color(0xFFFF3B30) else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MonthGrid(monthStart: Long, occ: List<CalendarMath.Occurrence>, clashes: Set<CalendarMath.Occurrence>, picked: Long?, onPick: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = monthStart }
    val days = c.getActualMaximum(Calendar.DAY_OF_MONTH)
    val lead = (c.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
    val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    Column {
        Row { listOf("M", "T", "W", "T", "F", "S", "S").forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall) } }
        val cells = lead + days
        for (w in 0 until (cells + 6) / 7) Row {
            for (d in 0 until 7) {
                val idx = w * 7 + d - lead
                Box(Modifier.weight(1f).height(40.dp), contentAlignment = Alignment.Center) {
                    if (idx in 0 until days) {
                        val day = Calendar.getInstance().apply { timeInMillis = monthStart; add(Calendar.DAY_OF_MONTH, idx) }.timeInMillis
                        val list = occ.filter { it.start >= day && it.start < day + 86_400_000L }
                        val sel = picked == day
                        Column(Modifier.size(36.dp).clip(CircleShape)
                            .background(when { sel -> MaterialTheme.colorScheme.primary; day == today -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f); else -> Color.Transparent })
                            .clickable { onPick(day) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text("${idx + 1}", style = MaterialTheme.typography.labelMedium, color = if (sel) Color.White else MaterialTheme.colorScheme.onSurface)
                            if (list.isNotEmpty()) Box(Modifier.size(5.dp).clip(CircleShape).background(if (list.any { it in clashes }) Color(0xFFFF3B30) else if (sel) Color.White else MaterialTheme.colorScheme.primary))
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Subscription manager (above the Subscriptions list)
// ---------------------------------------------------------------------------------------------------------------

@Composable
fun SubscriptionsOverview(env: AppEnv) {
    val subs = remember(env.data) { env.data["items"].orEmpty().filter { it["type"] == "subscription" } }
    if (subs.isEmpty()) return
    val t = remember(subs) { Subscriptions.totals(subs) }
    val now = System.currentTimeMillis()
    val fmt = remember { SimpleDateFormat("d MMM", Locale.getDefault()) }
    val soon = subs.filter { Subscriptions.isLive(it) }.mapNotNull { s ->
        val start = s.l("renewalMillis") ?: return@mapNotNull null
        val every = Subscriptions.monthsPerCycle(s.s("cycle")).toInt().coerceAtLeast(1)
        val c = Calendar.getInstance().apply { timeInMillis = start }
        var k = 0; while (c.timeInMillis < now - 86_400_000L && k < 600) { c.add(Calendar.MONTH, every); k++ }
        s to c.timeInMillis
    }.filter { it.second <= now + 30 * 86_400_000L }.sortedBy { it.second }
    val stopped = subs.filter { Subscriptions.status(it) != Subscriptions.Status.ACTIVE && it["isDeleted"] != true }
        .sortedByDescending { it.l("updatedAt") ?: 0L }
    GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = CARD_SHAPE) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row {
                Column(Modifier.weight(1f)) { Text("Per month", style = MaterialTheme.typography.labelSmall); Text(inr(t.monthly), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }
                Column(Modifier.weight(1f)) { Text("Per year", style = MaterialTheme.typography.labelSmall); Text(inr(t.yearly), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }
            }
            Text("${t.active} active · ${t.paused} paused · ${t.cancelled} cancelled", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (soon.isNotEmpty()) {
                Text("Renewing in the next 30 days", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                soon.take(5).forEach { (s, d) -> Row { Text(s.s("title"), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${fmt.format(Date(d))} · ${inr((s["amountInr"] as? Number)?.toDouble() ?: 0.0)}") } }
            }
            if (stopped.isNotEmpty()) {
                Text("History", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                stopped.take(5).forEach { s -> Text("${s.s("title")}: ${Subscriptions.status(s).label.lowercase()}" + (s.l("updatedAt")?.let { " (changed ${fmt.format(Date(it))})" } ?: ""), style = MaterialTheme.typography.bodySmall) }
            }
            Text("Totals count active subscriptions only. Nothing is paid or cancelled from here.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Home and vehicle maintenance (above Maintenance and Vehicles)
// ---------------------------------------------------------------------------------------------------------------

@Composable
fun ServiceOverview(env: AppEnv, route: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val type = if (route == "vehicles") "vehicle" else "maintenance"
    val assets = remember(env.data) { env.data["items"].orEmpty().filter { it["type"] == type && it["isDeleted"] != true } }
    if (assets.isEmpty()) return
    val now = System.currentTimeMillis()
    val fmt = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }
    val dueKeys = if (type == "vehicle") listOf("serviceDueMillis" to "Service", "insuranceExpiryMillis" to "Insurance", "pucExpiryMillis" to "PUC", "warrantyExpiryMillis" to "Warranty")
        else listOf("nextDueMillis" to "Service", "warrantyExpiryMillis" to "Warranty")
    val upcoming = assets.flatMap { a -> dueKeys.mapNotNull { (k, label) -> a.l(k)?.let { Triple(a, label, it) } } }
        .filter { it.third <= now + 60 * 86_400_000L }.sortedBy { it.third }
    var logFor by remember { mutableStateOf<Rec?>(null) }
    var openHistory by remember { mutableStateOf<String?>(null) }
    GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).animateContentSize(), shape = CARD_SHAPE) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (type == "vehicle") "Service, insurance and PUC" else "Services and warranties", fontWeight = FontWeight.SemiBold)
            if (upcoming.isEmpty()) Text("Nothing due in the next 60 days.", style = MaterialTheme.typography.bodySmall)
            upcoming.take(5).forEach { (a, label, d) ->
                val days = ((d - now) / 86_400_000L).toInt()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${a.s("title")}: $label", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    Text(if (days < 0) "${-days} d overdue" else if (days == 0) "today" else "in $days d", color = if (days < 0) Color(0xFFFF3B30) else Color(0xFFFF9500), style = MaterialTheme.typography.labelLarge)
                }
            }
            Text("Service history", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            assets.forEach { a ->
                val h = Maintenance.history(a)
                Row(Modifier.fillMaxWidth().clickable { openHistory = if (openHistory == a.s("id")) null else a.s("id") }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.s("title"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (h.isEmpty()) "No services logged" else "${h.size} service(s) · ${inr(Maintenance.totalSpent(a))} · last ${fmt.format(Date(h.first().atMillis))}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { logFor = a }) { Text("Log service") }
                }
                if (openHistory == a.s("id")) h.take(10).forEach { e ->
                    Text("• ${fmt.format(Date(e.atMillis))}: ${inr(e.cost)}" + listOf(e.provider, e.notes).filter { it.isNotBlank() }.joinToString(", ", prefix = " – ").takeIf { e.provider.isNotBlank() || e.notes.isNotBlank() }.orEmpty(),
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
                }
            }
            Text("Keep bills and warranty cards in Documents (type Warranty).", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
        }
    }
    logFor?.let { a ->
        var cost by remember(a) { mutableStateOf("") }
        var provider by remember(a) { mutableStateOf(a.s("vendor")) }
        var notes by remember(a) { mutableStateOf("") }
        var saving by remember(a) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!saving) logFor = null },
            title = { Text("Log service: ${a.s("title")}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(cost, { cost = it.filter { c -> c.isDigit() || c == '.' }.take(8) }, label = { Text("Cost (₹)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(provider, { provider = it.take(60) }, label = { Text("Done by") }, singleLine = true)
                    OutlinedTextField(notes, { notes = it.take(200) }, label = { Text("What was done") })
                    val rep = a.s("repeat")
                    Text(if (type == "vehicle") "Saved as today's service. The next service date stays as it is; change it in the vehicle if needed."
                        else if (Maintenance.everyMonths(rep) != null) "Saved as today's service; the next one moves to ${fmt.format(Date(Maintenance.nextAfter(System.currentTimeMillis(), rep)!!))} ($rep)."
                        else "Saved as today's service.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(enabled = !saving, onClick = {
                    saving = true
                    val now2 = System.currentTimeMillis()
                    val c = cost.toDoubleOrNull() ?: 0.0
                    val fields = hashMapOf<String, Any?>(
                        "serviceLog" to Maintenance.withEntry(a, Maintenance.Log(now2, c, provider.trim(), notes.trim())),
                        "lastServiceMillis" to now2, "updatedAt" to now2, "updatedByUid" to env.uid, "updatedByName" to env.profile.name
                    )
                    if (type == "maintenance") { fields["costInr"] = c; Maintenance.nextAfter(now2, a.s("repeat"))?.let { fields["nextDueMillis"] = it } }
                    if (provider.isNotBlank()) fields["vendor"] = provider.trim()
                    scope.launch {
                        try {
                            env.repo.setFieldsScoped(env.familyId, env.uid, "items", a, fields)
                            Toast.makeText(ctx, "Service saved.", Toast.LENGTH_SHORT).show(); logFor = null
                        } catch (e: Exception) { Toast.makeText(ctx, "Not saved: ${e.message?.take(60)}", Toast.LENGTH_LONG).show() }
                        saving = false
                    }
                }) { Text(if (saving) "Saving…" else "Save") }
            },
            dismissButton = { TextButton(enabled = !saving, onClick = { logFor = null }) { Text("Cancel") } }
        )
    }
}
