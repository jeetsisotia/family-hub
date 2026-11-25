package com.familyhub.app.ui.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.familyhub.app.data.model.FamilyMember
import com.familyhub.app.data.remote.Rec
import java.net.URLEncoder
import java.util.Locale

/**
 * Turns an assistant suggestion into a link to the official app or website, with details filled in.
 * Nothing is booked, paid or sent automatically: the person confirms inside that app.
 * Only the services listed here can be opened, so the AI cannot send anyone to an arbitrary website.
 */
object SmartLinks {

    val SERVICES = setOf(
        "uber", "ola", "rapido", "maps", "call", "whatsapp", "sms", "upi",
        "linkedin", "naukri", "indeed", "amazon", "flipkart", "blinkit", "bigbasket", "zepto",
        "swiggy", "zomato", "flights", "trains", "hotels", "google", "youtube"
    )

    private val PACKAGES = mapOf(
        "uber" to "com.ubercab",
        "ola" to "com.olacabs.customer",
        "rapido" to "com.rapido.passenger",
        "whatsapp" to "com.whatsapp",
        "zepto" to "com.zeptoconsumerapp"
    )

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun label(service: String): String = when (service) {
        "uber" -> "Uber"; "ola" -> "Ola"; "rapido" -> "Rapido"; "maps" -> "Google Maps"; "call" -> "Call"
        "whatsapp" -> "WhatsApp"; "sms" -> "Message"; "upi" -> "UPI payment"; "linkedin" -> "LinkedIn Jobs"
        "naukri" -> "Naukri"; "indeed" -> "Indeed"; "amazon" -> "Amazon"; "flipkart" -> "Flipkart"
        "blinkit" -> "Blinkit"; "bigbasket" -> "BigBasket"; "zepto" -> "Zepto"; "swiggy" -> "Swiggy"
        "zomato" -> "Zomato"; "flights" -> "Flights"; "trains" -> "Trains"; "hotels" -> "Hotels"
        "youtube" -> "YouTube"; else -> "Google"
    }

    /** Finds a phone number for a family member or emergency contact by name, or digits typed directly. */
    fun findPhone(nameOrNumber: String, members: List<FamilyMember>, userDoc: Rec): String {
        val digits = nameOrNumber.filter { it.isDigit() || it == '+' }
        if (digits.count { it.isDigit() } >= 10) return digits
        val n = nameOrNumber.trim().lowercase(Locale.ROOT)
        if (n.isEmpty()) return ""
        members.firstOrNull { it.name.lowercase(Locale.ROOT).let { m -> m == n || m.substringBefore(' ') == n } && it.phone.isNotBlank() }
            ?.let { return it.phone }
        for (i in 1..2) {
            val en = (userDoc["emergency${i}Name"] as? String).orEmpty().lowercase(Locale.ROOT)
            val rel = (userDoc["emergency${i}Relation"] as? String).orEmpty().lowercase(Locale.ROOT)
            val ph = (userDoc["emergency${i}Phone"] as? String).orEmpty()
            if (ph.isNotBlank() && (en == n || en.substringBefore(' ') == n || rel == n)) return ph
        }
        return ""
    }

    private fun waPhone(p: String): String {
        val d = p.filter { it.isDigit() }
        return if (d.length == 10) "91$d" else d
    }

    /** The intent to open, or null if the details are not enough. [fallback] is a web page if the app is missing. */
    data class Target(val intent: Intent, val fallback: Uri?, val isUpi: Boolean = false, val copyText: String = "")

