package com.familyhub.app.ui.health

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.familyhub.app.core.theme.GlassCard
import com.familyhub.app.core.theme.tileBrush
import com.familyhub.app.data.remote.Rec
import com.familyhub.app.health.StepService
import com.familyhub.app.health.StepTracker
import com.familyhub.app.ui.AppEnv
import com.familyhub.app.ui.common.*
import com.familyhub.app.ui.modules.DAY_MS
import com.familyhub.app.ui.modules.lng
import com.familyhub.app.ui.modules.num
import com.familyhub.app.ui.modules.startOfDay
import com.familyhub.app.ui.modules.str
import com.familyhub.app.ui.money.BarChart
import com.familyhub.app.ui.money.HBar
import com.familyhub.app.ui.money.ProgressRing
import com.familyhub.app.ui.money.chartColor
import com.familyhub.app.ui.stringList
import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.launch
import java.text.NumberFormat

private val GOALS = listOf(5000, 8000, 10000, 12000)

/** One dose time of one medicine today. */
private data class Dose(val med: Rec, val hour: Int, val minute: Int, val slot: String, val taken: Boolean)

private fun habitStreak(days: List<String>, now: Long = System.currentTimeMillis()): Int {
    val set = days.toSet()
    var d = startOfDay(now)
    if (dayKey(d) !in set) d -= DAY_MS // today not ticked yet: count up to yesterday
    var n = 0
    while (dayKey(d) in set) {
        n++
        d -= DAY_MS
    }
    return n
}

