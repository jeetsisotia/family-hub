package com.familyhub.app.ui.calls

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.familyhub.app.calling.GroupCallManager
import com.familyhub.app.calling.GroupCallMath
import com.familyhub.app.calling.GroupPhase
import com.familyhub.app.calling.GroupUi
import com.familyhub.app.core.theme.GlassDialog
import com.familyhub.app.ui.AppEnv
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

private val CallBg = Brush.verticalGradient(listOf(Color(0xFF15161B), Color(0xFF0B0B0E)))

/** Shown over the app while a group call rings or is going on. */
@Composable
fun GroupCallOverlay(env: AppEnv?) {
    val ui by GroupCallManager.ui.collectAsState()
    when (ui.phase) {
        GroupPhase.IDLE -> Unit
        GroupPhase.RINGING -> GroupRinging(ui)
        GroupPhase.IN_CALL -> GroupInCall(ui, env?.uid.orEmpty())
        GroupPhase.ENDED -> Box(Modifier.fillMaxSize().background(CallBg), contentAlignment = Alignment.Center) {
            Text(ui.message, color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
        }
    }
}

@Composable
private fun GroupRinging(ui: GroupUi) {
    val others = ui.invited.mapNotNull { ui.names[it] }.filter { it != ui.hostName }
    val start = rememberCallStarter { _, _, _ -> GroupCallManager.accept() }
    Column(
        Modifier.fillMaxSize().background(CallBg).systemBarsPadding().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(96.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Groups, null, tint = Color.White, modifier = Modifier.size(52.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text("${ui.hostName.ifBlank { "Family" }} started a group call", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            (if (ui.video) "Video · " else "Voice · ") + "with " + (listOf("you") + others).joinToString(", "),
            color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center
        )
        Spacer(Modifier.weight(2f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RoundButton(Icons.Rounded.CallEnd, "Decline", Color(0xFFFF3B30)) { GroupCallManager.decline() }
            RoundButton(if (ui.video) Icons.Rounded.Videocam else Icons.Rounded.Call, "Join", Color(0xFF34C759)) { start(ui.video, "", "") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun GroupInCall(ui: GroupUi, me: String) {
    val videos by GroupCallManager.remoteVideos.collectAsState()
    var seconds by remember { mutableLongStateOf(0L) }
    var confirmEnd by remember { mutableStateOf(false) }
    LaunchedEffect(ui.startedAt) { while (true) { seconds = (System.currentTimeMillis() - ui.startedAt) / 1000; delay(1000) } }
    val others = ui.invited.filter { it != me }
    val inCall = ui.joined.filter { it != me }
    val worst = ui.quality.values.maxByOrNull { it.ordinal }?.takeIf { it != GroupCallMath.Quality.UNKNOWN }
    Column(Modifier.fillMaxSize().background(CallBg).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Group call", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                Text(
                    if (inCall.isEmpty()) "Ringing ${others.size} ${if (others.size == 1) "person" else "people"}…" else "${inCall.size + 1} in the call · ${formatDuration(seconds)}",
                    color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall
                )
            }
            if (worst != null) {
                val c = when (worst) { GroupCallMath.Quality.GOOD -> Color(0xFF34C759); GroupCallMath.Quality.FAIR -> Color(0xFFFFCC00); else -> Color(0xFFFF3B30) }
                Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.1f)).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.SignalCellularAlt, null, tint = c, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                    Text(worst.label, color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        // Tiles: everyone invited except me, plus me.
        val tiles = others + me
        LazyVerticalGrid(
            GridCells.Fixed(GroupCallMath.columns(tiles.size)), Modifier.weight(1f).padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(tiles, key = { it }) { uid ->
                val isMe = uid == me
                val state = when {
                    isMe -> "You"
                    uid !in ui.joined -> "Ringing…"
                    else -> when (ui.peerStates[uid]) { "connected" -> ""; "reconnecting" -> "Reconnecting…"; "failed" -> "Couldn't connect"; else -> "Connecting…" }
                }
                Tile(
                    name = if (isMe) "You" else ui.names[uid].orEmpty().ifBlank { "Family member" },
                    state = state,
                    track = if (isMe) (if (ui.video && ui.cameraOn) GroupCallManager.engine?.localVideoTrack else null) else videos[uid],
                    mirror = isMe,
                    speaking = ui.speaking == uid,
                    muted = isMe && ui.muted,
                    height = if (tiles.size <= 2) 260.dp else 200.dp
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            SmallRound(if (ui.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, if (ui.muted) "Unmute" else "Mute", ui.muted) { GroupCallManager.toggleMute() }
            if (ui.video) {
                SmallRound(if (ui.cameraOn) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff, if (ui.cameraOn) "Camera off" else "Camera on", !ui.cameraOn) { GroupCallManager.toggleCamera() }
                SmallRound(Icons.Rounded.Cameraswitch, "Switch camera", false) { GroupCallManager.switchCamera() }
            }
            SmallRound(if (ui.speaker) Icons.Rounded.VolumeUp else Icons.Rounded.Hearing, if (ui.speaker) "Speaker on" else "Speaker off", ui.speaker) { GroupCallManager.toggleSpeaker() }
            RoundButton(Icons.Rounded.CallEnd, "Leave", Color(0xFFFF3B30), size = 60) { if (ui.hostUid == me && inCall.isNotEmpty()) confirmEnd = true else GroupCallManager.leave() }
        }
    }
    if (confirmEnd) GlassDialog(
        onDismissRequest = { confirmEnd = false },
        title = { Text("Leave the group call?") },
        text = { Text("You can leave and let the others carry on, or end the call for everyone.") },
        confirmButton = { TextButton(onClick = { confirmEnd = false; GroupCallManager.leave(endForAll = true) }) { Text("End for everyone", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmEnd = false; GroupCallManager.leave() }) { Text("Just leave") } }
    )
}

@Composable
private fun Tile(name: String, state: String, track: VideoTrack?, mirror: Boolean, speaking: Boolean, muted: Boolean, height: androidx.compose.ui.unit.Dp) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier.fillMaxWidth().height(height).clip(shape).background(Color.White.copy(alpha = 0.07f))
            .border(if (speaking) 3.dp else 0.5.dp, if (speaking) Color(0xFF34C759) else Color.White.copy(alpha = 0.15f), shape)
    ) {
        val egl = GroupCallManager.engine?.eglBase
        if (track != null && egl != null) {
            key(track) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        SurfaceViewRenderer(ctx).apply {
                            init(egl.eglBaseContext, null)
                            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                            setMirror(mirror)
                            setEnableHardwareScaler(true)
                            track.addSink(this)
                        }
                    },
                    onRelease = { v -> runCatching { track.removeSink(v) }; v.release() }
                )
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(72.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                    Text(name.take(1).uppercase(), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Row(Modifier.align(Alignment.BottomStart).padding(10.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (muted) { Icon(Icons.Rounded.MicOff, "Muted", tint = Color.White, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
            Text(name, color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            if (state.isNotBlank() && state != "You") { Spacer(Modifier.width(6.dp)); Text(state, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color, size: Int = 70, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size.dp).clip(CircleShape).background(color).clickable { onClick() }, contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size((size * 0.45).dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun SmallRound(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(if (active) Color.White else Color.White.copy(alpha = 0.16f)).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) { Icon(icon, label, tint = if (active) Color.Black else Color.White) }
}

/** Choose up to 3 family members and start a voice or video group call. */
@Composable
fun GroupCallStartDialog(env: AppEnv, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val others = env.members.filter { it.uid != env.uid && it.status == "ACTIVE" }
    var picked by remember { mutableStateOf(setOf<String>()) }
    val start = rememberCallStarter { video, _, _ ->
        val err = GroupCallManager.start(others.filter { it.uid in picked }.associate { it.uid to it.name }, video)
        if (err != null) Toast.makeText(ctx, err, Toast.LENGTH_LONG).show() else onDismiss()
    }
    GlassDialog(
        onDismissRequest = onDismiss,
        title = { Text("Group call") },
        text = {
            Column {
                Text("Choose up to 3 people (${GroupCallMath.MAX_PEOPLE} with you). They must have Family Hub open to hear it ring.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                if (others.isEmpty()) Text("No other active family members yet.")
                others.forEach { m ->
                    val on = m.uid in picked
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = on || picked.size < GroupCallMath.MAX_PEOPLE - 1) {
                            picked = if (on) picked - m.uid else picked + m.uid
                        }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = on, onCheckedChange = null, enabled = on || picked.size < GroupCallMath.MAX_PEOPLE - 1)
                        Spacer(Modifier.width(6.dp))
                        Text(m.name)
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(enabled = picked.isNotEmpty(), onClick = { start(false, "", "") }) { Icon(Icons.Rounded.Call, null); Spacer(Modifier.width(4.dp)); Text("Voice") }
                TextButton(enabled = picked.isNotEmpty(), onClick = { start(true, "", "") }) { Icon(Icons.Rounded.Videocam, null); Spacer(Modifier.width(4.dp)); Text("Video") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
