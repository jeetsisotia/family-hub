package com.familyhub.app.ui.profile

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.familyhub.app.core.security.AppLock
import com.familyhub.app.core.theme.AccentCard
import com.familyhub.app.core.theme.GlassCard
import com.familyhub.app.data.model.UserProfile
import com.familyhub.app.data.remote.Rec
import com.familyhub.app.data.remote.RecordRepository
import com.familyhub.app.ui.common.*
import com.google.firebase.firestore.FieldValue

/** Text fields kept in the person's own (private) user document. */
private val TEXT_KEYS = listOf(
    "displayName", "gender", "bloodGroup", "phone", "altPhone", "address",
    "emergency1Name", "emergency1Relation", "emergency1Phone",
    "emergency2Name", "emergency2Relation", "emergency2Phone",
    "allergies", "conditions", "occupation", "healthInsurer",
    "idAadhaar", "idPan", "idVoter", "idPassport", "idDl", "idAbha", "idHealthPolicy"
)
private val NUMBER_KEYS = listOf("heightCm", "weightKg")
private val DATE_KEYS = listOf("dobMillis", "anniversaryMillis", "idPassportExpiry", "idDlExpiry")
private val SHARE_KEYS = mapOf(
    "sharePhoto" to true, "shareContact" to true, "shareDob" to true, "shareBlood" to true, "shareBody" to false
)

private val ID_FIELDS = listOf(
    Triple("idAadhaar", "Aadhaar number", KeyboardType.Number),
    Triple("idPan", "PAN", KeyboardType.Text),
    Triple("idVoter", "Voter ID (EPIC)", KeyboardType.Text),
    Triple("idPassport", "Passport number", KeyboardType.Text),
    Triple("idDl", "Driving licence", KeyboardType.Text),
    Triple("idAbha", "ABHA health ID", KeyboardType.Number),
    Triple("idHealthPolicy", "Health insurance policy no.", KeyboardType.Text)
)

private val BLOOD_GROUPS = listOf("A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-")

/** Shows only the last 4 characters, e.g. "XXXX XXXX 1234". */
fun maskId(v: String): String {
    val t = v.replace(" ", "")
    if (t.length <= 4) return if (t.isEmpty()) "" else "••••"
    return "•••• •••• " + t.takeLast(4)
}

