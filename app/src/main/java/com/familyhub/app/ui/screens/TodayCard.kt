package com.familyhub.app.ui.screens

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.familyhub.app.calling.CallManager
import com.familyhub.app.core.theme.GlassCard
import com.familyhub.app.data.remote.Rec
import com.familyhub.app.safety.SafetyLogic
import com.familyhub.app.safety.SafetyStore
import com.familyhub.app.ui.AppEnv
import com.familyhub.app.ui.modules.bool
import com.familyhub.app.ui.modules.lng
import com.familyhub.app.ui.modules.nextOccurrenceOf
import com.familyhub.app.ui.modules.str
import com.familyhub.app.work.Notifier
import com.familyhub.app.work.ReminderRules
import kotlinx.coroutines.delay
import java.time.ZoneId

/** One line in Today: a reminder or event, with its time ("All day" when it has none). */
private data class TodayLine(val sortAt: Long, val time: String, val title: String, val route: String)

/** Today's reminders and calendar events, earliest first. Uses records already on screen (no extra reads). */
private fun todayLines(data: Map<String, List<Rec>>, now: Long): List<TodayLine> {
    val day0 = ReminderRules.startOfDay(now)
    val day1 = day0 + 24 * 60 * 60_000L
    val out = ArrayList<TodayLine>()
    fun add(r: Rec, start: Long, repeat: String, route: String) {
        val next = nextOccurrenceOf(r, start, repeat, now)
        if (next < day0 || next >= day1) return
        val time = r.str("time")
        val t = ReminderRules.parseTime(time)
        val sortAt = if (t == null) day0 else ReminderRules.atTime(day0, t.first, t.second)
        out.add(TodayLine(sortAt, ReminderRules.timeText(time), r.str("title").ifBlank { "Untitled" }, route))
    }
    data["items"].orEmpty()
        .filter { it.str("type") == "reminder" && !it.bool("done") && it["isDeleted"] != true }
        .forEach { r ->
            val d = r.lng("dateMillis") ?: return@forEach
            // A repeating reminder already marked done for today is left out.
            val doneToday = r.str("lastDoneSlot").startsWith(ReminderRules.dayKey(now))
            if (!doneToday) add(r, d, r.str("repeat"), "reminders")
        }
    data["events"].orEmpty()
        .filter { it["isDeleted"] != true }
        .forEach { e -> e.lng("startTimeMillis")?.let { add(e, it, e.str("recurrence"), "calendar") } }
    return out.sortedBy { it.sortAt }
}

private fun canExactAlarm(ctx: Context): Boolean {
    if (Build.VERSION.SDK_INT < 31) return true
    val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return true
    return try { am.canScheduleExactAlarms() } catch (_: Exception) { true }
}

private fun open(ctx: Context, intent: Intent) {
    try { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
}

/**
 * Home "Today": what is due today with its time, safety status with an SOS button, missed calls, and a
 * warning when notifications or on-time alarms are off (reminders would then be silent or late).
 */
@Composable
fun TodayCard(env: AppEnv) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            now = System.currentTimeMillis()
        }
    }
    val safetyTick by SafetyStore.tick
    val lines = remember(env.data, now / 60_000L) { todayLines(env.data, now) }
    val share = remember(safetyTick, now) { SafetyStore.share(context) }
    val arrival = remember(safetyTick, now) { SafetyStore.arrival(context) }
    val lowOn = remember(safetyTick) { SafetyStore.lowBatteryOn(context) }
    val calls by CallManager.history.collectAsState()
    val missed = calls.count { it.calleeUid == env.uid && it.state == "missed" && now - it.clientAt < 24 * 60 * 60_000L }
    val notificationsOn = Notifier.canPost(context)
    val exact = canExactAlarm(context)
    val zone = ZoneId.systemDefault()

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.EditCalendar, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Today", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { env.navigate("reminders") }) { Text("Reminders") }
            }
            if (lines.isEmpty()) {
                Text("Nothing due today.", style = MaterialTheme.typography.bodyMedium)
            }
            lines.take(5).forEach { l ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { env.navigate(l.route) }.padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(l.time, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(64.dp), style = MaterialTheme.typography.bodyMedium)
                    Text(l.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (lines.size > 5) Text("+${lines.size - 5} more", style = MaterialTheme.typography.bodySmall)

            if (missed > 0) {
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { env.navigate("calls") }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Call, contentDescription = null, tint = Color(0xFFFF453A))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("$missed missed call" + (if (missed == 1) "" else "s") + " today", color = Color(0xFFFF453A))
                }
            }

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Shield, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f).clickable { env.navigate("safety") }) {
                    val parts = listOfNotNull(
                        share?.let { s -> "Sharing location" + (s.endAt?.let { " until " + SafetyLogic.timeText(it, zone).substringAfter(", ") } ?: "") },
                        arrival?.let { "Arrival check by " + SafetyLogic.timeText(it.deadline, zone).substringAfter(", ") },
                        if (lowOn) "Low-battery SMS on" else null
                    )
                    Text("Safety", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    Text(parts.ifEmpty { listOf("Alerts off. Tap to set up.") }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = { env.navigate("sos") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC20F0F)),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) { Text("SOS") }
            }

            if (!notificationsOn || !exact) {
                HorizontalDivider()
                Text(
                    if (!notificationsOn) "Notifications are off, so reminders cannot show."
                    else "On-time alarms are not allowed, so reminders may be a few minutes late.",
                    color = Color(0xFFD97706),
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = {
                    if (!notificationsOn) {
                        open(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    } else if (Build.VERSION.SDK_INT >= 31) {
                        open(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName)))
                    }
                }) { Text("Fix in Settings") }
            }
        }
    }
}
