package com.tormeo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tormeo.app.TormeoApp
import com.tormeo.app.data.PlayRequest
import com.tormeo.app.player.PlayerActivity
import com.tormeo.app.stream.Codec
import com.tormeo.app.stream.Compat
import com.tormeo.app.stream.StreamCandidate
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StreamsScreen(s: Screen.Streams) {
    val app = TormeoApp.instance
    val ctx = LocalContext.current
    val caps = app.deviceCaps
    val settings = app.settings
    val maxHeight = settings.maxQuality.takeIf { it > 0 } ?: caps.autoMaxHeight
    val all = remember(s.key) { mutableStateListOf<StreamCandidate>() }
    var responded by remember(s.key) { mutableIntStateOf(0) }
    var errors by remember(s.key) { mutableStateOf(listOf<String>()) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var autoPlayDone by rememberSaveable { mutableStateOf(!s.autoPlay) }
    val targets = remember(s.key) { app.addons.addons.value.count { it.manifest.supports("stream", s.type, s.videoId) } }

    fun play(c: StreamCandidate) {
        val st = c.stream
        PlayerActivity.start(
            ctx,
            PlayRequest(
                title = s.title, subtitle = s.subtitle, metaId = s.metaId, type = s.type, videoId = s.videoId,
                poster = s.poster,
                url = if (c.isTorrent) null else st.url,
                headers = st.behaviorHints?.proxyHeaders?.request,
                infoHash = if (c.isTorrent) st.infoHash else null,
                fileIdx = st.fileIdx, sources = st.sources, filename = c.filename,
                sizeBytes = c.sizeBytes, bitrateBps = c.bitrateBps,
            ),
        )
    }

    LaunchedEffect(s.key) {
        val free = app.torrentEngine.freeSpace
        app.addons.streams(s.type, s.videoId).collect { r ->
            responded++
            if (r.error != null) errors = errors + "${r.addon.manifest.name}: ${r.error}"
            val parsed = r.streams.map { StreamCandidate.parse(it, r.addon, caps, maxHeight, s.runtimeMinutes, free) }
            all.addAll(parsed)
            all.sortByDescending { it.score }
        }
    }
    // Auto-play: wait for all addons (or 8 s after the first results), then start the best compatible stream.
    LaunchedEffect(s.key, autoPlayDone) {
        if (autoPlayDone) return@LaunchedEffect
        var waited = 0
        while (responded < targets && (all.isEmpty() || waited < 8000) && waited < 30_000) { delay(250); waited += 250 }
        val best = all.firstOrNull { it.compat != Compat.NO && it.height <= maxHeight } ?: all.firstOrNull { it.compat != Compat.NO }
        autoPlayDone = true
        if (best != null) play(best)
    }

    val visible = all.filter { showAll || (it.height <= maxHeight && !(settings.hideIncompatible && it.compat == Compat.NO)) }
    val hidden = all.size - visible.size

    Column(Modifier.fillMaxSize().padding(screenPadding)) {
        Text(s.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            listOfNotNull(s.subtitle, "Max kvalitet: ${if (maxHeight >= 2160) "4K" else "${maxHeight}p"}").joinToString(" · "),
            color = TextDim, fontSize = 13.sp,
        )
        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (responded < targets) {
                CircularProgressIndicator(Modifier.padding(end = 10.dp).width(18.dp), strokeWidth = 2.dp)
                Text("Tražim izvore… ($responded/$targets addona)", color = TextDim, fontSize = 13.sp)
            } else {
                Text("${visible.size} izvora", color = TextDim, fontSize = 13.sp)
            }
            Spacer(Modifier.weight(1f))
            if (hidden > 0 || showAll) {
                TvButton(if (showAll) "Sakrij nekompatibilne" else "Prikaži sve (+$hidden)") { showAll = !showAll }
            }
        }
        if (!autoPlayDone) {
            Box(Modifier.fillMaxWidth().background(Surface2).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Biram najbolji izvor za ovaj uređaj…", modifier = Modifier.weight(1f))
                    TvButton("Otkaži", requestFocus = true) { autoPlayDone = true }
                }
            }
        }
        if (responded >= targets && all.isEmpty()) {
            CenterMessage(if (targets == 0) "Nijedan instaliran addon ne daje strimove za ovaj sadržaj" else "Nema pronađenih izvora")
            errors.forEach { Text(it, color = Bad, fontSize = 12.sp) }
        }
        LazyColumn(state = rememberLazyListState(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(visible) { c ->
                StreamRow(c, requestFocus = autoPlayDone && c === visible.first()) { play(c) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StreamRow(c: StreamCandidate, requestFocus: Boolean, onClick: () -> Unit) {
    FocusBox(Modifier.fillMaxWidth(), scaleOnFocus = false, requestFocus = requestFocus, onClick = onClick) { focused ->
        Column(Modifier.fillMaxWidth().background(if (focused) Surface2 else Surface1).padding(horizontal = 14.dp, vertical = 10.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Badge(c.qualityLabel, if (c.height >= 2160) Accent else Surface2)
                if (c.dv) Badge("DV", Color(0xFF8E44AD))
                if (c.hdr10Plus) Badge("HDR10+", Color(0xFFB7950B)) else if (c.hdr10) Badge("HDR", Color(0xFFB7950B))
                if (c.codec != Codec.UNKNOWN) Badge(c.codec.label)
                if (c.remux) Badge("REMUX")
                c.audio?.let { Badge(it) }
                if (c.debridCached) Badge("⚡ DIREKTNO", Color(0xFF1E8449))
                else if (c.isTorrent) Badge("P2P")
            }
            val line = buildList {
                c.sizeBytes?.let { add(StreamCandidate.fmtSize(it)) }
                c.seeders?.let { add("👤 $it") }
                c.bitrateBps?.let { add("~${it / 1_000_000} Mbps") }
                add(c.addon.manifest.name)
            }.joinToString("  ·  ")
            Text(line, fontSize = 13.sp, color = TextDim, modifier = Modifier.padding(top = 6.dp))
            Text(
                c.filename ?: c.details.lineSequence().firstOrNull().orEmpty(),
                fontSize = 12.sp, color = TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            c.compatReason?.let {
                Text(
                    (if (c.compat == Compat.NO) "✕ " else "⚠ ") + it,
                    fontSize = 12.sp, color = if (c.compat == Compat.NO) Bad else Warn,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
