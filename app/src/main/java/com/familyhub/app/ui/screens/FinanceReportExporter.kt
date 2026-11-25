package com.familyhub.app.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.familyhub.app.data.model.FinanceTransaction
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object FinanceReportExporter {
    /** Keeps only letters and digits so names like "Yadav/Family" cannot break the file path. */
    private fun safeName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifBlank { "Family" }

    fun exportToCsvAndShare(context: Context, familyName: String, transactions: List<FinanceTransaction>) {
        val csvFile = File(context.cacheDir, "${safeName(familyName)}_Finance_Report.csv")
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        csvFile.bufferedWriter().use { out ->
            out.write("Date,Type,Title,Category,Member,Amount_INR,Status\n")
            transactions.forEach { tx ->
                out.write("${fmt.format(Date(tx.dateMillis))},${tx.type},\"${tx.title}\",${tx.category},\"${tx.memberName}\",${tx.amountInr},${if (tx.isPaid) "PAID" else "DUE"}\n")
            }
        }
        shareFile(context, csvFile, "text/csv")
    }

    fun exportToPdfAndShare(context: Context, familyName: String, transactions: List<FinanceTransaction>) {
        val pdf = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = pdf.startPage(pageInfo)
        val canvas = page.canvas
        val titlePaint = Paint().apply { textSize = 18f; isFakeBoldText = true }
        val bodyPaint = Paint().apply { textSize = 11f }

        canvas.drawText("$familyName - Family Financial Statement (INR)", 40f, 50f, titlePaint)
        var y = 90f
        transactions.take(35).forEach { tx ->
            canvas.drawText("${tx.type} | ${tx.title} (${tx.category}) - ₹${tx.amountInr}", 40f, y, bodyPaint)
            y += 20f
        }
        pdf.finishPage(page)

        val pdfFile = File(context.cacheDir, "${safeName(familyName)}_Finance_Report.pdf")
        FileOutputStream(pdfFile).use { pdf.writeTo(it) }
        pdf.close()
        shareFile(context, pdfFile, "application/pdf")
    }

    private fun shareFile(context: Context, file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Export Family Finance Report"))
    }
}
