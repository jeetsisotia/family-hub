package com.familyhub.app.ui.search

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.People
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.familyhub.app.core.search.SearchIndex
import com.familyhub.app.core.search.SearchSeed
import com.familyhub.app.core.theme.EmptyState
import com.familyhub.app.core.theme.GlassFilterChip
import com.familyhub.app.core.theme.GlassSearchBar
import com.familyhub.app.core.theme.glass
import com.familyhub.app.core.theme.riseIn
import com.familyhub.app.ui.AppEnv
import com.familyhub.app.ui.common.ScreenTopBar
import com.familyhub.app.ui.modules.AllModules
import com.familyhub.app.ui.modules.num
import com.familyhub.app.ui.modules.recordsFor
import com.familyhub.app.ui.modules.str
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where a module's search result opens (some modules have their own screen). */
private fun openRoute(route: String): String = when (route) {
    "finance" -> "transactions"
    else -> route
}

private val SKIP_KEYS = setOf("id", "type", "familyId", "createdByUid", "updatedByUid", "ownerUid", "uid", "_private", "visibility")

/** Everything searchable this person may see, built from records already on the phone. */
fun buildSearchDocs(env: AppEnv): List<SearchIndex.Doc> {
    val docs = mutableListOf<SearchIndex.Doc>()
    for (m in AllModules) {
        val recs = recordsFor(m, env.data[m.collection].orEmpty())
        for (r in recs) {
            val title = r.str(m.titleKey)
            if (title.isBlank()) continue
            val subtitle = m.subtitleKeys.map { r.str(it) }.filter { it.isNotBlank() && it.length < 80 }.joinToString(" · ")
            // Words in every short text field (notes, payee, provider…), never photos or long encoded data.
            val text = r.entries.asSequence()
                .filter { (k, v) -> k !in SKIP_KEYS && v is String && v.length in 1..400 && !k.contains("photo", true) && !v.startsWith("data:") && !v.startsWith("local:") }
                .joinToString(" ") { it.value as String }
            val date = m.dateKeys.firstNotNullOfOrNull { (r[it.key] as? Number)?.toLong() } ?: (r["dateMillis"] as? Number)?.toLong()
            docs.add(SearchIndex.Doc(r.str("id").ifBlank { "${m.route}:$title" }, m.title, openRoute(m.route), title, subtitle, text, date, m.amountKey?.let { r.num(it) }))
        }
    }
    env.members.filter { it.status == "ACTIVE" && it.name.isNotBlank() }.forEach { p ->
        docs.add(SearchIndex.Doc("member:" + p.uid, "People", "family", p.name, listOf(p.relation, p.email).filter { it.isNotBlank() }.joinToString(" · ")))
    }
    return docs
}

private object RecentSearches {
    private const val PREFS = "universal_search"
    fun load(ctx: Context): List<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("recent", "").orEmpty().split('\n').filter { it.isNotBlank() }
    fun save(ctx: Context, list: List<String>) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("recent", list.joinToString("\n")).apply()
}

private fun highlight(text: String, ranges: List<IntRange>, color: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    var at = 0
    for (r in ranges) {
        if (r.first < at || r.last >= text.length) continue
        append(text.substring(at, r.first))
        pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = color))
        append(text.substring(r.first, r.last + 1))
        pop()
        at = r.last + 1
    }
    if (at < text.length) append(text.substring(at))
}

