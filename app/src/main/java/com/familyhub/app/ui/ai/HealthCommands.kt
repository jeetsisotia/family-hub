package com.familyhub.app.ui.ai

import com.familyhub.app.data.remote.Rec
import com.familyhub.app.ui.modules.DAY_MS
import com.familyhub.app.ui.modules.startOfDay
import com.familyhub.app.ui.modules.str
import java.util.Locale

/**
 * Understands health commands on the phone, so period, medicine, vitals and similar details are
 * never sent to the AI. For example:
 *  "Start my periods from today with heavy flow", "period ended yesterday", "cramps and headache today",
 *  "weight 63.5 kg", "BP 120/80", "sugar fasting 98", "slept 7 hours", "walked 30 minutes",
 *  "2 glasses of water", "took my Vitamin D".
 */
object HealthCommands {

    data class Result(
        /** Actions understood on the phone. */
        val actions: List<AiAction>,
        /** Health sentences that could not be understood (kept on the phone, not sent anywhere). */
        val notUnderstood: List<String>,
        /** Everything else, which may go to the AI. */
        val rest: String
    )

    private val strongHealth = Regex(
        "(?i)\\b(periods?|menstrua\\w*|menses|chums|cramps?|weight|weigh\\w*|bp|blood pressure|sugar|glucose|pulse|" +
            "heart rate|spo2|oxygen|temperature|fever|slept|sleep|medicines?|tablets?|pills?|dose)\\b"
    )
    private val money = Regex("(?i)(rs\\.?|₹|inr|rupees?)\\s*\\d|\\d+\\s*(rs|rupees?|₹)\\b|\\b(expense|spent|paid|bill|bought)\\b")
    /** Shopping, tasks and reminders go to the AI even if they mention sugar or medicine. */
    private val everyday = Regex("(?i)\\b(shopping|buy|purchase|grocery|list|remind|reminder|task|todo|to-do|event|meeting|note|order)\\b")
    private val numberWords = mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6)

    private val symptomWords = listOf(
        "Cramps" to listOf("cramp"),
        "Headache" to listOf("headache", "head ache"),
        "Mood swings" to listOf("mood"),
        "Bloating" to listOf("bloat"),
        "Back pain" to listOf("back pain", "backache", "back ache"),
        "Tired" to listOf("tired", "fatigue", "weak"),
        "Acne" to listOf("acne", "pimple"),
        "Cravings" to listOf("craving")
    )

    private val workoutWords = listOf(
        "Walk" to "walk", "Run" to "ran|run|jog", "Cycling" to "cycl|bik", "Yoga" to "yoga",
        "Gym" to "gym|workout|exercise|weights", "Swimming" to "swim|swam", "Dance" to "danc", "Sports" to "sport|cricket|football|badminton|tennis"
    )

    fun parse(text: String, medicines: List<Rec>, periodOngoing: Boolean, now: Long = System.currentTimeMillis()): Result {
        val actions = ArrayList<AiAction>()
        val unclear = ArrayList<String>()
        val rest = ArrayList<String>()
        val clauses = text.split(Regex("[.;\\n]+|\\s+(?:and then|also|then)\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        for (c in clauses) {
            val isMoney = money.containsMatchIn(c) || everyday.containsMatchIn(c)
            val found = if (isMoney) emptyList() else parseClause(c, medicines, periodOngoing, now)
            when {
                found.isNotEmpty() -> actions.addAll(found)
                !isMoney && strongHealth.containsMatchIn(c) -> unclear.add(c)
                else -> rest.add(c)
            }
        }
        return Result(actions, unclear, rest.joinToString(". "))
    }

    private fun dayOf(c: String, now: Long): Long {
        val l = c.lowercase(Locale.ROOT)
        val back = when {
            "day before yesterday" in l -> 2
            "yesterday" in l -> 1
            else -> Regex("(\\d+)\\s*days?\\s*ago").find(l)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }
        return startOfDay(now - back * DAY_MS) + 12 * 3_600_000L
    }

    private fun num(s: String?): String? = s?.replace(',', '.')?.trim()?.takeIf { it.isNotEmpty() }

    private fun parseClause(c: String, medicines: List<Rec>, periodOngoing: Boolean, now: Long): List<AiAction> {
        val l = c.lowercase(Locale.ROOT)
        val day = dayOf(c, now)
        val out = ArrayList<AiAction>()

        // Period
        val symptoms = symptomWords.filter { (_, words) -> words.any { it in l } }.map { it.first }
        val periodWord = Regex("\\b(periods?|menstrua\\w*|menses|chums)\\b").containsMatchIn(l)
        if (periodWord || (symptoms.isNotEmpty() && periodOngoing)) {
            val flow = when {
                "heavy" in l -> "Heavy"
                "medium" in l || "moderate" in l || "normal flow" in l -> "Medium"
                "light" in l || "spotting" in l -> "Light"
                else -> ""
            }
            val ended = periodWord && Regex("\\b(end|ended|ends|stopped|over|finished|done)\\b").containsMatchIn(l)
            val started = periodWord && !ended
            out.add(
                AiAction(
                    kind = when { ended -> "period_end"; started -> "period"; else -> "period_log" },
                    title = when { ended -> "Period ended"; started -> "Period started"; else -> "Period symptoms" },
                    dateMillis = day, flow = flow, symptoms = symptoms, private = true, local = true
                )
            )
            return out
        }

        // Vitals
        fun vital(kind: String, value: String) =
            out.add(AiAction(kind = "vital", title = kind, dateMillis = day, vitalKind = kind, value = value, private = true, local = true))

        Regex("\\b(?:bp|blood pressure)\\D{0,12}(\\d{2,3})\\s*(?:/|over|by|-|\\s)\\s*(\\d{2,3})").find(l)?.let {
            vital("Blood pressure", it.groupValues[1] + "/" + it.groupValues[2])
        }
        (Regex("\\b(?:weight|weigh\\w*)\\D{0,12}(\\d{2,3}(?:[.,]\\d{1,2})?)").find(l)
            ?: Regex("(\\d{2,3}(?:[.,]\\d{1,2})?)\\s*(?:kg|kgs|kilos?)\\b").find(l))?.let { vital("Weight (kg)", num(it.groupValues[1])!!) }
        Regex("\\b(?:sugar|glucose)\\D{0,20}?(\\d{2,3})").find(l)?.let {
            vital(if (Regex("after|pp|post|lunch|dinner|meal").containsMatchIn(l)) "Sugar after food" else "Sugar fasting", it.groupValues[1])
        }
        Regex("\\b(?:pulse|heart rate)\\D{0,12}(\\d{2,3})").find(l)?.let { vital("Heart rate", it.groupValues[1]) }
        Regex("\\b(?:spo2|oxygen)\\D{0,12}(\\d{2,3})").find(l)?.let { vital("SpO2 (%)", it.groupValues[1]) }
        Regex("\\b(?:temperature|temp|fever)\\D{0,12}(\\d{2,3}(?:[.,]\\d)?)").find(l)?.let { vital("Temperature", num(it.groupValues[1])!!) }
        (Regex("\\b(?:slept|sleep)\\D{0,15}?(\\d{1,2}(?:[.,]\\d)?)\\s*(?:h|hr|hrs|hours?)\\b").find(l)
            ?: Regex("(\\d{1,2}(?:[.,]\\d)?)\\s*(?:h|hr|hrs|hours?)\\s*(?:of\\s*)?sleep").find(l))?.let {
            vital("Sleep (hours)", num(it.groupValues[1])!!)
        }

        // Water
        Regex("\\b(\\d+|a|an|one|two|three|four|five|six)\\s*(?:glass(?:es)?|cups?|bottles?)\\s*(?:of\\s*)?water").find(l)?.let {
            val n = it.groupValues[1].toIntOrNull() ?: numberWords[it.groupValues[1]] ?: 1
            out.add(AiAction(kind = "water", title = "Water", count = n, dateMillis = day, private = true, local = true))
        }

        // Workout: "walked 30 minutes", "30 min yoga", "1 hour gym"
        val dur = Regex("(\\d{1,3}(?:[.,]\\d)?)\\s*(min|mins|minutes?|hour|hours|hr|hrs)\\b").find(l)
        if (dur != null) {
            val activity = workoutWords.firstOrNull { (_, rx) -> Regex("\\b($rx)").containsMatchIn(l) }?.first
            if (activity != null && out.none { it.kind == "vital" && it.vitalKind == "Sleep (hours)" }) {
                val v = dur.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0
                val minutes = if (dur.groupValues[2].startsWith("h")) (v * 60).toInt() else v.toInt()
                if (minutes in 1..600) {
                    out.add(AiAction(kind = "workout", title = activity, minutes = minutes, dateMillis = day, private = true, local = true))
                }
            }
        }

        // Medicine taken: "took my Vitamin D", "had thyroxine"
        if (Regex("\\b(took|taken|had|take|have taken)\\b").containsMatchIn(l)) {
            val med = medicines.firstOrNull { m ->
                val t = m.str("title").lowercase(Locale.ROOT)
                t.isNotBlank() && (t in l || t.split(' ').firstOrNull()?.let { w -> w.length >= 4 && w in l } == true)
            }
            if (med != null) {
                out.add(AiAction(kind = "medicine_taken", title = med.str("title"), dateMillis = day, private = true, local = true))
            }
        }
        return out
    }
}
