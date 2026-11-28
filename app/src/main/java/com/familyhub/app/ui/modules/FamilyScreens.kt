package com.familyhub.app.ui.modules

import com.familyhub.app.core.theme.GlassDialog
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.familyhub.app.core.theme.GlassCard
import com.familyhub.app.ui.common.ScreenTopBar
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.familyhub.app.data.model.FamilyGroup
import com.familyhub.app.data.model.FamilyMember
import com.familyhub.app.data.model.FamilyRole
import com.familyhub.app.data.model.UserProfile
import com.familyhub.app.ui.common.PersonAvatar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------- reminders ----------

@Composable
private fun AlertRow(alert: AlertItem, onClick: () -> Unit) {
    val fmt = remember { SimpleDateFormat("dd MMM", Locale.getDefault()) }
    val days = daysUntil(alert.dateMillis)
    val whenText = when {
        days == -1 -> "overdue by 1 day"
        days < 0 -> "overdue by ${-days} days"
        days == 0 -> "today"
        days == 1 -> "tomorrow"
        else -> "in $days days"
    }
    com.familyhub.app.core.theme.GlassNotificationCard(
        title = alert.title.ifBlank { "(untitled)" },
        subtitle = "${alert.label} • ${fmt.format(Date(alert.dateMillis))}",
        icon = if (alert.overdue) Icons.Rounded.WarningAmber else Icons.Rounded.Notifications,
        accent = if (alert.overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        trailing = whenText,
        urgent = alert.overdue,
        onClick = onClick
    )
}

@Composable
fun AlertsStrip(alerts: List<AlertItem>, onSeeAll: () -> Unit, onOpen: (String) -> Unit) {
    if (alerts.isEmpty()) return
    GlassCard(
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Due soon (${alerts.size})", fontWeight = FontWeight.Bold)
                TextButton(onClick = onSeeAll) { Text("See all") }
            }
            alerts.take(3).forEach { a -> AlertRow(a) { onOpen(a.route) } }
        }
    }
}

@Composable
fun AlertsScreen(alerts: List<AlertItem>, onBack: () -> Unit, onOpen: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Due soon", onBack = onBack)
        if (alerts.isEmpty()) {
            Text(
                "All clear. Nothing is due or expiring in the next 30 days.",
                modifier = Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        } else {
            // Notification centre: overdue first, then soonest; filter by priority or by section.
            val sectionOf: (AlertItem) -> String = { a -> AllModules.firstOrNull { it.route == a.route }?.title ?: a.label }
            val overdueN = alerts.count { it.overdue }
            val sections = alerts.map(sectionOf).distinct().sorted()
            val options = listOf("All (${alerts.size})") + (if (overdueN > 0) listOf("Overdue ($overdueN)") else emptyList()) + sections
            var filter by remember { mutableStateOf(options.first()) }
            val shown = alerts.filter { a ->
                when {
                    filter.startsWith("All") -> true
                    filter.startsWith("Overdue") -> a.overdue
                    else -> sectionOf(a) == filter
                }
            }.sortedWith(compareBy<AlertItem>({ !it.overdue }, { it.dateMillis }))
            com.familyhub.app.ui.common.ChoiceChips(
                options, filter, { if (it.isNotBlank()) filter = it },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), scroll = true
            )
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(shown) { a -> AlertRow(a) { onOpen(a.route) } }
            }
        }
    }
}

// ---------- family ----------

