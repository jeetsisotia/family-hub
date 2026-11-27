package com.familyhub.app.ui.chat

import com.familyhub.app.core.theme.GlassBottomSheet
import com.familyhub.app.core.theme.GlassDialog
import com.familyhub.app.core.theme.GlassInputField
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.familyhub.app.core.theme.GlassCard
import com.familyhub.app.data.remote.ChatRepository
import com.familyhub.app.data.remote.ChatRepository.Chat
import com.familyhub.app.data.remote.ChatRepository.Message
import com.familyhub.app.ui.AppEnv
import com.familyhub.app.ui.calls.CallButtons
import com.familyhub.app.ui.common.PersonAvatar
import com.familyhub.app.ui.common.ScreenTopBar
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Things kept only on this phone: pinned, muted and archived chats, starred and hidden messages, drafts. */
object ChatPrefs {
    private const val P = "family_hub_chat"
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(P, Context.MODE_PRIVATE)
    private fun set(ctx: Context, key: String): Set<String> = prefs(ctx).getStringSet(key, emptySet()).orEmpty()
    private fun toggle(ctx: Context, key: String, value: String) {
        val s = set(ctx, key).toMutableSet()
        if (!s.add(value)) s.remove(value)
        prefs(ctx).edit().putStringSet(key, s).apply()
        seenTick.intValue++
    }
    fun pinned(ctx: Context) = set(ctx, "pinned")
    fun muted(ctx: Context) = set(ctx, "muted")
    fun archived(ctx: Context) = set(ctx, "archived")
    fun starred(ctx: Context) = set(ctx, "starred")
    fun hidden(ctx: Context) = set(ctx, "hidden")
    fun togglePinned(ctx: Context, id: String) = toggle(ctx, "pinned", id)
    fun toggleMuted(ctx: Context, id: String) = toggle(ctx, "muted", id)
    fun toggleArchived(ctx: Context, id: String) = toggle(ctx, "archived", id)
    fun toggleStarred(ctx: Context, id: String) = toggle(ctx, "starred", id)
    fun hide(ctx: Context, id: String) { if (id !in hidden(ctx)) toggle(ctx, "hidden", id) }
    fun draft(ctx: Context, chatId: String): String = prefs(ctx).getString("draft_$chatId", "").orEmpty()
    fun saveDraft(ctx: Context, chatId: String, text: String) = prefs(ctx).edit().putString("draft_$chatId", text).apply()
    fun recentEmojis(ctx: Context): List<String> = prefs(ctx).getString("recentEmoji", "").orEmpty().split('\u0001').filter { it.isNotBlank() }
    fun useEmoji(ctx: Context, e: String) {
        val list = (listOf(e) + recentEmojis(ctx).filter { it != e }).take(24)
        prefs(ctx).edit().putString("recentEmoji", list.joinToString("\u0001")).apply()
    }

    /** Changes whenever a chat is read here, so the Chats tab count updates. */
    val seenTick = mutableIntStateOf(0)

    fun seenAt(ctx: Context, chatId: String): Long = prefs(ctx).getLong("seen_$chatId", 0L)

    /** Remembers that this chat was read here up to [at] (server time of the newest message shown). */
    fun markSeen(ctx: Context, chatId: String, at: Long) {
        if (at <= seenAt(ctx, chatId)) return
        prefs(ctx).edit().putLong("seen_$chatId", at).apply()
        seenTick.intValue++
    }

    /** From when the Chats tab count starts: the first time this version ran, so old history is not counted as new. */
    private fun countSince(ctx: Context): Long {
        val p = prefs(ctx)
        val since = p.getLong("badge_since", 0L)
        if (since > 0L) return since
        val now = System.currentTimeMillis()
        p.edit().putLong("badge_since", now).apply()
        return now
    }

    /** How many chats (not muted or archived) have a newer message from someone else than was read on this phone. */
    fun unreadChats(ctx: Context, chats: List<Chat>, uid: String): Int {
        if (chats.isEmpty() || uid.isBlank()) return 0
        val since = countSince(ctx)
        val muted = muted(ctx)
        val archived = archived(ctx)
        return chats.count { c ->
            c.lastSenderUid.isNotBlank() && c.lastSenderUid != uid && c.id !in muted && c.id !in archived &&
                c.lastAt > maxOf(seenAt(ctx, c.id), since)
        }
    }
}

private val QUICK = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

private fun timeText(t: Long): String = if (t <= 0) "" else SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(t))

private fun dayText(t: Long): String {
    val c = Calendar.getInstance()
    val today = c.apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    return when {
        t >= today -> "Today"
        t >= today - 86_400_000L -> "Yesterday"
        else -> SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(Date(t))
    }
}

