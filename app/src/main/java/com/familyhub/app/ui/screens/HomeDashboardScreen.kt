package com.familyhub.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.familyhub.app.core.ai.FamilyAiAssistant
import com.familyhub.app.core.theme.AccentCard
import com.familyhub.app.core.theme.GlassCard
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
    homeHidden: Set<String> = emptySet()
) {
    var familyWideView by remember { mutableStateOf(true) }
    var profileMenu by remember { mutableStateOf(false) }
    var aiQuestion by remember { mutableStateOf("") }
    var aiResponse by remember { mutableStateOf<String?>(null) }
    val assistant = remember { FamilyAiAssistant() }
    val inrFormat = remember { NumberFormat.getCurrencyInstance(Locale("en", "IN")) }
    val todayLabel = remember { SimpleDateFormat("EEEE, dd MMM yyyy", Locale.getDefault()).format(Date()) }

    val visibleTx = if (familyWideView) transactions else transactions.filter { it.memberUid == currentUser.uid }
    val monthlyExpense = visibleTx.filter { it.type == "EXPENSE" }.sumOf { it.amountInr }
    val upcomingBills = transactions.filter { !it.isPaid && it.type in listOf("BILL", "EMI") }
    val pendingTasks = tasks.filter { !it.isCompleted }
    val expiringDocs = documents.filter {
        val exp = it.expiryDateMillis ?: return@filter false
        exp - System.currentTimeMillis() in 0..(30L * 24 * 60 * 60 * 1000)
    }

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

        // The top categories, counted for the family or just for me.
        item {
            val filter = remember(familyWideView, currentUser.uid, currentUser.name) {
                com.familyhub.app.ui.hub.homeViewFilter(familyWideView, currentUser.uid, currentUser.name)
            }
            val cats = remember(homeOrder, homeHidden) { com.familyhub.app.ui.hub.arrangedHomeCategories(homeOrder, homeHidden) }
            Column(modifier = Modifier.riseIn(1), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (familyWideView) "FAMILY CATEGORIES" else "MY CATEGORIES",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { onQuickNavigate("arrange_home") }) { Text("Arrange") }
                }
                cats.chunked(3).forEachIndexed { row, line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        line.forEachIndexed { col, c ->
                            val n = remember(records, filter) { c.count(records, filter) }
                            GlassCard(
                                onClick = { onQuickNavigate(c.route) },
                                shape = RoundedCornerShape(22.dp),
                                modifier = Modifier.weight(1f).height(112.dp)
                            ) {
                                Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                                    IconTile(c.icon, row * 3 + col)
                                    Spacer(modifier = Modifier.weight(1f))
                                    com.familyhub.app.ui.hub.FitLabel(
                                        c.title, MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Start
                                    )
                                    Text(
                                        if (c.unit.isBlank()) "Ask anything" else "$n ${c.unit}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                        repeat(3 - line.size) { Spacer(modifier = Modifier.weight(1f)) }
                    }
                }
            }
        }

        if (aiCard != null) {
            item { Box(modifier = Modifier.riseIn(3)) { aiCard() } }
        } else item {
            GlassCard(
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.riseIn(3)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconTile(Icons.Rounded.AutoAwesome, 4)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Family AI Assistant", fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = aiQuestion,
                        onValueChange = { aiQuestion = it },
                        placeholder = { Text("Ask about bills, events, insurance expiry...") },
                        trailingIcon = {
                            IconButton(onClick = {
                                if (aiQuestion.isNotBlank()) {
                                    aiResponse = assistant.answerQuery(
                                        aiQuestion, currentMember, events, transactions, documents, tasks, trips
                                    )
                                }
                            }) {
                                Icon(Icons.Rounded.Send, contentDescription = "Ask")
                            }
                        },
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (aiResponse != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(text = aiResponse!!, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.riseIn(4)) {
                items(members.filter { it.status == "ACTIVE" }) { member ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(modifier = Modifier.size(56.dp).glass(CircleShape, strong = true).padding(3.dp)) {
                            PersonAvatar(
                                name = member.name,
                                photoData = member.photoData,
                                photoUrl = member.photoUrl,
                                size = 50.dp
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(member.name.substringBefore(" "), style = MaterialTheme.typography.labelMedium)
                        Text(member.role.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        item {
            AccentCard(
                onClick = { onQuickNavigate("finance") },
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.riseIn(2)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
                    Text(
                        text = if (familyWideView) "Monthly Family Expenses" else "My Monthly Expenses",
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        text = inrFormat.format(monthlyExpense),
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "${upcomingBills.size} upcoming bills • Budget: ${inrFormat.format(family.monthlyBudget)}",
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // Everyday shortcuts, like app icons on a phone's home screen.
        item {
            val shortcuts: List<Triple<String, String, ImageVector>> = listOf(
                Triple("assistant_voice", "Speak", Icons.Rounded.Mic),
                Triple("sos", "SOS", Icons.Rounded.Warning),
                Triple("health_hub", "Health", Icons.Rounded.FavoriteBorder),
                Triple("medicines", "Medicines", Icons.Rounded.Medication),
                Triple("reminders", "Reminders", Icons.Rounded.Alarm),
                Triple("insights", "Insights", Icons.Rounded.PieChart),
                Triple("cards", "Card bills", Icons.Rounded.CreditCard),
                Triple("assistant", "Ask AI", Icons.Rounded.AutoAwesome),
                Triple("import", "Import", Icons.Rounded.MarkEmailRead),
                Triple("periods", "Periods", Icons.Rounded.WaterDrop)
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.riseIn(4)) {
                itemsIndexed(shortcuts) { i, (route, label, icon) ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(64.dp).clickable { onQuickNavigate(route) }
                    ) {
                        Box(
                            modifier = Modifier.size(54.dp).clip(RoundedCornerShape(15.dp)).background(
                                if (route == "sos") androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFFFF453A), Color(0xFFC20F0F)))
                                else tileBrush(i + 3)
                            ),
                            contentAlignment = Alignment.Center
                        ) { Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp)) }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().riseIn(5)) {
                GlassCard(
                    onClick = { onQuickNavigate("household") },
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        IconTile(Icons.Rounded.TaskAlt, 1)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("${pendingTasks.size} Pending", fontWeight = FontWeight.Bold)
                        Text("Household Tasks", style = MaterialTheme.typography.bodySmall)
                    }
                }
                GlassCard(
                    onClick = { onQuickNavigate("documents") },
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        IconTile(Icons.Rounded.WarningAmber, 2)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("${expiringDocs.size} Alerts", fontWeight = FontWeight.Bold)
                        Text("Doc Renewals", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
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
