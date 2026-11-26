package com.familyhub.app.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.familyhub.app.core.theme.GlassCard
import com.familyhub.app.core.theme.tileBrush
import com.familyhub.app.ui.AppEnv
import com.familyhub.app.ui.common.ScreenTopBar
import com.familyhub.app.data.remote.PRIVATE_FLAG
import com.familyhub.app.data.remote.Rec
import com.familyhub.app.ui.modules.ModuleByRoute
import com.familyhub.app.ui.modules.bool
import com.familyhub.app.ui.modules.lng
import com.familyhub.app.ui.modules.recordsFor
import com.familyhub.app.ui.modules.startOfDay
import com.familyhub.app.ui.modules.str

/** One screen reachable from a hub. */
data class HubItem(val route: String, val title: String, val icon: ImageVector)

/** A group of related screens shown together instead of as separate icons on More. */
data class Hub(val route: String, val title: String, val subtitle: String, val icon: ImageVector, val items: List<HubItem>)

object Hubs {
    val health = Hub(
        "health_hub", "Health & Fitness", "Steps, periods, medicines and more", Icons.Rounded.FavoriteBorder,
        listOf(
            HubItem("periods", "Periods", Icons.Rounded.WaterDrop),
            HubItem("medicines", "Medicines", Icons.Rounded.Medication),
            HubItem("food", "Food diary", Icons.Rounded.Restaurant),
            HubItem("vitals", "Vitals", Icons.Rounded.MonitorHeart),
            HubItem("workouts", "Workouts", Icons.Rounded.FitnessCenter),
            HubItem("habits", "Habits", Icons.Rounded.SelfImprovement),
            HubItem("health", "Doctors & records", Icons.Rounded.MedicalServices),
            HubItem("emergency", "Emergency info", Icons.Rounded.LocalHospital)
        )
    )

    /** Shown at the top of the Finance tab. */
    val money = Hub(
        "finance", "Money", "Spending, cards, bills and savings", Icons.Rounded.AccountBalanceWallet,
        listOf(
            HubItem("accounts_home", "Accounts", Icons.Rounded.AccountBalance),
            HubItem("cards", "Credit cards", Icons.Rounded.CreditCard),
            HubItem("loans_home", "Loans & EMIs", Icons.Rounded.RequestQuote),
            HubItem("due_calendar", "Due calendar", Icons.Rounded.EventNote),
            HubItem("transfer", "Transfer", Icons.Rounded.SwapHoriz),
            HubItem("import", "Scan SMS & Gmail", Icons.Rounded.MarkEmailRead),
            HubItem("insights", "Insights", Icons.Rounded.PieChart),
            HubItem("subscriptions", "Subscriptions", Icons.Rounded.Subscriptions),
            HubItem("categories", "Categories", Icons.Rounded.Category),
            HubItem("duplicates", "Duplicates", Icons.Rounded.ContentCopy),
            HubItem("goals", "Savings goals", Icons.Rounded.Savings),
            HubItem("settle", "Settle up", Icons.Rounded.Groups),
            HubItem("policies", "Insurance", Icons.Rounded.Shield)
        )
    )

    val home = Hub(
        "home_hub", "Home & Living", "Household, shopping, meals and upkeep", Icons.Rounded.HomeWork,
        listOf(
            HubItem("shopping", "Shopping", Icons.Rounded.ShoppingCart),
            HubItem("household", "Household", Icons.Rounded.HomeWork),
            HubItem("meals", "Meal planner", Icons.Rounded.Restaurant),
            HubItem("chores", "Chores", Icons.Rounded.CleaningServices),
            HubItem("maintenance", "Maintenance", Icons.Rounded.Build),
            HubItem("staff", "Staff", Icons.Rounded.Badge),
            HubItem("home_vault", "Home vault", Icons.Rounded.Inventory2),
            HubItem("vehicles", "Vehicles", Icons.Rounded.DirectionsCar),
            HubItem("property", "Property", Icons.Rounded.Apartment),
            HubItem("pets", "Pets", Icons.Rounded.Pets)
        )
    )

    val life = Hub(
        "life_hub", "Family Life", "Birthdays, school, trips and memories", Icons.Rounded.FamilyRestroom,
        listOf(
            HubItem("birthdays", "Birthdays", Icons.Rounded.Cake),
            HubItem("school", "School", Icons.Rounded.School),
            HubItem("travel", "Travel", Icons.Rounded.FlightTakeoff),
            HubItem("memories", "Memories", Icons.Rounded.PhotoLibrary),
            HubItem("notices", "Notice board", Icons.Rounded.Campaign),
            HubItem("notes", "Family notes", Icons.Rounded.StickyNote2)
        )
    )

