package com.familyhub.app.health

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import com.familyhub.app.ui.common.dayKey
import com.familyhub.app.ui.modules.DAY_MS
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Counts steps with the phone's own step sensor and keeps 60 days of daily totals on the phone.
 * Water glasses per day are kept here too. The service syncs them to the person's private activity log.
 */
object StepTracker {
    private const val PREFS = "family_hub_steps"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_GOAL = "goal"
    private const val KEY_SHARE = "share"
    private const val KEY_LAST = "lastCounter"
    private const val KEY_DAY = "day"
    private const val KEY_TODAY = "today"
    private const val KEY_HISTORY = "history"
    private const val KEY_WATER = "water"
    private const val KEY_FAMILIES = "familyIds"
    private const val KEY_WATER_GOAL = "waterGoal"
    private const val KEY_EXTERNAL = "external"
    private const val KEY_IN_BAR = "showInBar"

    private val _today = MutableStateFlow(0L)
    val todayFlow: StateFlow<Long> = _today

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isSupported(ctx: Context): Boolean {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
        return sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
    }

    fun hasPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            ctx.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply()
        if (on) StepService.start(ctx) else StepService.stop(ctx)
    }

    fun waterGoal(ctx: Context): Int = prefs(ctx).getInt(KEY_WATER_GOAL, 8)
    fun setWaterGoal(ctx: Context, glasses: Int) = prefs(ctx).edit().putInt(KEY_WATER_GOAL, glasses.coerceIn(1, 20)).apply()

    /** Keep steps and water in the notification bar, even when step counting is paused. */
    fun showInBar(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_IN_BAR, false)
    fun setShowInBar(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_IN_BAR, on).apply()

    private fun external(ctx: Context): JSONObject =
        try { JSONObject(prefs(ctx).getString(KEY_EXTERNAL, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    /**
     * Daily totals from Health Connect (Google Fit and other apps write there). Kept apart from the phone's own count,
     * and each day shows whichever is higher, so the same steps are never added twice.
     */
    fun setExternal(ctx: Context, totals: Map<String, Long>) {
        val ext = external(ctx)
        totals.forEach { (day, steps) -> ext.put(day, steps) }
        trim(ext)
        prefs(ctx).edit().putString(KEY_EXTERNAL, ext.toString()).apply()
        today(ctx)
    }

    fun goal(ctx: Context): Int = prefs(ctx).getInt(KEY_GOAL, 8000)
    fun setGoal(ctx: Context, goal: Int) = prefs(ctx).edit().putInt(KEY_GOAL, goal.coerceIn(1000, 50000)).apply()

    fun shareWithFamily(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SHARE, true)
    fun setShare(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_SHARE, on).apply()

    /** Families to update with today's steps (the app saves this each time it opens). */
    fun setFamilies(ctx: Context, ids: List<String>) =
        prefs(ctx).edit().putString(KEY_FAMILIES, ids.filter { it.isNotBlank() }.distinct().joinToString(",")).apply()

    fun families(ctx: Context): List<String> =
        prefs(ctx).getString(KEY_FAMILIES, "").orEmpty().split(",").filter { it.isNotBlank() }

    private fun history(ctx: Context): JSONObject =
        try { JSONObject(prefs(ctx).getString(KEY_HISTORY, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    /** Adds the steps taken since the last sensor reading. The counter restarts from zero after a reboot. */
    @Synchronized
    fun record(ctx: Context, counter: Long) {
        val p = prefs(ctx)
        val today = dayKey()
        val savedDay = p.getString(KEY_DAY, today) ?: today
        var steps = p.getLong(KEY_TODAY, 0L)
        val hist = history(ctx)
        if (savedDay != today) {
            hist.put(savedDay, steps)
            steps = 0L
        }
        val last = p.getLong(KEY_LAST, -1L)
        val delta = when {
            last < 0 -> 0L
            counter >= last -> counter - last
            else -> counter // phone restarted, counter began again from zero
        }
        if (delta in 0..40_000) steps += delta
        hist.put(today, steps)
        trim(hist)
        p.edit()
            .putLong(KEY_LAST, counter)
            .putString(KEY_DAY, today)
            .putLong(KEY_TODAY, steps)
            .putString(KEY_HISTORY, hist.toString())
            .apply()
        _today.value = steps
    }

    private fun trim(hist: JSONObject) {
        val keys = hist.keys().asSequence().toList().sorted()
        if (keys.size > 60) keys.take(keys.size - 60).forEach { hist.remove(it) }
    }

    fun today(ctx: Context): Long {
        val p = prefs(ctx)
        val own = if (p.getString(KEY_DAY, "") == dayKey()) p.getLong(KEY_TODAY, 0L) else 0L
        val v = maxOf(own, external(ctx).optLong(dayKey(), 0L))
        _today.value = v
        return v
    }

    /** Steps for each of the last [days] days, oldest first, as (day start millis, steps). */
    fun lastDays(ctx: Context, days: Int, now: Long = System.currentTimeMillis()): List<Pair<Long, Long>> {
        val hist = history(ctx)
        val ext = external(ctx)
        val todayKey = dayKey(now)
        return (days - 1 downTo 0).map { back ->
            val t = now - back * DAY_MS
            val key = dayKey(t)
            val v = if (key == todayKey) today(ctx) else maxOf(hist.optLong(key, 0L), ext.optLong(key, 0L))
            com.familyhub.app.ui.modules.startOfDay(t) to v
        }
    }

    fun week(ctx: Context): Long = lastDays(ctx, 7).sumOf { it.second }

    fun water(ctx: Context, day: String = dayKey()): Int {
        val p = prefs(ctx)
        return if (p.getString("$KEY_WATER-day", "") == day) p.getInt(KEY_WATER, 0) else 0
    }

    fun addWater(ctx: Context, delta: Int): Int {
        val day = dayKey()
        val v = (water(ctx, day) + delta).coerceIn(0, 30)
        prefs(ctx).edit().putString("$KEY_WATER-day", day).putInt(KEY_WATER, v).apply()
        return v
    }
}
