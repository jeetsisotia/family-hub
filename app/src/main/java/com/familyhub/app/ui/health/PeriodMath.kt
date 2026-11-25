package com.familyhub.app.ui.health

import com.familyhub.app.data.remote.Rec
import com.familyhub.app.ui.modules.DAY_MS
import com.familyhub.app.ui.modules.lng
import com.familyhub.app.ui.modules.startOfDay
import kotlin.math.roundToInt

data class PeriodPrediction(
    val lastStart: Long,
    val avgCycle: Int,
    val avgLength: Int,
    val nextStart: Long,
    val ovulation: Long,
    val fertileStart: Long,
    val fertileEnd: Long,
    val cycleDay: Int,
    val loggedCycles: Int
)

/**
 * Simple calendar method: the average of the last six cycles predicts the next period,
 * ovulation is about 14 days before it and the fertile window is the 5 days before ovulation
 * plus the day after. It is an estimate only, not medical advice or contraception.
 */
object PeriodMath {

    fun predict(periods: List<Rec>, now: Long = System.currentTimeMillis()): PeriodPrediction? {
        val sorted = periods.filter { it.lng("startMillis") != null }.sortedBy { it.lng("startMillis") }
        if (sorted.isEmpty()) return null
        val starts = sorted.map { startOfDay(it.lng("startMillis")!!) }.distinct()
        val gaps = starts.zipWithNext { a, b -> ((b - a) / DAY_MS).toInt() }.filter { it in 18..60 }.takeLast(6)
        val avgCycle = if (gaps.isEmpty()) 28 else gaps.average().roundToInt()
        val lengths = sorted.mapNotNull { r ->
            val s = r.lng("startMillis")
            val e = r.lng("endMillis")
            if (s != null && e != null && e >= s) ((startOfDay(e) - startOfDay(s)) / DAY_MS).toInt() + 1 else null
        }.filter { it in 1..12 }.takeLast(6)
        val avgLen = if (lengths.isEmpty()) 5 else lengths.average().roundToInt()

        val last = starts.last()
        val today = startOfDay(now)
        var next = last + avgCycle * DAY_MS
        // If several cycles were not logged, move forward but still show "late" for up to 10 days.
        while (next < today - 10 * DAY_MS) next += avgCycle * DAY_MS
        val ovulation = next - 14 * DAY_MS
        val cycleDay = ((today - last) / DAY_MS).toInt() + 1
        return PeriodPrediction(
            lastStart = last,
            avgCycle = avgCycle,
            avgLength = avgLen,
            nextStart = next,
            ovulation = ovulation,
            fertileStart = ovulation - 5 * DAY_MS,
            fertileEnd = ovulation + DAY_MS,
            cycleDay = cycleDay,
            loggedCycles = starts.size
        )
    }

    /** What a given day is: logged period, predicted period, fertile, ovulation or nothing. */
    fun dayKind(day: Long, periods: List<Rec>, p: PeriodPrediction?): String {
        val d = startOfDay(day)
        for (r in periods) {
            val s = r.lng("startMillis") ?: continue
            val e = r.lng("endMillis") ?: (s + ((p?.avgLength ?: 5) - 1) * DAY_MS)
            if (d >= startOfDay(s) && d <= startOfDay(e)) return "period"
        }
        if (p == null) return ""
        if (d >= p.nextStart && d < p.nextStart + p.avgLength * DAY_MS) return "predicted"
        if (d == startOfDay(p.ovulation)) return "ovulation"
        if (d >= startOfDay(p.fertileStart) && d <= startOfDay(p.fertileEnd)) return "fertile"
        return ""
    }
}
