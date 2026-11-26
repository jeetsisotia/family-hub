package com.familyhub.app.ui.sos

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.familyhub.app.core.theme.GlassCard
import com.familyhub.app.data.remote.ChatRepository
import com.familyhub.app.safety.LocationShareService
import com.familyhub.app.safety.SafetyChat
import com.familyhub.app.safety.SafetyLogic
import com.familyhub.app.safety.SafetySms
import com.familyhub.app.safety.SafetyStore
import com.familyhub.app.safety.ShareState
import com.familyhub.app.ui.AppEnv
import com.familyhub.app.ui.common.ScreenTopBar
import com.familyhub.app.ui.common.SectionTitle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId

/** Opens the phone's dialer with the number filled in. Family Hub never places a call by itself. */
internal fun openDialer(ctx: Context, phone: String) {
    val clean = phone.filter { it.isDigit() || it == '+' }
    try {
        ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$clean")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(ctx, "No phone app found.", Toast.LENGTH_SHORT).show()
    }
}

/** Opens the SMS app with the message and numbers filled in; the person taps Send. */
internal fun openSmsApp(ctx: Context, phones: List<String>, text: String) {
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + phones.joinToString(";")))
                .putExtra("sms_body", text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        Toast.makeText(ctx, "No SMS app found.", Toast.LENGTH_SHORT).show()
    }
}

private fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

/**
 * SOS: a large button with a 5-second countdown that can be cancelled. Then it sends an SMS from this phone to
 * the chosen emergency contacts, posts in the family chat, and (if location features are on) shares live
 * location with the family for 1 hour. Emergency services are never called or messaged automatically: the
 * 112 button opens the dialer.
 */
