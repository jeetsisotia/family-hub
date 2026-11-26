package com.familyhub.app.ui.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Text a page hands to the assistant when the person taps that page's AI button,
 * plus the page it came from so the assistant can show matching suggestions.
 */
object AiDraft {
    var text by mutableStateOf<String?>(null)
    var page by mutableStateOf<String?>(null)

    fun open(page: String?, text: String = "") {
        this.page = page
        this.text = text
    }

    fun takeText(): String? = text.also { text = null }
}

/** Set by the app shell: opens the assistant for the named page. Null on the assistant itself. */
val LocalPageAi = androidx.compose.runtime.staticCompositionLocalOf<((String) -> Unit)?> { null }
