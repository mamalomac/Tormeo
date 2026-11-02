package com.tormeo.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tormeo.app.TormeoApp
import com.tormeo.app.data.AddonRepository
import com.tormeo.app.data.MetaPreview
import com.tormeo.app.player.PlayerActivity

@Composable
fun HomeScreen(nav: Navigator) {
    val app = TormeoApp.instance
    val addons by app.addons.addons.collectAsState()
    val progress by app.progress.entries.collectAsState()
    val catalogs = remember(addons) { app.addons.homeCatalogs() }
    val cont = remember(progress) { app.progress.continueWatching() }
    val ctx = LocalContext.current
    var lastFocused by rememberSaveable { mutableStateOf<String?>(null) }
    val pad = screenPadding
    val dir = LocalLayoutDirection.current
    val hPad = PaddingValues(start = pad.calculateStartPadding(dir), end = pad.calculateEndPadding(dir))

    LazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 48.dp),
    ) {
        item("top") {
            Row(Modifier.fillMaxWidth().padding(hPad).padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                // narrow phones: short labels so the top bar fits on one line
                val compact = !app.isTv && LocalConfiguration.current.screenWidthDp < 600
                Text("Tormeo", fontSize = if (compact) 24.sp else 26.sp, fontWeight = FontWeight.Black, color = Accent, maxLines = 1)
                Spacer(Modifier.weight(1f))
                TvButton(if (compact) "🔍 Traži" else "Pretraga", requestFocus = lastFocused == null) { nav.go(Screen.Search) }
                Spacer(Modifier.width(8.dp))
                TvButton(if (compact) "⚙" else "Podešavanja") { nav.go(Screen.Settings) }
            }
        }
        if (addons.isEmpty()) {
            item("empty") { CenterMessage("Učitavam addone… (proveri internet konekciju)") }
        }
        if (cont.isNotEmpty()) {
            item("continue") {
                Column {
                    SectionTitle("Nastavi gledanje", Modifier.padding(hPad))
                    LazyRow(contentPadding = hPad, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(cont, key = { "c:" + it.request.videoId }) { e ->
                            val key = "c:" + e.request.videoId
                            Poster(
                                title = e.request.title,
                                image = e.request.poster,
                                progress = e.fraction,
                                subtitle = e.request.subtitle ?: PlayerActivity.fmtTime(e.positionMs),
                                requestFocus = key == lastFocused,
                                onLongClick = {
                                    lastFocused = key
                                    nav.go(Screen.Detail(e.request.type, e.request.metaId, e.request.title, e.request.poster))
                                },
                            ) {
                                lastFocused = key
                                PlayerActivity.start(ctx, e.request)
                            }
                        }
                    }
                }
            }
        }
        items(catalogs, key = { it.key }) { ref ->
            CatalogRow(ref, hPad, lastFocused, onFocusKey = { lastFocused = it }) { m ->
                nav.go(Screen.Detail(m.type, m.id, m.name, m.poster))
            }
        }
    }
}

@Composable
private fun CatalogRow(
    ref: AddonRepository.CatalogRef,
    hPad: PaddingValues,
    lastFocused: String?,
    onFocusKey: (String) -> Unit,
    onOpen: (MetaPreview) -> Unit,
) {
    val repo = TormeoApp.instance.addons
    val items by produceState(initialValue = repo.cachedCatalog(ref), ref.key) {
        if (value == null) value = runCatching { repo.catalog(ref) }.getOrDefault(emptyList())
    }
    val list = items
    if (list != null && list.isEmpty()) return
    Column(Modifier.padding(bottom = 8.dp)) {
        SectionTitle(ref.title, Modifier.padding(hPad))
        if (list == null) {
            Spacer(Modifier.height(posterWidth() * 1.5f))
            return@Column
        }
        LazyRow(contentPadding = hPad, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(list, key = { it.type + it.id }) { m ->
                val key = ref.key + "/" + m.id
                Poster(
                    m.name, m.poster,
                    subtitle = listOfNotNull(m.releaseInfo, m.imdbRating?.let { "★ $it" }).joinToString("  ").ifEmpty { null },
                    requestFocus = key == lastFocused,
                ) {
                    onFocusKey(key)
                    onOpen(m)
                }
            }
        }
    }
}
