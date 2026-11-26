package com.familyhub.app.money

import java.io.ByteArrayInputStream
import java.util.Calendar
import java.util.Locale
import java.util.zip.ZipInputStream

/** One line of a bank or card statement (or an Axio / Walnut export). */
data class StatementRow(
    val index: Int,
    val dateMillis: Long,
    val description: String,
    val amount: Double,
    val isCredit: Boolean,
    val balance: Double?,
    val category: String,
    val refNo: String
)

/** Which column holds what. -1 means none. */
data class ColumnMap(
    val date: Int = -1,
    val description: Int = -1,
    val debit: Int = -1,
    val credit: Int = -1,
    val amount: Int = -1,
    val drCr: Int = -1,
    val balance: Int = -1,
    val category: Int = -1,
    val ref: Int = -1
)

/**
 * Reads statements saved as CSV or Excel (.xlsx) on the phone. Nothing is uploaded.
 * The header row and columns are guessed and can be changed by the person before anything is saved.
 */
object StatementParser {

    class UnsupportedFile(message: String) : Exception(message)

    // ---------- reading the table ----------

    fun readTable(bytes: ByteArray, fileName: String): List<List<String>> {
        if (bytes.size >= 4 && bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte()) {
            throw UnsupportedFile("This is an old Excel (.xls) file. Open it and save it as .xlsx or .csv, then try again.")
        }
        if (bytes.size >= 4 && bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte()) {
            throw UnsupportedFile("PDF statements can't be read. Download the statement as CSV or Excel from your bank's website or app.")
        }
        val isZip = bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        return if (isZip || fileName.lowercase(Locale.ROOT).endsWith(".xlsx")) readXlsx(bytes) else readCsv(String(bytes, Charsets.UTF_8))
    }

