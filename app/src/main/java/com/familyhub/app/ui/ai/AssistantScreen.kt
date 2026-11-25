package com.familyhub.app.ui.ai

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.familyhub.app.core.ai.AiContext
import com.familyhub.app.core.ai.AiException
import com.familyhub.app.core.ai.GeminiClient
import com.familyhub.app.core.theme.GlassCard
import com.familyhub.app.core.theme.IosBlue
import com.familyhub.app.ui.AppEnv
import com.familyhub.app.ui.common.ScreenTopBar
import com.familyhub.app.ui.common.dayKey
import com.familyhub.app.ui.common.formatDate
import com.familyhub.app.ui.money.inr
import com.familyhub.app.ui.modules.localNoon
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Something the assistant offers to add; added only when the user taps Add. */
data class AiAction(
    val kind: String,
    val title: String,
    val dateMillis: Long?,
    val time: String,
    val amount: Double?,
    val category: String,
    var state: String = "new" // new, added, dismissed, failed
)

data class ChatMsg(val fromUser: Boolean, val text: String, val actions: List<AiAction> = emptyList())

/** The conversation stays while the app is open, so moving between screens does not lose it. */
object AssistantMemory {
    val messages = mutableStateListOf<ChatMsg>()
}

private val SUGGESTIONS = listOf(
    "What's due this week?",
    "How much did we spend this month?",
    "Where can we save money?",
    "Add milk, eggs and bread to shopping",
    "Remind me to pay rent on the 1st at 9 am",
    "Plan healthy dinners for this week"
)

fun parseAiDate(s: String): Long? {
    if (s.isBlank()) return null
    return try {
        val f = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        f.parse(s.trim())?.time?.let { localNoon(it) }
    } catch (e: Exception) {
        null
    }
}

fun parseAiReply(text: String): ChatMsg {
    val raw = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    return try {
        val o = JSONObject(raw)
        val acts = ArrayList<AiAction>()
        val arr = o.optJSONArray("actions")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                val kind = a.optString("kind").lowercase(Locale.ROOT)
                val title = a.optString("title").trim()
                if (title.isBlank() || kind !in setOf("shopping", "task", "event", "reminder", "expense", "note")) continue
                acts.add(
                    AiAction(
                        kind = kind,
                        title = title.take(120),
                        dateMillis = parseAiDate(a.optString("date")),
                        time = a.optString("time").trim().take(5),
                        amount = a.optDouble("amount").takeIf { !it.isNaN() && it > 0 },
                        category = a.optString("category").trim()
                    )
                )
            }
        }
        ChatMsg(false, o.optString("reply").ifBlank { if (acts.isEmpty()) raw else "Here's what I can add:" }, acts.take(12))
    } catch (e: Exception) {
        ChatMsg(false, raw)
    }
}

/** Adds one confirmed action to Family Hub. Reminders and notes are "Only me"; the rest go to the family. */
suspend fun applyAction(env: AppEnv, a: AiAction) {
    val now = System.currentTimeMillis()
    val day = a.dateMillis ?: localNoon(now)
    when (a.kind) {
        "shopping" -> env.repo.saveScoped(env.familyId, env.uid, "tasks", null,
            mapOf("type" to "SHOPPING", "title" to a.title, "isCompleted" to false, "createdAt" to now), false)
        "task" -> env.repo.saveScoped(env.familyId, env.uid, "tasks", null,
            mapOf("type" to "TASK", "title" to a.title, "isCompleted" to false, "assignedToName" to "",
                "dueDateMillis" to a.dateMillis, "createdAt" to now), false)
        "event" -> {
            val start = com.familyhub.app.ui.common.parseTimes(a.time).firstOrNull()
                ?.let { (h, m) -> com.familyhub.app.ui.common.atTime(com.familyhub.app.ui.modules.startOfDay(day), h, m) } ?: day
            env.repo.saveScoped(env.familyId, env.uid, "events", null,
                mapOf("title" to a.title, "category" to (a.category.ifBlank { "Family" }), "startTimeMillis" to start,
                    "endTimeMillis" to start + 3_600_000L, "recurrence" to "NONE", "isDeleted" to false,
                    "reminderMinutesBefore" to 30L, "createdByUid" to env.uid, "notes" to ""), false)
        }
        "reminder" -> env.repo.saveScoped(env.familyId, env.uid, "items", null,
            mapOf("type" to "reminder", "title" to a.title, "dateMillis" to day, "time" to a.time,
                "repeat" to "None", "done" to false, "notes" to ""), true)
        "expense" -> env.repo.saveScoped(env.familyId, env.uid, "expenses", null,
            mapOf("type" to "EXPENSE", "title" to a.title, "amountInr" to (a.amount ?: 0.0),
                "category" to a.category.ifBlank { "Other" }, "dateMillis" to day, "paymentMethod" to "",
                "isPaid" to true, "isDeleted" to false, "memberUid" to env.uid, "memberName" to env.profile.name,
                "createdAt" to now), false)
        "note" -> env.repo.saveScoped(env.familyId, env.uid, "items", null,
            mapOf("type" to "note", "title" to a.title, "category" to (a.category.ifBlank { "General" }),
                "notes" to "", "createdAt" to now), true)
    }
}

