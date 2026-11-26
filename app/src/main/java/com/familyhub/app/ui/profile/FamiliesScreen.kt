package com.familyhub.app.ui.profile

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.familyhub.app.core.theme.GlassCard
import com.familyhub.app.core.theme.tileBrush
import com.familyhub.app.data.remote.FamilyInfo
import com.familyhub.app.data.remote.RecordRepository
import com.familyhub.app.ui.common.ScreenTopBar
import com.familyhub.app.ui.common.SectionTitle
import com.familyhub.app.ui.common.TextInput
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * One person can belong to several families (for example their own and their parents').
 * This screen switches between them, creates a new one or joins another with an invite code.
 */
@Composable
fun FamiliesScreen(
    uid: String,
    activeFamilyId: String,
    familyIds: List<String>,
    repo: RecordRepository,
    scope: CoroutineScope,
    onSwitch: suspend (String) -> Unit,
    onCreate: suspend (String) -> String?,
    onJoin: suspend (String) -> String?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var infos by remember { mutableStateOf<List<FamilyInfo>?>(null) }
    var newName by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf<FamilyInfo?>(null) }

    LaunchedEffect(familyIds) {
        infos = repo.familyInfos(familyIds)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenTopBar(title = "My Families", onBack = onBack)
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    "You can be part of more than one family. Each family has its own calendar, money and records; " +
                        "your \"Only me\" records and profile go with you everywhere.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f)
                )
            }
            val list = infos
            if (list == null) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else {
                items(list, key = { it.id }) { f ->
                    val active = f.id == activeFamilyId
                    GlassCard(
                        onClick = if (active) null else ({
                            scope.launch {
                                try {
                                    onSwitch(f.id)
                                    Toast.makeText(context, "Switched to ${f.name}", Toast.LENGTH_SHORT).show()
                                    onBack()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Could not switch: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(tileBrush(f.name.length)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.Home, contentDescription = null, tint = Color.White)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(f.name, fontWeight = FontWeight.SemiBold, maxLines = 2)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (active) {
                                        Icon(
                                            Icons.Rounded.CheckCircle, contentDescription = null,
                                            tint = Color(0xFF34C759), modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                    }
                                    Text(
                                        listOfNotNull(
                                            if (active) "Active now" else "Tap to switch",
                                            if (f.ownerUid == uid) "you are the owner" else null
                                        ).joinToString(" • "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                            }
                            if (f.ownerUid != uid) {
                                IconButton(onClick = { leaving = f }) {
                                    Icon(Icons.Rounded.Logout, contentDescription = "Leave family")
                                }
                            }
                        }
                    }
                }
            }

            item { SectionTitle("Start another family") }
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextInput("Family name (for example Sharma Parents)", newName, { newName = it })
                        Button(
                            enabled = !busy && newName.isNotBlank(),
                            onClick = {
                                busy = true
                                scope.launch {
                                    val err = onCreate(newName.trim())
                                    busy = false
                                    if (err != null) {
                                        Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(context, "Family created", Toast.LENGTH_SHORT).show()
                                        onBack()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Create family") }
                    }
                }
            }

            item { SectionTitle("Join another family") }
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextInput("Invite code", code, { code = it.uppercase() })
                        TextButton(onClick = {
                            com.familyhub.app.ui.common.InviteQr.scan(
                                context,
                                onCode = { code = it },
                                onError = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                            )
                        }) { Text("Scan the family's QR code") }
                        Text(
                            "The owner of that family will need to approve you.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Button(
                            enabled = !busy && code.isNotBlank(),
                            onClick = {
                                busy = true
                                scope.launch {
                                    val err = onJoin(code.trim())
                                    busy = false
                                    if (err != null) {
                                        Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(context, "Request sent. You can switch back any time.", Toast.LENGTH_LONG).show()
                                        onBack()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Join") }
                    }
                }
            }
        }
    }

    val l = leaving
    if (l != null) {
        AlertDialog(
            onDismissRequest = { leaving = null },
            title = { Text("Leave ${l.name}?") },
            text = { Text("You will stop seeing this family's shared records. Your own \"Only me\" records are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    leaving = null
                    scope.launch {
                        val remaining = familyIds.filter { it != l.id }
                        val newActive = if (activeFamilyId == l.id) remaining.firstOrNull().orEmpty() else activeFamilyId
                        try {
                            repo.leaveFamily(uid, l.id, remaining, newActive)
                            Toast.makeText(context, "You left ${l.name}", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "Could not leave: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("Leave") }
            },
            dismissButton = { TextButton(onClick = { leaving = null }) { Text("Cancel") } }
        )
    }
}
