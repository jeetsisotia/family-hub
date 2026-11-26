package com.familyhub.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.familyhub.app.core.theme.GlassCard
import com.familyhub.app.core.theme.tileBrush
import com.familyhub.app.ui.common.ScreenTopBar
import com.familyhub.app.ui.navigation.MoreDestinations
import com.familyhub.app.ui.navigation.arrangedDestinations

/** Lets the person reorder the icons on the More screen and hide the ones they never use. */
@Composable
fun ArrangeScreen(
    order: List<String>,
    hidden: Set<String>,
    onSave: (List<String>, List<String>) -> Unit,
    onBack: () -> Unit
) {
    val routes = remember { mutableStateListOf<String>().also { l -> l.addAll(arrangedDestinations(order).map { it.route }) } }
    val hiddenNow = remember { mutableStateMapOf<String, Boolean>().also { m -> hidden.forEach { m[it] = true } } }
    val byRoute = remember { MoreDestinations.associateBy { it.route } }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Arrange", onBack = onBack) {
            TextButton(onClick = {
                onSave(routes.toList(), hiddenNow.filterValues { it }.keys.toList())
            }) { Text("Done", fontWeight = FontWeight.Bold) }
        }
        Text(
            "Use the arrows to move icons. Turn the switch off to hide an icon from the More screen.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
        )
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(routes, key = { _, r -> r }) { index, route ->
                val d = byRoute[route] ?: return@itemsIndexed
                val visible = hiddenNow[route] != true
                GlassCard(modifier = Modifier.fillMaxWidth().animateItem()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(tileBrush(index)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(d.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        com.familyhub.app.ui.hub.FitLabel(
                            d.title,
                            MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (visible) 1f else 0.45f)
                        )
                        IconButton(onClick = {
                            if (index > 0) {
                                val r = routes.removeAt(index)
                                routes.add(index - 1, r)
                            }
                        }, enabled = index > 0) { Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Move up") }
                        IconButton(onClick = {
                            if (index < routes.size - 1) {
                                val r = routes.removeAt(index)
                                routes.add(index + 1, r)
                            }
                        }, enabled = index < routes.size - 1) { Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Move down") }
                        Switch(checked = visible, onCheckedChange = { on -> hiddenNow[route] = !on })
                    }
                }
            }
            item {
                TextButton(onClick = {
                    routes.clear()
                    routes.addAll(MoreDestinations.map { it.route })
                    hiddenNow.clear()
                }, modifier = Modifier.fillMaxWidth()) { Text("Reset to default order") }
            }
        }
    }
}
