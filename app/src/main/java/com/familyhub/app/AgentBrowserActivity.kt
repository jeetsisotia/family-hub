package com.familyhub.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.familyhub.app.core.theme.FamilyHubTheme

/**
 * The official website of a service, opened inside Family Hub with the details the assistant prepared.
 * You sign in here yourself (the sign-in stays for next time) and you tap the final Book, Pay or Apply.
 * Family Hub never fills passwords, never taps those buttons and never reads the pages.
 */
class AgentBrowserActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"

        fun open(ctx: Context, url: String, title: String) {
            ctx.startActivity(
                Intent(ctx, AgentBrowserActivity::class.java)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_TITLE, title)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val start = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Official site" }
        if (!start.startsWith("https://")) {
            finish()
            return
        }
        setContent {
            FamilyHubTheme {
                var web by remember { mutableStateOf<WebView?>(null) }
                var host by remember { mutableStateOf(Uri.parse(start).host.orEmpty()) }
                var progress by remember { mutableStateOf(0) }
                var canBack by remember { mutableStateOf(false) }
                BackHandler(enabled = canBack) { web?.goBack() }
                Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { finish() }) { Icon(Icons.Rounded.Close, contentDescription = "Close") }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(12.dp), tint = Color(0xFF34C759))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(host, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        IconButton(onClick = {
                            val u = web?.url ?: start
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                            } catch (e: Exception) {
                                // No other app can open it; stay here.
                            }
                        }) { Icon(Icons.Rounded.OpenInNew, contentDescription = "Open in the app or browser") }
                    }
                    if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "You're on the official site. Sign in and check the details, then tap Book, Pay or Apply yourself. " +
                            "Family Hub never taps those for you.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.setSupportMultipleWindows(false)
                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        progress = newProgress
                                    }
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                        val u = request?.url ?: return false
                                        if (u.scheme == "https") return false
                                        // App links (upi://, tel:, intent://) open in their own app, where the person confirms.
                                        try {
                                            val i = if (u.scheme == "intent") Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME)
                                            else Intent(Intent.ACTION_VIEW, u)
                                            i.addCategory(Intent.CATEGORY_BROWSABLE)
                                            i.component = null
                                            i.selector = null
                                            startActivity(i)
                                        } catch (e: Exception) {
                                            // Nothing can open it.
                                        }
                                        return true
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        host = Uri.parse(url.orEmpty()).host.orEmpty()
                                        canBack = view?.canGoBack() == true
                                        CookieManager.getInstance().flush()
                                    }
                                }
                                loadUrl(start)
                                web = this
                            }
                        }
                    )
                }
            }
        }
    }
}
