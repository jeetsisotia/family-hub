package com.familyhub.app.health

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.familyhub.app.work.Notifier
import java.text.NumberFormat

/** Today's steps and water in the notification bar, with a button to add a glass of water. */
object HealthNotification {
    const val ID = 4201
    private const val ACTION_WATER = "com.familyhub.app.ADD_WATER"

    fun build(ctx: Context, steps: Long = StepTracker.today(ctx)): Notification {
        val goal = StepTracker.goal(ctx)
        val water = StepTracker.water(ctx)
        val waterGoal = StepTracker.waterGoal(ctx)
        val fmt = NumberFormat.getIntegerInstance()
        val add = PendingIntent.getBroadcast(
            ctx, ID, Intent(ctx, WaterReceiver::class.java).setAction(ACTION_WATER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(ctx, Notifier.CH_STEPS)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle("${fmt.format(steps)} of ${fmt.format(goal)} steps")
            .setContentText("Water $water of $waterGoal glasses • tap to open Health")
            .setProgress(goal, steps.toInt().coerceAtMost(goal), false)
            .addAction(android.R.drawable.ic_input_add, "+1 glass of water", add)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(Notifier.openApp(ctx, "health_hub", ID))
            .build()
    }

    /** Shows or refreshes the notification; the step service owns it while it runs. */
    fun refresh(ctx: Context) {
        // While steps are being counted the step service shows this notification; it is updated in place.
        if (!StepTracker.showInBar(ctx) && !StepTracker.isEnabled(ctx)) {
            NotificationManagerCompat.from(ctx).cancel(ID)
            return
        }
        if (!Notifier.canPost(ctx)) return
        Notifier.ensureChannels(ctx)
        try {
            NotificationManagerCompat.from(ctx).notify(ID, build(ctx))
        } catch (e: SecurityException) {
            // Notifications are switched off.
        }
    }

    fun hide(ctx: Context) {
        if (!StepTracker.isEnabled(ctx)) NotificationManagerCompat.from(ctx).cancel(ID)
    }
}

/** "+1 glass of water" from the notification bar. */
class WaterReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        StepTracker.addWater(context, 1)
        HealthNotification.refresh(context)
        try {
            StepService.sync(context)
        } catch (e: Exception) {
            // Saved on the phone; it syncs next time.
        }
    }
}
