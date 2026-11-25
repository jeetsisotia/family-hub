package com.familyhub.app.ui.sos

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
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
import com.familyhub.app.ui.AppEnv
import com.familyhub.app.ui.common.ScreenTopBar
import com.familyhub.app.ui.common.SectionTitle

private data class Contact(val name: String, val relation: String, val phone: String)

@SuppressLint("MissingPermission")
private fun lastLocation(ctx: Context): Location? {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val fine = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val coarse = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!fine && !coarse) return null
    return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (e: Exception) { null } }
        .maxByOrNull { it.time }
}

private fun dial(ctx: Context, phone: String) {
    val clean = phone.filter { it.isDigit() || it == '+' }
    val canCall = ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
    try {
        ctx.startActivity(Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:$clean")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(ctx, "Could not start the call.", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Emergency help: one tap calls your emergency contact (or 112), and another shares a message with your
 * location by WhatsApp or SMS. Nothing is sent without you choosing the app and tapping send.
 */
@Composable
fun SosScreen(env: AppEnv) {
    val context = LocalContext.current
    val doc = env.userDoc
    val contacts = (1..2).mapNotNull { i ->
        val phone = (doc["emergency${i}Phone"] as? String).orEmpty()
        if (phone.isBlank()) null else Contact(
            (doc["emergency${i}Name"] as? String).orEmpty().ifBlank { "Emergency contact $i" },
            (doc["emergency${i}Relation"] as? String).orEmpty(),
            phone
        )
    } + env.members.filter { it.uid != env.uid && it.phone.isNotBlank() }.map { Contact(it.name, "Family", it.phone) }
    val primary = contacts.firstOrNull()

    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        afterPermission?.invoke()
        afterPermission = null
    }

    fun withPermissions(list: Array<String>, then: () -> Unit) {
        val missing = list.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) then() else {
            afterPermission = then
            permissions.launch(missing.toTypedArray())
        }
    }

    fun shareLocation(phone: String?) {
        withPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) {
            val loc = lastLocation(context)
            val name = env.profile.name.ifBlank { "I" }
            val text = buildString {
                append("SOS from $name via Family Hub: I need help.")
                if (loc != null) append(" My location: https://maps.google.com/?q=${loc.latitude},${loc.longitude}")
                else append(" (Location is not available right now.)")
            }
            val intent = if (phone != null) {
                val d = phone.filter { it.isDigit() }
                val wa = if (d.length == 10) "91$d" else d
                Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$wa?text=" + Uri.encode(text)))
            } else {
                Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Send SOS")
            }
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                context.startActivity(
                    Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Send SOS")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
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
                        .size(190.dp)
                        .clip(CircleShape)
                        .background(Brush.radialGradient(listOf(Color(0xFFFF453A), Color(0xFFC20F0F))))
                        .clickable {
                            withPermissions(arrayOf(Manifest.permission.CALL_PHONE)) { dial(context, primary?.phone ?: "112") }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Call, contentDescription = null, tint = Color.White, modifier = Modifier.size(44.dp))
                        Text("SOS", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
                        Text(
                            if (primary != null) "Call ${primary.name.substringBefore(' ')}" else "Call 112",
                            color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp
                        )
                    }
                }
            }
            item {
                Button(
                    onClick = { shareLocation(primary?.phone) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.MyLocation, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (primary != null) "Send my location to ${primary.name.substringBefore(' ')}" else "Send my location")
                }
            }
            item {
                OutlinedButton(onClick = { shareLocation(null) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Share, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Share SOS with anyone")
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
                                modifier = Modifier.fillMaxWidth().clickable { dial(context, num) }.padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                Text(what, modifier = Modifier.weight(1f))
                                Text(num, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.width(10.dp))
                                Icon(Icons.Rounded.Phone, contentDescription = "Call $num", tint = Color(0xFF34C759))
                            }
                        }
                    }
                }
            }
            if (contacts.isNotEmpty()) {
                item { SectionTitle("Your contacts", modifier = Modifier.fillMaxWidth()) }
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                            contacts.distinctBy { it.phone }.forEach { c ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(c.name, fontWeight = FontWeight.SemiBold)
                                        Text(listOf(c.relation, c.phone).filter { it.isNotBlank() }.joinToString(" • "),
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                    IconButton(onClick = { shareLocation(c.phone) }) {
                                        Icon(Icons.Rounded.MyLocation, contentDescription = "Send location to ${c.name}")
                                    }
                                    IconButton(onClick = { dial(context, c.phone) }) {
                                        Icon(Icons.Rounded.Call, contentDescription = "Call ${c.name}", tint = Color(0xFF34C759))
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth(), onClick = { env.navigate("profile") }) {
                        Text(
                            "Add emergency contacts in My Profile so SOS calls them first and can send them your location.",
                            modifier = Modifier.padding(14.dp), textAlign = TextAlign.Start
                        )
                    }
                }
            }
        }
    }
}
