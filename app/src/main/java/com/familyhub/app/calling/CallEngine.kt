package com.familyhub.app.calling

import android.content.Context
import android.media.AudioManager
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Thin wrapper around WebRTC for one call at a time.
 *
 * Connection details are exchanged once (no trickle): we wait for the phone to finish finding its network
 * addresses and send the finished offer or answer. Only a free public STUN server is used; there is no TURN
 * relay, so some networks (many mobile-data connections) cannot connect and the call ends with a clear message.
 */
class CallEngine(private val context: Context) {

    interface Listener {
        fun onConnectionState(state: PeerConnection.PeerConnectionState)
        fun onRemoteVideo(track: VideoTrack?)
    }

    var listener: Listener? = null

    val eglBase: EglBase by lazy { EglBase.create() }

    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var audioSource: org.webrtc.AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var localVideo: VideoTrack? = null
    private var capturer: VideoCapturer? = null
    private var helper: SurfaceTextureHelper? = null
    private var usingFront = true
    private var closed = false
    private var wantVideo = false

    val localVideoTrack: VideoTrack? get() = localVideo
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    companion object {
        // Free public STUN. It only tells the phone its own public address; it never carries the call.
        val STUN_URLS = listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302")
        private var initialised = false

        @Synchronized
        fun ensureInit(context: Context) {
            if (initialised) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions()
            )
            initialised = true
        }
    }

    private fun ensureFactory(): PeerConnectionFactory {
        factory?.let { return it }
        ensureInit(context)
        val f = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        factory = f
        return f
    }

    /** Creates the connection and starts the microphone (and camera for video calls). */
    fun prepare(video: Boolean) {
        val f = ensureFactory()
        val rtc = PeerConnection.RTCConfiguration(STUN_URLS.map { PeerConnection.IceServer.builder(it).createIceServer() }).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        pc = f.createPeerConnection(rtc, object : PeerConnection.Observer {
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(p0: IceCandidate?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: org.webrtc.MediaStream?) {}
            override fun onRemoveStream(p0: org.webrtc.MediaStream?) {}
            override fun onDataChannel(p0: org.webrtc.DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                listener?.onConnectionState(newState)
            }
            override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) {
                val t = transceiver?.receiver?.track()
                if (t is VideoTrack) listener?.onRemoteVideo(t)
            }
        })

        audioSource = f.createAudioSource(MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        })
        audioTrack = f.createAudioTrack("fh-audio", audioSource)
        pc?.addTrack(audioTrack, listOf("fh-stream"))

        wantVideo = video
        if (video) startCamera(f)
    }

    private fun startCamera(f: PeerConnectionFactory) {
        val enumerator = Camera2Enumerator(context)
        val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull() ?: return
        usingFront = enumerator.isFrontFacing(name)
        val cap = enumerator.createCapturer(name, null) ?: return
        capturer = cap
        helper = SurfaceTextureHelper.create("fh-capture", eglBase.eglBaseContext)
        videoSource = f.createVideoSource(cap.isScreencast)
        cap.initialize(helper, context, videoSource!!.capturerObserver)
        cap.startCapture(640, 480, 24)
        localVideo = f.createVideoTrack("fh-video", videoSource)
        pc?.addTrack(localVideo, listOf("fh-stream"))
    }

    private suspend fun createOffer(): SessionDescription = suspendCoroutine { c ->
        pc!!.createOffer(object : SimpleSdp() {
            override fun onCreateSuccess(d: SessionDescription) { c.resume(d) }
            override fun onCreateFailure(e: String?) { c.resume(SessionDescription(SessionDescription.Type.OFFER, "")) }
        }, MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (wantVideo) "true" else "false"))
        })
    }

    private suspend fun createAnswer(): SessionDescription = suspendCoroutine { c ->
        pc!!.createAnswer(object : SimpleSdp() {
            override fun onCreateSuccess(d: SessionDescription) { c.resume(d) }
            override fun onCreateFailure(e: String?) { c.resume(SessionDescription(SessionDescription.Type.ANSWER, "")) }
        }, MediaConstraints())
    }

    private suspend fun setLocal(d: SessionDescription): Boolean = suspendCoroutine { c ->
        pc!!.setLocalDescription(object : SimpleSdp() {
            override fun onSetSuccess() { c.resume(true) }
            override fun onSetFailure(e: String?) { c.resume(false) }
        }, d)
    }

    private suspend fun setRemote(d: SessionDescription): Boolean = suspendCoroutine { c ->
        pc!!.setRemoteDescription(object : SimpleSdp() {
            override fun onSetSuccess() { c.resume(true) }
            override fun onSetFailure(e: String?) { c.resume(false) }
        }, d)
    }

    /** Waits (up to about 4 seconds) until the phone has found its network addresses. */
    private suspend fun waitForGathering() {
        val started = System.currentTimeMillis()
        while (pc?.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE &&
            System.currentTimeMillis() - started < 4000 && !closed) {
            kotlinx.coroutines.delay(100)
        }
    }

    /** Caller side: returns the finished offer text, or null if it could not be made. */
    suspend fun makeOffer(): String? {
        val offer = createOffer()
        if (offer.description.isEmpty() || !setLocal(offer)) return null
        waitForGathering()
        return pc?.localDescription?.description
    }

    /** Callee side: takes the offer, returns the finished answer text, or null. */
    suspend fun makeAnswer(offerSdp: String): String? {
        if (!setRemote(SessionDescription(SessionDescription.Type.OFFER, offerSdp))) return null
        val answer = createAnswer()
        if (answer.description.isEmpty() || !setLocal(answer)) return null
        waitForGathering()
        return pc?.localDescription?.description
    }

    /** Caller side: applies the callee's answer. */
    suspend fun applyAnswer(answerSdp: String): Boolean =
        setRemote(SessionDescription(SessionDescription.Type.ANSWER, answerSdp))

    fun setMuted(muted: Boolean) { audioTrack?.setEnabled(!muted) }
    fun setCameraOn(on: Boolean) { localVideo?.setEnabled(on) }

    fun switchCamera() {
        (capturer as? CameraVideoCapturer)?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFront: Boolean) { usingFront = isFront }
            override fun onCameraSwitchError(error: String?) {}
        })
    }

    val isFrontCamera: Boolean get() = usingFront

    fun attachLocal(view: SurfaceViewRenderer) { localVideo?.addSink(view) }
    fun detachLocal(view: SurfaceViewRenderer) { try { localVideo?.removeSink(view) } catch (_: Exception) { } }

    // Speaker, earpiece and Bluetooth headset routing.
    @Suppress("DEPRECATION")
    fun startAudioMode() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    }

    @Suppress("DEPRECATION")
    fun setSpeaker(on: Boolean) { audioManager.isSpeakerphoneOn = on }

    @Suppress("DEPRECATION")
    fun setBluetooth(on: Boolean) {
        try {
            if (on) { audioManager.startBluetoothSco(); audioManager.isBluetoothScoOn = true }
            else { audioManager.stopBluetoothSco(); audioManager.isBluetoothScoOn = false }
        } catch (_: Exception) { }
    }

    @Suppress("DEPRECATION")
    fun hasBluetoothHeadset(): Boolean = try { audioManager.isBluetoothScoAvailableOffCall } catch (_: Exception) { false }

    fun close() {
        if (closed) return
        closed = true
        try { (capturer)?.stopCapture() } catch (_: Exception) { }
        try { capturer?.dispose() } catch (_: Exception) { }
        try { localVideo?.dispose() } catch (_: Exception) { }
        try { videoSource?.dispose() } catch (_: Exception) { }
        try { helper?.dispose() } catch (_: Exception) { }
        try { audioTrack?.dispose() } catch (_: Exception) { }
        try { audioSource?.dispose() } catch (_: Exception) { }
        try { pc?.close() } catch (_: Exception) { }
        try { pc?.dispose() } catch (_: Exception) { }
        try { factory?.dispose() } catch (_: Exception) { }
        pc = null; factory = null
        setBluetooth(false)
        @Suppress("DEPRECATION")
        run { audioManager.isSpeakerphoneOn = false; audioManager.mode = AudioManager.MODE_NORMAL }
        try { eglBase.release() } catch (_: Exception) { }
    }
}

/** SdpObserver with empty defaults so each use only overrides what it needs. */
open class SimpleSdp : SdpObserver {
    override fun onCreateSuccess(d: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(e: String?) {}
    override fun onSetFailure(e: String?) {}
}