@Composable
fun SosScreen(env: AppEnv) {
    val context = LocalContext.current
    val tick by SafetyStore.tick
    LaunchedEffect(env.uid) {
        SafetyStore.setIdentity(context, env.uid, env.profile.name, env.familyId)
        SafetyStore.restoreFromCloud(context, env.uid)
        SafetyStore.importFromProfile(context, env.userDoc)
    }
    val contacts = remember(tick) { SafetyStore.contacts(context) }
    val sosContacts = contacts.filter { it.sos }
    val locationOn = remember(tick) { SafetyStore.locationOn(context) }
    var countdown by remember { mutableStateOf<Int?>(null) }
    var result by remember { mutableStateOf<List<String>?>(null) }
    var sentText by remember { mutableStateOf("") }

    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        afterPermission?.invoke()
        afterPermission = null
    }

    fun fire() {
        val now = System.currentTimeMillis()
        val name = env.profile.name
        val loc = SafetySms.bestLocation(context)
        val text = SafetyLogic.sosText(name, now, loc, SafetyStore.card(context), ZoneId.systemDefault())
        sentText = text
        val lines = mutableListOf<String>()
        if (sosContacts.isNotEmpty()) {
            val problem = SafetySms.problem(context)
            if (problem == null) {
                val n = SafetySms.send(context, "sos", sosContacts, text, automatic = false)
                lines += "SMS handed to your phone for $n of ${sosContacts.size} contacts. Results are in the alert log."
            } else {
                lines += "SMS could not be sent automatically ($problem). Your SMS app is open with the message: tap Send."
                openSmsApp(context, sosContacts.mapNotNull { SafetyLogic.cleanPhone(it.phone) }, text)
            }
        } else {
            lines += "No emergency contacts chosen for SOS, so no SMS was sent."
        }
        val share = locationOn && LocationShareService.hasPermission(context)
        val others = env.members.filter { it.uid != env.uid && it.status == "ACTIVE" }
        env.scope.launch {
            try { ChatRepository.ensureChat(env.familyId, ChatRepository.FAMILY_CHAT, emptyList(), env.uid) } catch (_: Exception) { }
            SafetyChat.post(
                env.familyId, env.uid, name,
                "🆘 SOS: I need help. Please call me." +
                    (if (loc != null) " Last location: " + SafetyLogic.mapLink(loc.lat, loc.lng) else "") +
                    (if (share && others.isNotEmpty()) " Live location is shared with the family for 1 hour (Family safety)." else "")
            )
        }
        lines += "Family chat message sent (it goes out as soon as there is internet)."
        if (share && others.isNotEmpty()) {
            if (SafetyStore.share(context) == null) {
                LocationShareService.start(
                    context,
                    ShareState(env.familyId, env.uid, name, others.map { it.uid }, others.map { it.name }, now + SafetyLogic.SOS_SHARE_MS, now)
                )
            }
            lines += "Live location shared with the family for 1 hour."
        } else if (!locationOn) {
            lines += "Live location not shared: location features are off (Family safety)."
        }
        result = lines
    }

    LaunchedEffect(countdown) {
        val c = countdown ?: return@LaunchedEffect
        if (c > 0) {
            delay(1000)
            if (countdown == c) countdown = c - 1
        } else {
            countdown = null
            fire()
        }
    }

    fun startCountdown() {
        val need = buildList {
            if (sosContacts.isNotEmpty() && context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) &&
                !granted(context, Manifest.permission.SEND_SMS)) add(Manifest.permission.SEND_SMS)
            if (locationOn && !LocationShareService.hasPermission(context)) {
                add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= 33 && !granted(context, Manifest.permission.POST_NOTIFICATIONS)) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        result = null
        if (need.isEmpty()) countdown = SafetyLogic.SOS_COUNTDOWN_S
        else {
            afterPermission = { countdown = SafetyLogic.SOS_COUNTDOWN_S }
            permissions.launch(need.toTypedArray())
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenTopBar(title = "SOS", onBack = env.onBack)
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .clip(CircleShape)
                        .background(Brush.radialGradient(listOf(Color(0xFFFF453A), Color(0xFFC20F0F))))
                        .clickable { if (countdown == null) startCountdown() else countdown = null },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Warning, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
                        Text(countdown?.let { "$it" } ?: "SOS", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Black)
                        Text(
                            if (countdown != null) "Sending alert… tap to cancel" else "Tap to alert your contacts",
                            color = Color.White.copy(alpha = 0.92f), fontSize = 13.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 18.dp)
                        )
                    }
                }
            }
            if (countdown != null) {
                item {
                    Button(onClick = { countdown = null }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                }
            }
            item {
                Text(
                    "After a ${SafetyLogic.SOS_COUNTDOWN_S}-second countdown: SMS to ${sosContacts.size} contact" +
                        (if (sosContacts.size == 1) "" else "s") + " from your SIM (carrier charges may apply), a family chat message" +
                        (if (locationOn) ", and live location for 1 hour." else ". Turn on location features in Family safety to add your location.") +
                        " Emergency services are not contacted automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }
            result?.let { lines ->
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth(), strong = true) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Alert sent", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            lines.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { env.navigate("safety") }) { Text("Alert log") }
                                OutlinedButton(onClick = {
                                    SafetyChat.post(env.familyId, env.uid, env.profile.name, "✅ I'm safe now.")
                                    result = null
                                    Toast.makeText(context, "Family told you are safe.", Toast.LENGTH_SHORT).show()
                                }) { Text("I'm safe now") }
                            }
                        }
                    }
                }
            }
            item {
                Button(
                    onClick = { openDialer(context, "112") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC20F0F)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Call, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Call 112 (opens the dialer)")
                }
            }
            if (sosContacts.isNotEmpty()) {
                item { SectionTitle("Call a contact", modifier = Modifier.fillMaxWidth()) }
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                            sosContacts.forEach { c ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().clickable { openDialer(context, c.phone) }
                                        .padding(horizontal = 14.dp, vertical = 10.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(c.name, fontWeight = FontWeight.SemiBold)
                                        Text(listOf(c.relation, c.phone).filter { it.isNotBlank() }.joinToString(" • "),
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                    Icon(Icons.Rounded.Call, contentDescription = "Call ${c.name}", tint = Color(0xFF34C759))
                                }
                            }
                        }
                    }
                }
            } else {
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth(), onClick = { env.navigate("safety") }) {
                        Text(
                            "Add emergency contacts in Family safety. They do not need the app: SOS reaches them by SMS.",
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
            }
            item {
                OutlinedButton(onClick = {
                    val loc = SafetySms.bestLocation(context)
                    val text = sentText.ifBlank { SafetyLogic.sosText(env.profile.name, System.currentTimeMillis(), loc, null, ZoneId.systemDefault()) }
                    try {
                        context.startActivity(
                            Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Send SOS")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Exception) { }
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Share, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Share SOS by another app (you tap send)")
                }
            }
            item {
                OutlinedButton(onClick = { env.navigate("safety") }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Shield, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Family safety: contacts, location, alerts")
                }
            }
            item { SectionTitle("India emergency numbers", modifier = Modifier.fillMaxWidth()) }
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                        listOf(
                            "112" to "All emergencies", "100" to "Police", "102" to "Ambulance", "101" to "Fire",
                            "1091" to "Women helpline", "1098" to "Child helpline"
                        ).forEach { (num, what) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { openDialer(context, num) }.padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                Text(what, modifier = Modifier.weight(1f))
                                Text(num, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.width(10.dp))
                                Icon(Icons.Rounded.Phone, contentDescription = "Dial $num", tint = Color(0xFF34C759))
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "Numbers open in your dialer; you press call. Alerts cannot be promised if the phone is off, the " +
                        "battery is dead, there is no signal, or a permission is off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