private fun actionLabel(a: AiAction): String {
    val what = when (a.kind) {
        "shopping" -> "Shopping"
        "task" -> "Task"
        "event" -> "Calendar"
        "reminder" -> "Reminder"
        "expense" -> "Expense"
        else -> "Note"
    }
    val extra = listOfNotNull(
        a.dateMillis?.let { formatDate(it) },
        a.time.takeIf { it.isNotBlank() },
        a.amount?.let { inr(it) }
    ).joinToString(" • ")
    return "$what: ${a.title}" + if (extra.isNotBlank()) "  ($extra)" else ""
}

private fun actionIcon(kind: String) = when (kind) {
    "shopping" -> Icons.Rounded.ShoppingCart
    "task" -> Icons.Rounded.TaskAlt
    "event" -> Icons.Rounded.CalendarMonth
    "reminder" -> Icons.Rounded.Alarm
    "expense" -> Icons.Rounded.CurrencyRupee
    else -> Icons.Rounded.StickyNote2
}

@Composable
fun AssistantScreen(env: AppEnv) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages = AssistantMemory.messages
    var input by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var refresh by remember { mutableStateOf(0) }

    fun send(text: String) {
        val q = text.trim()
        if (q.isEmpty() || busy) return
        input = ""
        messages.add(ChatMsg(true, q))
        busy = true
        scope.launch {
            try {
                val system = AiContext.systemPrompt(env, AiContext.build(env))
                val turns = messages.takeLast(12).map { GeminiClient.Turn(it.fromUser, it.text) }
                val reply = GeminiClient.generate(system, turns, json = true)
                messages.add(parseAiReply(reply))
            } catch (e: AiException) {
                messages.add(ChatMsg(false, "⚠️ " + (e.message ?: "Something went wrong.")))
            } catch (e: Exception) {
                messages.add(ChatMsg(false, "⚠️ Something went wrong: ${e.message}"))
            }
            busy = false
        }
    }

    LaunchedEffect(messages.size, busy) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size + 1)
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        ScreenTopBar(title = "AI Assistant", onBack = env.onBack) {
            if (messages.isNotEmpty()) {
                IconButton(onClick = { messages.clear() }) { Icon(Icons.Rounded.DeleteSweep, contentDescription = "Clear chat") }
            }
        }
        if (!GeminiClient.isConfigured()) {
            GlassCard(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    "The AI assistant is not switched on in this version of the app. Everything else works as usual.",
                    modifier = Modifier.padding(16.dp)
                )
            }
            return@Column
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                        Box(
                            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(12.dp))
                                .background(Brush.linearGradient(listOf(Color(0xFF5E5CE6), IosBlue))),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = Color.White) }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("Ask about your family's plans, bills and spending, or ask me to add things.",
                                style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Private: ID numbers, health, medicines, periods, vitals and notes are never sent to the AI.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
            if (messages.isEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SUGGESTIONS.forEach { s ->
                            SuggestionChip(onClick = { send(s) }, label = { Text(s) })
                        }
                    }
                }
            }
            itemsIndexed(messages) { index, m ->
                key(refresh) {
                    MessageBubble(m, onAction = { a, add ->
                        if (!add) {
                            a.state = "dismissed"
                            refresh++
                        } else {
                            scope.launch {
                                a.state = try {
                                    applyAction(env, a)
                                    "added"
                                } catch (e: Exception) {
                                    "failed"
                                }
                                refresh++
                            }
                        }
                    })
                }
            }
            if (busy) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Thinking…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Ask or tell Family Hub…") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(22.dp),
                maxLines = 4
            )
            Spacer(modifier = Modifier.width(8.dp))
            FilledIconButton(onClick = { send(input) }, enabled = !busy && input.isNotBlank()) {
                Icon(Icons.Rounded.ArrowUpward, contentDescription = "Send")
            }
        }
    }
}