    /** Everything with a date to remember: bills, renewals, services, documents and birthdays. */
    val reminders = Hub(
        "reminders_hub", "Reminders", "Renewals, services, bills and dates", Icons.Rounded.Alarm,
        listOf(
            HubItem("import", "Find in SMS & Gmail", Icons.Rounded.MarkEmailRead),
            HubItem("reminders", "My reminders", Icons.Rounded.Alarm),
            HubItem("alerts", "Due soon", Icons.Rounded.NotificationsActive),
            HubItem("vehicles", "Vehicle service", Icons.Rounded.DirectionsCar),
            HubItem("maintenance", "Home service", Icons.Rounded.Build),
            HubItem("documents", "Document renewals", Icons.Rounded.FolderShared),
            HubItem("policies", "Insurance policies", Icons.Rounded.Shield),
            HubItem("subscriptions", "Subscriptions", Icons.Rounded.Subscriptions),
            HubItem("cards", "Card bills", Icons.Rounded.CreditCard),
            HubItem("birthdays", "Birthdays", Icons.Rounded.Cake)
        )
    )

    /** Hubs that open their own screen (Health has its own dashboard, Money is the Finance tab). */
    val screens: Map<String, Hub> = listOf(home, life, reminders).associateBy { it.route }

    /** Every route that lives inside a hub, so it is left off the More grid. */
    val grouped: Set<String> = listOf(health, money, home, life, reminders).flatMap { h -> h.items.map { it.route } }.toSet()
}

/** A top category on the Home screen, with how many entries it holds for the chosen view. */
data class HomeCategory(
    val route: String,
    val title: String,
    val icon: ImageVector,
    val unit: String,
    val count: (Map<String, List<Rec>>, (Rec) -> Boolean) -> Int
)

private val HEALTH_TYPES = setOf("medicine", "vital", "workout", "habit", "health", "period", "emergency", "food")
private val REMINDER_TYPES = setOf("reminder", "maintenance", "vehicle", "document", "subscription", "card", "birthday", "policy")
private val LIFE_TYPES = setOf("birthday", "school", "memory", "notice", "note")

