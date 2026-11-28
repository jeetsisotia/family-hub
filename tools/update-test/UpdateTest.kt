// Update checker rules (run by the Unit tests workflow).
import com.familyhub.app.update.UpdateMath as U

var failures = 0
fun check(name: String, cond: Boolean, detail: Any? = null) { if (cond) println("PASS $name") else { failures++; println("FAIL $name ${detail ?: ""}") } }

fun main() {
    check("reads the build number from the tag", U.buildNumber("build-112") == 112 && U.buildNumber(" build-7 ") == 7)
    check("ignores other tags", U.buildNumber("v2.0") == null && U.buildNumber("build-") == null && U.buildNumber("build-12a") == null)
    check("newer only when the number is higher", U.isNewer("build-113", 112) && !U.isNewer("build-112", 112) && !U.isNewer("build-100", 112))
    check("an unknown tag is never offered", !U.isNewer("latest", 1))
    check("picks the APK file", U.pickApk(listOf("notes.txt", "FamilyHub-2.1.5.APK", "x.apk")) == "FamilyHub-2.1.5.APK" && U.pickApk(listOf("a.zip")) == null)
    val h = 60L * 60 * 1000
    check("checks at most every 12 hours", U.dueToCheck(0, 5) && !U.dueToCheck(1000, 1000 + 11 * h) && U.dueToCheck(1000, 1000 + 12 * h))
    check("a clock moved back allows a new check", U.dueToCheck(10 * h, 2 * h))
    check("sizes are readable", U.sizeText(31_500_000) == "31.5 MB" && U.sizeText(4_200) == "4 KB" && U.sizeText(12) == "12 B")
    val n = U.notes("Line 1\n\n- change A\nCo-Authored-By: X\nClaude-Session: y\n- change B")
    check("notes drop blank and attribution lines", n == "Line 1\n- change A\n- change B", n)
    check("notes are kept short", U.notes((1..30).joinToString("\n") { "l$it" }).lines().size == 12)
    check("checksum without its prefix", U.checksum("sha256:abc") == "abc" && U.checksum("") == null && U.checksum(null) == null)
    println(if (failures == 0) "ALL PASSED" else "FAILED: $failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
