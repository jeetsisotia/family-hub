package com.familyhub.app.calling

import android.content.Context
import com.familyhub.app.data.remote.CallRepository
import com.familyhub.app.data.remote.GroupCall
import com.familyhub.app.data.remote.GroupCallRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.webrtc.PeerConnection
import org.webrtc.VideoTrack

enum class GroupPhase { IDLE, RINGING, IN_CALL, ENDED }

data class GroupUi(
    val phase: GroupPhase = GroupPhase.IDLE,
    val callId: String = "",
    val hostUid: String = "",
    val hostName: String = "",
    val video: Boolean = false,
    val names: Map<String, String> = emptyMap(),
    val invited: List<String> = emptyList(),
    val joined: List<String> = emptyList(),
    /** Each other person's connection: connecting, connected, reconnecting, failed. */
    val peerStates: Map<String, String> = emptyMap(),
    val quality: Map<String, GroupCallMath.Quality> = emptyMap(),
    val speaking: String? = null,
    val muted: Boolean = false,
    val cameraOn: Boolean = false,
    val speaker: Boolean = false,
    val startedAt: Long = 0,
    val message: String = ""
)

/**
 * Runs family group calls (up to 4 people, each pair connected directly). One-to-one calls stay in
 * [CallManager]; a phone in one kind of call does not ring for the other.
 */
object GroupCallManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val repo = GroupCallRepository()
    private val _ui = MutableStateFlow(GroupUi())
    val ui: StateFlow<GroupUi> = _ui.asStateFlow()
    private val _videos = MutableStateFlow<Map<String, VideoTrack>>(emptyMap())
    val remoteVideos: StateFlow<Map<String, VideoTrack>> = _videos.asStateFlow()
    private val _history = MutableStateFlow<List<GroupCall>>(emptyList())
    val history: StateFlow<List<GroupCall>> = _history.asStateFlow()

    var engine: GroupCallEngine? = null
        private set
    private var appContext: Context? = null
    private var familyId = ""
    private var myUid = ""
    private var myName = ""
    private var listJob: Job? = null
    private var callJob: Job? = null
    private val peerJobs = mutableMapOf<String, Job>()
    private val myNonce = mutableMapOf<String, String>()
    private val answered = mutableMapOf<String, String>()
    private val retries = mutableMapOf<String, Int>()
    private val dismissed = mutableSetOf<String>()
    private var ringJob: Job? = null

    fun attach(context: Context, fid: String, uid: String, name: String) {
        if (fid == familyId && uid == myUid && listJob?.isActive == true) return
        appContext = context.applicationContext; familyId = fid; myUid = uid; myName = name
        listJob?.cancel()
        listJob = scope.launch {
            runCatching { repo.observeMine(fid, uid).collect { onCalls(it) } }
        }
        if (ringJob == null) ringJob = scope.launch {
            var ringingId = ""
            _ui.collect { u ->
                val ctx = appContext ?: return@collect
                if (u.phase == GroupPhase.RINGING && u.callId != ringingId) {
                    ringingId = u.callId
                    com.familyhub.app.work.Notifier.ringing(ctx, "${u.hostName.ifBlank { "A family member" }} started a group call", if (u.video) "Video · open Family Hub to join" else "Voice · open Family Hub to join", slot = 1)
                } else if (u.phase != GroupPhase.RINGING && ringingId.isNotEmpty()) {
                    ringingId = ""
                    com.familyhub.app.work.Notifier.stopRinging(ctx, slot = 1)
                }
            }
        }
    }

    private fun onCalls(list: List<GroupCall>) {
        _history.value = list
        val ctx = appContext ?: return
        val now = System.currentTimeMillis()
        val cur = _ui.value
        if (cur.phase == GroupPhase.IDLE || cur.phase == GroupPhase.ENDED) {
            val busy1to1 = CallManager.ui.value.phase.let { it != CallPhase.IDLE && it != CallPhase.ENDED }
            val ring = list.firstOrNull { GroupCallMath.rings(myUid, it.hostUid, it.memberUids, it.joined, it.state, it.clientAt, now, dismissed, it.id) }
            if (ring != null && !busy1to1) {
                _ui.value = GroupUi(GroupPhase.RINGING, ring.id, ring.hostUid, ring.hostName, ring.video, ring.names, ring.memberUids, ring.joined)
            }
        }
        if (cur.phase == GroupPhase.RINGING) {
            val c = list.firstOrNull { it.id == cur.callId }
            if (c == null || c.state != "open" || now - c.clientAt > GroupCallMath.RING_MS) {
                _ui.value = GroupUi()
                if (c != null && myUid !in c.joined) MissedCalls.notify(ctx, c.id, c.hostName, group = true)
            }
        }
        // Calls that rang while the app was closed or that I did not answer.
        list.filter { it.hostUid != myUid && myUid in it.memberUids && myUid !in it.joined && (it.state == "ended" || now - it.clientAt > GroupCallMath.RING_MS) && now - it.clientAt < 86_400_000L }
            .forEach { MissedCalls.notify(ctx, it.id, it.hostName, group = true) }
    }

    /** Starts a group call with [invited] (uid to name), at most 3 others. */
    fun start(invited: Map<String, String>, video: Boolean): String? {
        GroupCallMath.canStart(myUid, invited.keys.toList())?.let { return it }
        if (_ui.value.phase == GroupPhase.IN_CALL) return "You are already in a group call."
        val id = CallRepository().newCallId()
        scope.launch {
            try {
                repo.start(familyId, id, myUid, myName, invited, video)
                enter(id, video, myUid, myName, invited + (myUid to myName), listOf(myUid) + invited.keys)
                // Nobody answered in a minute: the call ends.
                launch {
                    delay(GroupCallMath.RING_MS)
                    val u = _ui.value
                    if (u.callId == id && u.phase == GroupPhase.IN_CALL && u.joined.none { it != myUid }) {
                        runCatching { repo.end(familyId, id) }
                        finish("No one answered.")
                    }
                }
            } catch (e: Exception) {
                finish("Could not start the group call. Check your internet and try again.")
            }
        }
        return null
    }

    fun accept() {
        val u = _ui.value
        if (u.phase != GroupPhase.RINGING) return
        scope.launch {
            try {
                repo.join(familyId, u.callId, myUid)
                enter(u.callId, u.video, u.hostUid, u.hostName, u.names, u.invited)
            } catch (e: Exception) { finish("Could not join the call.") }
        }
    }

    fun decline() {
        dismissed += _ui.value.callId
        _ui.value = GroupUi()
    }

    private fun enter(id: String, video: Boolean, hostUid: String, hostName: String, names: Map<String, String>, invited: List<String>) {
        val ctx = appContext ?: return
        val e = GroupCallEngine(ctx)
        e.listener = object : GroupCallEngine.Listener {
            override fun onPeerState(uid: String, state: PeerConnection.PeerConnectionState) { scope.launch { onPeerState(uid, state) } }
            override fun onRemoteVideo(uid: String, track: VideoTrack?) {
                scope.launch { _videos.update { if (track == null) it - uid else it + (uid to track) } }
            }
        }
        try { e.prepare(video) } catch (ex: Exception) { e.close(); finish("Could not use the microphone or camera."); return }
        engine = e
        MissedCalls.markSeen(ctx, id)
        CallService.start(ctx, "Group call")
        _ui.value = GroupUi(
            GroupPhase.IN_CALL, id, hostUid, hostName, video, names, invited, listOf(myUid),
            cameraOn = video, speaker = video, startedAt = System.currentTimeMillis()
        )
        callJob?.cancel()
        callJob = scope.launch {
            launch { statsLoop() }
            repo.observe(familyId, id).collect { c ->
                if (c == null) return@collect
                if (c.state == "ended") { finish("The call has ended."); return@collect }
                onJoined(c.joined)
                _ui.update { it.copy(joined = c.joined, names = it.names + c.names) }
            }
        }
    }

    private fun onJoined(joined: List<String>) {
        val e = engine ?: return
        val u = _ui.value
        // People who left.
        val known = peerJobs.keys.toList()
        GroupCallMath.gone(joined, known).forEach { p -> dropPeer(p) }
        // People I connect to (I start the connection) and people who start it with me (I answer).
        joined.filter { it != myUid && it !in peerJobs }.forEach { p ->
            _ui.update { it.copy(peerStates = it.peerStates + (p to "connecting")) }
            peerJobs[p] = scope.launch {
                if (GroupCallMath.isOfferer(myUid, p)) {
                    val sdp = e.offerTo(p) ?: run { markFailed(p); return@launch }
                    val nonce = java.util.UUID.randomUUID().toString().take(8)
                    myNonce[p] = nonce
                    runCatching { repo.sendSignal(familyId, u.callId, myUid, p, "offer", sdp, nonce) }
                    repo.observeSignal(familyId, u.callId, p, myUid, "answer").collect { a ->
                        if (a != null && a.nonce == myNonce[p]) {
                            myNonce[p] = ""   // applied once
                            if (!e.applyAnswer(p, a.sdp)) markFailed(p)
                        }
                    }
                } else {
                    repo.observeSignal(familyId, u.callId, p, myUid, "offer").collect { o ->
                        if (o != null && o.nonce != answered[p]) {
                            answered[p] = o.nonce
                            val ans = e.answer(p, o.sdp)
                            if (ans == null) markFailed(p) else runCatching { repo.sendSignal(familyId, u.callId, myUid, p, "answer", ans, o.nonce) }
                        }
                    }
                }
            }
        }
    }

    private fun dropPeer(p: String) {
        peerJobs.remove(p)?.cancel()
        engine?.closePeer(p)
        myNonce.remove(p); answered.remove(p); retries.remove(p)
        _ui.update { it.copy(peerStates = it.peerStates - p, quality = it.quality - p) }
    }

    private fun markFailed(p: String) { _ui.update { it.copy(peerStates = it.peerStates + (p to "failed")) } }

    private fun onPeerState(p: String, s: PeerConnection.PeerConnectionState) {
        val label = when (s) {
            PeerConnection.PeerConnectionState.CONNECTED -> "connected"
            PeerConnection.PeerConnectionState.DISCONNECTED -> "reconnecting"
            PeerConnection.PeerConnectionState.FAILED -> "failed"
            PeerConnection.PeerConnectionState.CLOSED -> return
            else -> "connecting"
        }
        _ui.update { it.copy(peerStates = it.peerStates + (p to label)) }
        if (s == PeerConnection.PeerConnectionState.FAILED && GroupCallMath.isOfferer(myUid, p)) {
            // Try again twice: the network may have changed (Wi-Fi to mobile data).
            val n = (retries[p] ?: 0) + 1
            retries[p] = n
            if (n <= 2) scope.launch {
                delay(3_000)
                peerJobs.remove(p)?.cancel()
                retries[p] = n
                onJoined(_ui.value.joined)
            }
        }
    }

    private suspend fun statsLoop() {
        while (_ui.value.phase == GroupPhase.IN_CALL) {
            delay(2_000)
            val st = runCatching { engine?.stats() }.getOrNull() ?: continue
            _ui.update { u ->
                u.copy(
                    quality = st.mapValues { GroupCallMath.quality(it.value.rttMs, it.value.loss) },
                    speaking = GroupCallMath.activeSpeaker(st.mapValues { it.value.audioLevel })
                )
            }
        }
    }

    /** Emulator check screens only (DemoActivity): shows a made-up call state, with no connection. */
    fun showForDemo(u: GroupUi) { _ui.value = u }

    fun toggleMute() { val m = !_ui.value.muted; engine?.setMuted(m); _ui.update { it.copy(muted = m) } }
    fun toggleCamera() { val on = !_ui.value.cameraOn; engine?.setCameraOn(on); _ui.update { it.copy(cameraOn = on) } }
    fun switchCamera() { engine?.switchCamera() }
    fun toggleSpeaker() { val on = !_ui.value.speaker; engine?.setSpeaker(on); _ui.update { it.copy(speaker = on) } }

    /** Leaves the call; the call ends for everyone when the last person leaves. [endForAll] is for the host. */
    fun leave(endForAll: Boolean = false) {
        val u = _ui.value
        if (u.phase != GroupPhase.IN_CALL) return
        val others = u.joined.filter { it != myUid }
        scope.launch {
            runCatching { repo.leave(familyId, u.callId, myUid) }
            if (endForAll || others.isEmpty()) runCatching { repo.end(familyId, u.callId) }
            runCatching { repo.cleanUp(familyId, u.callId, myUid, u.invited) }
        }
        finish("You left the call.")
    }

    private fun finish(message: String) {
        callJob?.cancel(); callJob = null
        peerJobs.values.forEach { it.cancel() }; peerJobs.clear()
        myNonce.clear(); answered.clear(); retries.clear()
        engine?.close(); engine = null
        _videos.value = emptyMap()
        appContext?.let { CallService.stop(it) }
        dismissed += _ui.value.callId
        _ui.value = GroupUi(phase = GroupPhase.ENDED, message = message)
        scope.launch { delay(2_500); if (_ui.value.phase == GroupPhase.ENDED) _ui.value = GroupUi() }
    }
}

/** "Missed call" notifications, each call once (kept on the phone). */
object MissedCalls {
    private const val PREFS = "missed_calls"

    /** A call this person took part in: never reported as missed. */
    fun markSeen(ctx: Context, callId: String) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = p.getStringSet("ids", emptySet()).orEmpty()
        if (callId !in seen) p.edit().putStringSet("ids", (seen + callId).toList().takeLast(200).toSet()).apply()
    }

    fun notify(ctx: Context, callId: String, fromName: String, group: Boolean) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = p.getStringSet("ids", emptySet()).orEmpty()
        if (callId in seen) return
        p.edit().putStringSet("ids", (seen + callId).toList().takeLast(200).toSet()).apply()
        val who = fromName.ifBlank { "A family member" }
        com.familyhub.app.work.Notifier.missedCall(ctx, callId.hashCode(), if (group) "Missed group call" else "Missed call", if (group) "$who started a group call" else "$who called you")
    }
}