@Composable
fun ProfileScreen(
    uid: String,
    profile: UserProfile,
    userDoc: Rec,
    familyIds: List<String>,
    repo: RecordRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val loaded = userDoc.isNotEmpty()

    // Editable copy; starts from what is saved and is written only when Save is tapped.
    val text = remember(loaded) {
        mutableStateMapOf<String, String>().also { m ->
            for (k in TEXT_KEYS) m[k] = (userDoc[k] as? String).orEmpty()
            if (m["displayName"].isNullOrBlank()) m["displayName"] = profile.name
            for (k in NUMBER_KEYS) m[k] = numberText(userDoc[k])
        }
    }
    val dates = remember(loaded) {
        mutableStateMapOf<String, Long?>().also { m -> for (k in DATE_KEYS) m[k] = (userDoc[k] as? Number)?.toLong() }
    }
    val share = remember(loaded) {
        mutableStateMapOf<String, Boolean>().also { m ->
            for ((k, def) in SHARE_KEYS) m[k] = (userDoc[k] as? Boolean) ?: def
        }
    }
    var photo by remember(loaded) { mutableStateOf((userDoc["photoData"] as? String).orEmpty()) }
    var photoThumb by remember(loaded) { mutableStateOf((userDoc["photoThumb"] as? String).orEmpty()) }
    var idsUnlocked by remember { mutableStateOf(false) }
    var appLock by remember { mutableStateOf(AppLock.isEnabled(context)) }
    var saving by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val big = PhotoCodec.encode(context, uri, 320, 80)
            val small = PhotoCodec.encode(context, uri, 96, 70)
            if (big != null && small != null) {
                photo = big
                photoThumb = small
            } else {
                Toast.makeText(context, "Could not read that picture.", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun save() {
        saving = true
        val data = HashMap<String, Any?>()
        for (k in TEXT_KEYS) data[k] = text[k].orEmpty().trim()
        for (k in NUMBER_KEYS) data[k] = parseNumber(text[k].orEmpty())
        for (k in DATE_KEYS) data[k] = dates[k]
        for (k in SHARE_KEYS.keys) data[k] = share[k] ?: false
        data["photoData"] = photo
        data["photoThumb"] = photoThumb
        val name = text["displayName"].orEmpty().trim()
        if (name.isNotBlank()) data["name"] = name

        // What the family sees on the member card. Anything not shared is removed.
        val card = HashMap<String, Any?>()
        if (name.isNotBlank()) card["name"] = name
        card["photoData"] = if (share["sharePhoto"] == true && photoThumb.isNotBlank()) photoThumb else FieldValue.delete()
        val contact = share["shareContact"] == true
        card["phone"] = if (contact) text["phone"].orEmpty().trim() else FieldValue.delete()
        card["emergencyName"] = if (contact) text["emergency1Name"].orEmpty().trim() else FieldValue.delete()
        card["emergencyPhone"] = if (contact) text["emergency1Phone"].orEmpty().trim() else FieldValue.delete()
        card["dobMillis"] = if (share["shareDob"] == true && dates["dobMillis"] != null) dates["dobMillis"] else FieldValue.delete()
        card["anniversaryMillis"] =
            if (share["shareDob"] == true && dates["anniversaryMillis"] != null) dates["anniversaryMillis"] else FieldValue.delete()
        card["bloodGroup"] = if (share["shareBlood"] == true) text["bloodGroup"].orEmpty() else FieldValue.delete()
        val body = share["shareBody"] == true
        card["heightCm"] = if (body && data["heightCm"] != null) data["heightCm"] else FieldValue.delete()
        card["weightKg"] = if (body && data["weightKg"] != null) data["weightKg"] else FieldValue.delete()

        repo.updateUserDoc(uid, data) { ok ->
            saving = false
            Toast.makeText(context, if (ok) "Profile saved" else "Saved on this phone; it will sync when online.", Toast.LENGTH_SHORT).show()
        }
        repo.updateMemberCards(uid, familyIds, card)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenTopBar(title = "My Profile", onBack = onBack) {
            TextButton(onClick = { save() }, enabled = !saving) { Text("Save", fontWeight = FontWeight.Bold) }
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                GlassCard(shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        PersonAvatar(
                            name = text["displayName"].orEmpty(),
                            photoData = photo,
                            photoUrl = profile.photoUrl,
                            size = 104.dp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(text["displayName"].orEmpty(), style = MaterialTheme.typography.titleLarge)
                        Text(
                            profile.email,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            FilledTonalButton(onClick = {
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }) {
                                Icon(Icons.Rounded.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (photo.isBlank()) "Add photo" else "Change photo")
                            }
                            if (photo.isNotBlank()) {
                                TextButton(onClick = { photo = ""; photoThumb = "" }) { Text("Remove") }
                            }
                        }
                    }
                }
            }

            item { SectionTitle("About you") }
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextInput("Name", text["displayName"].orEmpty(), { text["displayName"] = it })
                        DatePickField("Date of birth", dates["dobMillis"], { dates["dobMillis"] = it })
                        DatePickField("Anniversary", dates["anniversaryMillis"], { dates["anniversaryMillis"] = it })
                        TextInput("Occupation", text["occupation"].orEmpty(), { text["occupation"] = it })
                        Text("Gender", style = MaterialTheme.typography.labelLarge)
                        ChoiceChips(listOf("Female", "Male", "Other"), text["gender"].orEmpty(), { text["gender"] = it })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextInput(
                                "Height (cm)", text["heightCm"].orEmpty(), { text["heightCm"] = it },
                                modifier = Modifier.weight(1f), keyboard = KeyboardType.Decimal
                            )
                            TextInput(
                                "Weight (kg)", text["weightKg"].orEmpty(), { text["weightKg"] = it },
                                modifier = Modifier.weight(1f), keyboard = KeyboardType.Decimal
                            )
                        }
                        val h = parseNumber(text["heightCm"].orEmpty())
                        val w = parseNumber(text["weightKg"].orEmpty())
                        if (h != null && w != null && h > 50) {
                            val bmi = w / ((h / 100) * (h / 100))
                            val label = when {
                                bmi < 18.5 -> "underweight"
                                bmi < 23 -> "healthy for Indian adults"
                                bmi < 25 -> "overweight"
                                else -> "obese"
                            }
                            Text(
                                "BMI %.1f (%s)".format(bmi, label),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text("Blood group", style = MaterialTheme.typography.labelLarge)
                        ChoiceChips(BLOOD_GROUPS, text["bloodGroup"].orEmpty(), { text["bloodGroup"] = it })
                    }
                }
            }

            item { SectionTitle("Contact") }
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextInput("Mobile number", text["phone"].orEmpty(), { text["phone"] = it }, keyboard = KeyboardType.Phone)
                        TextInput("Other number", text["altPhone"].orEmpty(), { text["altPhone"] = it }, keyboard = KeyboardType.Phone)
                        TextInput("Address", text["address"].orEmpty(), { text["address"] = it }, singleLine = false)
                    }
                }
            }

            item { SectionTitle("Emergency contacts") }
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (n in 1..2) {
                            Text("Contact $n", style = MaterialTheme.typography.labelLarge)
                            TextInput("Name", text["emergency${n}Name"].orEmpty(), { text["emergency${n}Name"] = it })
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextInput(
                                    "Relation", text["emergency${n}Relation"].orEmpty(), { text["emergency${n}Relation"] = it },
                                    modifier = Modifier.weight(1f)
                                )
                                TextInput(
                                    "Phone", text["emergency${n}Phone"].orEmpty(), { text["emergency${n}Phone"] = it },
                                    modifier = Modifier.weight(1.3f), keyboard = KeyboardType.Phone
                                )
                            }
                        }
                    }
                }
            }

            item { SectionTitle("Health notes (only you)") }
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextInput("Allergies", text["allergies"].orEmpty(), { text["allergies"] = it }, singleLine = false)
                        TextInput("Conditions", text["conditions"].orEmpty(), { text["conditions"] = it }, singleLine = false)
                        TextInput("Health insurer (policy number goes in ID numbers)", text["healthInsurer"].orEmpty(), { text["healthInsurer"] = it })
                    }
                }
            }

            item { SectionTitle("ID numbers (only you)") }
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!idsUnlocked) {
                            for ((key, label, _) in ID_FIELDS) {
                                val v = text[key].orEmpty()
                                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        if (v.isBlank()) "Not added" else maskId(v),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (v.isBlank()) 0.45f else 0.85f)
                                    )
                                }
                            }
                            Button(
                                onClick = {
                                    AppLock.authenticate(
                                        context, "Unlock ID numbers", "Confirm it's you to view or edit",
                                        onSuccess = { idsUnlocked = true },
                                        onFail = { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Rounded.Fingerprint, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Unlock to view or edit")
                            }
                        } else {
                            for ((key, label, kb) in ID_FIELDS) {
                                TextInput(label, text[key].orEmpty(), { text[key] = it }, keyboard = kb)
                                if (key == "idPassport") {
                                    DatePickField("Passport expiry", dates["idPassportExpiry"], { dates["idPassportExpiry"] = it })
                                }
                                if (key == "idDl") {
                                    DatePickField("Licence expiry", dates["idDlExpiry"], { dates["idDlExpiry"] = it })
                                }
                            }
                            TextButton(onClick = { idsUnlocked = false }) {
                                Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Lock again")
                            }
                        }
                        Text(
                            "ID numbers are never shown to your family or sent to the AI assistant.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            item { SectionTitle("Share with my family") }
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                        ShareRow("Profile photo", "sharePhoto", share)
                        ShareRow("Phone and emergency contact", "shareContact", share)
                        ShareRow("Birthday and anniversary", "shareDob", share)
                        ShareRow("Blood group", "shareBlood", share)
                        ShareRow("Height and weight", "shareBody", share)
                    }
                }
            }

            item { SectionTitle("Security") }
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Lock Family Hub", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Ask for fingerprint or screen lock when the app opens",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        Switch(checked = appLock, onCheckedChange = { on ->
                            if (on && !AppLock.canUse(context)) {
                                Toast.makeText(context, "Set up a fingerprint or screen lock on this phone first.", Toast.LENGTH_LONG).show()
                            } else {
                                AppLock.setEnabled(context, on)
                                appLock = on
                            }
                        })
                    }
                }
            }

            item {
                AccentCard(onClick = { save() }, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().height(54.dp)) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (saving) "Saving..." else "Save profile", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareRow(label: String, key: String, share: MutableMap<String, Boolean>) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = share[key] == true, onCheckedChange = { share[key] = it })
    }
}