@Composable
private fun Avatar(name: String) {
    Box(
        modifier = Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name.trim().take(1).uppercase().ifBlank { "?" },
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun FamilyScreen(
    family: FamilyGroup,
    members: List<FamilyMember>,
    currentUid: String,
    isOwner: Boolean,
    onApprove: (String) -> Unit,
    onRemove: (String) -> Unit,
    /** Super Admin or Family Admin: approves and removes members. */
    isAdmin: Boolean = isOwner,
    /** Only the Super Admin (the family's creator) makes or unmakes Family Admins. */
    onSetAdmin: (String, Boolean) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    fun isSuper(m: FamilyMember) = m.uid == family.ownerUid || m.role == FamilyRole.OWNER
    fun roleLabel(m: FamilyMember) = when {
        isSuper(m) -> "Super Admin"
        m.isAdmin -> "Family Admin"
        else -> "Member"
    }
    var confirmAdmin by remember { mutableStateOf<FamilyMember?>(null) }
    confirmAdmin?.let { who ->
        GlassDialog(
            onDismissRequest = { confirmAdmin = null },
            title = { Text(if (who.isAdmin) "Remove ${who.name} as Family Admin?" else "Make ${who.name} a Family Admin?") },
            text = {
                Text(
                    if (who.isAdmin) "${who.name} will be a member again: they can add and edit entries, but not approve members, delete for good, reset or restore data."
                    else "Family Admins can approve and remove members, restore or permanently delete entries, back up, restore and reset family data. Only you can make or remove admins."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmAdmin = null; onSetAdmin(who.uid, !who.isAdmin) }) { Text(if (who.isAdmin) "Remove admin" else "Make admin", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirmAdmin = null }) { Text("Cancel") } }
        )
    }
    val clipboard = LocalClipboardManager.current
    val active = members.filter { it.status == "ACTIVE" }
    val pending = members.filter { it.status != "ACTIVE" }
    // Removing or declining someone asks first, so a stray tap cannot drop a family member.
    var confirmRemove by remember { mutableStateOf<FamilyMember?>(null) }
    confirmRemove?.let { who ->
        val isPending = who.status != "ACTIVE"
        GlassDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(if (isPending) "Decline ${who.name}?" else "Remove ${who.name}?") },
            text = {
                Text(
                    if (isPending) "They won't join ${family.name}. They can ask again with the invite code."
                    else "${who.name} will lose access to ${family.name}'s shared calendar, money and lists. You can invite them again later."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = null
                    onRemove(who.uid)
                }) { Text(if (isPending) "Decline" else "Remove", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } }
        )
    }

    var showQr by remember { mutableStateOf(false) }
    if (showQr && family.inviteCode.isNotBlank()) {
        com.familyhub.app.ui.common.InviteQrDialog(family.name, family.inviteCode) { showQr = false }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = family.name,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
            )
        }
        item {
            GlassCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "Invite code",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Text(
                        family.inviteCode,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 2.sp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Family members install the app, sign in with Google, choose Join Existing Family and enter this code or scan the QR. You approve them below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        TextButton(contentPadding = PaddingValues(horizontal = 8.dp), onClick = {
                            clipboard.setText(AnnotatedString(family.inviteCode))
                            Toast.makeText(context, "Invite code copied", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy", maxLines = 1, softWrap = false)
                        }
                        TextButton(contentPadding = PaddingValues(horizontal = 8.dp), onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    "Join our family on Family Hub. Install the app, sign in, choose Join Existing Family and enter code ${family.inviteCode}"
                                )
                            }
                            context.startActivity(Intent.createChooser(send, "Invite to Family Hub"))
                        }) {
                            Icon(Icons.Rounded.Share, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Share", maxLines = 1, softWrap = false)
                        }
                        TextButton(contentPadding = PaddingValues(horizontal = 8.dp), onClick = { showQr = true }) {
                            Icon(Icons.Rounded.QrCode2, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("QR", maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }

        if (isAdmin && pending.isNotEmpty()) {
            item { Text("Waiting for your approval", fontWeight = FontWeight.Bold) }
            items(pending, key = { "p_" + it.uid }) { m ->
                GlassCard(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(m.name)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(m.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                m.email,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                            )
                        }
                        TextButton(onClick = { confirmRemove = m }) { Text("Decline") }
                        Button(onClick = { onApprove(m.uid) }) { Text("Approve") }
                    }
                }
            }
        }

        item { Text("Members (${active.size})", fontWeight = FontWeight.Bold) }
        items(active, key = { "a_" + it.uid }) { m ->
            GlassCard(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PersonAvatar(name = m.name, photoData = m.photoData, photoUrl = m.photoUrl, size = 44.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(m.name + if (m.uid == currentUid) " (you)" else "", fontWeight = FontWeight.SemiBold)
                        Text(
                            listOf(roleLabel(m), m.relation.takeIf { it != "Member" }.orEmpty(), m.email)
                                .filter { it.isNotBlank() }.joinToString(" • "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                            maxLines = 1
                        )
                        // Details the member chose to share from their profile.
                        val extra = listOfNotNull(
                            m.bloodGroup.takeIf { it.isNotBlank() }?.let { "Blood $it" },
                            m.dobMillis?.let { "Born " + SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(it)) },
                            if (m.emergencyName.isNotBlank()) "Emergency: ${m.emergencyName} ${m.emergencyPhone}" else null
                        )
                        if (extra.isNotEmpty()) {
                            Text(
                                extra.joinToString(" • "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 2
                            )
                        }
                    }
                    if (m.phone.isNotBlank()) {
                        IconButton(onClick = {
                            try {
                                context.startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + m.phone)))
                            } catch (e: Exception) {
                                Toast.makeText(context, "Could not open the phone app.", Toast.LENGTH_SHORT).show()
                            }
                        }) { Icon(Icons.Rounded.Call, contentDescription = "Call ${m.name}") }
                    }
                    // A Family Admin may remove members, but not other admins.
                    val canRemove = isOwner || (isAdmin && !m.isAdmin)
                    if (m.uid != currentUid && !isSuper(m) && (isOwner || canRemove)) {
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "Options for ${m.name}") }
                            com.familyhub.app.core.theme.GlassDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                if (isOwner) DropdownMenuItem(
                                    text = { Text(if (m.isAdmin) "Remove as Family Admin" else "Make Family Admin") },
                                    onClick = { menu = false; confirmAdmin = m }
                                )
                                if (canRemove) DropdownMenuItem(
                                    text = { Text("Remove from family", color = MaterialTheme.colorScheme.error) },
                                    onClick = { menu = false; confirmRemove = m }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PendingApprovalScreen(familyName: String, status: String, onLeave: () -> Unit, onSignOut: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Rounded.HourglassTop,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (status == "NONE") "You are no longer part of $familyName"
            else "Waiting for approval",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (status == "NONE") "The family owner removed you, or the request was declined."
            else "The owner of $familyName must approve your request in the Family tab. Open the app again after they approve you.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onLeave, modifier = Modifier.fillMaxWidth()) { Text("Set up or join a different family") }
        TextButton(onClick = onSignOut) { Text("Sign out / switch Google account") }
    }
}

// ---------- settings ----------

@Composable
fun SettingsScreen(
    family: FamilyGroup,
    user: UserProfile,
    isOwner: Boolean,
    onBack: () -> Unit,
    onSignOut: () -> Unit,
    onOpen: ((String) -> Unit)? = null
) {
    var confirmSignOut by remember { mutableStateOf(false) }
    if (confirmSignOut) {
        GlassDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("You can sign back in with the same Google account. Data saved only on this phone (steps, water, app lock) stays here.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    onSignOut()
                }) { Text("Sign out", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } }
        )
    }
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Settings", onBack = onBack)
        val ctx = LocalContext.current
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            item {
                com.familyhub.app.core.theme.GroupedSection(title = "Your account") {
                    com.familyhub.app.core.theme.GroupedRow(
                        title = user.name.ifBlank { "Signed in" },
                        note = user.email,
                        icon = Icons.Rounded.AccountCircle,
                        tile = 0,
                        divider = onOpen != null,
                        onClick = onOpen?.let { open -> { open("profile") } }
                    )
                    if (onOpen != null) {
                        com.familyhub.app.core.theme.GroupedRow("My Families", note = "Switch, create or join a family", icon = Icons.Rounded.Diversity3, tile = 7, divider = false) { onOpen("families") }
                    }
                }
            }
            if (onOpen != null) {
                item {
                    com.familyhub.app.core.theme.GroupedSection(title = "Layout and data") {
                        com.familyhub.app.core.theme.GroupedRow("Arrange Home", note = "Move Home categories up or down, or hide them", icon = Icons.Rounded.Dashboard, tile = 1) { onOpen("arrange_home") }
                        com.familyhub.app.core.theme.GroupedRow("Arrange More", note = "Order and hide the More icons", icon = Icons.Rounded.Tune, tile = 2) { onOpen("arrange") }
                        com.familyhub.app.core.theme.GroupedRow("Data & backup", note = "Back up, restore, recently deleted and reset", icon = Icons.Rounded.Backup, tile = 6, divider = false) { onOpen("data_tools") }
                    }
                }
            }
            item {
                com.familyhub.app.core.theme.GroupedSection(
                    title = "Appearance",
                    footer = "Reduce transparency makes cards and bars solid and stops the moving background. It can be easier to read and lighter on older phones."
                ) {
                    val labels = mapOf(
                        com.familyhub.app.core.theme.ThemeChoice.SYSTEM to "Automatic",
                        com.familyhub.app.core.theme.ThemeChoice.LIGHT to "Light",
                        com.familyhub.app.core.theme.ThemeChoice.DARK to "Dark"
                    )
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text("Theme", style = MaterialTheme.typography.bodyLarge)
                        Spacer(modifier = Modifier.height(8.dp))
                        com.familyhub.app.core.theme.SegmentedControl(
                            options = labels.values.toList(),
                            selected = labels[com.familyhub.app.core.theme.ThemeChoice.mode.value] ?: "Automatic",
                            onSelect = { picked ->
                                val key = labels.entries.firstOrNull { it.value == picked }?.key ?: com.familyhub.app.core.theme.ThemeChoice.SYSTEM
                                com.familyhub.app.core.theme.ThemeChoice.set(ctx, key)
                            }
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(start = 14.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    com.familyhub.app.core.theme.GroupedSwitchRow(
                        title = "Reduce transparency",
                        checked = com.familyhub.app.core.theme.GlassPrefs.reduceTransparency.value,
                        onChange = { com.familyhub.app.core.theme.GlassPrefs.setReduceTransparency(ctx, it) },
                        divider = false
                    )
                }
            }
            item {
                var privateWidgets by remember { mutableStateOf(com.familyhub.app.widget.HomeWidgets.isPrivate(ctx)) }
                com.familyhub.app.core.theme.GroupedSection(
                    title = "Home screen widgets",
                    footer = "Add \"Family overview\" or \"Shopping list\" from your phone's widget picker. Health items are never shown. Private widgets show only counts, no names."
                ) {
                    com.familyhub.app.core.theme.GroupedSwitchRow(
                        title = "Private widgets",
                        checked = privateWidgets,
                        onChange = { privateWidgets = it; com.familyhub.app.widget.HomeWidgets.setPrivate(ctx, it) },
                        divider = false
                    )
                }
            }
            item {
                com.familyhub.app.core.theme.GroupedSection(title = "Family") {
                    com.familyhub.app.core.theme.GroupedRow("Name", value = family.name, divider = true)
                    com.familyhub.app.core.theme.GroupedRow("Invite code", value = family.inviteCode, divider = true)
                    com.familyhub.app.core.theme.GroupedRow(
                        "Monthly budget",
                        value = money(family.monthlyBudget),
                        note = if (isOwner) "Edit it in Finance" else null,
                        divider = false
                    )
                }
            }
            item {
                com.familyhub.app.core.theme.GroupedSection(
                    title = "Privacy",
                    footer = "Your family's data lives in your own private Firebase project and is visible only to approved family members. " +
                        "Pictures stay on the phone that added them. Documents, health and other records store details only. " +
                        "Chats are protected by access rules and Google's encryption in transit and at rest; they are not end-to-end encrypted."
                ) {
                    com.familyhub.app.core.theme.GroupedRow(
                        "Sign out / switch Google account",
                        icon = Icons.Rounded.Logout,
                        tile = 3,
                        divider = false,
                        onClick = { confirmSignOut = true }
                    )
                }
            }
        }
    }
}

