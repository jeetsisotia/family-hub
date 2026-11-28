package com.familyhub.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.familyhub.app.data.remote.SyncMath
import com.familyhub.app.data.remote.SyncStatus

/**
 * Offline or still-syncing notice. Hidden when everything is saved, unless [always] (Settings shows it always).
 */
@Composable
fun SyncBadge(modifier: Modifier = Modifier, always: Boolean = false) {
    val s by SyncStatus.flow.collectAsState()
    if (!always && s.level == SyncMath.Level.SAVED) return
    val (icon, tint) = when (s.level) {
        SyncMath.Level.SAVED -> Icons.Rounded.CloudDone to MaterialTheme.colorScheme.primary
        SyncMath.Level.SYNCING -> Icons.Rounded.CloudSync to MaterialTheme.colorScheme.tertiary
        else -> Icons.Rounded.CloudOff to MaterialTheme.colorScheme.error
    }
    Row(
        modifier.clip(RoundedCornerShape(50)).background(tint.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(s.text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}