@Composable
fun HealthScreen(env: AppEnv) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fmt = remember { NumberFormat.getIntegerInstance() }
    val items = env.data["items"].orEmpty()
    val now = remember { System.currentTimeMillis() }
    val today = dayKey(now)

    val supported = remember { StepTracker.isSupported(context) }
    var enabled by remember { mutableStateOf(StepTracker.isEnabled(context)) }
    var goal by remember { mutableStateOf(StepTracker.goal(context)) }
    var share by remember { mutableStateOf(StepTracker.shareWithFamily(context)) }
    val steps by StepTracker.todayFlow.collectAsState()
    var water by remember { mutableStateOf(StepTracker.water(context)) }
    LaunchedEffect(Unit) {
        StepTracker.today(context)
        StepTracker.setFamilies(context, env.familyIds)
    }
    val week = remember(steps) { StepTracker.lastDays(context, 7) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            StepTracker.setEnabled(context, true)
            enabled = true
        } else {
            Toast.makeText(context, "Allow \"Physical activity\" to count steps.", Toast.LENGTH_LONG).show()
        }
    }

    fun turnOnSteps() {
        if (Build.VERSION.SDK_INT >= 29 && !StepTracker.hasPermission(context)) {
            permission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        } else {
            StepTracker.setEnabled(context, true)
            enabled = true
        }
    }

    // Body numbers from the profile, for distance, calories and BMI.
    val heightCm = (env.userDoc["heightCm"] as? Number)?.toDouble()
    val weightKg = (env.userDoc["weightKg"] as? Number)?.toDouble()
    val strideM = (heightCm ?: 165.0) * 0.415 / 100.0
    val km = steps * strideM / 1000.0
    val kcal = steps * 0.04 * ((weightKg ?: 65.0) / 65.0)

    // Medicines due today, for anyone in the family whose medicine you can see.
    val meds = items.filter { it.str("type") == "medicine" }
    val todayStart = startOfDay(now)
    val doses = meds.flatMap { m ->
        val start = m.lng("startMillis")?.let { startOfDay(it) }
        val end = m.lng("endMillis")?.let { startOfDay(it) }
        if ((start != null && todayStart < start) || (end != null && todayStart > end)) return@flatMap emptyList<Dose>()
        val taken = m["taken"] as? Map<*, *>
        parseTimes(m.str("times")).map { (h, mi) ->
            val slot = today + "_" + "%02d%02d".format(h, mi)
            Dose(m, h, mi, slot, taken?.get(slot) == true)
        }
    }.sortedBy { it.hour * 60 + it.minute }

    val periods = items.filter { it.str("type") == "period" && it["_private"] == true }
    val prediction = PeriodMath.predict(periods, now)
    val showPeriod = periods.isNotEmpty() || env.userDoc["gender"] == "Female"

    val vitals = items.filter { it.str("type") == "vital" }.sortedByDescending { it.lng("dateMillis") ?: 0L }
    val latestVitals = vitals.groupBy { it.str("kind") }.mapValues { it.value.first() }.toList().take(6)
    val weightSeries = vitals.filter { it.str("kind").startsWith("Weight") }
        .mapNotNull { r -> r.str("value").replace(",", ".").filter { it.isDigit() || it == '.' }.toDoubleOrNull()?.let { r to it } }
        .take(10).reversed()

    val habits = items.filter { it.str("type") == "habit" }
    val weekAgo = todayStart - 6 * DAY_MS
    val workouts = items.filter { it.str("type") == "workout" && (it.lng("dateMillis") ?: 0L) >= weekAgo }
    val activeMinutes = workouts.sumOf { (it.num("minutes") ?: 0.0) }.toInt()
    val healthMembers = env.members.filter {
        it.status == "ACTIVE" && (it.bloodGroup.isNotBlank() || (it.heightCm != null && it.weightKg != null) || it.stepsWeek != null)
    }
    val board = env.members.filter { it.stepsDate == today && (it.stepsToday ?: 0L) > 0 }
        .sortedByDescending { it.stepsToday ?: 0L }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Health & Fitness", onBack = env.onBack)
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Steps
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (!supported) {
                            Text("This phone has no step sensor, so steps cannot be counted here.", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            ProgressRing(fraction = steps.toFloat() / goal.coerceAtLeast(1)) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Rounded.DirectionsWalk, contentDescription = null, tint = Color(0xFF34C759))
                                    Text(fmt.format(steps), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                    Text("of ${fmt.format(goal)}", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                                Metric("%.1f km".format(km), "Distance")
                                Metric("%.0f".format(kcal), "kcal")
                                Metric(fmt.format(week.sumOf { it.second }), "This week")
                            }
                            if (!enabled) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Button(onClick = { turnOnSteps() }) { Text("Turn on step counting") }
                                Text(
                                    "Uses the phone's own step sensor. A small notification shows while counting.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
            if (supported) {
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Last 7 days", style = MaterialTheme.typography.labelLarge)
                            BarChart(
                                labels = week.map { shortDate(it.first).take(2) },
                                values = week.map { it.second.toDouble() },
                                selected = week.size - 1,
                                color = Color(0xFF34C759),
                                goal = goal.toDouble()
                            )
                            Text("Daily goal", style = MaterialTheme.typography.labelLarge)
                            ChoiceChips(GOALS.map { fmt.format(it) }, fmt.format(goal), { s ->
                                val g = s.filter { it.isDigit() }.toIntOrNull() ?: return@ChoiceChips
                                StepTracker.setGoal(context, g)
                                goal = g
                            })
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Share steps with family", fontWeight = FontWeight.Medium)
                                    Text("Only today's and this week's step totals are shared.", style = MaterialTheme.typography.labelSmall)
                                }
                                Switch(checked = share, onCheckedChange = {
                                    share = it
                                    StepTracker.setShare(context, it)
                                    if (it) StepService.sync(context)
                                    else env.repo.updateMemberCards(
                                        env.uid, env.familyIds,
                                        mapOf("stepsToday" to FieldValue.delete(), "stepsDate" to FieldValue.delete(), "stepsWeek" to FieldValue.delete())
                                    )
                                })
                            }
                            if (enabled) {
                                TextButton(onClick = { StepTracker.setEnabled(context, false); enabled = false }) {
                                    Text("Pause step counting")
                                }
                            }
                        }
                    }
                }
            }

            // Family comparison
            if (board.isNotEmpty()) {
                item { SectionTitle("Family steps today") }
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            val max = (board.first().stepsToday ?: 1L).toDouble()
                            board.forEachIndexed { i, m ->
                                val medal = when (i) { 0 -> "🥇 "; 1 -> "🥈 "; 2 -> "🥉 "; else -> "" }
                                HBar(
                                    medal + m.name + if (m.uid == env.uid) " (you)" else "",
                                    (m.stepsToday ?: 0L).toDouble(), max,
                                    fmt.format(m.stepsToday ?: 0L) + (m.stepsWeek?.let { " • week " + fmt.format(it) } ?: ""),
                                    chartColor(i + 1)
                                )
                            }
                        }
                    }
                }
            }

            // Workouts
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Workouts this week", modifier = Modifier.weight(1f))
                    TextButton(onClick = { env.navigate("workouts") }) { Text("Log") }
                }
            }
            item {
                GlassCard(modifier = Modifier.fillMaxWidth(), onClick = { env.navigate("workouts") }) {
                    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.FitnessCenter, contentDescription = null, tint = Color(0xFFFF9500))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("$activeMinutes active minutes • ${workouts.size} workout(s)", fontWeight = FontWeight.SemiBold)
                            LinearProgressIndicator(
                                progress = { (activeMinutes / 150f).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                color = Color(0xFFFF9500)
                            )
                            Text(
                                "WHO suggests at least 150 minutes of activity a week.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }

            // Water
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.WaterDrop, contentDescription = null, tint = Color(0xFF0A84FF))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Water: $water of 8 glasses", fontWeight = FontWeight.SemiBold)
                            LinearProgressIndicator(
                                progress = { (water / 8f).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                color = Color(0xFF0A84FF)
                            )
                        }
                        IconButton(onClick = { water = StepTracker.addWater(context, -1); saveWater(env, water) }) {
                            Icon(Icons.Rounded.Remove, contentDescription = "One glass less")
                        }
                        FilledIconButton(onClick = { water = StepTracker.addWater(context, 1); saveWater(env, water) }) {
                            Icon(Icons.Rounded.Add, contentDescription = "One more glass")
                        }
                    }
                }
            }

            // Medicines
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Medicines today", modifier = Modifier.weight(1f))
                    TextButton(onClick = { env.navigate("medicines") }) { Text(if (meds.isEmpty()) "Add" else "Manage") }
                }
            }
            if (doses.isEmpty()) {
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (meds.isEmpty()) "Add medicines with their times and you'll get a reminder at each time, with a Taken button."
                            else "No doses due today.",
                            modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                            doses.forEach { d ->
                                val late = !d.taken && atTime(todayStart, d.hour, d.minute) < System.currentTimeMillis() - 30 * 60_000L
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        scope.launch {
                                            try {
                                                env.repo.setFieldScoped(env.familyId, env.uid, "items", d.med, "taken.${d.slot}", !d.taken)
                                                val stock = d.med.num("stock")
                                                if (stock != null) {
                                                    env.repo.setFieldScoped(env.familyId, env.uid, "items", d.med, "stock",
                                                        FieldValue.increment(if (d.taken) 1 else -1))
                                                }
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "Could not save. Check the internet.", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }.padding(horizontal = 8.dp)
                                ) {
                                    Checkbox(checked = d.taken, onCheckedChange = null)
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(d.med.str("title"), fontWeight = FontWeight.SemiBold)
                                        Text(
                                            listOf(timeLabel(d.hour, d.minute), d.med.str("dose"), d.med.str("food"), d.med.str("memberName"))
                                                .filter { it.isNotBlank() }.joinToString(" • "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (late) Color(0xFFFF3B30) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                        )
                                    }
                                    val stock = d.med.num("stock")
                                    if (stock != null && stock <= 5) {
                                        Text("${stock.toInt()} left", color = Color(0xFFFF9500), style = MaterialTheme.typography.labelSmall)
                                    } else if (late) {
                                        Text("Missed?", color = Color(0xFFFF3B30), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Period
            if (showPeriod) {
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth(), onClick = { env.navigate("periods") }) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(0xFFFF2D55).copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Rounded.WaterDrop, contentDescription = null, tint = Color(0xFFFF2D55)) }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Period tracker", fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (prediction == null) "Log your last period to see predictions."
                                    else {
                                        val inDays = ((prediction.nextStart - todayStart) / DAY_MS).toInt()
                                        when {
                                            inDays > 1 -> "Next period in $inDays days (${shortDate(prediction.nextStart)})"
                                            inDays == 1 -> "Next period expected tomorrow"
                                            inDays == 0 -> "Period expected today"
                                            else -> "Period is ${-inDays} day(s) late"
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                        }
                    }
                }
            }

            // Vitals
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Vitals", modifier = Modifier.weight(1f))
                    TextButton(onClick = { env.navigate("vitals") }) { Text("Log") }
                }
            }
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (heightCm != null && weightKg != null && heightCm > 50) {
                            val bmi = weightKg / ((heightCm / 100) * (heightCm / 100))
                            val label = when {
                                bmi < 18.5 -> "underweight"
                                bmi < 23 -> "healthy for Asian adults"
                                bmi < 25 -> "overweight range for Asian adults"
                                else -> "obese range for Asian adults"
                            }
                            Text("BMI %.1f — %s".format(bmi, label), fontWeight = FontWeight.SemiBold)
                        }
                        if (latestVitals.isEmpty()) {
                            Text("No readings yet. Log weight, blood pressure, sugar and more.", style = MaterialTheme.typography.bodyMedium)
                        }
                        latestVitals.forEach { (kind, r) ->
                            Row {
                                Text(kind, modifier = Modifier.weight(1f))
                                Text(r.str("value"), fontWeight = FontWeight.SemiBold)
                                Text("  " + shortDate(r.lng("dateMillis") ?: 0L), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (weightSeries.size >= 2) {
                            Text("Weight trend", style = MaterialTheme.typography.labelLarge)
                            BarChart(
                                labels = weightSeries.map { shortDate(it.first.lng("dateMillis") ?: 0L).take(6) },
                                values = weightSeries.map { it.second },
                                selected = weightSeries.size - 1,
                                height = 120.dp,
                                color = Color(0xFFAF52DE),
                                goal = null
                            )
                        }
                    }
                }
            }

            // Family health comparison (only what each person chose to share)
            if (healthMembers.size > 1) {
                item { SectionTitle("Family health") }
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row {
                                Text("Member", modifier = Modifier.weight(1.4f), style = MaterialTheme.typography.labelSmall)
                                Text("Blood", modifier = Modifier.weight(0.7f), style = MaterialTheme.typography.labelSmall)
                                Text("BMI", modifier = Modifier.weight(0.7f), style = MaterialTheme.typography.labelSmall)
                                Text("Steps (week)", modifier = Modifier.weight(1.1f), style = MaterialTheme.typography.labelSmall)
                            }
                            healthMembers.forEach { m ->
                                val h = m.heightCm
                                val w = m.weightKg
                                val bmi = if (h != null && w != null && h > 50) w / ((h / 100) * (h / 100)) else null
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(m.name.substringBefore(' ') + if (m.uid == env.uid) " (you)" else "",
                                        modifier = Modifier.weight(1.4f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(m.bloodGroup.ifBlank { "–" }, modifier = Modifier.weight(0.7f), fontWeight = FontWeight.SemiBold)
                                    Text(
                                        bmi?.let { "%.1f".format(it) } ?: "–",
                                        modifier = Modifier.weight(0.7f),
                                        color = when {
                                            bmi == null -> MaterialTheme.colorScheme.onSurface
                                            bmi < 18.5 || bmi >= 25 -> Color(0xFFFF3B30)
                                            bmi >= 23 -> Color(0xFFFF9500)
                                            else -> Color(0xFF34C759)
                                        }
                                    )
                                    Text(m.stepsWeek?.let { fmt.format(it) } ?: "–", modifier = Modifier.weight(1.1f))
                                }
                            }
                            Text(
                                "Each person chooses what to share in My Profile.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }

            // Habits
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Daily habits", modifier = Modifier.weight(1f))
                    TextButton(onClick = { env.navigate("habits") }) { Text(if (habits.isEmpty()) "Add" else "Manage") }
                }
            }
            if (habits.isEmpty()) {
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text("Add habits like a morning walk, 8 glasses of water or no sugar, then tick them off each day.",
                            modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                            habits.forEach { h ->
                                val days = h.stringList("doneDays")
                                val done = today in days
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        scope.launch {
                                            try {
                                                env.repo.setFieldScoped(
                                                    env.familyId, env.uid, "items", h, "doneDays",
                                                    if (done) FieldValue.arrayRemove(today) else FieldValue.arrayUnion(today)
                                                )
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "Could not save. Check the internet.", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }.padding(horizontal = 8.dp)
                                ) {
                                    Checkbox(checked = done, onCheckedChange = null)
                                    Text(h.str("title"), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val streak = habitStreak(days)
                                    if (streak > 0) Text("🔥 $streak", fontWeight = FontWeight.SemiBold)
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                            }
                        }
                    }
                }
            }

            // Shortcuts
            item { SectionTitle("More") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val links: List<Triple<String, String, ImageVector>> = listOf(
                        Triple("medicines", "Medicines", Icons.Rounded.Medication),
                        Triple("reminders", "Reminders", Icons.Rounded.Alarm),
                        Triple("vitals", "Vitals", Icons.Rounded.MonitorHeart),
                        Triple("workouts", "Workouts", Icons.Rounded.FitnessCenter),
                        Triple("habits", "Habits", Icons.Rounded.SelfImprovement),
                        Triple("periods", "Periods", Icons.Rounded.WaterDrop),
                        Triple("health", "Records", Icons.Rounded.MedicalServices),
                        Triple("emergency", "Emergency", Icons.Rounded.LocalHospital)
                    )
                    items(links) { (route, label, icon) ->
                        val i = links.indexOfFirst { it.first == route }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.width(72.dp).clickable { env.navigate(route) }
                        ) {
                            Box(
                                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(tileBrush(i + 2)),
                                contentAlignment = Alignment.Center
                            ) { Icon(icon, contentDescription = null, tint = Color.White) }
                            Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp), maxLines = 1)
                        }
                    }
                }
            }
            item {
                Text(
                    "Health, medicine, period and vitals entries are \"Only me\" unless you choose Family. They are never sent to the AI assistant.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
        }
    }
}

private fun saveWater(env: AppEnv, glasses: Int) {
    val day = dayKey()
    env.repo.putPrivate(env.uid, "activity", day, mapOf("day" to day, "water" to glasses))
}

@Composable
private fun Metric(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}
