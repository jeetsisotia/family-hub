// Per-member section access (run by the Unit tests workflow). Also checks the app's kind lists match firestore.rules.
import com.familyhub.app.family.Permissions as P

var failures = 0
fun check(name: String, cond: Boolean, detail: Any? = null) { if (cond) println("PASS $name") else { failures++; println("FAIL $name ${detail ?: ""}") } }

fun main(args: Array<String>) {
    val none = emptyMap<String, String>()
    check("no settings means full access everywhere", P.SECTIONS.all { P.canEdit(none, it.key, false) } && !P.restricted(none, false))
    val p = mapOf("health" to "none", "finance" to "view")
    check("None hides, View reads only", !P.canSee(p, "health", false) && P.canSee(p, "finance", false) && !P.canEdit(p, "finance", false) && P.canEdit(p, "tasks", false))
    check("admins are never restricted", P.canEdit(p, "health", true) && !P.restricted(p, true))
    check("only None makes loading restricted", P.restricted(p, false) && !P.restricted(mapOf("finance" to "view"), false))
    check("items map to sections by kind", P.sectionOf("items", "subscription") == "finance" && P.sectionOf("items", "medicine") == "health" && P.sectionOf("items", "document") == "documents")
    check("whole collections map to sections", P.sectionOf("events", null) == "calendar" && P.sectionOf("expenses", null) == "finance" && P.sectionOf("trips", null) == "travel" && P.sectionOf("tasks", "") == "tasks")
    check("unknown kinds have no section (always allowed)", P.sectionOf("items", "something_new") == null && P.canEdit(p, null, false))
    val vis = P.visibleItemTypes(p, false)
    check("visible kinds leave out None sections only", "medicine" !in vis && "health" !in vis && "subscription" in vis && "document" in vis, vis)
    check("kinds load in groups of at most 30", P.chunks(vis).all { it.size <= 30 } && P.chunks(vis).flatten().toSet() == vis.toSet())
    check("hidden collections are not loaded", !P.canLoadCollection(mapOf("calendar" to "none"), "events", false) && P.canLoadCollection(mapOf("calendar" to "view"), "events", false))
    val f = P.memberFields(mapOf("finance" to "view", "tasks" to "edit", "health" to "none"))
    check("saved access keeps only limits and syncs Finance editing", f["perms"] == mapOf("finance" to "view", "health" to "none") && f["canEditFinance"] == false, f)
    check("full Finance turns Finance editing back on", P.memberFields(emptyMap())["canEditFinance"] == true)
    check("summary is readable", P.summary(p).contains("Health") && P.summary(p).contains("Finance"), P.summary(p))

    // The rules file must list exactly the same kinds per section.
    val rulesPath = args.firstOrNull()
    if (rulesPath != null) {
        val rules = java.io.File(rulesPath).readText()
        val fn = mapOf("finance" to "finTypes", "calendar" to "calTypes", "tasks" to "taskTypes", "documents" to "docTypes",
            "health" to "healthTypes", "home" to "homeTypes", "family" to "famTypes", "memories" to "memTypes")
        fn.forEach { (sec, name) ->
            val m = Regex("function $name\\(\\)\\s*\\{\\s*return\\s*\\[([^]]*)]").find(rules)
            val inRules = m?.groupValues?.get(1)?.split(",")?.map { it.trim().trim('\'', '"') }?.filter { it.isNotBlank() }?.toSet()
            val inApp = P.SECTIONS.first { it.key == sec }.itemTypes.toSet()
            check("rules and app agree on $sec kinds", inRules == inApp, "rules=$inRules app=$inApp")
        }
    }
    println(if (failures == 0) "RESULT: all passed" else "RESULT: $failures failed")
    if (failures > 0) kotlin.system.exitProcess(1)
}
