package com.familyhub.app.update

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.familyhub.app.core.theme.GroupedRow
import com.familyhub.app.core.theme.GroupedSection
import kotlinx.coroutines.launch

/** Settings → App updates: this version, the latest release, its notes and a Download button. */
@Composable
fun UpdateSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by UpdateChecker.flow.collectAsState()
    GroupedSection(
        title = "App updates",
        footer = "Updates come from FamilyHub's GitHub Releases. Nothing installs by itself: you download the file and Android " +
            "asks before installing, and refuses it unless it is signed with the same key as this app."
    ) {
        GroupedRow("This version", value = "${UpdateChecker.currentVersionName} (build ${UpdateChecker.currentVersionCode})", divider = true)
        when (val s = st) {
            is UpdateChecker.State.Available -> ReleaseCard(s.release, newer = true) { UpdateChecker.download(ctx, s.release) }
            is UpdateChecker.State.UpToDate -> GroupedRow(
                "You're up to date",
                value = s.latest?.let { "Latest: build ${it.build ?: "?"}" } ?: "No releases yet",
                divider = true
            )
            is UpdateChecker.State.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
            UpdateChecker.State.Checking -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
            UpdateChecker.State.Idle -> {}
        }
        GroupedRow("Check for updates", divider = false) { scope.launch { UpdateChecker.check(ctx) } }
    }
}

@Composable
private fun ReleaseCard(r: UpdateChecker.Release, newer: Boolean, onDownload: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(if (newer) "Update available: ${r.name}" else r.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
        Text(listOfNotNull("build ${r.build ?: "?"}", r.publishedAt.takeIf { it.isNotBlank() }, r.apkBytes.takeIf { it > 0 }?.let { UpdateMath.sizeText(it) })
            .joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        if (r.notes.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text("What's new", fontWeight = FontWeight.Medium)
            Text(r.notes, style = MaterialTheme.typography.bodySmall)
        }
        r.sha256?.let {
            Spacer(Modifier.height(6.dp))
            Text("SHA-256: $it", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, maxLines = 2)
        }
        Spacer(Modifier.height(10.dp))
        Button(onClick = onDownload) { Text("Download update") }
    }
}
