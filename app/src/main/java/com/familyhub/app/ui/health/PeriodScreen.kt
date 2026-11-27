package com.familyhub.app.ui.health

import com.familyhub.app.core.theme.GlassDialog
import com.familyhub.app.core.theme.GlassFilterChip
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.familyhub.app.core.theme.GlassCard
import com.familyhub.app.data.remote.Rec
import com.familyhub.app.ui.AppEnv
import com.familyhub.app.ui.common.*
import com.familyhub.app.ui.modules.DAY_MS
import com.familyhub.app.ui.modules.lng
import com.familyhub.app.ui.modules.noonToday
import com.familyhub.app.ui.modules.startOfDay
import com.familyhub.app.ui.modules.str
import com.familyhub.app.ui.stringList
import kotlinx.coroutines.launch
import java.util.Calendar

private val PeriodRed = Color(0xFFFF2D55)
private val FertileTeal = Color(0xFF30B0C7)
private val OvulationPurple = Color(0xFFAF52DE)

private val FLOWS = listOf("Light", "Medium", "Heavy")
private val SYMPTOMS = listOf("Cramps", "Headache", "Mood swings", "Bloating", "Back pain", "Tired", "Acne", "Cravings")

/**
 * Period tracker. Entries are always "Only me". Predictions use the average of your recent cycles;
 * they are an estimate, not medical advice or birth control.
 */
