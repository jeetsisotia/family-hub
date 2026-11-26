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
import com.familyhub.app.ui.modules.ModuleByRoute
import com.familyhub.app.ui.modules.recordsFor

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
            HubItem("insights", "Insights", Icons.Rounded.PieChart),
            HubItem("cards", "Card bills", Icons.Rounded.CreditCard),
            HubItem("import", "Import SMS", Icons.Rounded.MarkEmailRead),
            HubItem("subscriptions", "Subscriptions", Icons.Rounded.Subscriptions),
            HubItem("loans", "Loans & EMIs", Icons.Rounded.AccountBalance),
            HubItem("goals", "Savings goals", Icons.Rounded.Savings),
            HubItem("settle", "Settle up", Icons.Rounded.SwapHoriz)
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

    /** Hubs that open their own screen (Health has its own dashboard, Money is the Finance tab). */
    val screens: Map<String, Hub> = listOf(home, life).associateBy { it.route }

    /** Every route that lives inside a hub, so it is left off the More grid. */
    val grouped: Set<String> = listOf(health, money, home, life).flatMap { h -> h.items.map { it.route } }.toSet()
}

/** How many records a hub item holds, or null for screens that are not plain lists. */
fun hubCount(env: AppEnv, route: String): Int? {
    val def = ModuleByRoute[route] ?: return null
    return recordsFor(def, env.data[def.collection].orEmpty()).size.takeIf { it > 0 }
}

/** A compact four-across grid of glass tiles, for the top of a hub screen. */
@Composable
fun HubTiles(items: List<HubItem>, onOpen: (String) -> Unit, counts: (String) -> Int? = { null }) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(4).forEachIndexed { row, chunk ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                chunk.forEachIndexed { col, item ->
                    HubTile(item, row * 4 + col, counts(item.route), Modifier.weight(1f)) { onOpen(item.route) }
                }
                repeat(4 - chunk.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun HubTile(item: HubItem, index: Int, count: Int?, modifier: Modifier, onClick: () -> Unit) {
    GlassCard(modifier = modifier, onClick = onClick, shape = RoundedCornerShape(20.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
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
            Text(
                item.title,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
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
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(tileBrush(index)),
                            contentAlignment = Alignment.Center
                        ) { Icon(item.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp)) }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(item.title, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 2)
                        Text(
                            if (count != null) "$count saved" else "Open",
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
