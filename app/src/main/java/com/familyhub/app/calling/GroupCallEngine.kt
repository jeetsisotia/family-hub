package com.familyhub.app.calling

import android.content.Context
import android.media.AudioManager
import org.webrtc.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * WebRTC for a group call: one microphone (and camera) shared by one connection per other person (mesh).
 * Uses the same free STUN servers as one-to-one calls; there is no relay server (TURN), so on some mobile
 * networks two phones cannot reach each other.
 */
class GroupCallEngine(private val context: Context) {

    interface Listener {
        fun onPeerState(uid: String, state: PeerConnection.PeerConnectionState)
        fun onRemoteVideo(uid: String, track: VideoTrack?)
    }

    var listener: Listener? = null
    val eglBase: EglBase by lazy { EglBase.create() }
    private var factory: PeerConnectionFactory? = null
    private val peers = java.util.concurrent.ConcurrentHashMap<String, PeerConnection>()
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var localVideo: VideoTrack? = null
    private var capturer: VideoCapturer? = null
    private var helper: SurfaceTextureHelper? = null
    @Volatile private var closed = false
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val localVideoTrack: VideoTrack? get() = localVideo
    val connectedPeers: Set<String> get() = peers.keys.toSet()

    fun prepare(video: Boolean) {
        CallEngine.ensureInit(context)
        val f = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        factory = f
        audioSource = f.createAudioSource(MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        })
        audioTrack = f.createAudioTrack("fhg-audio", audioSource)
        if (video) {
            val en = Camera2Enumerator(context)
            val name = en.deviceNames.firstOrNull { en.isFrontFacing(it) } ?: en.deviceNames.firstOrNull()
            val cap = name?.let { en.createCapturer(it, null) }
            if (cap != null) {
                capturer = cap
                helper = SurfaceTextureHelper.create("fhg-capture", eglBase.eglBaseContext)
                videoSource = f.createVideoSource(cap.isScreencast)
                cap.initialize(helper, context, videoSource!!.capturerObserver)
                // Smaller than one-to-one: the picture is sent to up to 3 people at once.
                cap.startCapture(480, 360, 15)
                localVideo = f.createVideoTrack("fhg-video", videoSource)
            }
        }
        @Suppress("DEPRECATION")
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = video
    }

    private fun newPeer(uid: String): PeerConnection? {
        closePeer(uid)
        val f = factory ?: return null
        val rtc = PeerConnection.RTCConfiguration(CallEngine.STUN_URLS.map { PeerConnection.IceServer.builder(it).createIceServer() }).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        val pc = f.createPeerConnection(rtc, object : PeerConnection.Observer {
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(p0: IceCandidate?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) { listener?.onPeerState(uid, newState) }
            override fun onTrack(transceiver: RtpTransceiver?) {
                val t = transceiver?.receiver?.track()
                if (t is VideoTrack) listener?.onRemoteVideo(uid, t)
            }
        }) ?: return null
        audioTrack?.let { pc.addTrack(it, listOf("fhg")) }
        localVideo?.let { pc.addTrack(it, listOf("fhg")) }
        peers[uid] = pc
        return pc
    }

    private suspend fun create(pc: PeerConnection, offer: Boolean): SessionDescription? = suspendCoroutine { c ->
        val obs = object : SimpleSdp() {
            override fun onCreateSuccess(d: SessionDescription) { c.resume(d) }
            override fun onCreateFailure(e: String?) { c.resume(null) }
        }
        if (offer) pc.createOffer(obs, MediaConstraints()) else pc.createAnswer(obs, MediaConstraints())
    }

    private suspend fun setLocal(pc: PeerConnection, d: SessionDescription): Boolean = suspendCoroutine { c ->
        pc.setLocalDescription(object : SimpleSdp() {
            override fun onSetSuccess() { c.resume(true) }
            override fun onSetFailure(e: String?) { c.resume(false) }
        }, d)
    }

    private suspend fun setRemote(pc: PeerConnection, d: SessionDescription): Boolean = suspendCoroutine { c ->
        pc.setRemoteDescription(object : SimpleSdp() {
            override fun onSetSuccess() { c.resume(true) }
            override fun onSetFailure(e: String?) { c.resume(false) }
        }, d)
    }

    private suspend fun gathered(pc: PeerConnection) {
        val t = System.currentTimeMillis()
        while (pc.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE && System.currentTimeMillis() - t < 4000 && !closed) kotlinx.coroutines.delay(100)
    }

    /** I start the connection to [uid]: returns the finished offer, or null. */
    suspend fun offerTo(uid: String): String? {
        val pc = newPeer(uid) ?: return null
        val o = create(pc, true) ?: return null
        if (!setLocal(pc, o)) return null
        gathered(pc)
        return pc.localDescription?.description
    }

    /** [uid] started the connection: returns my finished answer, or null. */
    suspend fun answer(uid: String, offerSdp: String): String? {
        val pc = newPeer(uid) ?: return null
        if (!setRemote(pc, SessionDescription(SessionDescription.Type.OFFER, offerSdp))) return null
        val a = create(pc, false) ?: return null
        if (!setLocal(pc, a)) return null
        gathered(pc)
        return pc.localDescription?.description
    }

    suspend fun applyAnswer(uid: String, sdp: String): Boolean {
        val pc = peers[uid] ?: return false
        return setRemote(pc, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    fun closePeer(uid: String) {
        peers.remove(uid)?.let { runCatching { it.close() }; runCatching { it.dispose() } }
        listener?.onRemoteVideo(uid, null)
    }

    data class PeerStats(val rttMs: Double?, val loss: Double?, val audioLevel: Double)

    /** Round-trip time, packet loss and how loud each person is right now (for quality and who is speaking). */
    suspend fun stats(): Map<String, PeerStats> {
        val out = mutableMapOf<String, PeerStats>()
        for ((uid, pc) in peers) {
            val report = suspendCoroutine<RTCStatsReport?> { c -> runCatching { pc.getStats { c.resume(it) } }.onFailure { c.resume(null) } } ?: continue
            var rtt: Double? = null; var lost = 0.0; var got = 0.0; var level = 0.0
            for (s in report.statsMap.values) {
                val m = s.members
                when (s.type) {
                    "candidate-pair" -> if (m["state"] == "succeeded" && m["nominated"] == true) (m["currentRoundTripTime"] as? Number)?.let { rtt = it.toDouble() * 1000 }
                    "inbound-rtp" -> {
                        (m["packetsLost"] as? Number)?.let { lost += it.toDouble() }
                        (m["packetsReceived"] as? Number)?.let { got += it.toDouble() }
                        if (m["kind"] == "audio") (m["audioLevel"] as? Number)?.let { level = maxOf(level, it.toDouble()) }
                    }
                }
            }
            out[uid] = PeerStats(rtt, if (got + lost > 0) lost / (got + lost) else null, level)
        }
        return out
    }

    fun setMuted(m: Boolean) { audioTrack?.setEnabled(!m) }
    fun setCameraOn(on: Boolean) { localVideo?.setEnabled(on) }
    fun switchCamera() {
        (capturer as? CameraVideoCapturer)?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFront: Boolean) {}
            override fun onCameraSwitchError(error: String?) {}
        })
    }
    @Suppress("DEPRECATION")
    fun setSpeaker(on: Boolean) { audioManager.isSpeakerphoneOn = on }

    fun close() {
        if (closed) return
        closed = true
        peers.keys.toList().forEach { closePeer(it) }
        runCatching { capturer?.stopCapture() }; runCatching { capturer?.dispose() }
        runCatching { localVideo?.dispose() }; runCatching { videoSource?.dispose() }; runCatching { helper?.dispose() }
        runCatching { audioTrack?.dispose() }; runCatching { audioSource?.dispose() }
        runCatching { factory?.dispose() }
        factory = null
        @Suppress("DEPRECATION")
        run { audioManager.isSpeakerphoneOn = false; audioManager.mode = AudioManager.MODE_NORMAL }
        runCatching { eglBase.release() }
    }
}
