package com.familyhub.app.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.familyhub.app.ui.common.dayKey
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId

/**
 * Reads daily step totals from Health Connect, where Google Fit, Samsung Health, watches and other apps
 * keep their data (Google Fit's own APIs are being retired). Read-only; nothing is written back.
 */
object HealthConnectSync {
    const val PROVIDER = "com.google.android.apps.healthdata"
    private const val PREFS = "family_hub_steps"
    private const val KEY_CONNECTED = "healthConnect"
    private const val KEY_SYNCED = "healthConnectSyncedAt"

    val PERMISSIONS: Set<String> = setOf(HealthPermission.getReadPermission(StepsRecord::class))

    /** SDK_AVAILABLE, SDK_UNAVAILABLE or SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED. */
    fun status(ctx: Context): Int = try {
        HealthConnectClient.getSdkStatus(ctx, PROVIDER)
    } catch (e: Exception) {
        HealthConnectClient.SDK_UNAVAILABLE
    }

    fun isAvailable(ctx: Context) = status(ctx) == HealthConnectClient.SDK_AVAILABLE

    fun isConnected(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CONNECTED, false)

    fun setConnected(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CONNECTED, on).apply()

    fun lastSynced(ctx: Context): Long = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_SYNCED, 0L)

    /** Opens the Play Store page to install or update Health Connect. */
    fun installIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PROVIDER&url=healthconnect%3A%2F%2Fonboarding"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    suspend fun hasPermission(ctx: Context): Boolean = try {
        isAvailable(ctx) && HealthConnectClient.getOrCreate(ctx).permissionController.getGrantedPermissions().containsAll(PERMISSIONS)
    } catch (e: Exception) {
        false
    }

    /** Reads the last [days] days and stores them next to the phone's own count. Returns today's total, or null. */
    suspend fun sync(ctx: Context, days: Int = 7): Long? {
        if (!isAvailable(ctx) || !hasPermission(ctx)) return null
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now(zone).minusDays((days - 1).toLong()).atStartOfDay()
        val end = LocalDate.now(zone).plusDays(1).atStartOfDay()
        val groups = HealthConnectClient.getOrCreate(ctx).aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(start, end),
                timeRangeSlicer = Period.ofDays(1)
            )
        )
        val totals = HashMap<String, Long>()
        for (g in groups) {
            val steps = g.result[StepsRecord.COUNT_TOTAL] ?: continue
            val millis = g.startTime.atZone(zone).toInstant().toEpochMilli()
            totals[dayKey(millis + 12 * 3_600_000L)] = steps
        }
        StepTracker.setExternal(ctx, totals)
        setConnected(ctx, true)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_SYNCED, System.currentTimeMillis()).apply()
        return totals[dayKey()]
    }
}
