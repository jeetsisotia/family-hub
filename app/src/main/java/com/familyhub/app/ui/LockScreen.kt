package com.familyhub.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.familyhub.app.core.theme.AccentCard
import com.familyhub.app.core.theme.AppLogo
import com.familyhub.app.core.theme.AuroraBackground

/** Covers the whole app until the person confirms with fingerprint, face or screen lock. */
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    // Ask straight away; the button is there if they cancel.
    LaunchedEffect(Unit) { onUnlock() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Swallow touches so nothing behind the lock can be tapped.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
        contentAlignment = Alignment.Center
    ) {
        AuroraBackground()
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            AppLogo(size = 96.dp)
            Spacer(modifier = Modifier.height(20.dp))
            Text("Family Hub is locked", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(24.dp))
            AccentCard(onClick = onUnlock, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Fingerprint, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Unlock", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
