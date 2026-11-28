// Memories: on this day, albums, reactions (run by the Unit tests workflow).
import com.familyhub.app.family.MemoryMath as M
import java.time.LocalDate
import java.time.ZoneId

var failures = 0
fun check(name: String, cond: Boolean, detail: Any? = null) { if (cond) println("PASS $name") else { failures++; println("FAIL $name ${detail ?: ""}") } }

fun main() {
    val z = ZoneId.of("Asia/Kolkata")
    fun at(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atTime(10, 0).atZone(z).toInstant().toEpochMilli()
    val now = at(2026, 11, 28)
    val list = listOf(
        M.Mem("a", "Goa trip", at(2024, 11, 28), "Trips"),
        M.Mem("b", "First steps", at(2025, 11, 28), "Baby"),
        M.Mem("c", "Today", at(2026, 11, 28), "Baby"),
        M.Mem("d", "Diwali", at(2025, 11, 1), "festivals"),
        M.Mem("e", "No date", null, "")
    )
    val otd = M.onThisDay(list, now, z)
    check("on this day: earlier years only, nearest first", otd.map { it.first.id to it.second } == listOf("b" to 1, "a" to 2), otd)
    check("years ago text", M.yearsAgoText(1) == "1 year ago" && M.yearsAgoText(3) == "3 years ago")
    check("albums counted, ignoring case, biggest first", M.albums(list + M.Mem("f", "x", null, "Festivals")) == listOf("Baby" to 2, "festivals" to 2, "Trips" to 1), M.albums(list))
    check("reactions: one per person, fixed order", M.reactionCounts(mapOf("u1" to "😂", "u2" to "❤️", "u3" to "😂")) == listOf("❤️" to 1, "😂" to 2))
    check("comments are trimmed and limited", M.cleanComment("  hi  ") == "hi" && M.cleanComment("   ") == null && M.cleanComment("x".repeat(900))!!.length == 500)
    check("at most 12 photos per memory", M.canAddPhotos(10, 2) && !M.canAddPhotos(11, 2))
    println(if (failures == 0) "ALL PASSED" else "FAILED: $failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
