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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------- reminders ----------

@Composable
private fun AlertRow(alert: AlertItem, onClick: () -> Unit) {
    val fmt = remember { SimpleDateFormat("dd MMM", Locale.getDefault()) }
    val days = daysUntil(alert.dateMillis)
    val whenText = when {
        days < 0 -> "overdue by ${-days} day(s)"
        days == 0 -> "today"
        days == 1 -> "tomorrow"
        else -> "in $days days"
    }
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Reminders (${alerts.size})", fontWeight = FontWeight.Bold)
                TextButton(onClick = onSeeAll) { Text("See all") }
            }
            alerts.take(3).forEach { a -> AlertRow(a) { onOpen(a.route) } }
        }
    }
}

@Composable
fun AlertsScreen(alerts: List<AlertItem>, onBack: () -> Unit, onOpen: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Back") }
            Text(
                "Reminders",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
            )
        }
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
    onRemove: (String) -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val active = members.filter { it.status == "ACTIVE" }
    val pending = members.filter { it.status != "ACTIVE" }

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
                        "Family members install the app, sign in with Google, choose Join Existing Family and enter this code. You approve them below.",
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
                    }
                }
            }
        }

        if (isOwner && pending.isNotEmpty()) {
            item { Text("Waiting for your approval", fontWeight = FontWeight.Bold) }
            items(pending, key = { "p_" + it.uid }) { m ->
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
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
                        TextButton(onClick = { onRemove(m.uid) }) { Text("Decline") }
                        Button(onClick = { onApprove(m.uid) }) { Text("Approve") }
                    }
                }
            }
        }

        item { Text("Members (${active.size})", fontWeight = FontWeight.Bold) }
        items(active, key = { "a_" + it.uid }) { m ->
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar(m.name)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(m.name + if (m.uid == currentUid) " (you)" else "", fontWeight = FontWeight.SemiBold)
                        Text(
                            (if (m.role == FamilyRole.OWNER) "Owner" else m.relation) + " • " + m.email,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                            maxLines = 1
                        )
                    }
                    if (isOwner && m.uid != currentUid) {
                        TextButton(onClick = { onRemove(m.uid) }) { Text("Remove") }
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
    onSignOut: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Back") }
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
            )
        }
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
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
            item {
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
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
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
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
                OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
                    Text("Sign out / switch Google account")
                }
            }
        }
    }
}
