import com.familyhub.app.ui.chat.Mentions

var failures = 0
fun check(name: String, cond: Boolean, detail: Any? = null) {
    if (cond) println("ok  $name") else { failures++; println("FAIL $name ${detail ?: ""}") }
}

fun main() {
    check("handle is the first name", Mentions.handle("Asha Rao") == "Asha")
    check("handle drops punctuation", Mentions.handle("Ravi-K.") == "RaviK")
    check("typing after @ is a mention", Mentions.partial("Hi @As") == "As")
    check("a lone @ starts a mention", Mentions.partial("Hi @") == "")
    check("an email address is not a mention", Mentions.partial("mail@x") == null)
    check("a finished mention followed by a space is not being typed", Mentions.partial("Hi @Asha ") == null)
    check("completing replaces only the typed part", Mentions.complete("Hi @as", "Asha Rao") == "Hi @Asha ", Mentions.complete("Hi @as", "Asha Rao"))
    check("suggestions match the start, case ignored", Mentions.suggestions("a", listOf("Asha Rao", "Ravi", "Anil")) == listOf("Asha Rao", "Anil"))
    check("suggestions are limited", Mentions.suggestions("", List(9) { "P$it" }).size == 5)
    val text = "@Asha and @ravi, not @Zoya or mail@asha"
    val r = Mentions.ranges(text, listOf("Asha", "Ravi"))
    check("known mentions are found, unknown and email are not", r.map { text.substring(it) } == listOf("@Asha", "@ravi"), r.map { text.substring(it) })
    check("a person is told when mentioned", Mentions.mentions("Dinner? @asha", "Asha Rao"))
    check("a longer name is not a mention", !Mentions.mentions("@Ashaben hi", "Asha"))
    check("no mention, no alert", !Mentions.mentions("Dinner at 8", "Asha"))
    check("Hindi names work", Mentions.mentions("@अमित चलो", "अमित कुमार"))
    failures += mediaTests()
    println(if (failures == 0) "ALL PASSED" else "$failures FAILED")
}
