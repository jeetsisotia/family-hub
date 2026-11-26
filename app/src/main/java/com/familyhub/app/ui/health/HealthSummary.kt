package com.familyhub.app.ui.health

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.familyhub.app.core.theme.GlassCard
import com.familyhub.app.data.remote.Rec
import com.familyhub.app.health.HealthConnectSync
import com.familyhub.app.health.StepTracker
import com.familyhub.app.ui.AppEnv
import com.familyhub.app.ui.common.ChoiceChips
import com.familyhub.app.ui.common.dayKey
import com.familyhub.app.ui.common.formatDate
import com.familyhub.app.ui.modules.DAY_MS
import com.familyhub.app.ui.modules.lng
import com.familyhub.app.ui.modules.num
import com.familyhub.app.ui.modules.startOfDay
import com.familyhub.app.ui.modules.str
import com.familyhub.app.ui.money.BarChart
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val MoveRed = Color(0xFFFA114F)
private val StepGreen = Color(0xFF34C759)
private val ExerciseBlue = Color(0xFF0A84FF)
private val SleepIndigo = Color(0xFF5E5CE6)

/** A value from Health Connect, else from what the person typed in; never made up. */
private data class Reading(val text: String, val source: String)

private fun manualVital(items: List<Rec>, kindStarts: String, since: Long): Pair<Double, Long>? =
    items.filter { it.str("type") == "vital" && it.str("kind").startsWith(kindStarts) && (it.lng("dateMillis") ?: 0L) >= since }
        .maxByOrNull { it.lng("dateMillis") ?: 0L }
        ?.let { r -> r.str("value").replace(",", ".").substringBefore('/').filter { it.isDigit() || it == '.' }.toDoubleOrNull()?.let { it to (r.lng("dateMillis") ?: 0L) } }

/** Three rings like a fitness watch: Move (active calories), Steps and Exercise minutes, plus today's numbers. */
@Composable
fun HealthToday(env: AppEnv, steps: Long, stepGoal: Int, refreshKey: Any?) {
    val context = LocalContext.current
    val fmt = remember { NumberFormat.getIntegerInstance() }
    val items = env.data["items"].orEmpty()
    val now = remember(refreshKey) { System.currentTimeMillis() }
    val today = remember(refreshKey) { HealthConnectSync.day(context, dayKey(now)) }
    val moveGoal = 400.0
    val exerciseGoal = 30
    val manualExercise = items.filter { it.str("type") == "workout" && (it.lng("dateMillis") ?: 0L) >= startOfDay(now) }
        .sumOf { (it.num("minutes") ?: 0.0) }.toInt()
    val exercise = today.exerciseMin ?: manualExercise.takeIf { it > 0 }
    val kcal = today.activeKcal

    // Sleep last night, heart rate, weight and BMI.
    val sleep: Reading? = today.sleepMin?.let { Reading("${it / 60}h ${it % 60}m", "Health Connect") }
        ?: manualVital(items, "Sleep", now - 2 * DAY_MS)?.let { (h, _) -> Reading("%.1f h".format(h), "your log") }
    val heart: Reading? = (today.restingHr ?: today.avgHr)?.let { Reading("$it bpm", if (today.restingHr != null) "resting" else "average") }
        ?: manualVital(items, "Heart", now - 7 * DAY_MS)?.let { (v, _) -> Reading("${v.toInt()} bpm", "your log") }
    val hcWeight = remember(refreshKey) { HealthConnectSync.latestWeight(context) }
    val logged = manualVital(items, "Weight", 0L)
    val weight: Pair<Double, Long>? = listOfNotNull(hcWeight, logged).maxByOrNull { it.second }
        ?: (env.userDoc["weightKg"] as? Number)?.toDouble()?.let { it to 0L }
    val heightCm = (env.userDoc["heightCm"] as? Number)?.toDouble()
    val bmi = if (weight != null && heightCm != null && heightCm > 0) weight.first / ((heightCm / 100) * (heightCm / 100)) else null

    GlassCard(modifier = Modifier.fillMaxWidth(), strong = true) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Today", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Rings(
                    listOf(
                        (kcal ?: 0.0) / moveGoal to MoveRed,
                        steps.toDouble() / stepGoal.coerceAtLeast(1) to StepGreen,
                        (exercise ?: 0).toDouble() / exerciseGoal to ExerciseBlue
                    ),
                    Modifier.size(120.dp)
                )
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    RingLegend(MoveRed, "Move", kcal?.let { "${it.toInt()} / ${moveGoal.toInt()} kcal" } ?: "— (from Health Connect)")
                    RingLegend(StepGreen, "Steps", "${fmt.format(steps)} / ${fmt.format(stepGoal)}")
                    RingLegend(ExerciseBlue, "Exercise", exercise?.let { "$it / $exerciseGoal min" } ?: "— (log a workout)")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile("Distance", today.distanceM?.let { "%.2f km".format(it / 1000) } ?: "—", today.distanceM?.let { "Health Connect" } ?: "no data", Modifier.weight(1f))
                Tile("Sleep", sleep?.text ?: "—", sleep?.source ?: "no data", Modifier.weight(1f))
                Tile("Heart", heart?.text ?: "—", heart?.source ?: "no data", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile(
                    "Weight", weight?.let { "%.1f kg".format(it.first) } ?: "—",
                    weight?.let { if (it.second > 0) formatDate(it.second) else "profile" } ?: "no data", Modifier.weight(1f)
                )
                Tile(
                    "BMI", bmi?.let { "%.1f".format(it) } ?: "—",
                    when {
                        bmi == null -> if (heightCm == null) "add height in profile" else "no weight"
                        bmi < 18.5 -> "below 18.5"
                        bmi < 25 -> "18.5 to 24.9"
                        bmi < 30 -> "25 to 29.9"
                        else -> "30 or more"
                    },
                    Modifier.weight(1f)
                )
                Tile("Water", "${StepTracker.water(context)} / ${StepTracker.waterGoal(context)}", "glasses", Modifier.weight(1f))
            }
            Text(
                "Numbers come from Health Connect (Google Fit, watches) or what you log here. BMI is a general guide, not a diagnosis.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun RingLegend(color: Color, label: String, value: String) {
    Column {
        Text(label, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Tile(label: String, value: String, note: String, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), modifier = modifier) {
        Column(Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), maxLines = 1)
            Text(value, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), maxLines = 1)
        }
    }
}

