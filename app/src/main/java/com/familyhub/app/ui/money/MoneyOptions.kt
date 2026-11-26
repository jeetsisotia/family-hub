package com.familyhub.app.ui.money

import com.familyhub.app.data.remote.Rec
import com.familyhub.app.ui.modules.bool
import com.familyhub.app.ui.modules.financeCategories
import com.familyhub.app.ui.modules.str

/**
 * Choices for the money fields of the add/edit form, from the person's own records:
 * "cards" (credit and debit), "accounts", "categories" (built-in plus custom main categories) and
 * "sub:<category>" for the subcategories inside each category.
 */
fun moneyFieldOptions(items: List<Rec>): Map<String, List<String>> {
    val live = items.filter { !it.bool("isDeleted") }
    fun titles(type: String) = live.filter { it.str("type") == type }.map { it.str("title").trim() }.filter { it.isNotBlank() }.distinct()
    val cats = live.filter { it.str("type") == "category" }
    fun parentOf(r: Rec) = r.str("parent").trim().takeIf { it.isNotBlank() && it != "None" }
    val main = cats.filter { parentOf(it) == null }.map { it.str("title").trim() }.filter { it.isNotBlank() }
    val out = HashMap<String, List<String>>()
    out["cards"] = listOf("None") + titles("card") + titles("debit_card")
    out["accounts"] = listOf("None") + titles("account")
    out["categories"] = (financeCategories + main).distinct()
    cats.mapNotNull { r -> parentOf(r)?.let { it to r.str("title").trim() } }
        .filter { it.second.isNotBlank() }
        .groupBy({ it.first }, { it.second })
        .forEach { (parent, subs) -> out["sub:$parent"] = subs.distinct() }
    return out
}
