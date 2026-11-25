package com.familyhub.app.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.familyhub.app.core.theme.GlassCard
import com.familyhub.app.core.theme.tileBrush
import kotlinx.coroutines.delay
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.familyhub.app.ui.modules.AllModules
import com.familyhub.app.ui.modules.HiddenFromMore

enum class PrimaryTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    CALENDAR("Calendar", Icons.Rounded.CalendarMonth),
    FAMILY("Family", Icons.Rounded.Groups),
    FINANCE("Finance", Icons.Rounded.AccountBalanceWallet),
    MORE("More", Icons.Rounded.GridView)
}

data class SecondaryDestination(val route: String, val title: String, val icon: ImageVector)

/** Screens that are not plain record lists (profile, families, health, money insights, AI). */
val ExtraDestinations: List<SecondaryDestination> = listOf(
    SecondaryDestination("assistant", "AI Assistant", Icons.Rounded.AutoAwesome),
    SecondaryDestination("health_hub", "Health & Fitness", Icons.Rounded.FavoriteBorder),
    SecondaryDestination("periods", "Period Tracker", Icons.Rounded.WaterDrop),
    SecondaryDestination("insights", "Money Insights", Icons.Rounded.PieChart),
    SecondaryDestination("cards", "Card Bills", Icons.Rounded.CreditCard),
    SecondaryDestination("import", "Import Expenses", Icons.Rounded.MarkEmailRead),
    SecondaryDestination("profile", "My Profile", Icons.Rounded.AccountCircle),
    SecondaryDestination("families", "My Families", Icons.Rounded.Diversity3)
)

/** Everything except the Calendar and Finance tabs shows up in the More grid. */
val MoreDestinations: List<SecondaryDestination> =
    ExtraDestinations +
        AllModules
            .filter { it.route != "calendar" && it.route != "finance" && it.route !in HiddenFromMore }
            .map { SecondaryDestination(it.route, it.title, it.icon) } +
        listOf(
            SecondaryDestination("alerts", "Due soon", Icons.Rounded.NotificationsActive),
            SecondaryDestination("settings", "Settings", Icons.Rounded.Settings)
        )

/** The More icons in the person's chosen order; new icons they have not placed yet go at the end. */
fun arrangedDestinations(order: List<String>): List<SecondaryDestination> {
    if (order.isEmpty()) return MoreDestinations
    val pos = order.withIndex().associate { it.value to it.index }
    return MoreDestinations.withIndex()
        .sortedBy { (i, d) -> pos[d.route] ?: (order.size + i) }
        .map { it.value }
}

@Composable
fun MoreHubGrid(
    onNavigate: (String) -> Unit,
    order: List<String> = emptyList(),
    hidden: Set<String> = emptySet(),
    onEdit: (() -> Unit)? = null
) {
    val shownDestinations = remember(order, hidden) { arrangedDestinations(order).filter { it.route !in hidden } }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "More",
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
            if (onEdit != null) {
                TextButton(onClick = onEdit) {
                    Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Arrange")
                }
            }
        }
        Text(
            text = "Everything your family keeps track of",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 4.dp, bottom = 16.dp)
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            itemsIndexed(shownDestinations, key = { _, d -> d.route }) { index, item ->
                // Tiles pop in one after another the first time the grid opens.
                var shown by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    delay(25L * index)
                    shown = true
                }
                val appear by animateFloatAsState(
                    targetValue = if (shown) 1f else 0f,
                    animationSpec = spring(dampingRatio = 0.6f, stiffness = 380f),
                    label = "tile"
                )
                GlassCard(
                    onClick = { onNavigate(item.route) },
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.graphicsLayer {
                        alpha = appear.coerceIn(0f, 1f)
                        scaleX = 0.85f + 0.15f * appear
                        scaleY = 0.85f + 0.15f * appear
                    }
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(tileBrush(index)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 2,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}
