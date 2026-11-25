package com.familyhub.app.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.familyhub.app.ui.modules.AllModules

enum class PrimaryTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    CALENDAR("Calendar", Icons.Rounded.CalendarMonth),
    FAMILY("Family", Icons.Rounded.Groups),
    FINANCE("Finance", Icons.Rounded.AccountBalanceWallet),
    MORE("More", Icons.Rounded.GridView)
}

data class SecondaryDestination(val route: String, val title: String, val icon: ImageVector)

/** Everything except the Calendar and Finance tabs shows up in the More grid. */
val MoreDestinations: List<SecondaryDestination> =
    AllModules
        .filter { it.route != "calendar" && it.route != "finance" }
        .map { SecondaryDestination(it.route, it.title, it.icon) } +
        listOf(
            SecondaryDestination("alerts", "Reminders", Icons.Rounded.NotificationsActive),
            SecondaryDestination("settings", "Settings", Icons.Rounded.Settings)
        )

@Composable
fun MoreHubGrid(onNavigate: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Family Modules",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(bottom = 16.dp)
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(MoreDestinations) { item ->
                Card(
                    onClick = { onNavigate(item.route) },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp, horizontal = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.title,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(30.dp)
                        )
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
