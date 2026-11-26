package com.familyhub.app.ui.modules

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
    GlassCard(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (alert.overdue) Icons.Rounded.WarningAmber else Icons.Rounded.Notifications,
                contentDescription = null,
                tint = if (alert.overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(alert.title.ifBlank { "(untitled)" }, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    text = "${alert.label} • ${fmt.format(Date(alert.dateMillis))} • $whenText",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (alert.overdue) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
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
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(alerts) { a -> AlertRow(a) { onOpen(a.route) } }
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
        AlertDialog(
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
        AlertDialog(
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
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "Invite code",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                    )
                    Text(
                        family.inviteCode,
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Text(
                        "Family members install the app, sign in with Google, choose Join Existing Family and enter this code or scan the QR. You approve them below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(family.inviteCode))
                            Toast.makeText(context, "Invite code copied", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Copy", color = MaterialTheme.colorScheme.onPrimary)
                        }
                        TextButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    "Join our family on Family Hub. Install the app, sign in, choose Join Existing Family and enter code ${family.inviteCode}"
                                )
                            }
                            context.startActivity(Intent.createChooser(send, "Invite to Family Hub"))
                        }) {
                            Icon(Icons.Rounded.Share, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Share", color = MaterialTheme.colorScheme.onPrimary)
                        }
                        TextButton(onClick = { showQr = true }) {
                            Icon(Icons.Rounded.QrCode2, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("QR", color = MaterialTheme.colorScheme.onPrimary)
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
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
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
        AlertDialog(
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
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                GlassCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Your account", fontWeight = FontWeight.Bold)
                        Text(user.name)
                        Text(
                            user.email,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }
            if (onOpen != null) {
                item {
                    GlassCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            SettingsLink(Icons.Rounded.AccountCircle, "My Profile", "Photo, details, IDs and app lock") { onOpen("profile") }
                            SettingsLink(Icons.Rounded.Diversity3, "My Families", "Switch, create or join a family") { onOpen("families") }
                            SettingsLink(Icons.Rounded.Dashboard, "Arrange Home", "Move Home categories up or down, or hide them") { onOpen("arrange_home") }
                            SettingsLink(Icons.Rounded.Tune, "Arrange More", "Order and hide the More icons") { onOpen("arrange") }
                            SettingsLink(Icons.Rounded.Backup, "Data & backup", "Back up, restore, recently deleted and reset") { onOpen("data_tools") }
                        }
                    }
                }
            }
            item {
                val themeContext = LocalContext.current
                GlassCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Appearance", fontWeight = FontWeight.Bold)
                        val labels = mapOf(
                            com.familyhub.app.core.theme.ThemeChoice.SYSTEM to "Phone setting",
                            com.familyhub.app.core.theme.ThemeChoice.LIGHT to "Light",
                            com.familyhub.app.core.theme.ThemeChoice.DARK to "Dark"
                        )
                        com.familyhub.app.ui.common.ChoiceChips(
                            labels.values.toList(),
                            labels[com.familyhub.app.core.theme.ThemeChoice.mode.value] ?: "Phone setting",
                            { picked ->
                                val key = labels.entries.firstOrNull { it.value == picked }?.key ?: com.familyhub.app.core.theme.ThemeChoice.SYSTEM
                                com.familyhub.app.core.theme.ThemeChoice.set(themeContext, key)
                            }
                        )
                    }
                }
            }
            item {
                GlassCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Family", fontWeight = FontWeight.Bold)
                        Text(family.name)
                        Text(
                            "Invite code: ${family.inviteCode}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Text(
                            "Monthly budget: ${money(family.monthlyBudget)}" + if (isOwner) " (edit it in Finance)" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }
            item {
                GlassCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Privacy", fontWeight = FontWeight.Bold)
                        Text(
                            "Your family's data lives in your own private Firebase project and is visible only to approved family members. Nothing is uploaded as files. Documents, health and other records store details only.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            item {
                OutlinedButton(onClick = { confirmSignOut = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Sign out / switch Google account", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun SettingsLink(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, note: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
    }
}
