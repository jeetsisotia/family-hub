package com.familyhub.app.ui.sos

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.familyhub.app.core.theme.GlassCard
import com.familyhub.app.core.theme.SegmentedControl
import com.familyhub.app.data.model.FamilyMember
import com.familyhub.app.safety.ArrivalTimer
import com.familyhub.app.safety.EmergencyCard
import com.familyhub.app.safety.EmergencyContact
import com.familyhub.app.safety.Freshness
import com.familyhub.app.safety.LocationShareService
import com.familyhub.app.safety.LocationShares
import com.familyhub.app.safety.LowBatteryWorker
import com.familyhub.app.safety.SafetyChat
import com.familyhub.app.safety.SafetyLogic
import com.familyhub.app.safety.SafetySms
import com.familyhub.app.safety.SafetyStore
import com.familyhub.app.safety.ShareState
import com.familyhub.app.safety.SharedLocation
import com.familyhub.app.ui.AppEnv
import com.familyhub.app.ui.chat.Mentions
import com.familyhub.app.ui.common.ScreenTopBar
import com.familyhub.app.ui.common.SectionTitle
import com.familyhub.app.ui.common.TextInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.UUID

private fun has(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

private fun openMap(ctx: Context, lat: Double, lng: Double, label: String) {
    val geo = Uri.parse("geo:$lat,$lng?q=$lat,$lng(" + Uri.encode(label.ifBlank { "Shared location" }) + ")")
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, geo).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SafetyLogic.mapLink(lat, lng))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(ctx, "No maps app found.", Toast.LENGTH_SHORT).show()
        }
    }
}

private fun openSettings(ctx: Context, action: String, withPackage: Boolean) {
    try {
        val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (withPackage) i.data = Uri.parse("package:" + ctx.packageName)
        ctx.startActivity(i)
    } catch (_: Exception) {
        Toast.makeText(ctx, "Could not open settings on this phone.", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp)
    )
}

@Composable
private fun SwitchRow(title: String, note: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun CheckRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(title)
    }
}

/**
 * Family safety: emergency contacts, low-battery SMS, live location, check-ins, the safe-arrival timer, the
 * emergency card and the alert log. Everything is off until this person turns it on, on their own phone.
 * Nobody, admins included, can turn on someone else's sharing.
 */
