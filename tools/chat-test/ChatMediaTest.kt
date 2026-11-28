// Chat attachments on the free plan: cutting files into Firestore parts and back, labels, waveform, typing.
import com.familyhub.app.data.remote.ChatMedia

private var mediaFailures = 0
private fun mcheck(name: String, cond: Boolean, detail: Any? = null) { if (cond) println("ok  $name") else { mediaFailures++; println("FAIL $name ${detail ?: ""}") } }

fun mediaTests(): Int {
    val small = ByteArray(1000) { (it % 251).toByte() }
    mcheck("a small file is one part", ChatMedia.split(small).size == 1)
    mcheck("parts join back to the same bytes", ChatMedia.join(ChatMedia.split(small)).contentEquals(small))
    val big = ByteArray(ChatMedia.PART_BYTES * 3 + 5) { (it * 7).toByte() }
    val parts = ChatMedia.split(big)
    mcheck("a 1.8 MB file is 4 parts, each within Firestore's document size", parts.size == 4 && parts.all { it.length <= 850_000 }, parts.map { it.length })
    mcheck("many parts join back in order", ChatMedia.join(parts).contentEquals(big))
    mcheck("the largest allowed file fits in 8 parts", ChatMedia.split(ByteArray(ChatMedia.MAX_BYTES)).size <= ChatMedia.MAX_PARTS)
    mcheck("too big is refused with a clear message", runCatching { ChatMedia.split(ByteArray(ChatMedia.MAX_BYTES + 1)) }.exceptionOrNull()?.message?.contains("Too big") == true)
    mcheck("empty is refused", runCatching { ChatMedia.split(ByteArray(0)) }.isFailure)
    mcheck("a missing part is reported, not silently dropped", runCatching { ChatMedia.join(listOf(parts[0], null)) }.exceptionOrNull()?.message?.contains("missing") == true)
    mcheck("labels for lists and notifications", ChatMedia.label("photo", "", 0) == "📷 Photo" && ChatMedia.label("voice", "", 65_000) == "🎤 Voice message (1:05)" && ChatMedia.label("file", "Policy.pdf", 0) == "📄 Policy.pdf")
    mcheck("sizes read naturally", ChatMedia.sizeText(1_240_000) == "1.2 MB" && ChatMedia.sizeText(45_300) == "45 KB" && ChatMedia.sizeText(900) == "900 B")
    val w = ChatMedia.wave((0 until 500).map { it % 100 })
    mcheck("waveform has 40 bars from 8 to 100", w.size == 40 && w.all { it in 8..100 } && w.max() == 100, w)
    mcheck("waveform of silence is flat and visible", ChatMedia.wave(emptyList()).all { it == 8 })
    mcheck("waveform of a short clip still has 40 bars", ChatMedia.wave(listOf(10, 3000, 50)).size == 40)
    mcheck("safe file names keep the extension and drop odd characters", ChatMedia.safeName("Car/insurance:2026.pdf", "file", "application/pdf") == "Car_insurance_2026.pdf")
    mcheck("files without a name get one", ChatMedia.safeName("", "voice", "audio/mp4") == "voice.m4a" && ChatMedia.safeName("", "photo", "image/jpeg") == "photo.jpg")
    mcheck("typing shows for 7 seconds", ChatMedia.isTyping(10_000, 16_000) && !ChatMedia.isTyping(10_000, 18_000))
    mcheck("typing allows a phone clock a little behind the server", ChatMedia.isTyping(20_000, 10_000) && !ChatMedia.isTyping(100_000, 10_000))
    mcheck("nothing typed, nothing shown", !ChatMedia.isTyping(0, 5_000))
    mcheck("typing line", ChatMedia.typingLine(listOf("Asha")) == "Asha is typing…" && ChatMedia.typingLine(listOf("Asha", "Ravi")) == "Asha and Ravi are typing…" && ChatMedia.typingLine(listOf("A", "B", "C")) == "3 people are typing…" && ChatMedia.typingLine(emptyList()) == "")
    return mediaFailures
}