@Composable
fun PeriodScreen(env: AppEnv) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val now = remember { System.currentTimeMillis() }
    val today = startOfDay(now)
    val periods = env.data["items"].orEmpty()
        .filter { it.str("type") == "period" && it["_private"] == true }
        .sortedByDescending { it.lng("startMillis") ?: 0L }
    val p = PeriodMath.predict(periods, now)

    // The period happening now, if any (started within the last 12 days and not ended before today).
    val current = periods.firstOrNull { r ->
        val s = r.lng("startMillis") ?: return@firstOrNull false
        val e = r.lng("endMillis")
        startOfDay(s) <= today && today - startOfDay(s) < 12 * DAY_MS && (e == null || startOfDay(e) >= today)
    }

    var monthOffset by rememberSaveable { mutableStateOf(0) }
    var pastStart by remember { mutableStateOf<Long?>(null) }
    var pastEnd by remember { mutableStateOf<Long?>(null) }
    var confirmDelete by remember { mutableStateOf<Rec?>(null) }

    fun save(existing: Rec?, data: Map<String, Any?>, done: String) {
        scope.launch {
            try {
                env.repo.saveScoped(env.familyId, env.uid, "items", existing, data, true)
                Toast.makeText(context, done, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Could not save. Check the internet.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Period Tracker", onBack = env.onBack)
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (p == null) {
                            Text("Log your last period", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "Tap \"Period started today\" when it starts, or add a past period below. " +
                                    "After two or more periods the predictions get better.",
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        } else {
                            val inDays = ((p.nextStart - today) / DAY_MS).toInt()
                            if (current != null) {
                                val day = ((today - startOfDay(current.lng("startMillis") ?: today)) / DAY_MS).toInt() + 1
                                Text("Period day $day", style = MaterialTheme.typography.headlineSmall, color = PeriodRed, fontWeight = FontWeight.Bold)
                            } else {
                                Text(
                                    when {
                                        inDays > 1 -> "Period in $inDays days"
                                        inDays == 1 -> "Period expected tomorrow"
                                        inDays == 0 -> "Period expected today"
                                        else -> "Period " + com.familyhub.app.ui.common.plural(-inDays, "day") + " late"
                                    },
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = PeriodRed,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text("Cycle day ${p.cycleDay}", style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                Fact(shortDate(p.nextStart), "Next period")
                                Fact("${shortDate(p.fertileStart)}–${shortDate(p.fertileEnd)}", "Fertile window")
                                Fact(shortDate(p.ovulation), "Ovulation")
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Average cycle ${p.avgCycle} days • period ${p.avgLength} days • ${p.loggedCycles} logged",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (current == null) {
                                Button(
                                    onClick = {
                                        save(null, mapOf("type" to "period", "title" to "Period", "startMillis" to noonToday(),
                                            "endMillis" to null, "flow" to "", "symptoms" to emptyList<String>()), "Period started today")
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = PeriodRed)
                                ) { Text("Period started today") }
                            } else {
                                OutlinedButton(onClick = {
                                    save(current, mapOf("endMillis" to noonToday()), "Period ended today")
                                }) { Text("Period ended today") }
                            }
                        }
                    }
                }
            }

            if (current != null) {
                item { SectionTitle("Today") }
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Flow", style = MaterialTheme.typography.labelLarge)
                            ChoiceChips(FLOWS, current.str("flow"), { f -> save(current, mapOf("flow" to f), "Saved") })
                            Text("Symptoms", style = MaterialTheme.typography.labelLarge)
                            val chosen = current.stringList("symptoms")
                            SymptomChips(chosen) { s ->
                                val next = if (s in chosen) chosen - s else chosen + s
                                save(current, mapOf("symptoms" to next), "Saved")
                            }
                        }
                    }
                }
            }

            // Calendar
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        val month = monthStart(now, monthOffset)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { monthOffset -= 1 }) { Icon(Icons.Rounded.ChevronLeft, "Previous month") }
                            Text(monthLabel(month), modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
                            IconButton(onClick = { if (monthOffset < 6) monthOffset += 1 }) { Icon(Icons.Rounded.ChevronRight, "Next month") }
                        }
                        MonthGrid(month, today) { day -> PeriodMath.dayKind(day, periods, p) }
                        LegendRow()
                    }
                }
            }

            item { SectionTitle("Add a past period") }
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DatePickField("Started on", pastStart, { pastStart = it })
                        DatePickField("Ended on (optional)", pastEnd, { pastEnd = it })
                        FilledTonalButton(
                            enabled = pastStart != null,
                            onClick = {
                                val s = pastStart ?: return@FilledTonalButton
                                val e = pastEnd?.takeIf { it >= s }
                                save(null, mapOf("type" to "period", "title" to "Period", "startMillis" to s, "endMillis" to e,
                                    "flow" to "", "symptoms" to emptyList<String>()), "Period added")
                                pastStart = null
                                pastEnd = null
                            }
                        ) { Text("Add period") }
                    }
                }
            }

            if (periods.isNotEmpty()) {
                item { SectionTitle("History") }
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                            periods.forEachIndexed { i, r ->
                                val s = r.lng("startMillis") ?: return@forEachIndexed
                                val e = r.lng("endMillis")
                                val prevStart = periods.getOrNull(i + 1)?.lng("startMillis")
                                val length = if (e != null) ((startOfDay(e) - startOfDay(s)) / DAY_MS).toInt() + 1 else null
                                val cycle = if (prevStart != null) ((startOfDay(s) - startOfDay(prevStart)) / DAY_MS).toInt() else null
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp)) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(formatDate(s) + (if (e != null) " – " + formatDate(e) else " (ongoing)"), fontWeight = FontWeight.Medium)
                                        Text(
                                            listOfNotNull(
                                                length?.let { "$it days" },
                                                cycle?.let { "cycle $it days" },
                                                r.str("flow").takeIf { it.isNotBlank() },
                                                r.stringList("symptoms").takeIf { it.isNotEmpty() }?.joinToString(", ")
                                            ).joinToString(" • "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                        )
                                    }
                                    IconButton(onClick = { confirmDelete = r }) {
                                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "Your period entries are private to you. Predictions are estimates from your own history; " +
                        "they are not medical advice or a method of birth control.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
        }
    }

    confirmDelete?.let { r ->
        GlassDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this period?") },
            text = { Text(formatDate(r.lng("startMillis"))) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch {
                        try { env.repo.deleteScoped(env.familyId, env.uid, "items", r) } catch (e: Exception) { }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun Fact(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LegendRow() {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(top = 8.dp)
    ) {
        Legend(PeriodRed, "Period")
        Legend(PeriodRed.copy(alpha = 0.35f), "Expected")
        Legend(FertileTeal, "Fertile")
        Legend(OvulationPurple, "Ovulation")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SymptomChips(chosen: List<String>, onToggle: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SYMPTOMS.forEach { s ->
            GlassFilterChip(selected = s in chosen, onClick = { onToggle(s) }, label = { Text(s) })
        }
    }
}

/** A month of days, Sunday first, each coloured by what [kind] returns. */
@Composable
private fun MonthGrid(month: Long, today: Long, kind: (Long) -> String) {
    val cal = Calendar.getInstance().apply { timeInMillis = month }
    val firstDow = cal.get(Calendar.DAY_OF_WEEK) - 1 // 0 = Sunday
    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val cells = firstDow + daysInMonth
    val rows = (cells + 6) / 7
    Column {
        Row {
            listOf("S", "M", "T", "W", "T", "F", "S").forEach { d ->
                Text(d, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
        }
        for (r in 0 until rows) {
            Row {
                for (c in 0 until 7) {
                    val index = r * 7 + c
                    val dayNum = index - firstDow + 1
                    Box(modifier = Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                        if (dayNum in 1..daysInMonth) {
                            val day = startOfDay(dayInMonth(month, dayNum))
                            val k = kind(day)
                            val bg = when (k) {
                                "period" -> PeriodRed
                                "predicted" -> PeriodRed.copy(alpha = 0.3f)
                                "ovulation" -> OvulationPurple
                                "fertile" -> FertileTeal.copy(alpha = 0.35f)
                                else -> Color.Transparent
                            }
                            val isToday = day == today
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                                    .background(bg)
                                    .then(if (isToday) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    dayNum.toString(),
                                    fontSize = 13.sp,
                                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                    color = if (k == "period" || k == "ovulation") Color.White else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
