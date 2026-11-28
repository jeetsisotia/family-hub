// Group call rules: who connects to whom, ringing, limits, quality and speaker (run by the Unit tests workflow).
import com.familyhub.app.calling.GroupCallMath
import com.familyhub.app.calling.GroupCallMath.Quality

var failures = 0
fun check(name: String, cond: Boolean, detail: Any? = null) { if (cond) println("ok  $name") else { failures++; println("FAIL $name ${detail ?: ""}") } }

fun main() {
    check("in each pair exactly one starts the connection", GroupCallMath.isOfferer("a", "b") != GroupCallMath.isOfferer("b", "a"))
    val joined = listOf("asha", "dadi", "ravi", "me")
    val offers = joined.associateWith { u -> GroupCallMath.toOffer(u, joined, emptyList()) }
    val pairs = offers.flatMap { (u, l) -> l.map { setOf(u, it) } }
    check("4 people: 6 connections, each made once", pairs.size == 6 && pairs.toSet().size == 6, pairs)
    check("already connected people are not offered again", GroupCallMath.toOffer("asha", joined, listOf("dadi")) == listOf("me", "ravi"))
    check("people who left are closed", GroupCallMath.gone(listOf("me", "asha"), listOf("asha", "ravi")) == listOf("ravi"))
    check("signal ids name the pair and the kind", GroupCallMath.signalId("a", "b", "offer") == "a__b__offer")

    val now = 1_000_000L
    fun rings(me: String = "me", host: String = "asha", invited: List<String> = listOf("asha", "me"), j: List<String> = listOf("asha"), state: String = "open", at: Long = now - 5_000, dismissed: Set<String> = emptySet()) =
        GroupCallMath.rings(me, host, invited, j, state, at, now, dismissed, "c1")
    check("rings for an invited person not yet in the call", rings())
    check("does not ring for the host", !rings(me = "asha"))
    check("does not ring for someone not invited", !rings(invited = listOf("asha", "ravi")))
    check("does not ring once joined", !rings(j = listOf("asha", "me")))
    check("does not ring after it ended", !rings(state = "ended"))
    check("stops ringing after a minute", !rings(at = now - 61_000))
    check("does not ring again after Decline", !rings(dismissed = setOf("c1")))

    check("start: needs someone to call", GroupCallMath.canStart("me", emptyList()) != null)
    check("start: up to 3 others", GroupCallMath.canStart("me", listOf("a", "b", "c")) == null && GroupCallMath.canStart("me", listOf("a", "b", "c", "d")) != null)
    check("start: not yourself", GroupCallMath.canStart("me", listOf("me", "a")) != null)

    check("quality: good", GroupCallMath.quality(80.0, 0.0) == Quality.GOOD)
    check("quality: fair on slow or a little loss", GroupCallMath.quality(350.0, 0.0) == Quality.FAIR && GroupCallMath.quality(50.0, 0.05) == Quality.FAIR)
    check("quality: poor on very slow or heavy loss", GroupCallMath.quality(700.0, 0.0) == Quality.POOR && GroupCallMath.quality(50.0, 0.2) == Quality.POOR)
    check("quality: unknown before any numbers", GroupCallMath.quality(null, null) == Quality.UNKNOWN)
    check("speaker: the loudest above the threshold", GroupCallMath.activeSpeaker(mapOf("a" to 0.3, "b" to 0.6, "c" to 0.01)) == "b")
    check("speaker: nobody when all are quiet", GroupCallMath.activeSpeaker(mapOf("a" to 0.001)) == null)
    check("grid: 1 column for 2, 2 columns for 3 or 4", GroupCallMath.columns(2) == 1 && GroupCallMath.columns(4) == 2)
    println(if (failures == 0) "ALL PASSED" else "$failures FAILED")
}
