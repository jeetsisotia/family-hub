package com.familyhub.app.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.familyhub.app.ui.common.dayKey
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId

/**
 * Reads health data from Health Connect, where Google Fit, Samsung Health, watches and other apps keep it.
 * Read-only; nothing is written back. Only what the person allows is read, and it stays on this phone
 * (never sent to Firestore or the AI). Nothing is estimated here: a day without data has no value.
 */
object HealthConnectSync {
    const val PROVIDER = "com.google.android.apps.healthdata"
    private const val PREFS = "family_hub_steps"
    private const val KEY_CONNECTED = "healthConnect"
    private const val KEY_SYNCED = "healthConnectSyncedAt"
    private const val KEY_DAILY = "hcDaily"
    private const val KEY_MONTHLY = "hcMonthlySteps"
    private const val KEY_WEIGHT = "hcWeight"

    /** Steps are needed to connect; the rest are read when allowed. */
    val PERMISSIONS: Set<String> = setOf(HealthPermission.getReadPermission(StepsRecord::class))

    val ALL_PERMISSIONS: Set<String> = PERMISSIONS + setOf(
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class)
    )

    /** SDK_AVAILABLE, SDK_UNAVAILABLE or SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED. */
    fun status(ctx: Context): Int = try {
        HealthConnectClient.getSdkStatus(ctx, PROVIDER)
    } catch (e: Exception) {
        HealthConnectClient.SDK_UNAVAILABLE
    }

    fun isAvailable(ctx: Context) = status(ctx) == HealthConnectClient.SDK_AVAILABLE

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isConnected(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_CONNECTED, false)

    fun setConnected(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_CONNECTED, on).apply()

    fun lastSynced(ctx: Context): Long = prefs(ctx).getLong(KEY_SYNCED, 0L)

    /** Opens the Play Store page to install or update Health Connect. */
    fun installIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PROVIDER&url=healthconnect%3A%2F%2Fonboarding"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    suspend fun granted(ctx: Context): Set<String> = try {
        if (isAvailable(ctx)) HealthConnectClient.getOrCreate(ctx).permissionController.getGrantedPermissions() else emptySet()
    } catch (e: Exception) {
        emptySet()
    }

    suspend fun hasPermission(ctx: Context): Boolean = granted(ctx).containsAll(PERMISSIONS)

    /** True when some of the extra kinds (sleep, heart rate, weight…) are not allowed yet. */
    suspend fun missingExtras(ctx: Context): Boolean = !granted(ctx).containsAll(ALL_PERMISSIONS)

    // ---------- what was read, per day ----------

    /** One day's readings. Null means Health Connect had nothing for that day. */
    data class Day(
        val distanceM: Double? = null,
        val activeKcal: Double? = null,
        val exerciseMin: Int? = null,
        val sleepMin: Int? = null,
        val avgHr: Int? = null,
        val restingHr: Int? = null
    )

    private fun daily(ctx: Context): JSONObject =
        try { JSONObject(prefs(ctx).getString(KEY_DAILY, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    fun day(ctx: Context, key: String = dayKey()): Day {
        val o = daily(ctx).optJSONObject(key) ?: return Day()
        fun d(k: String) = if (o.has(k)) o.optDouble(k) else null
        fun i(k: String) = if (o.has(k)) o.optInt(k) else null
        return Day(d("m"), d("kcal"), i("ex"), i("sleep"), i("hr"), i("rhr"))
    }

    /** Monthly step totals for the last year, oldest first ("2026-11" to steps). */
    fun monthlySteps(ctx: Context): List<Pair<String, Long>> {
        val o = try { JSONObject(prefs(ctx).getString(KEY_MONTHLY, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        return o.keys().asSequence().map { it to o.optLong(it) }.sortedBy { it.first }.toList()
    }

    /** The latest weight in Health Connect and when it was measured. */
    fun latestWeight(ctx: Context): Pair<Double, Long>? {
        val o = try { JSONObject(prefs(ctx).getString(KEY_WEIGHT, "{}") ?: "{}") } catch (e: Exception) { return null }
        return if (o.has("kg")) o.optDouble("kg") to o.optLong("at") else null
    }

    /** Reads the last [days] days and stores them on the phone. Returns today's steps, or null. */
    suspend fun sync(ctx: Context, days: Int = 31): Long? {
        if (!isAvailable(ctx) || !hasPermission(ctx)) return null
        val client = HealthConnectClient.getOrCreate(ctx)
        val allowed = granted(ctx)
        fun can(r: kotlin.reflect.KClass<out androidx.health.connect.client.records.Record>) = HealthPermission.getReadPermission(r) in allowed
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now(zone).minusDays((days - 1).toLong()).atStartOfDay()
        val end = LocalDate.now(zone).plusDays(1).atStartOfDay()
        val range = TimeRangeFilter.between(start, end)

        val metrics = buildSet<androidx.health.connect.client.aggregate.AggregateMetric<*>> {
            add(StepsRecord.COUNT_TOTAL)
            if (can(DistanceRecord::class)) add(DistanceRecord.DISTANCE_TOTAL)
            if (can(ActiveCaloriesBurnedRecord::class)) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
            if (can(ExerciseSessionRecord::class)) add(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL)
            if (can(HeartRateRecord::class)) add(HeartRateRecord.BPM_AVG)
            if (can(RestingHeartRateRecord::class)) add(RestingHeartRateRecord.BPM_AVG)
        }
        val groups = client.aggregateGroupByPeriod(AggregateGroupByPeriodRequest(metrics, range, Period.ofDays(1)))
        val totals = HashMap<String, Long>()
        val store = daily(ctx)
        for (g in groups) {
            val key = dayKey(g.startTime.atZone(zone).toInstant().toEpochMilli() + 12 * 3_600_000L)
            val o = JSONObject()
            g.result[StepsRecord.COUNT_TOTAL]?.let { totals[key] = it }
            g.result[DistanceRecord.DISTANCE_TOTAL]?.let { o.put("m", it.inMeters) }
            g.result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.let { o.put("kcal", it.inKilocalories) }
            g.result[ExerciseSessionRecord.EXERCISE_DURATION_TOTAL]?.let { o.put("ex", it.toMinutes().toInt()) }
            g.result[HeartRateRecord.BPM_AVG]?.let { o.put("hr", it.toInt()) }
            g.result[RestingHeartRateRecord.BPM_AVG]?.let { o.put("rhr", it.toInt()) }
            if (o.length() > 0) store.put(key, o) else store.remove(key)
        }
        // Sleep counts for the morning it ends ("last night").
        if (can(SleepSessionRecord::class)) {
            try {
                val sleeps = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(start.minusDays(1), end))).records
                val byDay = HashMap<String, Long>()
                for (s in sleeps) {
                    val key = dayKey(s.endTime.toEpochMilli())
                    byDay[key] = (byDay[key] ?: 0L) + java.time.Duration.between(s.startTime, s.endTime).toMinutes()
                }
                for ((k, min) in byDay) {
                    val o = store.optJSONObject(k) ?: JSONObject()
                    o.put("sleep", min.toInt())
                    store.put(k, o)
                }
            } catch (e: Exception) { }
        }
        if (can(WeightRecord::class)) {
            try {
                val w = client.readRecords(
                    ReadRecordsRequest(WeightRecord::class, TimeRangeFilter.between(Instant.now().minusSeconds(365L * 86_400), Instant.now()))
                ).records.maxByOrNull { it.time }
                if (w != null) prefs(ctx).edit().putString(KEY_WEIGHT, JSONObject().put("kg", w.weight.inKilograms).put("at", w.time.toEpochMilli()).toString()).apply()
            } catch (e: Exception) { }
        }
        // Steps by month for the year view.
        try {
            val yearStart = LocalDate.now(zone).withDayOfMonth(1).minusMonths(11).atStartOfDay()
            val months = client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(yearStart, end), Period.ofMonths(1))
            )
            val mo = JSONObject()
            for (g in months) {
                val k = "%04d-%02d".format(g.startTime.year, g.startTime.monthValue)
                mo.put(k, g.result[StepsRecord.COUNT_TOTAL] ?: 0L)
            }
            prefs(ctx).edit().putString(KEY_MONTHLY, mo.toString()).apply()
        } catch (e: Exception) { }
        // Keep about four months of days.
        val keys = store.keys().asSequence().toList().sorted()
        keys.dropLast(124).forEach { store.remove(it) }
        prefs(ctx).edit().putString(KEY_DAILY, store.toString()).putLong(KEY_SYNCED, System.currentTimeMillis()).apply()
        StepTracker.setExternal(ctx, totals)
        setConnected(ctx, true)
        return totals[dayKey()]
    }
}