/** Concentric progress rings, outermost first. A ring past 100% keeps going round. */
@Composable
fun Rings(rings: List<Pair<Double, Color>>, modifier: Modifier = Modifier) {
    val still = com.familyhub.app.core.theme.reducedMotion()
    val progress = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(rings.map { it.first }) {
        if (still) progress.snapTo(1f) else { progress.snapTo(0f); progress.animateTo(1f, tween(700)) }
    }
    Canvas(modifier) {
        val stroke = size.minDimension / 9f
        rings.forEachIndexed { i, (fraction, color) ->
            val inset = stroke / 2 + i * (stroke + stroke * 0.18f)
            val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
            val topLeft = Offset(inset, inset)
            drawArc(color.copy(alpha = 0.18f), 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            val sweep = (360f * fraction.toFloat().coerceIn(0f, 2f) * progress.value)
            if (sweep > 0f) drawArc(color, -90f, sweep, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}

/** Steps, sleep and active calories over a week, a month or a year. */
@Composable
fun HealthTrends(stepGoal: Int, refreshKey: Any?) {
    val context = LocalContext.current
    var span by remember { mutableStateOf("Week") }
    var what by remember { mutableStateOf("Steps") }
    val now = remember(refreshKey) { System.currentTimeMillis() }
    val dayFmt = remember { SimpleDateFormat("d", Locale.getDefault()) }
    val wkFmt = remember { SimpleDateFormat("EE", Locale.getDefault()) }
    val data: List<Pair<String, Double>> = remember(span, what, refreshKey) {
        if (span == "Year") {
            if (what != "Steps") emptyList()
            else HealthConnectSync.monthlySteps(context).map { (k, v) ->
                SimpleDateFormat("MMM", Locale.getDefault()).format(SimpleDateFormat("yyyy-MM", Locale.US).parse(k) ?: Date()).take(1) to v.toDouble()
            }
        } else {
            val n = if (span == "Week") 7 else 30
            val steps = StepTracker.lastDays(context, n, now)
            steps.map { (t, s) ->
                val label = if (n == 7) wkFmt.format(Date(t)).take(2) else dayFmt.format(Date(t)).let { if (it == "1" || it.toInt() % 5 == 0) it else "" }
                val d = HealthConnectSync.day(context, dayKey(t + 12 * 3_600_000L))
                label to when (what) {
                    "Sleep" -> (d.sleepMin ?: 0) / 60.0
                    "Calories" -> d.activeKcal ?: 0.0
                    else -> s.toDouble()
                }
            }
        }
    }
    val total = data.sumOf { it.second }
    val counted = data.count { it.second > 0 }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Trends", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            ChoiceChips(listOf("Week", "Month", "Year"), span, { span = it.ifBlank { "Week" } })
            ChoiceChips(if (span == "Year") listOf("Steps") else listOf("Steps", "Sleep", "Calories"), what, { what = it.ifBlank { "Steps" } })
            if (data.isEmpty() || counted == 0) {
                Text(
                    if (span == "Year") "Connect Health Connect to see steps by month." else "No ${what.lowercase()} data for this period yet.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                BarChart(
                    labels = data.map { it.first },
                    values = data.map { it.second },
                    selected = data.size - 1,
                    color = when (what) { "Sleep" -> SleepIndigo; "Calories" -> MoveRed; else -> StepGreen },
                    goal = if (what == "Steps" && span != "Year") stepGoal.toDouble() else null
                )
                val avg = total / counted
                Text(
                    when (what) {
                        "Sleep" -> "Average %.1f h a night (nights with data)".format(avg)
                        "Calories" -> "Average ${avg.toInt()} active kcal a day"
                        else -> "Average ${NumberFormat.getIntegerInstance().format(avg.toLong())} steps a ${if (span == "Year") "month" else "day"}"
                    },
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}
