package com.familyhub.app.calling

import android.content.Context
import com.familyhub.app.data.remote.CallRecord
import com.familyhub.app.data.remote.CallRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.webrtc.PeerConnection
import org.webrtc.VideoTrack

enum class CallPhase { IDLE, INCOMING, CALLING, CONNECTING, ACTIVE, ENDED }

data class CallUi(
    val phase: CallPhase = CallPhase.IDLE,
    val callId: String = "",
    val peerUid: String = "",
    val peerName: String = "",
    val video: Boolean = false,
    val outgoing: Boolean = false,
    val muted: Boolean = false,
    val cameraOn: Boolean = true,
    val speaker: Boolean = false,
    val bluetooth: Boolean = false,
    val reconnecting: Boolean = false,
    val connectedAt: Long = 0L,
    val message: String = ""
)

/**
 * Runs the one call that can be active at a time. Beta: calls only ring while Family Hub is open, and without
 * a relay server some networks cannot connect (the call then ends with a clear message).
 */
object CallManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val repo = CallRepository()
    private val _ui = MutableStateFlow(CallUi())
    val ui: StateFlow<CallUi> = _ui.asStateFlow()

    private val _remoteVideo = MutableStateFlow<VideoTrack?>(null)
    val remoteVideo: StateFlow<VideoTrack?> = _remoteVideo.asStateFlow()

    private val _history = MutableStateFlow<List<CallRecord>>(emptyList())
    val history: StateFlow<List<CallRecord>> = _history.asStateFlow()

    var engine: CallEngine? = null
        private set

    private var appContext: Context? = null
    private var familyId = ""
    private var myUid = ""
    private var myName = ""
    private var watchJob: Job? = null
    private var callJob: Job? = null
    private var timeoutJob: Job? = null
    private var disconnectJob: Job? = null
    private val dismissed = mutableSetOf<String>()
    private var ringJob: kotlinx.coroutines.Job? = null

    /** Starts watching this person's calls. Called when the app has a signed-in person in a family. */
    fun attach(context: Context, familyId: String, uid: String, name: String) {
        appContext = context.applicationContext
        if (this.familyId == familyId && myUid == uid && watchJob?.isActive == true) { myName = name; return }
        this.familyId = familyId; myUid = uid; myName = name
        watchJob?.cancel()
        watchJob = scope.launch {
            repo.observeMyCalls(familyId, uid).collect { list ->
                _history.value = list
                onRecords(list)
            }
        }
        // "Asha is calling" notification while it rings (heads-up, or full screen when the phone is locked).
        if (ringJob == null) ringJob = scope.launch {
            var ringingId = ""
            _ui.collect { u ->
                val ctx = appContext ?: return@collect
                if (u.phase == CallPhase.INCOMING && u.callId != ringingId) {
                    ringingId = u.callId
                    com.familyhub.app.work.Notifier.ringing(ctx, "${u.peerName.ifBlank { "A family member" }} is calling", if (u.video) "Video call · open Family Hub to answer" else "Voice call · open Family Hub to answer")
                } else if (u.phase != CallPhase.INCOMING && ringingId.isNotEmpty()) {
                    ringingId = ""
                    com.familyhub.app.work.Notifier.stopRinging(ctx)
                }
            }
        }
    }

    fun detach() {
        watchJob?.cancel(); watchJob = null
        familyId = ""; myUid = ""
    }

    private fun onRecords(list: List<CallRecord>) {
        val cur = _ui.value
        appContext?.let { ctx ->
            // Missed calls (rang while away or unanswered): one notification each.
            val now = System.currentTimeMillis()
            list.filter { it.calleeUid == myUid && it.state in setOf("missed", "cancelled", "busy") && now - it.clientAt < 86_400_000L }
                .forEach { MissedCalls.notify(ctx, it.id, it.callerName, group = false) }
        }
        val inGroup = GroupCallManager.ui.value.phase == GroupPhase.IN_CALL
        // A call that rings for me and I am free.
        if ((cur.phase == CallPhase.IDLE || cur.phase == CallPhase.ENDED) && !inGroup) {
            val now = System.currentTimeMillis()
            val ring = list.firstOrNull {
                it.calleeUid == myUid && it.state == "ringing" && it.id !in dismissed && now - it.clientAt < 60_000
            }
            if (ring != null) {
                _ui.value = CallUi(
                    phase = CallPhase.INCOMING, callId = ring.id, peerUid = ring.callerUid, peerName = ring.callerName,
                    video = ring.video, outgoing = false, cameraOn = ring.video
                )
                return
            }
        }
        // Someone else is calling while I am busy (in this kind of call or a group call).
        if ((cur.phase != CallPhase.IDLE && cur.phase != CallPhase.ENDED) || inGroup) {
            val now = System.currentTimeMillis()
            list.firstOrNull { it.calleeUid == myUid && it.state == "ringing" && it.id != cur.callId && it.id !in dismissed && now - it.clientAt < 60_000 }
                ?.let { dismissed += it.id; scope.launch { try { repo.setState(familyId, it.id, "busy") } catch (_: Exception) { } } }
        }
        val mine = list.firstOrNull { it.id == cur.callId } ?: return
        when {
            cur.phase == CallPhase.INCOMING && mine.state != "ringing" -> finish("The call ended before you answered.", cleanup = false)
            cur.outgoing && cur.phase == CallPhase.CALLING && mine.state == "declined" -> finish("${cur.peerName.ifBlank { "They" }} declined the call.")
            cur.outgoing && cur.phase == CallPhase.CALLING && mine.state == "busy" -> finish("${cur.peerName.ifBlank { "They" }} is on another call.")
            (cur.phase == CallPhase.CONNECTING || cur.phase == CallPhase.ACTIVE) && mine.state == "ended" -> finish("Call ended.")
        }
    }

    /** Caller: begins ringing the other person. Microphone (and camera) permission must already be granted. */
    fun startCall(calleeUid: String, calleeName: String, video: Boolean) {
        val ctx = appContext ?: return
        if (_ui.value.phase != CallPhase.IDLE && _ui.value.phase != CallPhase.ENDED) return
        val id = repo.newCallId()
        _ui.value = CallUi(
            phase = CallPhase.CALLING, callId = id, peerUid = calleeUid, peerName = calleeName,
            video = video, outgoing = true, cameraOn = video, speaker = video
        )
        CallService.start(ctx, calleeName)
        callJob?.cancel()
        callJob = scope.launch {
            try {
                val e = newEngine(ctx, video)
                val offer = e.makeOffer()
                if (offer == null) { finish("Could not start the call on this phone.", cleanup = false); return@launch }
                repo.start(familyId, id, myUid, myName, calleeUid, calleeName, video, offer)
                timeoutJob?.cancel()
                timeoutJob = scope.launch {
                    delay(45_000)
                    if (_ui.value.callId == id && _ui.value.phase == CallPhase.CALLING) {
                        try { repo.setState(familyId, id, "missed") } catch (_: Exception) { }
                        finish("No answer.")
                    }
                }
                repo.observeAnswer(familyId, id).collect { sdp ->
                    if (sdp != null && _ui.value.callId == id && _ui.value.phase == CallPhase.CALLING) {
                        _ui.update { it.copy(phase = CallPhase.CONNECTING) }
                        timeoutJob?.cancel()
                        if (!e.applyAnswer(sdp)) finish("The connection could not be set up.")
                        armConnectTimeout(id)
                    }
                }
            } catch (ex: Exception) {
                finish("Could not start the call. Check your internet and try again.", cleanup = false)
            }
        }
    }

    /** Callee: accepts the ringing call. */
    fun accept() {
        val ctx = appContext ?: return
        val cur = _ui.value
        if (cur.phase != CallPhase.INCOMING) return
        _ui.update { it.copy(phase = CallPhase.CONNECTING) }
        CallService.start(ctx, cur.peerName)
        callJob?.cancel()
        callJob = scope.launch {
            try {
                val offer = repo.readSignal(familyId, cur.callId, "offer")
                if (offer == null) { finish("The call is no longer available."); return@launch }
                val e = newEngine(ctx, cur.video)
                val answer = e.makeAnswer(offer)
                if (answer == null) { finish("The connection could not be set up."); return@launch }
                repo.accept(familyId, cur.callId, myUid, answer)
                armConnectTimeout(cur.callId)
            } catch (ex: Exception) {
                finish("Could not answer the call. Check your internet and try again.")
            }
        }
    }

    fun decline() {
        val cur = _ui.value
        if (cur.phase != CallPhase.INCOMING) return
        dismissed += cur.callId
        scope.launch { try { repo.setState(familyId, cur.callId, "declined") } catch (_: Exception) { } }
        _ui.value = CallUi()
    }

    /** Ends or cancels the current call, whoever is on it. */
    fun hangUp() {
        val cur = _ui.value
        when (cur.phase) {
            CallPhase.CALLING -> scope.launch { try { repo.setState(familyId, cur.callId, "cancelled") } catch (_: Exception) { } }
            CallPhase.CONNECTING, CallPhase.ACTIVE -> scope.launch { try { repo.setState(familyId, cur.callId, "ended") } catch (_: Exception) { } }
            else -> {}
        }
        finish("", cleanup = true, silent = true)
    }

    fun clearEnded() { if (_ui.value.phase == CallPhase.ENDED) _ui.value = CallUi() }

    private fun newEngine(ctx: Context, video: Boolean): CallEngine {
        engine?.close()
        val e = CallEngine(ctx)
        e.listener = object : CallEngine.Listener {
            override fun onConnectionState(state: PeerConnection.PeerConnectionState) {
                scope.launch { onConnection(state) }
            }
            override fun onRemoteVideo(track: VideoTrack?) { scope.launch { _remoteVideo.value = track } }
        }
        e.startAudioMode()
        e.prepare(video)
        e.setSpeaker(video)
        engine = e
        return e
    }

    private fun onConnection(state: PeerConnection.PeerConnectionState) {
        when (state) {
            PeerConnection.PeerConnectionState.CONNECTED -> {
                disconnectJob?.cancel()
                connectTimeout?.cancel()
                _ui.update {
                    it.copy(
                        phase = CallPhase.ACTIVE, reconnecting = false,
                        connectedAt = if (it.connectedAt == 0L) System.currentTimeMillis() else it.connectedAt
                    )
                }
            }
            PeerConnection.PeerConnectionState.DISCONNECTED -> {
                _ui.update { it.copy(reconnecting = true) }
                disconnectJob?.cancel()
                disconnectJob = scope.launch {
                    delay(15_000)
                    if (_ui.value.reconnecting) endWithFailure()
                }
            }
            PeerConnection.PeerConnectionState.FAILED -> endWithFailure()
            else -> {}
        }
    }

    private var connectTimeout: Job? = null
    private fun armConnectTimeout(id: String) {
        connectTimeout?.cancel()
        connectTimeout = scope.launch {
            delay(25_000)
            val cur = _ui.value
            if (cur.callId == id && cur.phase == CallPhase.CONNECTING) endWithFailure()
        }
    }

    private fun endWithFailure() {
        val cur = _ui.value
        scope.launch { try { repo.setState(familyId, cur.callId, "ended") } catch (_: Exception) { } }
        finish("The call could not connect. Calls without a relay server do not work on every network, especially mobile data. Try both phones on the same Wi-Fi.")
    }

    // Controls
    fun toggleMute() { val m = !_ui.value.muted; engine?.setMuted(m); _ui.update { it.copy(muted = m) } }
    fun toggleCamera() { val on = !_ui.value.cameraOn; engine?.setCameraOn(on); _ui.update { it.copy(cameraOn = on) } }
    fun switchCamera() { engine?.switchCamera() }
    fun toggleSpeaker() {
        val s = !_ui.value.speaker
        engine?.setBluetooth(false); engine?.setSpeaker(s)
        _ui.update { it.copy(speaker = s, bluetooth = false) }
    }
    fun toggleBluetooth() {
        val e = engine ?: return
        val b = !_ui.value.bluetooth
        if (b) e.setSpeaker(false)
        e.setBluetooth(b)
        _ui.update { it.copy(bluetooth = b, speaker = false) }
    }
    fun hasBluetooth(): Boolean = engine?.hasBluetoothHeadset() ?: false

    private fun finish(message: String, cleanup: Boolean = true, silent: Boolean = false) {
        val cur = _ui.value
        timeoutJob?.cancel(); connectTimeout?.cancel(); disconnectJob?.cancel(); callJob?.cancel()
        engine?.close(); engine = null
        _remoteVideo.value = null
        appContext?.let { CallService.stop(it) }
        if (cur.callId.isNotEmpty()) dismissed += cur.callId
        if (cleanup && cur.callId.isNotEmpty() && familyId.isNotEmpty()) {
            val fid = familyId
            scope.launch { delay(2000); repo.cleanUp(fid, cur.callId) }
        }
        _ui.value = if (silent || message.isBlank()) CallUi() else CallUi(phase = CallPhase.ENDED, peerName = cur.peerName, message = message)
    }
}
