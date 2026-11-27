package com.familyhub.app.ui.family

import com.familyhub.app.core.theme.GlassFilterChip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.familyhub.app.core.theme.GlassCard
import com.familyhub.app.family.Planner
import com.familyhub.app.ui.AppEnv
import com.familyhub.app.ui.ai.PlannerAnswers
import com.familyhub.app.ui.common.ScreenTopBar

/**
 * The weekly family brief (More → Weekly brief): events, tasks, bills, documents, maintenance, recent activity and
 * money, made on the phone from the family's data. Each section can be switched off; the choice stays on this phone.
 * The same brief answers "weekly brief" in either AI screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WeeklyBriefScreen(env: AppEnv) {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(PlannerAnswers.briefSections(ctx)) }
    val text = remember(env.data, on) { PlannerAnswers.brief(env, on) }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Weekly brief", onBack = env.onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Show in the brief", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Planner.BRIEF_SECTIONS.forEach { sct ->
                    GlassFilterChip(selected = sct in on, onClick = {
                        on = if (sct in on) on - sct else on + sct
                        PlannerAnswers.briefPrefs(ctx).edit().putStringSet("sections", on).apply()
                    }, label = { Text(sct, maxLines = 1) })
                }
            }
            GlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
            Text("Made on this phone from the family's shared data and your own \"Only me\" items. Nothing is sent to the AI.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
        }
    }
}
