package com.familyhub.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import com.familyhub.app.ui.common.PersonAvatar
import com.familyhub.app.core.theme.glass
import com.familyhub.app.core.theme.riseIn
import com.familyhub.app.core.theme.tileBrush
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.familyhub.app.data.model.*
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HomeDashboardScreen(
    family: FamilyGroup,
    currentUser: UserProfile,
    currentMember: FamilyMember,
    members: List<FamilyMember>,
    events: List<FamilyEvent>,
    transactions: List<FinanceTransaction>,
    documents: List<FamilyDocument>,
    tasks: List<HouseholdTask>,
    trips: List<FamilyTrip>,
    onQuickNavigate: (String) -> Unit,
    /** The Gemini daily brief; when present it replaces the simple offline assistant card. */
    aiCard: (@Composable () -> Unit)? = null,
    /** Family plus "Only me" records by collection, for the category counts. */
    records: Map<String, List<com.familyhub.app.data.remote.Rec>> = emptyMap(),
    homeOrder: List<String> = emptyList(),
    homeHidden: Set<String> = emptySet(),
    /** Bills, card bills, EMIs and renewals due now, with Mark paid; paid ones drop off. */
    duesCard: (@Composable () -> Unit)? = null,
    /** Money and health at a glance for the chosen view (true = Family View). */
    glanceCard: (@Composable (Boolean) -> Unit)? = null,
    /** Today: reminders and events with times, safety status and SOS, missed calls. */
    todayCard: (@Composable () -> Unit)? = null
) {
    var familyWideView by remember { mutableStateOf(true) }
    var profileMenu by remember { mutableStateOf(false) }
    val todayLabel = remember { SimpleDateFormat("EEEE, dd MMM yyyy", Locale.getDefault()).format(Date()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().riseIn(0),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = family.name.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Welcome, ${currentUser.name.substringBefore(" ")}",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        text = todayLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                }
                // Tap your picture for your profile, families and settings.
                Box {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .glass(CircleShape, strong = true)
                            .clickable { profileMenu = true }
                            .padding(3.dp)
                    ) {
                        PersonAvatar(
                            name = currentUser.name,
                            photoData = currentMember.photoData,
                            photoUrl = currentUser.photoUrl,
                            size = 46.dp
                        )
                    }
                    DropdownMenu(expanded = profileMenu, onDismissRequest = { profileMenu = false }) {
                        listOf(
                            Triple("profile", "Manage my profile", Icons.Rounded.AccountCircle),
                            Triple("families", "My families", Icons.Rounded.Diversity3),
                            Triple("arrange_home", "Arrange Home", Icons.Rounded.Dashboard),
                            Triple("settings", "Settings", Icons.Rounded.Settings)
                        ).forEach { (route, label, icon) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                leadingIcon = { Icon(icon, contentDescription = null) },
                                onClick = { profileMenu = false; onQuickNavigate(route) }
                            )
                        }
                    }
                }
            }
        }

        item {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().riseIn(1)) {
                SegmentedButton(
                    selected = familyWideView,
                    onClick = { familyWideView = true },
                    shape = SegmentedButtonDefaults.itemShape(0, 2)
                ) { Text("Family View") }
                SegmentedButton(
                    selected = !familyWideView,
                    onClick = { familyWideView = false },
                    shape = SegmentedButtonDefaults.itemShape(1, 2)
                ) { Text("My View") }
            }
        }

        if (glanceCard != null) {
            item { Box(modifier = Modifier.riseIn(1)) { glanceCard(familyWideView) } }
        }
        if (todayCard != null) {
            item { Box(modifier = Modifier.riseIn(1)) { todayCard() } }
        }
        if (duesCard != null) {
            item { Box(modifier = Modifier.riseIn(1)) { duesCard() } }
        }

        // Everyday actions: a few round buttons, like a phone's control row.
        item {
            val actions: List<Triple<String, String, ImageVector>> = listOf(
                Triple("transactions", "Spending", Icons.Rounded.ReceiptLong),
                Triple("reminders", "Reminders", Icons.Rounded.Alarm),
                Triple("calendar", "Calendar", Icons.Rounded.CalendarMonth),
                Triple("assistant", "Ask AI", Icons.Rounded.AutoAwesome)
            )
            Row(modifier = Modifier.fillMaxWidth().riseIn(2), horizontalArrangement = Arrangement.SpaceEvenly) {
                actions.forEach { (route, label, icon) ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(76.dp).clip(RoundedCornerShape(14.dp)).clickable { onQuickNavigate(route) }.padding(vertical = 6.dp)
                    ) {
                        Box(
                            modifier = Modifier.size(52.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
            }
        }

        if (aiCard != null) {
            item { Box(modifier = Modifier.riseIn(3)) { aiCard() } }
        }

        // The areas of the app, counted for the family or just for me (order and visibility from Arrange Home).
        item {
            val filter = remember(familyWideView, currentUser.uid, currentUser.name) {
                com.familyhub.app.ui.hub.homeViewFilter(familyWideView, currentUser.uid, currentUser.name)
            }
            val cats = remember(homeOrder, homeHidden) {
                com.familyhub.app.ui.hub.arrangedHomeCategories(homeOrder, homeHidden).filter { it.route != "assistant" }
            }
            com.familyhub.app.core.theme.GroupedSection(
                title = if (familyWideView) "Family" else "Mine",
                modifier = Modifier.riseIn(3)
            ) {
                cats.forEachIndexed { i, c ->
                    val n = remember(records, filter) { c.count(records, filter) }
                    com.familyhub.app.core.theme.GroupedRow(
                        c.title,
                        icon = c.icon,
                        tile = i,
                        value = when {
                            c.unit.isBlank() -> null
                            c.unit == "entries" -> com.familyhub.app.ui.common.plural(n, "entry", "entries")
                            else -> "$n ${c.unit}"
                        },
                        divider = i < cats.lastIndex
                    ) { onQuickNavigate(c.route) }
                }
            }
        }

        // Recent activity: the newest money entries, tasks and events (health and "Only me" notes stay off Home).
        item {
            val filter = remember(familyWideView, currentUser.uid, currentUser.name) {
                com.familyhub.app.ui.hub.homeViewFilter(familyWideView, currentUser.uid, currentUser.name)
            }
            val recent = remember(records, filter) { recentActivity(records, filter) }
            if (recent.isNotEmpty()) {
                val fmt = remember { SimpleDateFormat("d MMM", Locale.getDefault()) }
                com.familyhub.app.core.theme.GroupedSection(title = "Recent", modifier = Modifier.riseIn(4)) {
                    recent.forEachIndexed { i, r ->
                        com.familyhub.app.core.theme.GroupedRow(
                            r.title,
                            note = r.kind + " · " + fmt.format(Date(r.at)),
                            value = r.amount,
                            divider = i < recent.lastIndex
                        ) { onQuickNavigate(r.route) }
                    }
                }
            }
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.riseIn(5)) {
                items(members.filter { it.status == "ACTIVE" }) { member ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        PersonAvatar(name = member.name, photoData = member.photoData, photoUrl = member.photoUrl, size = 48.dp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(member.name.substringBefore(" "), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/** One line of Home's "Recent" list. */
data class RecentItem(val title: String, val kind: String, val at: Long, val amount: String?, val route: String)

/** The five newest money entries, tasks and events the chosen view shows. Health records are never listed. */
fun recentActivity(records: Map<String, List<com.familyhub.app.data.remote.Rec>>, include: (com.familyhub.app.data.remote.Rec) -> Boolean): List<RecentItem> {
    val inr = NumberFormat.getCurrencyInstance(Locale("en", "IN")).apply { maximumFractionDigits = 0 }
    fun at(r: com.familyhub.app.data.remote.Rec): Long? =
        listOf("updatedAt", "createdAt").firstNotNullOfOrNull { (r[it] as? Number)?.toLong() }
    fun live(r: com.familyhub.app.data.remote.Rec) = r["isDeleted"] != true && include(r)
    val out = ArrayList<RecentItem>()
    records["expenses"].orEmpty().filter { live(it) }.forEach { r ->
        val t = at(r) ?: return@forEach
        val type = (r["type"] as? String).orEmpty()
        val title = (r["title"] as? String).orEmpty().ifBlank { (r["category"] as? String).orEmpty() }.ifBlank { "Entry" }
        out += RecentItem(title, if (type == "INCOME") "Income" else "Money", t,
            (r["amountInr"] as? Number)?.toDouble()?.let { inr.format(it) }, "transactions")
    }
    records["tasks"].orEmpty().filter { live(it) }.forEach { r ->
        val t = at(r) ?: return@forEach
        val shopping = (r["type"] as? String) == "SHOPPING"
        out += RecentItem((r["title"] as? String).orEmpty().ifBlank { "Task" }, if (shopping) "Shopping" else "Task", t, null,
            if (shopping) "shopping" else "household")
    }
    records["events"].orEmpty().filter { live(it) }.forEach { r ->
        val t = at(r) ?: return@forEach
        out += RecentItem((r["title"] as? String).orEmpty().ifBlank { "Event" }, "Event", t, null, "calendar")
    }
    return out.sortedByDescending { it.at }.take(5)
}

/** A small rounded colour tile with a white icon, like the icons in the iPhone Settings app. */
@Composable
private fun IconTile(icon: ImageVector, colorIndex: Int) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(tileBrush(colorIndex)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}