/** Universal search across every FamilyHub section this person may see. */
@Composable
fun UniversalSearchScreen(env: AppEnv, initialQuery: String = "") {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val docs = remember(env.data, env.members) { buildSearchDocs(env) }
    val icons: Map<String, ImageVector> = remember { AllModules.associate { it.title to it.icon } + ("People" to Icons.Rounded.People) }
    val order = remember { AllModules.map { it.title } + "People" }
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var section by rememberSaveable { mutableStateOf<String?>(null) }
    var recent by remember { mutableStateOf(RecentSearches.load(ctx)) }
    val requester = remember { FocusRequester() }
    val hitsAll = remember(query, docs) { SearchIndex.search(query, docs) }
    val hits = remember(hitsAll, section) { if (section == null) hitsAll else hitsAll.filter { it.doc.section == section } }
    val suggestions = remember(query, docs) { SearchIndex.suggestions(query, docs) }
    val fmt = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
    val accent = MaterialTheme.colorScheme.primary

    fun keep(q: String) { recent = SearchIndex.remember(recent, q); RecentSearches.save(ctx, recent) }
    fun open(hit: SearchIndex.Hit) {
        keep(query)
        SearchSeed.put(hit.doc.route, hit.doc.title)
        env.navigate(hit.doc.route)
    }

    // Voice search, where the phone has speech recognition.
    val speech = remember { Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_PROMPT, "Search FamilyHub") }
    val canSpeak = remember { speech.resolveActivity(ctx.packageManager) != null }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { query = it; section = null }
        }
    }
    LaunchedEffect(Unit) { if (initialQuery.isBlank()) runCatching { requester.requestFocus() } }

    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenTopBar(title = "Search", onBack = env.onBack, compact = true)
        GlassSearchBar(
            value = query,
            onValueChange = { query = it; section = null },
            placeholder = "Search everything: bills, people, documents…",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            onSearch = { keep(query); focus.clearFocus() },
            onVoice = if (canSpeak) ({ runCatching { voice.launch(speech) } }) else null,
            requester = requester
        )
        if (query.isNotBlank() && hitsAll.isNotEmpty()) {
            val secs = SearchIndex.sections(hitsAll, order)
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                item { GlassFilterChip(section == null, { section = null }, { Text("All (${hitsAll.size})") }) }
                items(secs) { (name, n) -> GlassFilterChip(section == name, { section = if (section == name) null else name }, { Text("$name ($n)") }) }
            }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            when {
                query.isBlank() -> {
                    if (recent.isEmpty()) {
                        item { EmptyState(icon = Icons.Rounded.History, title = "Search all of FamilyHub", message = "Find bills, payments, documents, events, people, shopping items and more. Searches stay on this phone.") }
                    } else {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Recent searches", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                TextButton(onClick = { recent = emptyList(); RecentSearches.save(ctx, recent) }) { Text("Clear all") }
                            }
                        }
                        items(recent, key = { "r:$it" }) { r ->
                            Row(
                                Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp)).clickable { query = r }.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(r, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                IconButton(onClick = { recent = recent - r; RecentSearches.save(ctx, recent) }) { Icon(Icons.Rounded.Close, "Remove $r from recent searches", modifier = Modifier.size(18.dp)) }
                            }
                        }
                    }
                }
                hitsAll.isEmpty() -> item {
                    EmptyState(icon = Icons.Rounded.History, title = "No results for \"$query\"", message = "Try fewer words, another spelling, or an amount like 1200.")
                }
                else -> {
                    if (suggestions.isNotEmpty() && section == null) {
                        item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(suggestions) { s ->
                                    AssistChip(onClick = { query = s }, label = { Text(s, maxLines = 1) }, leadingIcon = { Icon(Icons.Rounded.NorthWest, null, Modifier.size(16.dp)) })
                                }
                            }
                        }
                    }
                    items(hits, key = { it.doc.section + "|" + it.doc.id }) { h ->
                        val d = h.doc
                        Row(
                            Modifier.fillMaxWidth().riseIn(0).glass(RoundedCornerShape(18.dp)).clickable { open(h) }.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(icons[d.section] ?: Icons.Rounded.History, null, tint = accent, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(highlight(d.title, h.titleRanges, accent), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val line = listOfNotNull(d.section, d.subtitle.takeIf { it.isNotBlank() }, d.dateMillis?.let { fmt.format(Date(it)) }).joinToString(" · ")
                                Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            d.amount?.takeIf { it > 0 }?.let { Text("₹" + String.format(Locale("en", "IN"), "%,.0f", it), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                }
            }
        }
    }
}