private fun monthStart(): Long = java.util.Calendar.getInstance().apply {
    set(java.util.Calendar.DAY_OF_MONTH, 1)
    set(java.util.Calendar.HOUR_OF_DAY, 0)
    set(java.util.Calendar.MINUTE, 0)
    set(java.util.Calendar.SECOND, 0)
    set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis

val HomeCategories: List<HomeCategory> = listOf(
    HomeCategory("finance", "Finance", Icons.Rounded.AccountBalanceWallet, "this month") { d, f ->
        val m = monthStart()
        d["expenses"].orEmpty().count { f(it) && !it.bool("isDeleted") && (it.lng("dateMillis") ?: 0L) >= m }
    },
    HomeCategory("health_hub", "Health", Icons.Rounded.FavoriteBorder, "entries") { d, f ->
        d["items"].orEmpty().count { f(it) && it.str("type") in HEALTH_TYPES }
    },
    HomeCategory("reminders_hub", "Reminders", Icons.Rounded.Alarm, "set") { d, f ->
        d["items"].orEmpty().count { f(it) && it.str("type") in REMINDER_TYPES }
    },
    HomeCategory("calendar", "Calendar", Icons.Rounded.CalendarMonth, "upcoming") { d, f ->
        val today = startOfDay(System.currentTimeMillis())
        d["events"].orEmpty().count { f(it) && !it.bool("isDeleted") && (it.lng("startTimeMillis") ?: 0L) >= today }
    },
    HomeCategory("home_hub", "Home", Icons.Rounded.HomeWork, "to do") { d, f ->
        d["tasks"].orEmpty().count { f(it) && !it.bool("isCompleted") } +
            d["items"].orEmpty().count { f(it) && it.str("type") == "chore" && !it.bool("done") }
    },
    HomeCategory("life_hub", "Family Life", Icons.Rounded.FamilyRestroom, "saved") { d, f ->
        d["items"].orEmpty().count { f(it) && it.str("type") in LIFE_TYPES } + d["trips"].orEmpty().count { f(it) }
    },
    HomeCategory("documents", "Documents", Icons.Rounded.FolderShared, "saved") { d, f ->
        d["items"].orEmpty().count { f(it) && it.str("type") == "document" }
    },
    HomeCategory("assistant", "Ask AI", Icons.Rounded.AutoAwesome, "") { _, _ -> 0 }
)

/** Home categories in the person's order, without the hidden ones. */
fun arrangedHomeCategories(order: List<String>, hidden: Set<String>): List<HomeCategory> {
    val pos = order.withIndex().associate { it.value to it.index }
    return HomeCategories.withIndex()
        .sortedBy { (i, c) -> pos[c.route] ?: (order.size + i) }
        .map { it.value }
        .filter { it.route !in hidden }
}

/** Which records a Home view counts: the family's shared ones, or the person's own. */
fun homeViewFilter(familyView: Boolean, uid: String, name: String): (Rec) -> Boolean {
    if (familyView) return { r -> r[PRIVATE_FLAG] != true }
    val first = name.substringBefore(' ').lowercase()
    return { r ->
        r[PRIVATE_FLAG] == true || r.str("createdByUid") == uid || r.str("memberUid") == uid ||
            listOf("memberName", "assignedToName", "assignedTo", "createdByName").any { k ->
                val v = r.str(k).lowercase()
                v.isNotBlank() && first.isNotBlank() && (v == name.lowercase() || v.substringBefore(' ') == first)
            }
    }
}

/** How many records a hub item holds, or null for screens that are not plain lists. */
fun hubCount(env: AppEnv, route: String): Int? {
    val def = ModuleByRoute[route] ?: return null
    return recordsFor(def, env.data[def.collection].orEmpty()).size.takeIf { it > 0 }
}

/**
 * A label that steps its size down instead of splitting a word across lines
 * ("Maintenance" rather than "Maintenan / ce").
 */
@Composable
fun FitLabel(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
    textAlign: TextAlign = TextAlign.Center,
    color: Color = Color.Unspecified
) {
    var size by remember(text) { mutableStateOf(style.fontSize) }
    Text(
        text,
        style = style.copy(fontSize = size),
        color = color,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
        onTextLayout = { r ->
            var broken = r.hasVisualOverflow
            for (i in 0 until r.lineCount - 1) {
                val end = r.getLineEnd(i, visibleEnd = true)
                if (end in 1 until text.length && text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit()) broken = true
            }
            if (broken && size.isSp && size.value > 9f) size = (size.value - 1f).sp
        }
    )
}

/** A compact three-across grid of glass tiles, for the top of a hub screen. */
@Composable
fun HubTiles(items: List<HubItem>, onOpen: (String) -> Unit, counts: (String) -> Int? = { null }) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(3).forEachIndexed { row, chunk ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                chunk.forEachIndexed { col, item ->
                    HubTile(item, row * 3 + col, counts(item.route), Modifier.weight(1f)) { onOpen(item.route) }
                }
                repeat(3 - chunk.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun HubTile(item: HubItem, index: Int, count: Int?, modifier: Modifier, onClick: () -> Unit) {
    GlassCard(modifier = modifier, onClick = onClick, shape = RoundedCornerShape(20.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().height(120.dp).padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box {
                Box(
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tileBrush(index)),
                    contentAlignment = Alignment.Center
                ) { Icon(item.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp)) }
                if (count != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 6.dp, y = (-6).dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFF3B30))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            if (count > 99) "99+" else count.toString(),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            FitLabel(item.title, MaterialTheme.typography.labelLarge)
        }
    }
}

/** A hub's own screen: a short intro and a grid of its screens. */
@Composable
fun HubScreen(hub: Hub, env: AppEnv) {
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenTopBar(title = hub.title, onBack = env.onBack)
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    hub.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                )
            }
            itemsIndexed(hub.items, key = { _, it -> it.route }) { index, item ->
                val count = hubCount(env, item.route)
                GlassCard(onClick = { env.navigate(item.route) }, shape = RoundedCornerShape(24.dp)) {
                    Column(
                        modifier = Modifier.fillMaxWidth().height(146.dp).padding(vertical = 12.dp, horizontal = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(tileBrush(index)),
                            contentAlignment = Alignment.Center
                        ) { Icon(item.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp)) }
                        Spacer(modifier = Modifier.height(8.dp))
                        FitLabel(item.title, MaterialTheme.typography.labelLarge)
                        if (count != null) {
                            Text(
                                "$count saved",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}
