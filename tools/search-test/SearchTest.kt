// Universal search: matching, ranking, highlighting, suggestions, recent searches (run by the Unit tests workflow).
import com.familyhub.app.core.search.SearchIndex
import com.familyhub.app.core.search.SearchIndex.Doc
import com.familyhub.app.core.search.SearchSeed

var failures = 0
fun check(name: String, cond: Boolean, detail: Any? = null) { if (cond) println("PASS $name") else { failures++; println("FAIL $name ${detail ?: ""}") } }

fun main() {
    val docs = listOf(
        Doc("1", "Finance", "transactions", "DMart groceries", "Groceries · UPI", "weekly shopping", 1_000L, 1250.0),
        Doc("2", "Finance", "transactions", "Uber", "Transport", "office cab", 3_000L, 220.0),
        Doc("3", "Documents", "documents", "Car insurance policy", "Insurance · Ravi", "renew before march", 2_000L),
        Doc("4", "Shopping", "shopping", "Milk", "Groceries · Main list", "", 4_000L, 60.0),
        Doc("5", "People", "family", "Ravi Kumar", "Brother · ravi@example.com"),
        Doc("6", "Maintenance", "maintenance", "Car service", "Vehicles", "next due in April", 5_000L, 4500.0),
        Doc("7", "Finance", "transactions", "Insurance premium", "Insurance", "LIC", 6_000L, 12000.0)
    )
    fun ids(q: String, sec: String? = null) = SearchIndex.search(q, docs, sec).map { it.doc.id }

    check("finds by title word", ids("uber") == listOf("2"))
    check("case and spacing ignored", ids("  DMART  ") == listOf("1"))
    check("every word must match (car + insurance → only the policy)", ids("car insurance") == listOf("3"), ids("car insurance"))
    check("words can be in any field (notes, subtitle)", ids("renew march") == listOf("3"))
    check("amount found with ₹ and commas", ids("₹1,250") == listOf("1") && ids("12000") == listOf("7"), ids("₹1,250"))
    check("searches across sections (people, documents, maintenance, finance)", ids("ravi").toSet() == setOf("3", "5"), ids("ravi"))
    check("title starting with the words ranks first", ids("car").first() == "3" || ids("car").first() == "6", ids("car"))
    check("title matches rank above matches elsewhere", ids("insurance").take(2).toSet() == setOf("3", "7") && ids("groceries").first() == "1", ids("groceries"))
    check("section filter keeps one section", ids("groceries", "Shopping") == listOf("4"))
    check("no words, no results", ids("   ").isEmpty())
    check("nothing matching, nothing returned", ids("zorbing").isEmpty())

    val hit = SearchIndex.search("car ins", docs).first { it.doc.id == "3" }
    check("highlight: ranges for every word in the title", hit.titleRanges == listOf(0..2, 4..6), hit.titleRanges)
    check("highlight: overlapping words merge", SearchIndex.ranges("Insurance", listOf("ins", "nsu")) == listOf(0..3))

    check("suggestions: titles starting with the text first", SearchIndex.suggestions("ca", docs) == listOf("Car insurance policy", "Car service"), SearchIndex.suggestions("ca", docs))
    check("suggestions: need 2 letters", SearchIndex.suggestions("c", docs).isEmpty())

    var recent = listOf("milk", "uber")
    recent = SearchIndex.remember(recent, "Uber")
    check("recent: newest first, repeat moved up (case ignored)", recent == listOf("Uber", "milk"), recent)
    recent = (1..15).fold(recent) { r, i -> SearchIndex.remember(r, "query $i") }
    check("recent: at most 10 kept", recent.size == 10 && recent.first() == "query 15")
    check("recent: one letter is not saved", SearchIndex.remember(listOf("a1"), "x") == listOf("a1"))

    val secs = SearchIndex.sections(SearchIndex.search("groceries", docs), listOf("Shopping", "Finance"))
    check("filter chips: sections in app order with counts", secs == listOf("Shopping" to 1, "Finance" to 1), secs)

    SearchSeed.put("documents", "Car insurance policy")
    check("seed: the opened screen gets the search once", SearchSeed.take("documents") == "Car insurance policy" && SearchSeed.take("documents") == "")

    println(if (failures == 0) "ALL PASSED" else "$failures FAILED")
    if (failures > 0) kotlin.system.exitProcess(1)
}
