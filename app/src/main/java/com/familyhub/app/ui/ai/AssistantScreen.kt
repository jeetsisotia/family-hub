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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.familyhub.app.core.ai.AiContext
import com.familyhub.app.core.ai.AiException
import com.familyhub.app.core.ai.GeminiClient
import com.familyhub.app.core.theme.GlassCard
import com.familyhub.app.ui.AppEnv
import com.familyhub.app.ui.common.ScreenTopBar
import com.familyhub.app.ui.common.dayKey
import com.familyhub.app.ui.modules.str
import kotlinx.coroutines.launch

/**
 * One chat line. [aiText] is what the AI may see of it: null for health commands, which are
 * understood on the phone and never sent.
 */
data class ChatMsg(
    val fromUser: Boolean,
    val text: String,
    val actions: List<AiAction> = emptyList(),
    val aiText: String? = text
)

/** The conversation stays while the app is open, so moving between screens does not lose it. */
object AssistantMemory {
    val messages = mutableStateListOf<ChatMsg>()
}

private val SUGGESTIONS = listOf(
    Icons.Rounded.CurrencyRupee to "Add ₹40 travel expense today via UPI from HDFC to Rajesh",
    Icons.Rounded.LocalTaxi to "Book an Uber to the airport",
    Icons.Rounded.Work to "Find Java developer jobs in Gurugram on LinkedIn",
    Icons.Rounded.WaterDrop to "Period started today, heavy flow",
    Icons.Rounded.MonitorHeart to "Weight 63.5 kg, BP 120/80",
    Icons.Rounded.ShoppingCart to "Add milk, eggs and bread to shopping",
    Icons.Rounded.Alarm to "Remind me to pay rent on the 1st at 9 am every month",
    Icons.Rounded.Event to "What's due this week?",
    Icons.Rounded.PieChart to "How much did we spend this month?"
)

