package com.familyhub.app.ui.common

import com.familyhub.app.core.theme.GlassDialog
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Joining a family by QR code. The QR holds only the family's invite code; joining still needs an admin's approval.
 * Scanning uses Google's code scanner, which shows its own camera screen, so the app needs no camera permission.
 */
object InviteQr {
    private const val PREFIX = "FAMILYHUB:"
    private val CODE = Regex("FH-[A-Z0-9]{6}")

    fun content(code: String) = PREFIX + code.trim().uppercase()

    /** The invite code inside a scanned QR (or typed text), or null when it has none. */
    fun codeFrom(raw: String?): String? = raw?.trim()?.uppercase()?.let { s ->
        CODE.find(s)?.value ?: s.removePrefix(PREFIX).takeIf { s.startsWith(PREFIX) && it.isNotBlank() }
    }

    fun bitmap(text: String, size: Int = 640): ImageBitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    /** Opens the scanner. [onCode] gets the invite code; [onError] a short message. Cancelling calls neither. */
    fun scan(context: Context, onCode: (String) -> Unit, onError: (String) -> Unit) {
        try {
            val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
            GmsBarcodeScanning.getClient(context, options).startScan()
                .addOnSuccessListener { b ->
                    val code = codeFrom(b.rawValue)
                    if (code != null) onCode(code) else onError("That QR code is not a Family Hub invite.")
                }
                .addOnFailureListener { onError("The scanner could not start. Type the invite code instead.") }
        } catch (e: Exception) {
            onError("The scanner is not available on this phone. Type the invite code instead.")
        }
    }
}

/** A dialog with the family's invite QR, to scan from another phone. */
@Composable
fun InviteQrDialog(familyName: String, code: String, onDismiss: () -> Unit) {
    val image = remember(code) { runCatching { InviteQr.bitmap(InviteQr.content(code)) }.getOrNull() }
    GlassDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join $familyName") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = "QR code for invite code $code",
                        modifier = Modifier.size(220.dp).background(Color.White, RoundedCornerShape(12.dp)).padding(8.dp)
                    )
                }
                Text(code, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "On the other phone: sign in, choose Join Existing Family and tap Scan QR. You approve them in Family.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}