    fun readCsv(raw: String): List<List<String>> {
        val text = raw.removePrefix("﻿")
        val sample = text.lineSequence().take(20).joinToString("\n")
        val delim = listOf(',', ';', '\t', '|').maxByOrNull { d -> sample.count { it == d } } ?: ','
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { cell.append('"'); i++ } else quoted = false
                } else cell.append(ch)
            } else when (ch) {
                '"' -> quoted = true
                delim -> { row.add(cell.toString().trim()); cell.setLength(0) }
                '\r' -> {}
                '\n' -> { row.add(cell.toString().trim()); cell.setLength(0); rows.add(row); row = ArrayList() }
                else -> cell.append(ch)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString().trim()); rows.add(row) }
        return rows.filter { r -> r.any { it.isNotBlank() } }
    }

    private fun unescape(s: String): String =
        s.replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: "" }
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    private fun colIndex(ref: String): Int {
        var n = 0
        for (c in ref) { if (c.isLetter()) n = n * 26 + (c.uppercaseChar() - 'A' + 1) else break }
        return n - 1
    }

    fun readXlsx(bytes: ByteArray): List<List<String>> {
        val files = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (e.name == "xl/sharedStrings.xml" || e.name.startsWith("xl/worksheets/sheet")) {
                    files[e.name] = String(z.readBytes(), Charsets.UTF_8)
                }
                e = z.nextEntry
            }
        }
        val shared = files["xl/sharedStrings.xml"]?.let { xml ->
            Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { si ->
                Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL).findAll(si.groupValues[1]).joinToString("") { unescape(it.groupValues[1]) }
            }.toList()
        }.orEmpty()
        val sheetName = files.keys.filter { it.startsWith("xl/worksheets/sheet") }.minByOrNull { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 99 }
            ?: throw UnsupportedFile("No sheet found in this Excel file.")
        val sheet = files[sheetName].orEmpty()
        val out = ArrayList<List<String>>()
        val rowRx = Regex("<row\\b[^>]*?(?:/>|>(.*?)</row>)", RegexOption.DOT_MATCHES_ALL)
        val cellRx = Regex("<c\\b([^>]*?)(?:/>|>(.*?)</c>)", RegexOption.DOT_MATCHES_ALL)
        for (rm in rowRx.findAll(sheet)) {
            val body = rm.groupValues[1]
            val cells = HashMap<Int, String>()
            var next = 0
            for (cm in cellRx.findAll(body)) {
                val attrs = cm.groupValues[1]
                val inner = cm.groupValues[2]
                val ref = Regex("r=\"([A-Z]+)\\d*\"").find(attrs)?.groupValues?.get(1)
                val col = if (ref != null) colIndex(ref) else next
                next = col + 1
                val type = Regex("t=\"(\\w+)\"").find(attrs)?.groupValues?.get(1).orEmpty()
                val v = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL).find(inner)?.groupValues?.get(1)
                val text = when (type) {
                    "s" -> v?.trim()?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                    "inlineStr" -> Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL).findAll(inner).joinToString("") { unescape(it.groupValues[1]) }
                    else -> v?.let { unescape(it) }.orEmpty()
                }
                cells[col] = text.trim()
            }
            if (cells.isEmpty()) continue
            val width = (cells.keys.maxOrNull() ?: 0) + 1
            out.add((0 until width).map { cells[it].orEmpty() })
        }
        return out.filter { r -> r.any { it.isNotBlank() } }
    }

    // ---------- finding the columns ----------

    private fun norm(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[^a-z/ ]"), " ").replace(Regex("\\s*/\\s*"), "/")
        .replace(Regex("\\s+"), " ").trim()

    private val dateWords = listOf("transaction date", "txn date", "tran date", "date", "posting date", "value date", "value dt")
    private val descWords = listOf("narration", "description", "particulars", "transaction details", "details", "remarks", "merchant", "place", "payee", "transaction remarks", "note")
    private val drCrWords = listOf("dr/cr", "cr/dr", "debit/credit", "transaction type", "txn type", "type")
    private val debitWords = listOf("withdrawal amt", "withdrawal amount", "withdrawals", "withdrawal", "debit amount", "debit amt", "debits", "debit", "dr amount", "money out", "paid out")
    private val creditWords = listOf("deposit amt", "deposit amount", "deposits", "deposit", "credit amount", "credit amt", "credits", "credit", "cr amount", "money in", "paid in")
    private val amountWords = listOf("transaction amount", "txn amount", "amount inr", "amount", "amt")
    private val balanceWords = listOf("closing balance", "running balance", "available balance", "balance", "bal")
    private val categoryWords = listOf("category")
    private val refWords = listOf("ref no", "reference no", "reference", "chq/ref", "chq/ref no", "cheque no", "cheque", "utr", "ref")

    private fun find(header: List<String>, words: List<String>, taken: Set<Int>): Int {
        val h = header.map { norm(it) }
        for (w in words) {
            val exact = h.indexOfFirst { it == w }
            if (exact >= 0 && exact !in taken) return exact
        }
        for (w in words) {
            val i = h.indices.firstOrNull { it !in taken && (h[it].startsWith("$w ") || h[it].endsWith(" $w") || h[it].contains(" $w ")) }
            if (i != null) return i
        }
        return -1
    }

    /** The header is the first row (of the first 40) naming a date column and an amount or debit column. */
    fun headerRow(table: List<List<String>>): Int {
        for (i in 0 until minOf(40, table.size)) {
            val m = guess(table[i])
            if (m.date >= 0 && (m.amount >= 0 || m.debit >= 0 || m.credit >= 0)) return i
        }
        return 0
    }

    fun guess(header: List<String>): ColumnMap {
        val taken = HashSet<Int>()
        fun pick(words: List<String>): Int = find(header, words, taken).also { if (it >= 0) taken.add(it) }
        val date = pick(dateWords)
        val drCr = pick(drCrWords)
        val debit = pick(debitWords)
        val credit = pick(creditWords)
        val amount = pick(amountWords)
        val balance = pick(balanceWords)
        val category = pick(categoryWords)
        val ref = pick(refWords)
        val desc = pick(descWords)
        return ColumnMap(date, desc, debit, credit, amount, drCr, balance, category, ref)
    }

    // ---------- values ----------

    /** 1,234.56 • ₹ 1,234 • (1,234.00) • -500 • 1,200.00 Dr • 1,200 CR. Null when the cell has no number. */
    fun parseAmount(raw: String): Double? {
        val s = raw.trim()
        if (s.isEmpty() || s == "-") return null
        val neg = s.startsWith("-") || (s.startsWith("(") && s.endsWith(")")) || Regex("(?i)dr\\.?$").containsMatchIn(s)
        val digits = Regex("[0-9][0-9,]*(?:\\.[0-9]+)?").find(s)?.value?.replace(",", "") ?: return null
        val v = digits.toDoubleOrNull() ?: return null
        return if (neg) -v else v
    }

    private val monthNames = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    private fun noon(y: Int, m: Int, d: Int): Long? {
        if (m !in 1..12 || d !in 1..31) return null
        val year = if (y < 100) 2000 + y else y
        if (year !in 1990..2100) return null
        return Calendar.getInstance().apply { clear(); set(year, m - 1, d, 12, 0, 0) }.timeInMillis
    }

    /** Reads a date cell. [dayFirst] decides 03/04/2026 (3 April when true). Excel date numbers are understood too. */
    fun parseDate(raw: String, dayFirst: Boolean = true): Long? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        Regex("^(\\d{5})(?:\\.\\d+)?$").find(s)?.let { m ->
            val serial = m.groupValues[1].toInt()
            if (serial in 25_000..80_000) {
                val c = Calendar.getInstance().apply { clear(); set(1899, Calendar.DECEMBER, 30, 12, 0, 0); add(Calendar.DAY_OF_YEAR, serial) }
                return c.timeInMillis
            }
        }
        Regex("^(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})").find(s)?.let { m ->
            return noon(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
        Regex("^(\\d{1,2})[-/. ](\\d{1,2})[-/. ](\\d{2,4})").find(s)?.let { m ->
            val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt(); val y = m.groupValues[3].toInt()
            return if (dayFirst) noon(y, b, a) else noon(y, a, b)
        }
        Regex("^(\\d{1,2})[-/ ]?([A-Za-z]{3,9})[-/ ,]*(\\d{2,4})").find(s)?.let { m ->
            val mon = monthNames.indexOf(m.groupValues[2].lowercase(Locale.ROOT).take(3)) + 1
            if (mon > 0) return noon(m.groupValues[3].toInt(), mon, m.groupValues[1].toInt())
        }
        Regex("^([A-Za-z]{3,9})[ -](\\d{1,2}),?[ -](\\d{4})").find(s)?.let { m ->
            val mon = monthNames.indexOf(m.groupValues[1].lowercase(Locale.ROOT).take(3)) + 1
            if (mon > 0) return noon(m.groupValues[3].toInt(), mon, m.groupValues[2].toInt())
        }
        return null
    }

    /** True unless a date in the column only makes sense month-first (like 04/23/2026). */
    fun looksDayFirst(values: List<String>): Boolean {
        var dayFirst = 0
        var monthFirst = 0
        for (v in values) {
            val m = Regex("^(\\d{1,2})[-/. ](\\d{1,2})[-/. ]\\d{2,4}").find(v.trim()) ?: continue
            if (m.groupValues[1].toInt() > 12) dayFirst++
            if (m.groupValues[2].toInt() > 12) monthFirst++
        }
        return !(monthFirst > 0 && dayFirst == 0)
    }

    private val skipRows = Regex("(?i)^(opening|closing) balance|^total|^balance (b/f|c/f|brought|carried)|^statement summary")

    /** Statement lines from [table], reading the rows after [header] with columns [map]. */
    fun rows(table: List<List<String>>, header: Int, map: ColumnMap, dayFirst: Boolean): List<StatementRow> {
        val out = ArrayList<StatementRow>()
        val signed = map.amount >= 0 && looksSignedCredit(table, header, map)
        for (i in header + 1 until table.size) {
            val r = table[i]
            fun cell(c: Int) = if (c >= 0) r.getOrNull(c).orEmpty() else ""
            val date = parseDate(cell(map.date), dayFirst) ?: continue
            val desc = cell(map.description).replace(Regex("\\s+"), " ").trim()
            if (skipRows.containsMatchIn(desc)) continue
            var amount: Double? = null
            var credit = false
            val dr = parseAmount(cell(map.debit))?.let { kotlin.math.abs(it) }?.takeIf { it > 0 }
            val cr = parseAmount(cell(map.credit))?.let { kotlin.math.abs(it) }?.takeIf { it > 0 }
            if (dr != null || cr != null) {
                if (cr != null && (dr == null || dr == 0.0)) { amount = cr; credit = true } else amount = dr
            } else {
                val a = parseAmount(cell(map.amount)) ?: continue
                val raw = cell(map.amount).trim()
                val flag = cell(map.drCr).lowercase(Locale.ROOT).trim()
                credit = when {
                    map.drCr >= 0 && (flag.startsWith("cr") || flag.startsWith("credit") || flag == "c" || "income" in flag || "deposit" in flag) -> true
                    map.drCr >= 0 && (flag.startsWith("dr") || flag.startsWith("debit") || flag == "d" || "expense" in flag || "withdraw" in flag) -> false
                    Regex("(?i)cr\\.?$").containsMatchIn(raw) -> true
                    Regex("(?i)dr\\.?$").containsMatchIn(raw) -> false
                    // One signed amount column: positive is money in only when the column also has negatives.
                    else -> a > 0 && signed
                }
                amount = kotlin.math.abs(a)
            }
            if (amount == null || amount <= 0.0) continue
            val ref = cell(map.ref).ifBlank { Regex("\\b(\\d{10,18})\\b").find(desc)?.value.orEmpty() }
            out.add(StatementRow(i, date, desc, amount, credit, parseAmount(cell(map.balance)), cell(map.category), ref))
        }
        return out
    }

    /** With one signed amount column, positives are credits only if the column has negatives too. */
    private fun looksSignedCredit(table: List<List<String>>, header: Int, map: ColumnMap): Boolean =
        (header + 1 until minOf(table.size, header + 200)).any { (parseAmount(table[it].getOrNull(map.amount).orEmpty()) ?: 0.0) < 0 }

    // ---------- reading a narration ----------

    private val junk = setOf(
        "upi", "dr", "cr", "imps", "neft", "rtgs", "pos", "ecom", "atm", "nfs", "ach", "nach", "inb", "mob", "ib", "bil", "billpay",
        "payment", "transfer", "trf", "to", "from", "by", "ref", "txn", "p2a", "p2m", "yesb", "icic", "hdfc", "sbin", "utib", "kkbk", "paytm", "ybl", "okaxis", "oksbi", "okhdfcbank", "okicici", "axl", "ibl"
    )

    /** A readable payee from "UPI/DR/432112345678/SWIGGY/YESB/swiggy@yesb" and the like. */
    fun merchantOf(desc: String): String {
        val parts = desc.split(Regex("[/|*\\\\:-]+|\\s{2,}")).map { it.trim() }.filter { it.isNotBlank() }
        val name = parts.firstOrNull { p ->
            val low = p.lowercase(Locale.ROOT)
            p.count { it.isLetter() } >= 3 && low !in junk && !low.contains('@') && p.count { it.isDigit() } * 2 < p.length
        } ?: desc
        return name.split(' ').filter { it.isNotBlank() }.take(4).joinToString(" ") { w ->
            if (w.length <= 3 && w.all { it.isUpperCase() }) w else w.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
        }.take(40)
    }

    private val cardPayRx = Regex("(?i)(credit ?card (bill )?pay|cc ?(bill )?pay|card bill|payment received|thank you for (your )?payment|bbps.*credit card|autopay.*card)")
    private val selfRx = Regex("(?i)(self transfer|to self|own account|transfer to .{0,20}(savings|a/c)|sweep|fd booking|rd installment)")
    private val atmRx = Regex("(?i)(\\batm\\b|cash withdrawal|nfs/|cwdr|atw)")

    fun isCardPayment(desc: String) = cardPayRx.containsMatchIn(desc)
    fun isSelfTransfer(desc: String) = selfRx.containsMatchIn(desc)
    fun isAtm(desc: String) = atmRx.containsMatchIn(desc)
}