/** History for the AI: only what it may see, starting with the user and alternating roles. */
private fun aiTurns(messages: List<ChatMsg>): List<GeminiClient.Turn> {
    val turns = ArrayList<GeminiClient.Turn>()
    for (m in messages.takeLast(14)) {
        val t = m.aiText?.takeIf { it.isNotBlank() } ?: continue
        if (turns.isNotEmpty() && turns.last().fromUser == m.fromUser) {
            turns[turns.size - 1] = GeminiClient.Turn(m.fromUser, turns.last().text + "\n" + t)
        } else {
            turns.add(GeminiClient.Turn(m.fromUser, t))
        }
    }
    while (turns.isNotEmpty() && !turns.first().fromUser) turns.removeAt(0)
    return turns
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AssistantScreen(env: AppEnv, autoAsk: String? = null, startVoice: Boolean = false, autoAsks: List<String> = emptyList()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages = AssistantMemory.messages
    // A page's AI button can hand over a starting text and the page it came from.
    var input by rememberSaveable { mutableStateOf(AiDraft.takeText().orEmpty()) }
    val page = AiDraft.page
    DisposableEffect(Unit) { onDispose { AiDraft.page = null } }
    var busy by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var refresh by remember { mutableStateOf(0) }

    suspend fun handle(text: String) {
        val q = text.trim()
        if (q.isEmpty()) return
        busy = true
        try {
            // Tidy-ups and changes across the app are worked out on the phone and shown as cards to confirm.
            val local = AppCommands.handle(q, env, page)
            if (local != null) {
                messages.add(ChatMsg(true, q, aiText = null))
                messages.add(ChatMsg(false, local.reply, local.actions, aiText = null))
                busy = false
                return
            }
            val items = env.data["items"].orEmpty()
            val meds = items.filter { it.str("type") == "medicine" }
            val health = HealthCommands.parse(q, meds, currentPeriod(items) != null)
            messages.add(ChatMsg(true, q, aiText = health.rest.ifBlank { null }))
            if (health.actions.isNotEmpty() || health.notUnderstood.isNotEmpty()) {
                val reply = buildString {
                    if (health.actions.isNotEmpty()) append("Saved on your phone only, never sent to the AI. Tap Add to confirm:")
                    if (health.notUnderstood.isNotEmpty()) {
                        if (isNotEmpty()) append("\n\n")
                        append(
                            "I couldn't read \"" + health.notUnderstood.joinToString("; ") + "\". Health details stay on your phone, " +
                                "so try a short form like \"period started today, heavy flow\", \"weight 63.5 kg\", \"BP 120/80\", " +
                                "\"walked 30 minutes\" or \"2 glasses of water\"."
                        )
                    }
                }
                messages.add(ChatMsg(false, reply, health.actions, aiText = null))
            }
            if (health.rest.isNotBlank()) {
                if (!GeminiClient.isConfigured()) {
                    messages.add(ChatMsg(false, "⚠️ To get AI answers, add your own free Gemini API key in AI settings (top right of the AI chat). Money questions like \"How much did I spend this month?\" still work without it.", aiText = null))
                } else {
                    val system = AiContext.systemPrompt(env, AiContext.build(env))
                    val reply = GeminiClient.generate(system, aiTurns(messages), json = true)
                    val parsed = parseAiReply(reply)
                    messages.add(parsed.copy(aiText = parsed.text))
                }
            }
        } catch (e: AiException) {
            messages.add(ChatMsg(false, "⚠️ " + (e.message ?: "Something went wrong."), aiText = null))
        } catch (e: Exception) {
            messages.add(ChatMsg(false, "⚠️ Something went wrong: ${e.message}", aiText = null))
        }
        busy = false
    }

    fun send(text: String) {
        if (text.isBlank() || busy) return
        input = ""
        scope.launch { handle(text) }
    }

    fun add(a: AiAction) {
        scope.launch {
            try {
                applyAction(env, context, a)
                a.state = "added"
                AiAudit.record(context, a, true)
            } catch (e: Exception) {
                a.state = "failed"
                a.error = e.message ?: "Could not save"
                AiAudit.record(context, a, false, a.error)
            }
            refresh++
        }
    }

    // Open the official app or website for "open" suggestions. UPI payments report back whether they went through.
    var pendingUpi by remember { mutableStateOf<AiAction?>(null) }
    val upiLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val a = pendingUpi
        pendingUpi = null
        if (a != null) {
            val r = (res.data?.getStringExtra("response") ?: res.data?.getStringExtra("Status") ?: "").lowercase()
            if ("status=success" in r || r == "success") {
                a.state = "added"
                messages.add(
                    ChatMsg(
                        false, "Payment done. Add it to Finance?",
                        listOf(
                            AiAction(
                                kind = "expense", title = a.payee.ifBlank { "UPI payment" }, amount = a.amount, method = "UPI",
                                payee = a.payee, category = normalizeCategory("", a.query + " " + a.payee),
                                dateMillis = com.familyhub.app.ui.modules.localNoon(System.currentTimeMillis())
                            )
                        ),
                        aiText = null
                    )
                )
            } else {
                messages.add(ChatMsg(false, "The payment app did not confirm a successful payment. If you did pay, add it as an expense.", aiText = null))
            }
            refresh++
        }
    }

    fun openAction(a: AiAction) {
        val t = SmartLinks.target(a, env.members, env.userDoc)
        if (t == null) {
            a.state = "failed"
            a.error = when (a.service) {
                "upi" -> "Needs the payee's UPI ID, for example name@okhdfcbank."
                "call", "whatsapp", "sms" -> "No phone number found for ${a.payee.ifBlank { a.phone }}. Add it in My Profile or the member's profile."
                else -> "Not enough details to open this."
            }
            refresh++
            return
        }
        if (t.isUpi) {
            pendingUpi = a
            try {
                upiLauncher.launch(t.intent)
            } catch (e: Exception) {
                pendingUpi = null
                a.state = "failed"
                a.error = "No UPI app found on this phone."
            }
        } else {
            val err = SmartLinks.open(context, t)
            if (err != null) {
                a.state = "failed"
                a.error = err
            } else {
                a.state = "added"
                if (t.copyText.isNotBlank()) {
                    android.widget.Toast.makeText(context, "\"${t.copyText}\" copied. Paste it in the app.", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
        refresh++
    }

    // The official site inside Family Hub, so the sign-in stays here; the person taps Book or Pay themselves.
    fun openInApp(a: AiAction) {
        val t = SmartLinks.target(a, env.members, env.userDoc)
        val url = listOfNotNull(t?.intent?.data, t?.fallback).map { it.toString() }.firstOrNull { it.startsWith("https://") }
        if (url == null) {
            a.state = "failed"
            a.error = "This one opens in its own app on the phone."
        } else {
            com.familyhub.app.AgentBrowserActivity.open(context, url, SmartLinks.label(a.service))
            a.state = "added"
        }
        refresh++
    }

    // Speak instead of typing (uses the phone's own speech recognition, in any language it supports).
    val voice = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val heard = res.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!heard.isNullOrBlank()) send(heard)
    }
    fun listen() {
        val i = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Say what to add or ask")
        }
        try {
            voice.launch(i)
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "Voice input is not available on this phone.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        val queue = listOfNotNull(autoAsk) + autoAsks
        if (queue.isNotEmpty() && messages.isEmpty()) queue.forEach { handle(it) }
        if (startVoice) listen()
    }

    // Keep the newest message in view when the keyboard opens.
    val imeOpen = WindowInsets.isImeVisible
    LaunchedEffect(imeOpen) {
        if (imeOpen && messages.isNotEmpty()) {
            val last = listState.layoutInfo.totalItemsCount - 1
            if (last >= 0) listState.scrollToItem(last)
        }
    }

    LaunchedEffect(messages.size, busy) {
        if (messages.isNotEmpty()) {
            val last = listState.layoutInfo.totalItemsCount - 1
            if (last >= 0) listState.animateScrollToItem(last)
        }
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        ScreenTopBar(title = "AI Assistant", onBack = env.onBack) {
            if (messages.isNotEmpty()) {
                IconButton(onClick = { messages.clear() }) { Icon(Icons.Rounded.DeleteSweep, contentDescription = "Clear chat") }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AiOrb(size = 64.dp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("How can I help?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        if (!page.isNullOrBlank()) {
                            Text(
                                "From $page. I can work on anything in the app.",
                                style = MaterialTheme.typography.labelMedium,
                                color = AiAccent,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        Text(
                            "Type or speak to add expenses, tasks, reminders, events and health entries, or ask about your family's plans and spending.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp, start = 8.dp, end = 8.dp)
                        )
                    }
                }
                item {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AppCommands.suggestionsFor(page).forEach { s -> AiSuggestion(Icons.Rounded.AutoAwesome, s) { send(s) } }
                        SUGGESTIONS.forEach { (icon, s) -> AiSuggestion(icon, s) { send(s) } }
                    }
                }
            }
            itemsIndexed(messages) { _, m ->
                key(refresh) {
                    MessageBubble(
                        m,
                        onAction = { a, keep ->
                            if (keep && a.kind == "open") openAction(a) else if (keep) add(a) else {
                                a.state = "dismissed"
                                refresh++
                            }
                        },
                        onAddAll = { m.actions.filter { it.state == "new" && it.kind != "open" }.forEach { add(it) } },
                        onOpenInApp = { a -> openInApp(a) }
                    )
                }
            }
            if (busy) {
                item { AiThinking() }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        "Health, IDs and notes stay on your phone. They're never sent to the AI.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                    )
                }
            }
        }
        AiInputBar(
            value = input,
            onValueChange = { input = it },
            busy = busy,
            onMic = { listen() },
            onSend = { send(input) },
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MessageBubble(
    m: ChatMsg,
    onAction: (AiAction, Boolean) -> Unit,
    onAddAll: () -> Unit,
    onOpenInApp: (AiAction) -> Unit = {}
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (m.fromUser) Alignment.End else Alignment.Start
    ) {
        if (m.fromUser) UserBubble(m.text) else AssistantBubble(m.text)
        // Action cards sit under the reply, lined up with the bubble rather than the orb.
        val cardStart = if (m.fromUser) 0.dp else 34.dp
        m.actions.forEach { a ->
            AnimatedVisibility(visible = a.state != "dismissed") {
                GlassCard(
                    modifier = Modifier.padding(top = 6.dp, start = cardStart).fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    strong = true
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                        Box(
                            modifier = Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(
                                if (a.local) Brush.linearGradient(listOf(Color(0xFFFF6B8B), Color(0xFFFF2D55)))
                                else aiFillBrush()
                            ),
                            contentAlignment = Alignment.Center
                        ) { Icon(actionIcon(a.kind), contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp)) }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(actionLabel(a), style = MaterialTheme.typography.bodyMedium)
                            if (a.state == "failed" && a.error.isNotBlank()) {
                                Text(a.error, style = MaterialTheme.typography.labelSmall, color = Color(0xFFFF3B30))
                            }
                            FlowRow(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.Center) {
                                when (a.state) {
                                    "added" -> Text(
                                        actionDone(a.kind),
                                        color = Color(0xFF34C759), fontWeight = FontWeight.SemiBold
                                    )
                                    else -> {
                                        AiPillButton(
                                            if (a.state == "failed") "Retry" else actionButton(a.kind),
                                            onClick = { onAction(a, true) }
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        if (a.kind == "open" && a.service !in setOf("call", "sms", "upi", "ola", "rapido", "zepto")) {
                                            TextButton(onClick = { onOpenInApp(a) }) { Text("In Family Hub") }
                                        }
                                        TextButton(onClick = { onAction(a, false) }) {
                                            Text("Dismiss", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (m.actions.any { it.kind == "open" && it.state != "dismissed" }) {
            Text(
                "Open takes you to the official app or site with the details filled in; In Family Hub opens the official site here, " +
                    "where you sign in once. You book, pay, apply or send yourself.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, start = cardStart + 4.dp).widthIn(max = 320.dp)
            )
        }
        val pending = m.actions.count { it.state == "new" && it.kind != "open" }
        if (pending >= 2) {
            val allAdds = m.actions.none { it.kind in TIDY_KINDS }
            Box(modifier = Modifier.padding(top = 8.dp, start = cardStart)) {
                AiPillButton((if (allAdds) "Add all " else "Do all ") + pending, Icons.Rounded.DoneAll, onClick = onAddAll)
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

    GlassCard(
        modifier = modifier.fillMaxWidth().aiGlowBorder(RoundedCornerShape(24.dp), width = 1.dp, active = loading),
        onClick = { env.navigate("assistant") }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AiOrb(size = 24.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (brief != null || loading) "Today's brief" else "AI Assistant",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
                )
                if (!loading && hasData) {
                    IconButton(onClick = { brief = null; load() }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh brief", modifier = Modifier.size(18.dp))
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            when {
                loading && brief == null -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = AiAccent)
                brief != null -> Text(brief.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                error != null -> Text(error.orEmpty(), style = MaterialTheme.typography.bodySmall)
                else -> Text("Say or type things like \"₹40 auto fare today via UPI\" or \"period started today\".",
                    style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    "Ask or add →",
                    color = AiAccent,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                AiPillButton("Speak", Icons.Rounded.Mic) { env.navigate("assistant_voice") }
            }
        }
    }
}
