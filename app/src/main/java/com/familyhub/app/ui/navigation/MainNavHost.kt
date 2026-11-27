package com.familyhub.app.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.familyhub.app.core.theme.GlassCard
import com.familyhub.app.core.theme.tileBrush
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

data class SecondaryDestination(val route: String, val title: String, val icon: ImageVector, val note: String = "")

/**
 * The More list: one row per area, each with one home in the app. Finance (with accounts and loans) and Health
 * have their own tabs, and SOS lives inside Family safety, so they are not repeated here.
 */
val MoreDestinations: List<SecondaryDestination> =
    listOf(
        SecondaryDestination("assistant", "AI Assistant", Icons.Rounded.AutoAwesome, "Ask anything, or act on family records"),
        SecondaryDestination("safety", "Family safety", Icons.Rounded.Shield, "SOS, live location, check-ins, emergency card"),
        SecondaryDestination("calendar", "Calendar", Icons.Rounded.CalendarMonth, "Events and birthdays"),
        SecondaryDestination("activity", "Family activity", Icons.Rounded.History, "Who added, changed or finished what"),
        SecondaryDestination(Hubs.reminders.route, Hubs.reminders.title, Hubs.reminders.icon, Hubs.reminders.subtitle),
        SecondaryDestination(Hubs.home.route, Hubs.home.title, Hubs.home.icon, Hubs.home.subtitle),
        SecondaryDestination(Hubs.life.route, Hubs.life.title, Hubs.life.icon, Hubs.life.subtitle)
    ) +
        AllModules
            .filter {
                it.route != "calendar" && it.route != "finance" && it.route !in HiddenFromMore && it.route !in Hubs.grouped &&
                    // In Finance already (Finance → Accounts, Finance → Loans & EMIs).
                    it.route !in setOf("loans", "accounts")
            }
            .map { SecondaryDestination(it.route, it.title, it.icon) } +
        listOf(
            SecondaryDestination("settings", "Settings", Icons.Rounded.Settings, "Profile, families, appearance, backup")
        )

/** Which group each More row belongs to. */
private fun moreSection(route: String): String = when (route) {
    "assistant", "safety" -> "Assistant and safety"
    "calendar", "activity", Hubs.reminders.route -> "Plan"
    "settings" -> "Settings"
    else -> "Home and family"
}

private val MoreSections = listOf("Assistant and safety", "Plan", "Home and family", "Settings")

/** The More rows in the person's chosen order; new rows they have not placed yet go at the end. */
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
    val shown = remember(order, hidden) { arrangedDestinations(order).filter { it.route !in hidden } }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "More",
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.weight(1f).padding(start = 4.dp)
                )
                if (onEdit != null) TextButton(onClick = onEdit) { Text("Edit") }
            }
        }
        MoreSections.forEach { section ->
            val rows = shown.filter { moreSection(it.route) == section }
            if (rows.isNotEmpty()) item(key = section) {
                com.familyhub.app.core.theme.GroupedSection(title = if (section == "Settings") null else section) {
                    rows.forEachIndexed { i, d ->
                        com.familyhub.app.core.theme.GroupedRow(
                            d.title,
                            note = d.note.ifBlank { null },
                            icon = d.icon,
                            tile = MoreDestinations.indexOfFirst { it.route == d.route }.coerceAtLeast(0),
                            divider = i < rows.lastIndex) { onNavigate(d.route) }
                    }
                }
            }
        }
    }
}
