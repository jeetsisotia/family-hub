package com.familyhub.app.ui.calls

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.familyhub.app.calling.CallManager
import com.familyhub.app.calling.CallPhase
import com.familyhub.app.core.theme.GlassCard
import com.familyhub.app.data.remote.CallRecord
import com.familyhub.app.ui.AppEnv
import com.familyhub.app.ui.common.PersonAvatar
import com.familyhub.app.ui.common.ScreenTopBar
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Plain-words limits shown wherever calling is offered. */
const val CALL_BETA_NOTE =
    "Beta. Calls ring only while Family Hub is open on the other phone. They use a free public server to find each phone " +
        "(it sees both phones' internet addresses, nothing else) and no relay, so a call may fail on some networks, " +
        "especially mobile data. Calls are not end-to-end encrypted by Family Hub; the audio and video go straight between the two phones, scrambled by WebRTC."

/** Asks for the microphone (and camera) and then runs [go]. */
@Composable
fun rememberCallStarter(go: (video: Boolean, calleeUid: String, calleeName: String) -> Unit): (Boolean, String, String) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Triple<Boolean, String, String>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val p = pending
        pending = null
        if (p != null) {
            val micOk = result[Manifest.permission.RECORD_AUDIO] == true ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val camOk = !p.first || result[Manifest.permission.CAMERA] == true ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            if (micOk && camOk) go(p.first, p.second, p.third)
            else Toast.makeText(context, if (!micOk) "Calls need the microphone." else "Video calls need the camera.", Toast.LENGTH_LONG).show()
        }
    }
    return { video, uid, name ->
        val need = buildList {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            if (video && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.CAMERA)
        }
        if (need.isEmpty()) go(video, uid, name)
        else { pending = Triple(video, uid, name); launcher.launch(need.toTypedArray()) }
    }
}

/** Phone and camera buttons for the top bar of a one-to-one chat. */
@Composable
fun androidx.compose.foundation.layout.RowScope.CallButtons(otherUid: String, otherName: String) {
    val busy = CallManager.ui.collectAsState().value.phase.let { it != CallPhase.IDLE && it != CallPhase.ENDED }
    val start = rememberCallStarter { video, uid, name -> CallManager.startCall(uid, name, video) }
    IconButton(enabled = !busy, onClick = { start(false, otherUid, otherName) }) { Icon(Icons.Rounded.Call, "Voice call (Beta)") }
    IconButton(enabled = !busy, onClick = { start(true, otherUid, otherName) }) { Icon(Icons.Rounded.Videocam, "Video call (Beta)") }
}

private fun stateText(c: CallRecord, me: String): Pair<String, Boolean> {
    val mine = c.callerUid == me
    return when (c.state) {
        "ended" -> (if (mine) "Outgoing" else "Incoming") to false
        "declined" -> (if (mine) "Declined by them" else "You declined") to !mine
        "busy" -> (if (mine) "They were busy" else "Missed (you were busy)") to !mine
        "missed", "cancelled" -> (if (mine) "No answer" else "Missed call") to !mine
        "accepted" -> "Connected" to false
        else -> "Ringing" to false
    }
}

