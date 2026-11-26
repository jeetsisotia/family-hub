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
import com.familyhub.app.ui.hub.Hubs

enum class PrimaryTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    FINANCE("Finance", Icons.Rounded.AccountBalanceWallet),
    HEALTH("Health", Icons.Rounded.FavoriteBorder),
    CHATS("Chats", Icons.Rounded.ChatBubbleOutline),
    FAMILY("Family", Icons.Rounded.Groups),
    MORE("More", Icons.Rounded.GridView)
}

data class SecondaryDestination(val route: String, val title: String, val icon: ImageVector)

/**
 * The More grid: a few hubs that group related screens (Health, Money, Home, Family life) plus the
 * things people open directly. Screens inside a hub are opened from that hub.
 */
val MoreDestinations: List<SecondaryDestination> =
    listOf(
        SecondaryDestination("assistant", "AI Assistant", Icons.Rounded.AutoAwesome),
        SecondaryDestination("sos", "SOS", Icons.Rounded.Warning),
        // Health and Finance have their own tabs; Calendar now lives here.
        SecondaryDestination("calendar", "Calendar", Icons.Rounded.CalendarMonth),
        SecondaryDestination(Hubs.home.route, Hubs.home.title, Hubs.home.icon),
        SecondaryDestination(Hubs.life.route, Hubs.life.title, Hubs.life.icon),
        SecondaryDestination(Hubs.reminders.route, Hubs.reminders.title, Hubs.reminders.icon)
    ) +
        AllModules
            .filter { it.route != "calendar" && it.route != "finance" && it.route !in HiddenFromMore && it.route !in Hubs.grouped }
            .map { SecondaryDestination(it.route, it.title, it.icon) } +
        listOf(
            // My Profile and My Families open from the picture on Home (and from Settings).
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
            text = "Grouped so it's quick to find",
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
                        modifier = Modifier.fillMaxWidth().height(128.dp).padding(vertical = 12.dp, horizontal = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
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
                        com.familyhub.app.ui.hub.FitLabel(item.title, MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}