    fun target(a: AiAction, members: List<FamilyMember>, userDoc: Rec): Target? {
        val s = a.service.lowercase(Locale.ROOT)
        val q = a.query.trim()
        val to = a.place.trim()
        val from = a.origin.trim()
        fun view(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        return when (s) {
            "uber" -> {
                val url = buildString {
                    append("https://m.uber.com/ul/?action=setPickup")
                    append(if (from.isBlank() || from.equals("current location", true)) "&pickup=my_location" else "&pickup[formatted_address]=" + enc(from))
                    if (to.isNotBlank()) append("&dropoff[formatted_address]=" + enc(to))
                }
                Target(view(url), Uri.parse(url))
            }
            "ola", "rapido" -> {
                val pkg = PACKAGES.getValue(s)
                Target(
                    Intent(Intent.ACTION_MAIN).setPackage(pkg).addCategory(Intent.CATEGORY_LAUNCHER),
                    Uri.parse(if (s == "ola") "https://book.olacabs.com/" else "https://play.google.com/store/apps/details?id=$pkg"),
                    copyText = to
                )
            }
            "maps" -> {
                if (to.isBlank()) return null
                val url = "https://www.google.com/maps/dir/?api=1&destination=" + enc(to) +
                    (if (from.isNotBlank() && !from.equals("current location", true)) "&origin=" + enc(from) else "") + "&travelmode=driving"
                Target(view(url), Uri.parse(url))
            }
            "call" -> {
                val phone = findPhone(a.phone.ifBlank { a.payee.ifBlank { q } }, members, userDoc)
                if (phone.isBlank()) return null
                Target(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone)), null)
            }
            "whatsapp" -> {
                val phone = findPhone(a.phone.ifBlank { a.payee }, members, userDoc)
                val url = if (phone.isBlank()) "https://wa.me/?text=" + enc(q) else "https://wa.me/" + waPhone(phone) + "?text=" + enc(q)
                Target(view(url), Uri.parse(url))
            }
            "sms" -> {
                val phone = findPhone(a.phone.ifBlank { a.payee }, members, userDoc)
                Target(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + phone)).putExtra("sms_body", q), null)
            }
            "upi" -> {
                val vpa = a.upi.trim()
                if (!Regex("^[A-Za-z0-9.\\-_]{2,}@[A-Za-z]{2,}$").matches(vpa)) return null
                val url = "upi://pay?pa=" + enc(vpa) + "&pn=" + enc(a.payee.ifBlank { vpa.substringBefore('@') }) +
                    (a.amount?.let { "&am=" + String.format(Locale.US, "%.2f", it) } ?: "") + "&cu=INR" +
                    (if (q.isNotBlank()) "&tn=" + enc(q.take(40)) else "")
                Target(Intent(Intent.ACTION_VIEW, Uri.parse(url)), null, isUpi = true)
            }
            "linkedin" -> view("https://www.linkedin.com/jobs/search/?keywords=" + enc(q) + if (to.isNotBlank()) "&location=" + enc(to) else "").let { Target(it, it.data) }
            "naukri" -> {
                val k = q.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
                val url = "https://www.naukri.com/$k-jobs" + if (to.isNotBlank()) "-in-" + to.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-") else ""
                Target(view(url), Uri.parse(url))
            }
            "indeed" -> view("https://in.indeed.com/jobs?q=" + enc(q) + "&l=" + enc(to)).let { Target(it, it.data) }
            "amazon" -> view("https://www.amazon.in/s?k=" + enc(q)).let { Target(it, it.data) }
            "flipkart" -> view("https://www.flipkart.com/search?q=" + enc(q)).let { Target(it, it.data) }
            "blinkit" -> view("https://blinkit.com/s/?q=" + enc(q)).let { Target(it, it.data) }
            "bigbasket" -> view("https://www.bigbasket.com/ps/?q=" + enc(q)).let { Target(it, it.data) }
            "zepto" -> Target(
                Intent(Intent.ACTION_MAIN).setPackage(PACKAGES.getValue("zepto")).addCategory(Intent.CATEGORY_LAUNCHER),
                Uri.parse("https://www.zeptonow.com/"), copyText = q
            )
            "swiggy" -> view("https://www.swiggy.com/search?query=" + enc(q)).let { Target(it, it.data) }
            "zomato" -> view("https://www.zomato.com/search?q=" + enc(q)).let { Target(it, it.data) }
            "flights" -> view("https://www.google.com/travel/flights?q=" + enc(("Flights " + (if (from.isNotBlank()) "from $from " else "") + "to $to " + a.dateText).trim())).let { Target(it, it.data) }
            "trains" -> view("https://www.google.com/search?q=" + enc(("Trains " + (if (from.isNotBlank()) "from $from " else "") + "to $to " + a.dateText).trim())).let { Target(it, it.data) }
            "hotels" -> view("https://www.google.com/travel/hotels/" + enc(to.ifBlank { q })).let { Target(it, it.data) }
            "youtube" -> view("https://www.youtube.com/results?search_query=" + enc(q)).let { Target(it, it.data) }
            "google" -> view("https://www.google.com/search?q=" + enc(q.ifBlank { to })).let { Target(it, it.data) }
            else -> null
        }
    }

    /** Opens the target; falls back to the website (or Play Store) if the app is not installed. Returns an error message or null. */
    fun open(context: Context, t: Target): String? {
        if (t.copyText.isNotBlank()) {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                ?.setPrimaryClip(ClipData.newPlainText("Family Hub", t.copyText))
        }
        return try {
            context.startActivity(t.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            null
        } catch (e: Exception) {
            val fb = t.fallback ?: return "No app on this phone can open this."
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, fb).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                null
            } catch (e2: Exception) {
                "No app on this phone can open this."
            }
        }
    }
}
