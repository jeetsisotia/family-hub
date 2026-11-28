// Offline sync status, edit conflicts and restore checks (run by the Unit tests workflow).
import com.familyhub.app.data.remote.SyncMath as S

var failures = 0
fun check(name: String, cond: Boolean, detail: Any? = null) { if (cond) println("PASS $name") else { failures++; println("FAIL $name ${detail ?: ""}") } }

fun main() {
    val opened = mapOf("id" to "r1", "title" to "Milk", "qty" to 1, "updatedAt" to 100L, "updatedByUid" to "me")
    val theirs = opened + mapOf("qty" to 3, "updatedAt" to 200L, "updatedByUid" to "asha", "updatedByName" to "Asha")
    check("someone else's newer save is a conflict, naming the field", S.conflict(opened, theirs, "me") == listOf("qty"))
    check("my own newer save is not a conflict", S.conflict(opened, theirs + ("updatedByUid" to "me"), "me").isEmpty())
    check("no newer save, no conflict", S.conflict(opened, opened, "me").isEmpty())
    check("only metadata changed, no conflict", S.conflict(opened, opened + mapOf("updatedAt" to 300L, "updatedByUid" to "asha"), "me").isEmpty())
    check("deleted meanwhile (no live copy), no conflict", S.conflict(opened, null, "me").isEmpty())
    val mine = opened + ("title" to "Oat milk")
    val merged = S.merge(opened, mine, theirs)
    check("keep both: their quantity and my title", merged["qty"] == 3 && merged["title"] == "Oat milk", merged)
    check("status: all saved", S.text(true, 0) == "All changes saved" && S.level(true, 0) == S.Level.SAVED)
    check("status: syncing", S.text(true, 2) == "Syncing 2 changes…")
    check("status: offline with waiting changes", S.text(false, 1).startsWith("Offline · 1 change will sync") && S.level(false, 1) == S.Level.OFFLINE_PENDING)
    check("status: offline", S.level(false, 0) == S.Level.OFFLINE)
    check("restore check finds what the server lacks", S.missingAfterRestore(mapOf("items" to setOf("a", "b"), "tasks" to setOf("t")), mapOf("items" to setOf("a"), "tasks" to setOf("t"))) == mapOf("items" to setOf("b")))
    println(if (failures == 0) "RESULT: all passed" else "RESULT: $failures failed")
    if (failures > 0) kotlin.system.exitProcess(1)
}