@Composable
private fun MessageBubble(m: ChatMsg, onAction: (AiAction, Boolean) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (m.fromUser) Alignment.End else Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(if (m.fromUser) IosBlue else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(m.text, color = if (m.fromUser) Color.White else MaterialTheme.colorScheme.onSurface)
        }
        m.actions.forEach { a ->
            AnimatedVisibility(visible = a.state != "dismissed") {
                GlassCard(modifier = Modifier.padding(top = 6.dp).widthIn(max = 340.dp), shape = RoundedCornerShape(16.dp)) {
                    Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(actionIcon(a.kind), contentDescription = null, tint = IosBlue, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(actionLabel(a), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        when (a.state) {
                            "added" -> Text("Added ✓", color = Color(0xFF34C759), fontWeight = FontWeight.SemiBold)
                            "failed" -> Text("Failed", color = Color(0xFFFF3B30))
                            else -> {
                                TextButton(onClick = { onAction(a, true) }) { Text("Add") }
                                IconButton(onClick = { onAction(a, false) }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A short daily summary on the Home screen, made once a day and kept on the phone. */
@Composable
fun AiBriefCard(env: AppEnv, modifier: Modifier = Modifier) {
    if (!GeminiClient.isConfigured() || env.uid.isBlank() || env.familyId.isBlank()) return
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("family_hub_ai", Context.MODE_PRIVATE) }
    val cacheKey = "brief-" + dayKey() + "-" + env.familyId
    var brief by remember(cacheKey) { mutableStateOf(prefs.getString(cacheKey, null)) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val hasData = env.data.values.any { it.isNotEmpty() }

    fun load() {
        if (loading) return
        loading = true
        error = null
        scope.launch {
            try {
                val system = AiContext.systemPrompt(env, AiContext.build(env))
                val ask = "Give me today's brief for the family as JSON with \"reply\" holding 3 or 4 short bullet lines " +
                    "starting with •: what is due today or this week, how spending compares with last month, and one useful tip. " +
                    "No actions."
                val r = parseAiReply(GeminiClient.generate(system, listOf(GeminiClient.Turn(true, ask)), json = true, temperature = 0.3))
                brief = r.text
                prefs.edit().apply {
                    prefs.all.keys.filter { it.startsWith("brief-") }.forEach { remove(it) }
                    putString(cacheKey, r.text)
                }.apply()
            } catch (e: Exception) {
                error = e.message ?: "Could not make today's brief."
            }
            loading = false
        }
    }

    LaunchedEffect(cacheKey, hasData) {
        if (brief == null && hasData) load()
    }

    if (brief == null && !loading && error == null) return
    GlassCard(modifier = modifier.fillMaxWidth(), onClick = { env.navigate("assistant") }) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = Color(0xFF5E5CE6), modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Today's brief", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (!loading) {
                    IconButton(onClick = { brief = null; load() }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh brief", modifier = Modifier.size(18.dp))
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            when {
                loading && brief == null -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                brief != null -> Text(brief.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                else -> Text(error.orEmpty(), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "Ask AI →",
                color = IosBlue,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