/** Call history, and a list of family members to call. */
@Composable
fun CallsScreen(env: AppEnv) {
    val history by CallManager.history.collectAsState()
    val groupHistory by com.familyhub.app.calling.GroupCallManager.history.collectAsState()
    val ui by CallManager.ui.collectAsState()
    val start = rememberCallStarter { video, uid, name -> CallManager.startCall(uid, name, video) }
    val fmt = remember { SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()) }
    val others = env.members.filter { it.uid != env.uid && it.status == "ACTIVE" }
    val busy = ui.phase != CallPhase.IDLE && ui.phase != CallPhase.ENDED

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Calls", onBack = env.onBack)
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Beta", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(4.dp))
                        Text(CALL_BETA_NOTE, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                // Group call: up to 4 people, each phone connected directly to the others.
                var showGroup by remember { mutableStateOf(false) }
                val gUi by com.familyhub.app.calling.GroupCallManager.ui.collectAsState()
                Button(
                    onClick = { showGroup = true }, enabled = !busy && gUi.phase == com.familyhub.app.calling.GroupPhase.IDLE && others.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                ) { Icon(Icons.Rounded.Groups, null); Spacer(Modifier.width(8.dp)); Text("Start a group call") }
                if (showGroup) GroupCallStartDialog(env) { showGroup = false }
            }
            item { Text("Call someone", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp)) }
            if (others.isEmpty()) item { Text("No other active family members yet.", style = MaterialTheme.typography.bodyMedium) }
            items(others, key = { it.uid }) { m ->
                GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        PersonAvatar(name = m.name, photoData = m.photoData, photoUrl = m.photoUrl, size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(m.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        IconButton(enabled = !busy, onClick = { start(false, m.uid, m.name) }) { Icon(Icons.Rounded.Call, "Voice call ${m.name}") }
                        IconButton(enabled = !busy, onClick = { start(true, m.uid, m.name) }) { Icon(Icons.Rounded.Videocam, "Video call ${m.name}") }
                    }
                }
            }
            item { Text("Recent", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp)) }
            val groups = groupHistory.take(10)
            if (history.isEmpty() && groups.isEmpty()) item { Text("No calls yet.", style = MaterialTheme.typography.bodyMedium) }
            items(groups, key = { "g" + it.id }) { g ->
                val missed = g.hostUid != env.uid && env.uid !in g.joined && g.state == "ended"
                GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Groups, null, tint = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(g.names.filterKeys { it != env.uid }.values.joinToString(", ").ifBlank { "Group call" }, fontWeight = FontWeight.Medium, maxLines = 1)
                            Text((if (g.video) "Group video · " else "Group voice · ") + (if (g.hostUid == env.uid) "you started it" else "started by ${g.hostName}") + " · " + fmt.format(java.util.Date(g.clientAt)),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            items(history, key = { it.id }) { c ->
                val (label, missed) = stateText(c, env.uid)
                val name = c.otherName(env.uid).ifBlank { "Family member" }
                GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (c.video) Icons.Rounded.Videocam else Icons.Rounded.Call, null,
                            tint = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, fontWeight = FontWeight.Medium, color = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                            Text("$label · ${fmt.format(Date(c.clientAt))}", style = MaterialTheme.typography.bodySmall)
                        }
                        val len = if (c.answeredAt != null && c.endedAt != null) (c.endedAt - c.answeredAt) / 1000 else 0L
                        if (len > 0) Text(formatDuration(len), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

fun formatDuration(seconds: Long): String {
    val m = seconds / 60; val s = seconds % 60
    return if (m >= 60) "%d:%02d:%02d".format(m / 60, m % 60, s) else "%d:%02d".format(m, s)
}

/** Full-screen call UI shown above everything while a call rings, connects, runs, or has just ended. */
@Composable
fun CallOverlay(env: AppEnv?) {
    val ui by CallManager.ui.collectAsState()
    if (ui.phase == CallPhase.IDLE) return
    val context = LocalContext.current
    val answer = rememberCallStarter { _, _, _ -> CallManager.accept() }
    val dark = Color(0xFF111418)

    Box(Modifier.fillMaxSize().background(dark)) {
        val remote by CallManager.remoteVideo.collectAsState()
        val engine = CallManager.engine
        val showVideo = ui.video && (ui.phase == CallPhase.ACTIVE || ui.phase == CallPhase.CONNECTING)

        if (showVideo && engine != null && remote != null) {
            val egl = engine.eglBase
            val track = remote
            androidx.compose.runtime.key(track) { AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    SurfaceViewRenderer(ctx).apply {
                        init(egl.eglBaseContext, null)
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                        setEnableHardwareScaler(true)
                        track?.addSink(this)
                    }
                },
                onRelease = { v -> try { track?.removeSink(v) } catch (_: Exception) { }; v.release() }
            ) }
        }

        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Family Hub call · Beta", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
            Spacer(Modifier.height(if (showVideo && remote != null) 4.dp else 48.dp))
            if (!(showVideo && remote != null)) {
                PersonAvatar(name = ui.peerName, photoData = env?.members?.firstOrNull { it.uid == ui.peerUid }?.photoData,
                    photoUrl = env?.members?.firstOrNull { it.uid == ui.peerUid }?.photoUrl, size = 120.dp)
                Spacer(Modifier.height(18.dp))
            }
            Text(ui.peerName.ifBlank { "Family member" }, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))

            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(ui.phase) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
            val status = when (ui.phase) {
                CallPhase.INCOMING -> if (ui.video) "Incoming video call…" else "Incoming voice call…"
                CallPhase.CALLING -> "Calling…"
                CallPhase.CONNECTING -> "Connecting…"
                CallPhase.ACTIVE -> if (ui.reconnecting) "Reconnecting…" else formatDuration(((now - ui.connectedAt) / 1000).coerceAtLeast(0))
                CallPhase.ENDED -> ui.message
                else -> ""
            }
            Text(
                status, color = Color.White.copy(alpha = 0.85f), fontSize = if (ui.phase == CallPhase.ENDED) 15.sp else 18.sp,
                textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp)
            )

            Spacer(Modifier.weight(1f))

            when (ui.phase) {
                CallPhase.INCOMING -> Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundAction(Icons.Rounded.CallEnd, "Decline", Color(0xFFFF3B30)) { CallManager.decline() }
                    RoundAction(if (ui.video) Icons.Rounded.Videocam else Icons.Rounded.Call, "Answer", Color(0xFF34C759)) {
                        answer(ui.video, ui.peerUid, ui.peerName)
                    }
                }
                CallPhase.ENDED -> TextButton(onClick = { CallManager.clearEnded() }) { Text("Close", color = Color.White) }
                else -> {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(Color.White.copy(alpha = 0.14f)).padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically
                    ) {
                        SmallAction(if (ui.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, if (ui.muted) "Unmute" else "Mute", ui.muted) { CallManager.toggleMute() }
                        SmallAction(Icons.Rounded.VolumeUp, "Speaker", ui.speaker) { CallManager.toggleSpeaker() }
                        if (CallManager.hasBluetooth()) SmallAction(Icons.Rounded.Bluetooth, "Bluetooth", ui.bluetooth) { CallManager.toggleBluetooth() }
                        if (ui.video) {
                            SmallAction(if (ui.cameraOn) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff, "Camera", !ui.cameraOn) { CallManager.toggleCamera() }
                            SmallAction(Icons.Rounded.FlipCameraAndroid, "Flip", false) { CallManager.switchCamera() }
                        }
                        RoundAction(Icons.Rounded.CallEnd, null, Color(0xFFFF3B30), 52) { CallManager.hangUp() }
                    }
                }
            }
        }

        // My own camera, small and draggable.
        if (ui.video && ui.cameraOn && engine != null && engine.localVideoTrack != null &&
            ui.phase != CallPhase.INCOMING && ui.phase != CallPhase.ENDED
        ) {
            var ox by remember { mutableFloatStateOf(0f) }
            var oy by remember { mutableFloatStateOf(0f) }
            Box(
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 36.dp, end = 16.dp)
                    .offset { IntOffset(ox.roundToInt(), oy.roundToInt()) }
                    .size(width = 104.dp, height = 148.dp).clip(RoundedCornerShape(16.dp))
                    .pointerInput(Unit) { detectDragGestures { change, drag -> change.consume(); ox += drag.x; oy += drag.y } }
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        SurfaceViewRenderer(ctx).apply {
                            init(engine.eglBase.eglBaseContext, null)
                            setMirror(engine.isFrontCamera)
                            setZOrderMediaOverlay(true)
                            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                            engine.attachLocal(this)
                        }
                    },
                    onRelease = { v -> engine.detachLocal(v); v.release() }
                )
            }
        }
    }
}

@Composable
private fun RoundAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String?, color: Color, size: Int = 68, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(size.dp).clip(CircleShape).background(color).clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) { Icon(icon, label ?: "End call", tint = Color.White, modifier = Modifier.size((size / 2.4).dp)) }
        if (label != null) { Spacer(Modifier.height(6.dp)); Text(label, color = Color.White, fontSize = 13.sp) }
    }
}

@Composable
private fun SmallAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(if (on) Color.White else Color.White.copy(alpha = 0.2f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, label, tint = if (on) Color(0xFF111418) else Color.White) }
}
