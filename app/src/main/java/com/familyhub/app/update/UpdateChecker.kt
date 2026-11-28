package com.familyhub.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.familyhub.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Looks for a newer FamilyHub build among the project's public GitHub Releases. It never downloads or installs
 * anything by itself: the person taps "Download update", the browser fetches the file, and Android installs it only
 * after they confirm — and only if it is signed with the same key as the installed app.
 */
object UpdateChecker {
    private const val LATEST = "https://api.github.com/repos/jeetsisotia/family-hub/releases/latest"
    const val RELEASES_PAGE = "https://github.com/jeetsisotia/family-hub/releases"
    private const val PREFS = "updates"

    data class Release(
        val tag: String,
        val name: String,
        val notes: String,
        val publishedAt: String,
        val apkName: String?,
        val apkUrl: String?,
        val apkBytes: Long,
        val sha256: String?,
        val pageUrl: String
    ) { val build get() = UpdateMath.buildNumber(tag) }

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val latest: Release?) : State
        data class Available(val release: Release) : State
        data class Failed(val message: String) : State
    }

    private val state = MutableStateFlow<State>(State.Idle)
    val flow: StateFlow<State> = state

    val currentVersionCode: Int get() = BuildConfig.VERSION_CODE
    val currentVersionName: String get() = BuildConfig.VERSION_NAME

    /** At app start: checks at most every 12 hours, quietly (a failure just leaves the last answer). */
    suspend fun checkIfDue(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!UpdateMath.dueToCheck(p.getLong("last", 0), System.currentTimeMillis())) return
        check(ctx, quiet = true)
    }

    suspend fun check(ctx: Context, quiet: Boolean = false) {
        if (!quiet) state.value = State.Checking
        try {
            val r = withContext(Dispatchers.IO) { fetchLatest() }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("last", System.currentTimeMillis()).apply()
            state.value = if (r != null && r.apkUrl != null && UpdateMath.isNewer(r.tag, currentVersionCode)) State.Available(r) else State.UpToDate(r)
        } catch (e: Exception) {
            if (!quiet) state.value = State.Failed("Could not check for updates: ${e.message ?: "no connection"}")
        }
    }

    private fun fetchLatest(): Release? {
        val c = (URL(LATEST).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "FamilyHub-Android")
        }
        try {
            if (c.responseCode == 404) return null
            if (c.responseCode !in 200..299) throw IllegalStateException("GitHub answered ${c.responseCode}")
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val assets = o.optJSONArray("assets")
            val list = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
            val apk = UpdateMath.pickApk(list.map { it.optString("name") })?.let { n -> list.first { it.optString("name") == n } }
            return Release(
                tag = o.optString("tag_name"),
                name = o.optString("name").ifBlank { o.optString("tag_name") },
                notes = UpdateMath.notes(o.optString("body")),
                publishedAt = o.optString("published_at").take(10),
                apkName = apk?.optString("name"),
                apkUrl = apk?.optString("browser_download_url")?.takeIf { it.startsWith("https://github.com/") },
                apkBytes = apk?.optLong("size") ?: 0L,
                sha256 = UpdateMath.checksum(apk?.optString("digest")),
                pageUrl = o.optString("html_url").ifBlank { RELEASES_PAGE }
            )
        } finally { c.disconnect() }
    }

    /** Opens the download in the browser. Installing is up to the person (Android asks and checks the signature). */
    fun download(ctx: Context, r: Release) {
        val url = r.apkUrl ?: r.pageUrl
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Test screens only. */
    fun showForDemo(s: State) { state.value = s }
}