private fun listTime(t: Long): String {
    if (t <= 0) return ""
    val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    return if (t >= c.timeInMillis) timeText(t) else SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(t))
}

/** Only emojis (up to six), shown large like other messengers do. */
fun isEmojiOnly(text: String): Boolean {
    val t = text.trim()
    if (t.isEmpty() || t.length > 24) return false
    var count = 0
    var i = 0
    while (i < t.length) {
        val cp = t.codePointAt(i)
        val isJoiner = cp == 0x200D || cp == 0xFE0F || cp in 0x1F3FB..0x1F3FF
        val isEmoji = cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF || cp == 0x2764
        if (!isEmoji && !isJoiner && !Character.isWhitespace(cp)) return false
        if (isEmoji) count++
        i += Character.charCount(cp)
    }
    return count in 1..6
}

private fun chatTitle(c: Chat, env: AppEnv): String =
    if (c.id == ChatRepository.FAMILY_CHAT) env.family.name.ifBlank { "Family" }
    else env.members.firstOrNull { it.uid in c.memberUids && it.uid != env.uid }?.name ?: "Chat"

// ---------------------------------------------------------------- chat list

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(env: AppEnv, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chats by remember(env.familyId, env.uid) { ChatRepository.observeChats(env.familyId, env.uid) }.collectAsState(initial = null)
    var prefsTick by remember { mutableIntStateOf(0) }
    val pinned = remember(prefsTick) { ChatPrefs.pinned(context) }
    val muted = remember(prefsTick) { ChatPrefs.muted(context) }
    val archived = remember(prefsTick) { ChatPrefs.archived(context) }
    var showArchived by remember { mutableStateOf(false) }
    var newChat by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Chat?>(null) }
    var query by remember { mutableStateOf("") }

    // Messages seen here are not notified again later.
    LaunchedEffect(chats) { com.familyhub.app.work.ChatWorker.markSeen(context) }
    // The family group chat is created the first time someone opens Chats.
    LaunchedEffect(env.familyId) {
        try { ChatRepository.ensureChat(env.familyId, ChatRepository.FAMILY_CHAT, emptyList(), env.uid) } catch (e: Exception) { }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Chats", onBack = null) {
            IconButton(onClick = { env.navigate("calls") }) { Icon(Icons.Rounded.Call, contentDescription = "Calls (Beta)") }
            IconButton(onClick = { newChat = true }) { Icon(Icons.Rounded.Edit, contentDescription = "New chat") }
        }
        com.familyhub.app.core.theme.GlassSearchField(
            value = query, onValueChange = { query = it }, placeholder = "Search chats",
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        val list = chats
        when {
            list == null -> com.familyhub.app.core.theme.LoadingState(rows = 3)
            else -> {
                val visible = list.filter { query.isBlank() || chatTitle(it, env).contains(query, true) || it.lastText.contains(query, true) }
                val active = visible.filter { it.id !in archived }.sortedWith(compareByDescending<Chat> { it.id in pinned }.thenByDescending { it.lastAt })
                val old = visible.filter { it.id in archived }
                LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (active.isEmpty()) item {
                        if (query.isBlank()) {
                            com.familyhub.app.core.theme.EmptyState(
                                icon = Icons.Rounded.ChatBubbleOutline,
                                title = "No chats yet",
                                message = "Message the whole family, or tap the pencil to chat with one person.",
                                actionLabel = "Open family chat",
                                onAction = { onOpen(ChatRepository.FAMILY_CHAT) }
                            )
                        } else {
                            com.familyhub.app.core.theme.EmptyState(
                                icon = Icons.Rounded.Search,
                                title = "No results",
                                message = "Search looks at chat names and each chat's latest message on this phone, not the whole history."
                            )
                        }
                    }
                    items(active, key = { it.id }) { c ->
                        SwipeChat(
                            pinned = c.id in pinned,
                            archived = false,
                            onPin = { ChatPrefs.togglePinned(context, c.id); prefsTick++ },
                            onArchive = { ChatPrefs.toggleArchived(context, c.id); prefsTick++ }
                        ) {
                            ChatRow(env, c, c.id in pinned, c.id in muted, onClick = { onOpen(c.id) }, onLong = { menuFor = c })
                        }
                    }
                    if (old.isNotEmpty()) {
                        item {
                            TextButton(onClick = { showArchived = !showArchived }) {
                                Icon(Icons.Rounded.Archive, null); Spacer(Modifier.width(6.dp)); Text("Archived (${old.size})")
                            }
                        }
                        if (showArchived) items(old, key = { "a_" + it.id }) { c ->
                            SwipeChat(
                                pinned = c.id in pinned,
                                archived = true,
                                onPin = { ChatPrefs.togglePinned(context, c.id); prefsTick++ },
                                onArchive = { ChatPrefs.toggleArchived(context, c.id); prefsTick++ }
                            ) {
                                ChatRow(env, c, false, c.id in muted, onClick = { onOpen(c.id) }, onLong = { menuFor = c })
                            }
                        }
                    }
                    item {
                        Text(
                            "Messages are protected by the family's access rules and Google's encryption in transit and at rest. " +
                                "They are not end-to-end encrypted. Direct chats can be read only by their two people.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                }
            }
        }
    }

    menuFor?.let { c ->
        GlassDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(chatTitle(c, env)) },
            text = {
                Column {
                    TextButton(onClick = { ChatPrefs.togglePinned(context, c.id); prefsTick++; menuFor = null }) { Text(if (c.id in pinned) "Unpin" else "Pin to top") }
                    TextButton(onClick = { ChatPrefs.toggleMuted(context, c.id); prefsTick++; menuFor = null }) { Text(if (c.id in muted) "Unmute" else "Mute notifications") }
                    TextButton(onClick = { ChatPrefs.toggleArchived(context, c.id); prefsTick++; menuFor = null }) { Text(if (c.id in archived) "Unarchive" else "Archive") }
                }
            },
            confirmButton = { TextButton(onClick = { menuFor = null }) { Text("Close") } }
        )
    }

    if (newChat) {
        val others = env.members.filter { it.status == "ACTIVE" && it.uid != env.uid }
        GlassDialog(
            onDismissRequest = { newChat = false },
            title = { Text("New chat") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { newChat = false; onOpen(ChatRepository.FAMILY_CHAT) }) { Text("Family group: everyone") }
                    if (others.isEmpty()) Text("Nobody else has joined the family yet.", style = MaterialTheme.typography.bodySmall)
                    others.forEach { m ->
                        TextButton(onClick = {
                            newChat = false
                            val id = ChatRepository.directId(env.uid, m.uid)
                            scope.launch {
                                try {
                                    ChatRepository.ensureChat(env.familyId, id, listOf(env.uid, m.uid), env.uid)
                                    onOpen(id)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Could not start the chat: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }) {
                            PersonAvatar(name = m.name, photoData = m.photoData, photoUrl = m.photoUrl, size = 28.dp)
                            Spacer(Modifier.width(8.dp)); Text(m.name)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { newChat = false }) { Text("Cancel") } }
        )
    }
}

/** Swipe right to pin or unpin, left to archive or unarchive (the row springs back afterwards). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeChat(
    pinned: Boolean,
    archived: Boolean,
    onPin: () -> Unit,
    onArchive: () -> Unit,
    content: @Composable () -> Unit
) {
    val tick = com.familyhub.app.core.theme.rememberTick()
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            when (v) {
                SwipeToDismissBoxValue.StartToEnd -> { tick(); onPin() }
                SwipeToDismissBoxValue.EndToStart -> { tick(); onArchive() }
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false
        }
    )
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            // Nothing behind the row until it is actually swiped (the rows are see-through glass).
            if (state.dismissDirection != SwipeToDismissBoxValue.Settled) {
                val toEnd = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
                val color = if (toEnd) Color(0xFFFF9F0A) else Color(0xFF8E8E93)
                Row(
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)).background(color).padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (toEnd) Arrangement.Start else Arrangement.End
                ) {
                    Icon(if (toEnd) Icons.Rounded.PushPin else Icons.Rounded.Archive, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (toEnd) (if (pinned) "Unpin" else "Pin") else (if (archived) "Unarchive" else "Archive"),
                        color = Color.White, fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    ) { content() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(env: AppEnv, c: Chat, pinned: Boolean, muted: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val other = env.members.firstOrNull { it.uid in c.memberUids && it.uid != env.uid }
    var unread by remember(c.id) { mutableIntStateOf(0) }
    LaunchedEffect(c.id, c.lastAt) { unread = ChatRepository.unreadCount(env.familyId, c.id, env.uid, c.lastAt, c.lastSenderUid) }
    val title = chatTitle(c, env)
    GlassCard(modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLong), shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (c.id == ChatRepository.FAMILY_CHAT) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Groups, null, tint = Color.White)
                }
            } else {
                PersonAvatar(name = title, photoData = other?.photoData, photoUrl = other?.photoUrl, size = 44.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (pinned) Icon(Icons.Rounded.PushPin, "Pinned", modifier = Modifier.padding(start = 4.dp).size(14.dp))
                    if (muted) Icon(Icons.Rounded.NotificationsOff, "Muted", modifier = Modifier.padding(start = 4.dp).size(14.dp))
                }
                val prefix = if (c.lastSenderUid == env.uid) "You: " else ""
                Text(
                    (prefix + c.lastText).ifBlank { "No messages yet" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (unread > 0) FontWeight.SemiBold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (unread > 0) 0.9f else 0.65f)
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(listTime(c.lastAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                if (unread > 0) {
                    Box(
                        Modifier.padding(top = 4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).padding(horizontal = 7.dp, vertical = 2.dp)
                    ) { Text(if (unread > 99) "99+" else unread.toString(), color = Color.White, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- one conversation

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(env: AppEnv, chatId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var limit by remember(chatId) { mutableLongStateOf(ChatRepository.PAGE) }
    val messages by remember(chatId, limit) { ChatRepository.observeMessages(env.familyId, chatId, limit) }.collectAsState(initial = null)
    val reads by remember(chatId) { ChatRepository.observeReads(env.familyId, chatId) }.collectAsState(initial = emptyMap())
    val chats by remember(env.familyId, env.uid) { ChatRepository.observeChats(env.familyId, env.uid) }.collectAsState(initial = emptyList())
    val chat = chats?.firstOrNull { it.id == chatId }
    var text by remember(chatId) { mutableStateOf(ChatPrefs.draft(context, chatId)) }
    var replyTo by remember { mutableStateOf<Message?>(null) }
    var editing by remember { mutableStateOf<Message?>(null) }
    var menuFor by remember { mutableStateOf<Message?>(null) }
    var forwardOf by remember { mutableStateOf<Message?>(null) }
    var showEmoji by remember { mutableStateOf(false) }
    var showPoll by remember { mutableStateOf(false) }
    var reactPickerFor by remember { mutableStateOf<Message?>(null) }
    var searching by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    val hidden = remember(tick) { ChatPrefs.hidden(context) }
    val starred = remember(tick) { ChatPrefs.starred(context) }
    val failed = remember { mutableStateMapOf<String, Message>() }
    val listState = rememberLazyListState()
    val myName = env.profile.name.ifBlank { "Me" }
    val title = chat?.let { chatTitle(it, env) } ?: if (chatId == ChatRepository.FAMILY_CHAT) env.family.name.ifBlank { "Family" } else "Chat"

    LaunchedEffect(chatId) {
        try { ChatRepository.ensureChat(env.familyId, chatId, chatId.removePrefix("dm_").split('_'), env.uid) } catch (e: Exception) { }
    }
    // Reading the chat marks it read (once per new message, not on every scroll).
    val handles = remember(env.members) { env.members.filter { it.status == "ACTIVE" }.map { Mentions.handle(it.name) } }
    val newest = messages?.lastOrNull()
    LaunchedEffect(newest?.id) {
        if (newest != null) {
            ChatPrefs.markSeen(context, chatId, newest.at)
            ChatRepository.markRead(env.familyId, chatId, env.uid)
        }
    }
    // Keep the draft when leaving.
    DisposableEffect(chatId) { onDispose { ChatPrefs.saveDraft(context, chatId, text) } }

    val shown = (messages.orEmpty() + failed.values).distinctBy { it.id }.sortedBy { it.at }
        .filter { it.id !in hidden && (search.isBlank() || it.text.contains(search, true)) }
    // Newest at the bottom: the list is reversed so it opens at the latest message.
    val reversed = shown.asReversed()
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex <= 1 } }
    LaunchedEffect(shown.size) { if (atBottom) listState.scrollToItem(0) }
    val pinnedMsg = chat?.pinnedMessageId?.takeIf { it.isNotBlank() }?.let { id -> shown.firstOrNull { it.id == id } }

    fun send() {
        val body = text.trim()
        if (body.isEmpty()) return
        val editingNow = editing
        if (editingNow != null) {
            scope.launch {
                try { ChatRepository.edit(env.familyId, chatId, editingNow.id, body) } catch (e: Exception) { Toast.makeText(context, "Could not edit: ${e.message}", Toast.LENGTH_LONG).show() }
            }
            editing = null; text = ""; return
        }
        try {
            val reply = replyTo
            val sentAt = System.currentTimeMillis()
            // If the server refuses it (for example after being removed from the family), show it as not sent.
            ChatRepository.send(env.familyId, chatId, env.uid, myName, body, reply) { id ->
                failed[id] = Message(id, env.uid, myName, body, sentAt, true, null, false, reply?.id.orEmpty(), reply?.text.orEmpty(), reply?.senderName.orEmpty(), false, emptyMap())
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Could not send: ${e.message}", Toast.LENGTH_LONG).show()
        }
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        text = ""; replyTo = null
        ChatPrefs.saveDraft(context, chatId, "")
        scope.launch { listState.animateScrollToItem(0) }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenTopBar(title = title, onBack = onBack, compact = true) {
            val peer = if (chatId.startsWith("dm_")) env.members.firstOrNull { chat != null && it.uid in chat.memberUids && it.uid != env.uid } else null
            if (peer != null) CallButtons(peer.uid, peer.name)
            IconButton(onClick = { searching = !searching; if (!searching) search = "" }) { Icon(Icons.Rounded.Search, "Search in this chat") }
        }
        if (searching) {
            GlassInputField(
                value = search, onValueChange = { search = it }, singleLine = true, placeholder = { Text("Search loaded messages") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(14.dp)
            )
            Text(
                "Searches the messages loaded on screen. Load earlier messages to search further back.",
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
        }
        if (pinnedMsg != null) {
            Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.PushPin, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp))
                    Text(pinnedMsg.text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { scope.launch { try { ChatRepository.pin(env.familyId, chatId, null) } catch (e: Exception) { } } }) { Text("Unpin") }
                }
            }
        }
        Box(Modifier.weight(1f)) {
            val list = messages
            if (list == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                LazyColumn(
                    state = listState, reverseLayout = true, modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(reversed.size, key = { reversed[it].id }) { i ->
                        val m = reversed[i]
                        val older = reversed.getOrNull(i + 1)
                        val newer = reversed.getOrNull(i - 1)
                        val firstOfGroup = older == null || older.senderUid != m.senderUid || m.at - older.at > 3 * 60_000L || dayText(older.at) != dayText(m.at)
                        val lastOfGroup = newer == null || newer.senderUid != m.senderUid || newer.at - m.at > 3 * 60_000L || dayText(newer.at) != dayText(m.at)
                        Column {
                            if (older == null || dayText(older.at) != dayText(m.at)) {
                                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)) {
                                        Text(dayText(m.at), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
                                    }
                                }
                            }
                            Bubble(
                                m = m, mine = m.senderUid == env.uid, showName = firstOfGroup && chatId == ChatRepository.FAMILY_CHAT && m.senderUid != env.uid,
                                tail = lastOfGroup, starred = m.id in starred, failed = m.id in failed,
                                seen = if (m.senderUid == env.uid && i == reversed.indexOfFirst { it.senderUid == env.uid }) seenText(m, reads, env) else "",
                                onLong = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); menuFor = m },
                                onSwipeReply = { if (!m.deletedForAll) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); replyTo = m } },
                                onRetry = {
                                    failed.remove(m.id)
                                    try { ChatRepository.resend(env.familyId, chatId, env.uid, myName, m) } catch (e: Exception) { }
                                },
                                onReact = { e -> scope.launch { try { ChatRepository.react(env.familyId, chatId, m.id, env.uid, if (m.reactions[env.uid] == e) null else e) } catch (x: Exception) { } } },
                                myUid = env.uid,
                                handles = handles,
                                onVote = { option ->
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    scope.launch {
                                        try { ChatRepository.vote(env.familyId, chatId, m.id, env.uid, if (m.votes[env.uid] == option) null else option) }
                                        catch (e: Exception) { Toast.makeText(context, "Could not vote: ${e.message}", Toast.LENGTH_LONG).show() }
                                    }
                                }
                            )
                        }
                    }
                    if (list.size.toLong() >= limit) item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            TextButton(onClick = { limit += ChatRepository.PAGE }) { Text("Load earlier messages") }
                        }
                    }
                    if (list.isEmpty()) item {
                        Text("Say hello 👋", modifier = Modifier.fillMaxWidth().padding(24.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                androidx.compose.animation.AnimatedVisibility(visible = !atBottom, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
                    SmallFloatingActionButton(onClick = { scope.launch { listState.animateScrollToItem(0) } }) {
                        Icon(Icons.Rounded.KeyboardArrowDown, "Jump to latest")
                    }
                }
            }
        }
        // Reply or edit strip above the box.
        val strip = editing ?: replyTo
        if (strip != null) {
            Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (editing != null) Icons.Rounded.Edit else Icons.Rounded.Reply, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (editing != null) "Editing" else "Replying to ${strip.senderName}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        Text(strip.text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { if (editing != null) { editing = null; text = "" } else replyTo = null }) { Icon(Icons.Rounded.Close, "Cancel") }
                }
            }
        }
        // @mentions in the family chat: suggest members while a name is typed after "@".
        val typing = if (chatId == ChatRepository.FAMILY_CHAT) Mentions.partial(text) else null
        if (typing != null) {
            val names = Mentions.suggestions(typing, env.members.filter { it.status == "ACTIVE" && it.uid != env.uid }.map { it.name })
            if (names.isNotEmpty()) Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                names.forEach { n -> AssistChip(onClick = { text = Mentions.complete(text, n) }, label = { Text("@" + Mentions.handle(n)) }) }
            }
        }
        if (showEmoji) EmojiPanel(onPick = { e -> text += e; ChatPrefs.useEmoji(context, e) })
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
            IconButton(onClick = { showEmoji = !showEmoji }) { Icon(if (showEmoji) Icons.Rounded.Keyboard else Icons.Rounded.EmojiEmotions, "Emoji") }
            if (text.isEmpty() && editing == null) IconButton(onClick = { showPoll = true }) { Icon(Icons.Rounded.Poll, "Create a poll") }
            GlassInputField(
                value = text, onValueChange = { if (it.length <= ChatRepository.MAX_TEXT) text = it },
                placeholder = { Text("Message") }, maxLines = 5, shape = RoundedCornerShape(22.dp), modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            FilledIconButton(onClick = { send() }, enabled = text.isNotBlank()) { Icon(if (editing != null) Icons.Rounded.Check else Icons.Rounded.Send, "Send") }
        }
    }

    if (showPoll) PollDialog(onDismiss = { showPoll = false }) { question, options ->
        showPoll = false
        try {
            val sentAt = System.currentTimeMillis()
            ChatRepository.send(env.familyId, chatId, env.uid, myName, question, pollOptions = options) { id ->
                failed[id] = Message(id, env.uid, myName, question, sentAt, true, null, false, "", "", "", false, emptyMap(), options)
            }
            scope.launch { listState.animateScrollToItem(0) }
        } catch (e: Exception) {
            Toast.makeText(context, "Could not send the poll: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // Long-press menu.
    menuFor?.let { m ->
        val mine = m.senderUid == env.uid
        GlassBottomSheet(onDismissRequest = { menuFor = null }) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                if (!m.deletedForAll) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    QUICK.forEach { e ->
                        Text(e, fontSize = 26.sp, modifier = Modifier.clip(CircleShape).clickable {
                            menuFor = null; ChatPrefs.useEmoji(context, e)
                            scope.launch { try { ChatRepository.react(env.familyId, chatId, m.id, env.uid, if (m.reactions[env.uid] == e) null else e) } catch (x: Exception) { } }
                        }.padding(6.dp))
                    }
                    IconButton(onClick = { reactPickerFor = m; menuFor = null }) { Icon(Icons.Rounded.AddReaction, "More reactions") }
                }
                val close: () -> Unit = { menuFor = null }
                if (!m.deletedForAll) {
                    SheetItem(close, Icons.Rounded.Reply, "Reply") { replyTo = m }
                    SheetItem(close, Icons.Rounded.ContentCopy, "Copy") {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("message", m.text))
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    }
                    SheetItem(close, Icons.Rounded.Shortcut, "Forward") { forwardOf = m }
                    SheetItem(close, if (m.id in starred) Icons.Rounded.StarOutline else Icons.Rounded.Star, if (m.id in starred) "Unstar" else "Star") { ChatPrefs.toggleStarred(context, m.id); tick++ }
                    SheetItem(close, Icons.Rounded.PushPin, if (chat?.pinnedMessageId == m.id) "Unpin" else "Pin in this chat") {
                        scope.launch { try { ChatRepository.pin(env.familyId, chatId, if (chat?.pinnedMessageId == m.id) null else m.id) } catch (e: Exception) { } }
                    }
                    if (mine && !m.pending) SheetItem(close, Icons.Rounded.Edit, "Edit") { editing = m; replyTo = null; text = m.text }
                }
                SheetItem(close, Icons.Rounded.VisibilityOff, "Delete for me") { ChatPrefs.hide(context, m.id); tick++ }
                if (mine && !m.deletedForAll && !m.pending) SheetItem(close, Icons.Rounded.Delete, "Delete for everyone") {
                    scope.launch { try { ChatRepository.deleteForEveryone(env.familyId, chatId, m.id) } catch (e: Exception) { Toast.makeText(context, "Could not delete: ${e.message}", Toast.LENGTH_LONG).show() } }
                }
            }
        }
    }

    reactPickerFor?.let { m ->
        GlassBottomSheet(onDismissRequest = { reactPickerFor = null }) {
            EmojiPanel(onPick = { e ->
                reactPickerFor = null; ChatPrefs.useEmoji(context, e)
                scope.launch { try { ChatRepository.react(env.familyId, chatId, m.id, env.uid, e) } catch (x: Exception) { } }
            })
        }
    }

    forwardOf?.let { m ->
        val targets = chats.orEmpty().filter { it.id != chatId }
        GlassDialog(
            onDismissRequest = { forwardOf = null },
            title = { Text("Forward to") },
            text = {
                Column {
                    if (targets.isEmpty()) Text("There is no other chat yet.")
                    targets.forEach { c ->
                        TextButton(onClick = {
                            forwardOf = null
                            try { ChatRepository.send(env.familyId, c.id, env.uid, myName, m.text, forwarded = true); Toast.makeText(context, "Forwarded", Toast.LENGTH_SHORT).show() }
                            catch (e: Exception) { Toast.makeText(context, "Could not forward: ${e.message}", Toast.LENGTH_LONG).show() }
                        }) { Text(chatTitle(c, env)) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { forwardOf = null }) { Text("Cancel") } }
        )
    }
}

/** "Seen" under your latest message: by the other person, or how many have seen it in the family chat. */
private fun seenText(m: Message, reads: Map<String, Long>, env: AppEnv): String {
    if (m.pending) return ""
    val by = reads.filter { (uid, at) -> uid != env.uid && at >= m.at }.keys
    return when {
        by.isEmpty() -> "Sent"
        env.members.count { it.status == "ACTIVE" } <= 2 -> "Seen"
        else -> "Seen by ${by.size}"
    }
}

@Composable
private fun Bubble(
    m: Message, mine: Boolean, showName: Boolean, tail: Boolean, starred: Boolean, failed: Boolean, seen: String,
    onLong: () -> Unit, onSwipeReply: () -> Unit, onRetry: () -> Unit, onReact: (String) -> Unit, myUid: String,
    handles: List<String> = emptyList(),
    onVote: (Int) -> Unit = {}
) {
    var drag by remember { mutableFloatStateOf(0f) }
    val emojiOnly = !m.deletedForAll && m.pollOptions.isEmpty() && isEmojiOnly(m.text)
    val bg = when {
        emojiOnly -> Color.Transparent
        mine -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = if (mine && !emojiOnly) Color.White else MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(
        topStart = 18.dp, topEnd = 18.dp,
        bottomStart = if (!mine && tail) 4.dp else 18.dp, bottomEnd = if (mine && tail) 4.dp else 18.dp
    )
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = if (showName) 6.dp else 0.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start
    ) {
        if (showName) Text(m.senderName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 10.dp, bottom = 2.dp))
        Box(
            Modifier
                .offset { IntOffset(drag.roundToInt(), 0) }
                .pointerInput(m.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = { if (drag > 120f) onSwipeReply(); drag = 0f },
                        onDragCancel = { drag = 0f },
                        onHorizontalDrag = { _, d -> drag = (drag + d).coerceIn(0f, 180f) }
                    )
                }
                .widthIn(max = 300.dp)
                .clip(shape)
                .background(bg)
                .combinedClickableCompat(onLong)
                .padding(horizontal = if (emojiOnly) 2.dp else 12.dp, vertical = if (emojiOnly) 0.dp else 8.dp)
        ) {
            Column {
                if (m.forwarded) Text("Forwarded", style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.7f))
                if (m.replyToId.isNotBlank() && !m.deletedForAll) {
                    Surface(shape = RoundedCornerShape(8.dp), color = fg.copy(alpha = 0.12f), modifier = Modifier.padding(bottom = 4.dp)) {
                        Column(Modifier.padding(6.dp)) {
                            Text(m.replyToName, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = fg)
                            Text(m.replyToText, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = fg.copy(alpha = 0.85f))
                        }
                    }
                }
                when {
                    m.deletedForAll -> Text("This message was deleted", color = fg.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
                    emojiOnly -> Text(m.text, fontSize = 44.sp, lineHeight = 52.sp)
                    m.pollOptions.size >= 2 -> PollBody(m, fg, myUid, onVote)
                    else -> Text(mentionText(m.text, handles), color = fg, style = MaterialTheme.typography.bodyLarge)
                }
                Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    if (starred) Icon(Icons.Rounded.Star, "Starred", tint = fg.copy(alpha = 0.7f), modifier = Modifier.size(12.dp))
                    if (m.editedAt != null && !m.deletedForAll) Text("edited ", style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.7f))
                    Text(timeText(m.at), style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.7f))
                    if (mine && m.pending && !failed) Icon(Icons.Rounded.Schedule, "Sending", tint = fg.copy(alpha = 0.7f), modifier = Modifier.padding(start = 3.dp).size(12.dp))
                }
            }
        }
        if (m.reactions.isNotEmpty()) {
            val grouped = m.reactions.values.groupingBy { it }.eachCount()
            Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                grouped.forEach { (e, n) ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (m.reactions[myUid] == e) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onReact(e) }
                    ) { Text(if (n > 1) "$e $n" else e, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp), fontSize = 13.sp) }
                }
            }
        }
        if (failed) TextButton(onClick = onRetry) { Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(4.dp)); Text("Not sent. Retry", color = MaterialTheme.colorScheme.error) }
        else if (seen.isNotBlank()) Text(seen, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), modifier = Modifier.padding(end = 6.dp, top = 1.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onLong: () -> Unit): Modifier = this.combinedClickable(onClick = {}, onLongClick = onLong)