@Composable
fun SafetyScreen(env: AppEnv) {
    val context = LocalContext.current
    val tick by SafetyStore.tick
    LaunchedEffect(env.uid) {
        SafetyStore.setIdentity(context, env.uid, env.profile.name, env.familyId)
        SafetyStore.restoreFromCloud(context, env.uid)
        SafetyStore.importFromProfile(context, env.userDoc)
        SafetySms.bestLocation(context)
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            now = System.currentTimeMillis()
        }
    }
    val contacts = remember(tick) { SafetyStore.contacts(context) }
    val lowOn = remember(tick) { SafetyStore.lowBatteryOn(context) }
    val locationOn = remember(tick) { SafetyStore.locationOn(context) }
    val share = remember(tick, now) { SafetyStore.share(context) }
    val arrival = remember(tick, now) { SafetyStore.arrival(context) }
    val log = remember(tick) { SafetyStore.log(context) }
    var card by remember(tick) { mutableStateOf(SafetyStore.card(context)) }
    var threshold by remember(tick) { mutableFloatStateOf(SafetyStore.threshold(context).toFloat()) }
    val others = remember(env.members) { env.members.filter { it.uid != env.uid && it.status == "ACTIVE" } }

    var editing by remember { mutableStateOf<EmergencyContact?>(null) }
    var sharePicker by remember { mutableStateOf(false) }
    var askCheckIn by remember { mutableStateOf(false) }
    var permissionNote by remember { mutableStateOf<String?>(null) }

    fun saveCloud() {
        env.scope.launch { SafetyStore.saveToCloud(context, env.uid) }
    }

    var afterPermission by remember { mutableStateOf<((Map<String, Boolean>) -> Unit)?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        afterPermission?.invoke(r)
        afterPermission = null
    }

    fun ask(list: List<String>, then: (Map<String, Boolean>) -> Unit) {
        val missing = list.filter { !has(context, it) }
        if (missing.isEmpty()) then(emptyMap()) else {
            afterPermission = then
            permissions.launch(missing.toTypedArray())
        }
    }

    fun notificationsPermission(): List<String> =
        if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Family safety", onBack = env.onBack)
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ---------- SOS ----------
            item {
                GlassCard(modifier = Modifier.fillMaxWidth(), onClick = { env.navigate("sos") }) {
                    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Warning, contentDescription = null, tint = Color(0xFFFF453A), modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("SOS", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text("5-second countdown, then SMS to your contacts and a family chat message. You can cancel.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            permissionNote?.let { n -> item { Hint(n) } }

            // ---------- Live location ----------
            item { SectionTitle("Live location") }
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    SwitchRow(
                        "Location features",
                        "Off by default. Lets you share live location and add your location to alerts.",
                        locationOn
                    ) { on ->
                        if (on) {
                            ask(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) {
                                if (LocationShareService.hasPermission(context)) {
                                    SafetyStore.setLocationOn(context, true)
                                    SafetySms.bestLocation(context)
                                    permissionNote = null
                                    saveCloud()
                                } else permissionNote = "Location permission was not allowed, so location features stay off. You can allow it in the phone's Settings → Apps → Family Hub → Permissions."
                            }
                        } else {
                            LocationShareService.stop(context)
                            SafetyStore.setLocationOn(context, false)
                            SafetyStore.clearLocation(context)
                            saveCloud()
                        }
                    }
                    if (locationOn) {
                        HorizontalDivider()
                        if (share != null) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Sharing your location", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "With " + share.viewerNames.ifEmpty { listOf("${share.viewerUids.size} members") }.joinToString(", ") +
                                        (share.endAt?.let { " until " + SafetyLogic.timeText(it, ZoneId.systemDefault()) } ?: " until you stop") +
                                        (if (!LocationShareService.running) ". Paused: open the app to continue." else "."),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Button(
                                    onClick = { LocationShareService.stop(context) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC20F0F))
                                ) { Text("Stop sharing") }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    if (others.isEmpty()) Toast.makeText(context, "No other approved family members yet.", Toast.LENGTH_SHORT).show()
                                    else sharePicker = true
                                }.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.MyLocation, contentDescription = null)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Share my live location…", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
            item {
                Hint(
                    "Updates at most once a minute while you move, and every 5 minutes when still. A notification stays " +
                        "while sharing. Only the people you choose can see it, not admins. Old positions are not kept: " +
                        "the shared place is deleted when sharing stops."
                )
            }
            item { SectionTitle("Shared with you") }
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        others.forEach { m ->
                            key(m.uid) { SharedLocationRow(env.familyId, m, now) { l -> openMap(context, l.lat, l.lng, m.name) } }
                        }
                        Text(
                            "Family members who share with you appear here, with when it was updated, its accuracy and their battery.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // ---------- Check-ins ----------
            item { SectionTitle("Check-ins") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = {
                        val loc = if (locationOn) SafetySms.bestLocation(context) else null
                        SafetyChat.post(env.familyId, env.uid, env.profile.name,
                            "✅ I'm safe." + (if (loc != null) " " + SafetyLogic.mapLink(loc.lat, loc.lng) else ""))
                        Toast.makeText(context, "Posted in the family chat.", Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.weight(1f)) { Text("I'm safe") }
                    OutlinedButton(onClick = {
                        if (others.isEmpty()) Toast.makeText(context, "No other approved family members yet.", Toast.LENGTH_SHORT).show()
                        else askCheckIn = true
                    }, modifier = Modifier.weight(1f)) { Text("Ask to check in") }
                }
            }
            item { Hint("Both post in the family group chat.") }

            // ---------- Safe arrival ----------
            item { SectionTitle("Safe-arrival timer") }
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (arrival != null) {
                            Text(
                                "Running" + (if (arrival.destination.isNotBlank()) " for ${arrival.destination}" else "") +
                                    ". Confirm by " + SafetyLogic.timeText(arrival.deadline, ZoneId.systemDefault()) + ".",
                                fontWeight = FontWeight.SemiBold
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    ArrivalTimer.confirm(context)
                                    SafetyChat.post(env.familyId, env.uid, env.profile.name, "✅ I arrived safely" +
                                        (if (arrival.destination.isNotBlank()) " at ${arrival.destination}." else "."))
                                }) { Text("I arrived") }
                                OutlinedButton(onClick = { ArrivalTimer.confirm(context) }) { Text("Cancel timer") }
                            }
                        } else {
                            var destination by remember { mutableStateOf("") }
                            var minutes by remember { mutableStateOf("30 min") }
                            TextInput("Where are you going? (optional)", destination, { destination = it.take(60) })
                            SegmentedControl(
                                options = listOf("15 min", "30 min", "1 hour", "2 hours"),
                                selected = minutes,
                                onSelect = { minutes = it }
                            )
                            Button(onClick = {
                                val m = when (minutes) { "15 min" -> 15; "1 hour" -> 60; "2 hours" -> 120; else -> 30 }
                                ask(notificationsPermission()) {
                                    ArrivalTimer.start(context, m, destination, env.familyId, env.uid, env.profile.name)
                                    Toast.makeText(context, "Timer started.", Toast.LENGTH_SHORT).show()
                                }
                            }) { Text("Start timer") }
                        }
                    }
                }
            }
            item {
                val arrivalCount = contacts.count { it.arrival }
                Hint(
                    "When time is up the phone asks if you arrived. With no answer in 5 minutes, " +
                        (if (arrivalCount > 0) "$arrivalCount contact" + (if (arrivalCount == 1) "" else "s") + " get an SMS and " else "") +
                        "the family chat is told."
                )
            }

            // ---------- Emergency contacts ----------
            item { SectionTitle("Emergency contacts") }
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        if (contacts.isEmpty()) {
                            Text("No contacts yet. They do not need the app: alerts reach them by SMS.",
                                modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                        contacts.forEachIndexed { i, c ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { editing = c }.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${i + 1}. ${c.name}", fontWeight = FontWeight.SemiBold)
                                    Text(listOf(c.relation, c.phone).filter { it.isNotBlank() }.joinToString(" • "), style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        listOfNotNull(if (c.sos) "SOS" else null, if (c.lowBattery) "Low battery" else null, if (c.arrival) "Safe arrival" else null)
                                            .ifEmpty { listOf("No alerts") }.joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                IconButton(onClick = { SafetyStore.saveContacts(context, SafetyLogic.move(contacts, i, i - 1)); saveCloud() }, enabled = i > 0) {
                                    Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Move up")
                                }
                                IconButton(onClick = { SafetyStore.saveContacts(context, SafetyLogic.move(contacts, i, i + 1)); saveCloud() }, enabled = i < contacts.size - 1) {
                                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Move down")
                                }
                                IconButton(onClick = { openDialer(context, c.phone) }) {
                                    Icon(Icons.Rounded.Call, contentDescription = "Call ${c.name}", tint = Color(0xFF34C759))
                                }
                            }
                        }
                        TextButton(
                            onClick = { editing = EmergencyContact(UUID.randomUUID().toString(), "", "") },
                            enabled = contacts.size < 10,
                            modifier = Modifier.padding(horizontal = 6.dp)
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Add contact")
                        }
                    }
                }
            }

            // ---------- Low battery ----------
            item { SectionTitle("Low-battery SMS") }
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    SwitchRow(
                        "Text my contacts when my battery is low",
                        "At ${threshold.toInt()}% and not charging. Once per low-battery episode, at most every 6 hours.",
                        lowOn
                    ) { on ->
                        if (on) {
                            ask(listOf(Manifest.permission.SEND_SMS) + notificationsPermission()) {
                                if (SafetySms.hasPermission(context)) {
                                    SafetyStore.setLowBattery(context, true, threshold.toInt())
                                    LowBatteryWorker.schedule(context)
                                    permissionNote = null
                                    saveCloud()
                                } else permissionNote = "SMS permission was not allowed, so low-battery SMS stays off. If Android says it is restricted, see Phone setup below."
                            }
                        } else {
                            SafetyStore.setLowBattery(context, false)
                            LowBatteryWorker.cancel(context)
                            saveCloud()
                        }
                    }
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                        Text("Alert level: ${threshold.toInt()}%", style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = threshold,
                            onValueChange = { threshold = (Math.round(it / 5f) * 5).toFloat() },
                            onValueChangeFinished = {
                                SafetyStore.setLowBattery(context, lowOn, threshold.toInt())
                                saveCloud()
                            },
                            valueRange = SafetyLogic.MIN_THRESHOLD.toFloat()..SafetyLogic.MAX_THRESHOLD.toFloat(),
                            steps = 8
                        )
                        val lowCount = contacts.count { it.lowBattery }
                        Text(
                            "Goes to $lowCount contact" + (if (lowCount == 1) "" else "s") + " from your SIM. Carrier SMS charges may apply." +
                                (if (lowOn && SafetySms.problem(context) != null) " Right now: " + SafetySms.problem(context) + "." else ""),
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = {
                            val to = contacts.filter { it.lowBattery }
                            if (to.isEmpty()) {
                                Toast.makeText(context, "Choose at least one contact for low battery.", Toast.LENGTH_SHORT).show()
                            } else {
                                val text = SafetyLogic.lowBatteryText(env.profile.name, threshold.toInt(), System.currentTimeMillis(),
                                    SafetySms.bestLocation(context), ZoneId.systemDefault())
                                SafetySms.send(context, "test", to, text, automatic = false, simulate = true)
                                Toast.makeText(context, "Test written to the alert log. No SMS was sent.", Toast.LENGTH_LONG).show()
                            }
                        }) { Text("Test (no SMS is sent)") }
                    }
                }
            }

            // ---------- Emergency card ----------
            item { SectionTitle("Emergency card") }
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    SwitchRow("Use an emergency card", "Kept private. Never sent to the AI assistant.", card.enabled) {
                        card = card.copy(enabled = it)
                        SafetyStore.saveCard(context, card)
                        saveCloud()
                    }
                    if (card.enabled) {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextInput("Blood group", card.bloodGroup, { card = card.copy(bloodGroup = it.take(10)) })
                            TextInput("Allergies", card.allergies, { card = card.copy(allergies = it.take(200)) })
                            TextInput("Medical notes", card.notes, { card = card.copy(notes = it.take(300)) }, singleLine = false)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    SafetyStore.saveCard(context, card)
                                    saveCloud()
                                    Toast.makeText(context, "Saved.", Toast.LENGTH_SHORT).show()
                                }) { Text("Save card") }
                            }
                        }
                        SwitchRow("Add it to SOS SMS", "Off unless you turn it on. Shared only with your SOS contacts.", card.includeInSms) {
                            card = card.copy(includeInSms = it)
                            SafetyStore.saveCard(context, card)
                            saveCloud()
                        }
                    }
                }
            }

            // ---------- Alert log ----------
            item { SectionTitle("Alert log (this phone)") }
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                        if (log.isEmpty()) Text("No alerts yet.", modifier = Modifier.padding(14.dp))
                        log.take(25).forEach { e ->
                            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
                                Text(
                                    when (e.kind) {
                                        "low_battery" -> "Low battery"
                                        "sos" -> "SOS"
                                        "arrival" -> "Safe arrival"
                                        else -> "Test"
                                    } + " → " + e.name,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(SafetyLogic.timeText(e.at, ZoneId.systemDefault()) + " · " + e.status,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (e.status.startsWith("Failed") || e.status.startsWith("Not sent")) Color(0xFFFF453A) else Color.Unspecified)
                            }
                        }
                        if (log.isNotEmpty()) TextButton(onClick = { SafetyStore.clearLog(context) }, modifier = Modifier.padding(horizontal = 6.dp)) {
                            Text("Clear log")
                        }
                    }
                }
            }
            item { Hint("\"Delivered\" appears only if your network reports it.") }

            // ---------- Phone setup and limits ----------
            item { SectionTitle("Phone setup") }
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "If Android says a permission is restricted (common for apps installed from an APK): open " +
                                "Settings → Apps → Family Hub, tap ⋮ at the top right, choose \"Allow restricted settings\", then allow SMS.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedButton(onClick = { openSettings(context, Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) }) { Text("Open app settings") }
                        Text(
                            "So low-battery checks are not delayed, set Family Hub's battery use to Unrestricted (Settings → Apps → Family Hub → Battery).",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedButton(onClick = { openSettings(context, Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, false) }) { Text("Battery optimisation") }
                        Text(
                            "Quick SOS: add the \"Family SOS\" tile to Quick Settings (swipe down twice, tap the pencil), or long-press " +
                                "the Family Hub icon and choose SOS. Both open this app's SOS screen.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("What these alerts cannot promise", fontWeight = FontWeight.Bold)
                        listOf(
                            "Nothing can be sent if the phone is off, the battery is dead, or there is no signal or SIM.",
                            "Android may delay the low-battery check by minutes or more in Doze or battery saver, and stops all of it if the app is force-stopped.",
                            "Live location pauses if Android closes the app or the phone restarts; it resumes when you open the app.",
                            "Shared location reaches family only over the internet; SMS contacts get one location link, not live tracking.",
                            "Fall or crash detection is not included: it cannot be made reliable here.",
                            "Emergency services are never contacted automatically."
                        ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }

    // ---------- dialogs ----------
    editing?.let { c ->
        ContactDialog(
            contact = c,
            isNew = contacts.none { it.id == c.id },
            onDismiss = { editing = null },
            onSave = { saved ->
                val list = if (contacts.any { it.id == saved.id }) contacts.map { if (it.id == saved.id) saved else it } else contacts + saved
                SafetyStore.saveContacts(context, list)
                saveCloud()
                editing = null
            },
            onDelete = {
                SafetyStore.saveContacts(context, contacts.filter { it.id != c.id })
                saveCloud()
                editing = null
            }
        )
    }

    if (sharePicker) {
        ShareDialog(
            members = others,
            onDismiss = { sharePicker = false },
            onStart = { chosen, endAfter ->
                sharePicker = false
                ask(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) + notificationsPermission()) {
                    if (!LocationShareService.hasPermission(context)) {
                        permissionNote = "Location permission is needed to share your location."
                    } else {
                        val t = System.currentTimeMillis()
                        LocationShareService.start(
                            context,
                            ShareState(env.familyId, env.uid, env.profile.name, chosen.map { it.uid }, chosen.map { it.name },
                                endAfter?.let { t + it }, t)
                        )
                    }
                }
            }
        )
    }

    if (askCheckIn) {
        AlertDialog(
            onDismissRequest = { askCheckIn = false },
            title = { Text("Ask someone to check in") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    others.forEach { m ->
                        Text(
                            m.name.ifBlank { "Member" },
                            modifier = Modifier.fillMaxWidth().clickable {
                                askCheckIn = false
                                val h = Mentions.handle(m.name)
                                SafetyChat.post(env.familyId, env.uid, env.profile.name,
                                    (if (h.isNotEmpty()) "@$h " else "") + "please check in: are you OK?")
                                Toast.makeText(context, "Asked in the family chat.", Toast.LENGTH_SHORT).show()
                            }.padding(vertical = 12.dp)
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { askCheckIn = false }) { Text("Close") } }
        )
    }
}

/** One family member's shared location, shown only while they share with me and it has not expired. */
@Composable
private fun SharedLocationRow(familyId: String, member: FamilyMember, now: Long, onOpen: (SharedLocation) -> Unit) {
    val flow = remember(familyId, member.uid) { LocationShares.observe(familyId, member.uid) }
    val loc by flow.collectAsState(initial = null)
    val l = loc ?: return
    val f = SafetyLogic.freshness(now, l.updatedAt, l.expiresAt)
    if (f == Freshness.EXPIRED) return
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onOpen(l) }.padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.MyLocation, contentDescription = null, tint = if (f == Freshness.LIVE) Color(0xFF34C759) else Color(0xFFFF9F0A))
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(member.name.ifBlank { l.name }, fontWeight = FontWeight.SemiBold)
            Text(
                listOfNotNull(
                    (if (f == Freshness.STALE) "Not updating · " else "") + "Updated " + SafetyLogic.agoText(now, l.updatedAt),
                    if (l.accuracyM > 0f) "±${l.accuracyM.toInt()} m" else null,
                    if (l.battery in 0..100) "Battery ${l.battery}%" else null,
                    if (l.endAt > 0L) "until " + SafetyLogic.timeText(l.endAt, ZoneId.systemDefault()).substringAfter(", ") else null
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Text("Map", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ContactDialog(
    contact: EmergencyContact,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (EmergencyContact) -> Unit,
    onDelete: () -> Unit
) {
    var name by remember { mutableStateOf(contact.name) }
    var phone by remember { mutableStateOf(contact.phone) }
    var relation by remember { mutableStateOf(contact.relation) }
    var sos by remember { mutableStateOf(contact.sos) }
    var low by remember { mutableStateOf(contact.lowBattery) }
    var arrival by remember { mutableStateOf(contact.arrival) }
    val cleaned = SafetyLogic.cleanPhone(phone)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Add emergency contact" else "Edit contact") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TextInput("Name", name, { name = it.take(60) })
                TextInput(
                    "Phone number", phone, { phone = it.take(20) }, keyboard = KeyboardType.Phone,
                    supporting = if (phone.isNotBlank() && cleaned == null) "Enter a valid phone number" else null
                )
                TextInput("Relation (optional)", relation, { relation = it.take(30) })
                Text("Send them:", style = MaterialTheme.typography.labelMedium)
                CheckRow("SOS alerts", sos) { sos = it }
                CheckRow("Low-battery alerts", low) { low = it }
                CheckRow("Safe-arrival alerts", arrival) { arrival = it }
                if (!isNew) TextButton(onClick = onDelete) {
                    Icon(Icons.Rounded.Delete, contentDescription = null, tint = Color(0xFFFF453A))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Remove contact", color = Color(0xFFFF453A))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && cleaned != null,
                onClick = {
                    onSave(contact.copy(name = name.trim(), phone = cleaned ?: phone, relation = relation.trim(),
                        sos = sos, lowBattery = low, arrival = arrival))
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ShareDialog(
    members: List<FamilyMember>,
    onDismiss: () -> Unit,
    onStart: (List<FamilyMember>, Long?) -> Unit
) {
    var chosen by remember { mutableStateOf(emptySet<String>()) }
    var duration by remember { mutableStateOf(SafetyLogic.SHARE_DURATIONS[1].first) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share live location") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Who can see it", style = MaterialTheme.typography.labelMedium)
                members.forEach { m ->
                    CheckRow(m.name.ifBlank { "Member" }, m.uid in chosen) { on ->
                        chosen = if (on) chosen + m.uid else chosen - m.uid
                    }
                }
                Text("For how long", style = MaterialTheme.typography.labelMedium)
                SegmentedControl(
                    options = SafetyLogic.SHARE_DURATIONS.map { it.first },
                    selected = duration,
                    onSelect = { duration = it }
                )
                Text("A notification stays on your phone while sharing, with a Stop button.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = chosen.isNotEmpty(), onClick = {
                onStart(members.filter { it.uid in chosen }, SafetyLogic.SHARE_DURATIONS.firstOrNull { it.first == duration }?.second)
            }) { Text("Start sharing") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
