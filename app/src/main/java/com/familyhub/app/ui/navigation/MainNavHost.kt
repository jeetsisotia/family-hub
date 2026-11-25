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
import androidx.compose.ui.unit.dp

enum class PrimaryTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    CALENDAR("Calendar", Icons.Rounded.CalendarMonth),
    FAMILY("Family", Icons.Rounded.Groups),
    FINANCE("Finance", Icons.Rounded.AccountBalanceWallet),
    MORE("More", Icons.Rounded.GridView)
}

data class SecondaryDestination(val route: String, val title: String, val icon: ImageVector)

val MoreDestinations = listOf(
    SecondaryDestination("home_vault", "Home Vault", Icons.Rounded.Inventory2),
    SecondaryDestination("documents", "Documents", Icons.Rounded.FolderShared),
    SecondaryDestination("health", "Health", Icons.Rounded.MedicalServices),
    SecondaryDestination("shopping", "Shopping", Icons.Rounded.ShoppingCart),
    SecondaryDestination("household", "Household", Icons.Rounded.HomeWork),
    SecondaryDestination("travel", "Travel", Icons.Rounded.FlightTakeoff),
    SecondaryDestination("memories", "Memories", Icons.Rounded.PhotoLibrary),
    SecondaryDestination("notifications", "Notifications", Icons.Rounded.NotificationsActive),
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
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(MoreDestinations) { item ->
                Card(
                    onClick = { onNavigate(item.route) },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp, horizontal = 8.dp),
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
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