/** The emoji keyboard: recent emojis first, then AndroidX's emoji picker (free, Apache 2.0) with every emoji. */
@Composable
private fun EmojiPanel(onPick: (String) -> Unit) {
    val context = LocalContext.current
    val recent = remember { ChatPrefs.recentEmojis(context) }
    Column(Modifier.fillMaxWidth().height(300.dp)) {
        if (recent.isNotEmpty()) Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            recent.take(8).forEach { e -> Text(e, fontSize = 24.sp, modifier = Modifier.clip(CircleShape).clickable { onPick(e) }.padding(4.dp)) }
        }
        AndroidView(
            factory = { ctx -> androidx.emoji2.emojipicker.EmojiPickerView(ctx).apply { setOnEmojiPickedListener { item -> onPick(item.emoji) } } },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun SheetItem(close: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, action: () -> Unit) =
    ListItem(headlineContent = { Text(label) }, leadingContent = { Icon(icon, null) }, modifier = Modifier.clickable { close(); action() })

/** Message text with @mentions of family members in bold. */
private fun mentionText(text: String, handles: List<String>): androidx.compose.ui.text.AnnotatedString {
    val ranges = Mentions.ranges(text, handles)
    if (ranges.isEmpty()) return androidx.compose.ui.text.AnnotatedString(text)
    return androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        ranges.forEach { r -> addStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold), r.first, r.last + 1) }
    }
}

