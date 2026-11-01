package com.tormeo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.tormeo.app.TormeoApp
import com.tormeo.app.data.Meta
import com.tormeo.app.data.Video
import com.tormeo.app.player.PlayerActivity

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(nav: Navigator, s: Screen.Detail) {
    val app = TormeoApp.instance
    val ctx = LocalContext.current
    val state by produceState<Result<Meta>?>(null, s.id) { value = runCatching { app.addons.meta(s.type, s.id) } }
    val progress by app.progress.entries.collectAsState()
    val meta = state?.getOrNull()

    Box(Modifier.fillMaxSize()) {
        meta?.background?.let {
            AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().alpha(0.18f))
        }
        val episodes = meta?.videos.orEmpty().filter { it.season != null }.sortedWith(compareBy({ it.season }, { it.ep }))
        val seasons = episodes.mapNotNull { it.season }.distinct().sortedWith(compareBy { if (it == 0) Int.MAX_VALUE else it })
        val lastWatched = progress.firstOrNull { it.request.metaId == s.id }
        var season by rememberSaveable(seasons.size) {
            mutableIntStateOf(
                lastWatched?.request?.videoId?.let { vid -> episodes.firstOrNull { it.id == vid }?.season }
                    ?: seasons.firstOrNull() ?: 1,
            )
        }

        LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState(), contentPadding = screenPadding) {
            item("header") {
                Row {
                    val w = posterWidth() * 1.3f
                    AsyncImage(
                        meta?.poster ?: s.poster, s.name, contentScale = ContentScale.Crop,
                        modifier = Modifier.width(w).height(w * 1.5f).clip(RoundedCornerShape(10.dp)).background(Surface2),
                    )
                    Spacer(Modifier.width(24.dp))
                    Column(Modifier.weight(1f)) {
                        Text(meta?.name ?: s.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        val info = listOfNotNull(meta?.releaseInfo, meta?.runtime, meta?.imdbRating?.let { "★ $it" })
                        if (info.isNotEmpty()) Text(info.joinToString("   "), color = TextDim, modifier = Modifier.padding(top = 4.dp))
                        if (!meta?.genres.isNullOrEmpty()) Text(meta!!.genres.joinToString(" · "), color = TextDim, fontSize = 13.sp)
                        meta?.description?.let {
                            Text(it, maxLines = 5, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
                        }
                        when {
                            state == null -> Text("Učitavam…", color = TextDim, modifier = Modifier.padding(top = 16.dp))
                            state!!.isFailure -> Text("Greška: ${state!!.exceptionOrNull()?.message}", color = Bad, modifier = Modifier.padding(top = 16.dp))
                            s.type != "series" || episodes.isEmpty() -> FlowRow(
                                Modifier.padding(top = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                val resume = progress.firstOrNull { it.request.videoId == s.id && it.fraction in 0.01f..0.94f }
                                if (resume != null) {
                                    TvButton("▶ Nastavi (${PlayerActivity.fmtTime(resume.positionMs)})", primary = true, requestFocus = true) {
                                        PlayerActivity.start(ctx, resume.request)
                                    }
                                }
                                TvButton("▶ Pusti najbolji izvor", primary = resume == null, requestFocus = resume == null) {
                                    nav.go(streamsFor(meta!!, s.id, null, autoPlay = true))
                                }
                                TvButton("Izaberi izvor") { nav.go(streamsFor(meta!!, s.id, null, autoPlay = false)) }
                            }
                            else -> lastWatched?.let { lw ->
                                Row(Modifier.padding(top = 20.dp)) {
                                    TvButton("▶ Nastavi ${lw.request.subtitle ?: ""}", primary = true, requestFocus = true) {
                                        PlayerActivity.start(ctx, lw.request)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (meta != null && seasons.isNotEmpty()) {
                item("seasons") {
                    LazyRow(Modifier.padding(top = 24.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(seasons) { n ->
                            TvButton(if (n == 0) "Specijali" else "Sezona $n", primary = n == season, requestFocus = lastWatched == null && n == season) {
                                season = n
                            }
                        }
                    }
                }
                items(episodes.filter { it.season == season }, key = { it.id }) { v ->
                    val p = progress.firstOrNull { it.request.videoId == v.id }
                    EpisodeRow(v, p?.fraction) {
                        nav.go(streamsFor(meta, v.id, v, autoPlay = false))
                    }
                }
            }
        }
    }
}

private fun streamsFor(meta: Meta, videoId: String, v: Video?, autoPlay: Boolean) = Screen.Streams(
    type = meta.type,
    metaId = meta.id,
    videoId = videoId,
    title = meta.name,
    subtitle = v?.let { "S${it.season}E${it.ep} · ${it.label}" },
    poster = meta.poster,
    runtimeMinutes = meta.runtimeMinutes,
    autoPlay = autoPlay,
)

@Composable
private fun EpisodeRow(v: Video, progress: Float?, onClick: () -> Unit) {
    FocusBox(Modifier.fillMaxWidth().padding(vertical = 4.dp), scaleOnFocus = false, onClick = onClick) { focused ->
        Column(Modifier.fillMaxWidth().background(if (focused) Surface2 else Surface1).padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${v.ep ?: "?"}.", fontWeight = FontWeight.Bold, color = Accent, modifier = Modifier.width(36.dp))
                Text(v.label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                v.released?.take(10)?.let { Text(it, color = TextDim, fontSize = 12.sp) }
            }
            (v.overview ?: v.description)?.let {
                Text(it, color = TextDim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 36.dp, top = 4.dp))
            }
            if (progress != null && progress > 0.01f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.padding(start = 36.dp, top = 8.dp).fillMaxWidth().height(3.dp),
                    color = Accent, trackColor = Color(0x33FFFFFF),
                )
            }
        }
    }
}