/** A poll inside a bubble: the question, then each answer with its votes. Tap an answer to vote; tap again to undo. */
@Composable
private fun PollBody(m: Message, fg: Color, myUid: String, onVote: (Int) -> Unit) {
    val total = m.votes.size
    val mine = m.votes[myUid]
    Column(Modifier.widthIn(min = 220.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Poll, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(m.text, color = fg, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        }
        m.pollOptions.forEachIndexed { i, option ->
            val count = m.votes.values.count { it == i }
            val share = if (total == 0) 0f else count.toFloat() / total
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onVote(i) }.padding(vertical = 4.dp, horizontal = 2.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (mine == i) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        contentDescription = if (mine == i) "Your vote" else null,
                        tint = fg, modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(option, color = fg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(count.toString(), color = fg, style = MaterialTheme.typography.labelLarge)
                }
                Box(Modifier.padding(start = 26.dp, top = 4.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(fg.copy(alpha = 0.18f))) {
                    Box(Modifier.fillMaxWidth(share).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(fg.copy(alpha = 0.8f)))
                }
            }
        }
        Text(
            if (total == 1) "1 vote" else "$total votes",
            color = fg.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall
        )
    }
}

/** Ask a question with 2 to 6 answers. */
@Composable
private fun PollDialog(onDismiss: () -> Unit, onSend: (String, List<String>) -> Unit) {
    var question by remember { mutableStateOf("") }
    val options = remember { mutableStateListOf("", "") }
    val ready = question.isNotBlank() && options.count { it.isNotBlank() } >= 2
    GlassDialog(
        onDismissRequest = onDismiss,
        title = { Text("New poll") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassInputField(
                    value = question, onValueChange = { question = it.take(300) },
                    label = { Text("Question") }, maxLines = 3, modifier = Modifier.fillMaxWidth()
                )
                options.forEachIndexed { i, o ->
                    GlassInputField(
                        value = o, onValueChange = { options[i] = it.take(ChatRepository.POLL_OPTION_MAX) },
                        label = { Text("Answer ${i + 1}") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        trailingIcon = if (options.size > 2) {
                            { IconButton(onClick = { options.removeAt(i) }) { Icon(Icons.Rounded.Close, "Remove answer") } }
                        } else null
                    )
                }
                if (options.size < ChatRepository.POLL_MAX) TextButton(onClick = { options.add("") }) {
                    Icon(Icons.Rounded.Add, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Add answer")
                }
                Text(
                    "Everyone in this chat can vote once and change their vote. Chat members can see who voted for what.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(enabled = ready, onClick = { onSend(question.trim(), options.map { it.trim() }.filter { it.isNotEmpty() }) }) {
                Text("Send", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
