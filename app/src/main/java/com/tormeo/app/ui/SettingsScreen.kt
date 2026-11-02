package com.tormeo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tormeo.app.BuildConfig
import com.tormeo.app.TormeoApp
import com.tormeo.app.diag.Diagnostics
import com.tormeo.app.stream.StreamCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen() {
    val app = TormeoApp.instance
    val st = app.settings
    val addons by app.addons.addons.collectAsState()
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) } // forces recomposition after a setting changes
    var newUrl by remember { mutableStateOf("") }
    var installMsg by remember { mutableStateOf<String?>(null) }
    var cacheMsg by remember { mutableStateOf<String?>(null) }
    var lastReport by remember { mutableStateOf<String?>(null) }
    lastReport?.let { CrashReportDialog(it) { lastReport = null } }

    fun <T> cycle(options: List<T>, current: T): T = options[(options.indexOf(current) + 1).mod(options.size)]
    fun changed() { tick++ }

    LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState(), contentPadding = screenPadding) {
        item { Text("Podešavanja", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }

        // ---------------------------------------------------------------- addons
        item { SectionTitle("Addoni", Modifier.padding(top = 16.dp)) }
        items(addons, key = { it.url }) { a ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp).background(Surface1).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${a.manifest.name}  ${a.manifest.version}", fontSize = 15.sp)
                    Text(a.url.replace(Regex("(realdebrid|alldebrid|premiumize|debridlink|torbox|offcloud|putio)=[^|/]+"), "$1=•••"),
                        fontSize = 11.sp, color = TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TvButton("↑") { app.addons.move(a, -1) }
                Spacer(Modifier.width(8.dp))
                TvButton("Ukloni") { app.addons.remove(a) }
            }
        }
        item {
            Column(Modifier.padding(top = 8.dp)) {
                OutlinedTextField(
                    value = newUrl, onValueChange = { newUrl = it }, singleLine = true,
                    label = { Text("URL addona (manifest.json ili stremio://…)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TvButton("Instaliraj", primary = true) {
                        val u = newUrl
                        installMsg = "Instaliram…"
                        scope.launch {
                            installMsg = runCatching { app.addons.install(u) }
                                .fold({ newUrl = ""; "Instaliran: ${it.manifest.name}" }, { "Greška: ${it.message}" })
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    installMsg?.let { Text(it, color = TextDim, fontSize = 13.sp) }
                }
                Text(
                    "Savet: za 4K bez baferovanja koristi Real-Debrid / AllDebrid / TorBox. Konfiguriši Torrentio na " +
                        "torrentio.strem.fun/configure, kopiraj link instalacije i nalepi ga ovde. Keširani izvori (⚡) se puštaju direktno preko HTTP-a.",
                    fontSize = 12.sp, color = TextDim, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        // ---------------------------------------------------------------- playback
        item(key = "playback-$tick") {
            Column {
                SectionTitle("Reprodukcija", Modifier.padding(top = 20.dp))
                val q = st.maxQuality
                ValueRow("Maksimalni kvalitet", when (q) { 0 -> "Auto (${if (app.deviceCaps.autoMaxHeight >= 2160) "4K" else "1080p"})"; 2160 -> "4K"; else -> "${q}p" },
                    "Auto bira prema ekranu i HW dekoderu") { st.maxQuality = cycle(listOf(0, 2160, 1080, 720), q); changed() }
                SwitchRow("Sakrij nekompatibilne izvore", "AV1 bez HW dekodera, 4K na 1080p dekoderu, DV profil 5…", st.hideIncompatible) { st.hideIncompatible = it; changed() }
                SwitchRow("Tunneling", "Dekoder šalje sliku direktno ekranu – manje opterećenje na slabim TV boksovima (automatski se isključi ako pravi problem)", st.tunneling) { st.tunneling = it; changed() }
                SwitchRow("Uskladi frekvenciju ekrana", "Prebacuje TV na 23.976/24/25/50/60 Hz (i 4K) prema filmu – bez trzanja", st.matchFrameRate) { st.matchFrameRate = it; changed() }
                SwitchRow("Softverski audio (ffmpeg)", "Za DTS / TrueHD / E-AC3 kada uređaj nema dekoder", st.ffmpegAudio) { st.ffmpegAudio = it; changed() }
                ValueRow("Jezik zvuka", langLabel(st.audioLang)) { st.audioLang = cycle(LANGS, st.audioLang); changed() }
                ValueRow("Jezik titla", langLabel(st.subLang)) { st.subLang = cycle(LANGS, st.subLang); changed() }
            }
        }

        // ---------------------------------------------------------------- buffering
        item(key = "buffer-$tick") {
            Column {
                SectionTitle("Baferovanje", Modifier.padding(top = 20.dp))
                ValueRow("Pre-buffer pre starta", "${st.prebufferSeconds} s", "Više = sporiji start, ali bez zastoja kasnije") {
                    st.prebufferSeconds = cycle(listOf(5, 10, 15, 20, 30, 45, 60), st.prebufferSeconds); changed()
                }
                ValueRow("Torrent: unapred na disk", "${st.torrentAheadMb} MB", "Koliko filma ispred trenutne pozicije se skida") {
                    st.torrentAheadMb = cycle(listOf(256, 512, 1024, 2048, 4096), st.torrentAheadMb); changed()
                }
                ValueRow("HTTP/Debrid: disk bafer", "${st.httpBufferMb} MB") {
                    st.httpBufferMb = cycle(listOf(256, 512, 768, 1024, 2048), st.httpBufferMb); changed()
                }
                ValueRow("Maks. konekcija (torrent)", "${st.maxConnections}", "Manje = manje opterećenje procesora (važi posle restarta)") {
                    st.maxConnections = cycle(listOf(60, 100, 150, 200, 300), st.maxConnections); changed()
                }
                ValueRow("Upload limit", if (st.uploadLimitKb == 0) "bez limita" else "${st.uploadLimitKb} KB/s", "(važi posle restarta)") {
                    st.uploadLimitKb = cycle(listOf(0, 256, 512, 1024, 4096), st.uploadLimitKb); changed()
                }
                SwitchRow("Zadrži preuzete torrente", "Inače se brišu kad zatvoriš plejer", st.keepCache) { st.keepCache = it; changed() }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TvButton("Obriši keš") {
                        scope.launch {
                            cacheMsg = withContext(Dispatchers.IO) {
                                val size = app.torrentEngine.cacheSize()
                                app.torrentEngine.clearCache()
                                "Obrisano ${StreamCandidate.fmtSize(size)} · slobodno ${StreamCandidate.fmtSize(app.torrentEngine.freeSpace)}"
                            }
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(cacheMsg ?: "Slobodno: ${StreamCandidate.fmtSize(app.torrentEngine.freeSpace)}", color = TextDim, fontSize = 13.sp)
                }
            }
        }

        // ---------------------------------------------------------------- device
        item {
            Column {
                SectionTitle("Ovaj uređaj", Modifier.padding(top = 20.dp))
                app.deviceCaps.summary().forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(k, color = TextDim, fontSize = 14.sp)
                        Text(v, fontSize = 14.sp)
                    }
                }
                Row(Modifier.padding(top = 12.dp)) {
                    TvButton("Poslednji izveštaj o grešci") { lastReport = Diagnostics.lastReport() ?: "Nema zabeleženih grešaka." }
                }
                Text("Tormeo ${BuildConfig.VERSION_NAME}", color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp))
            }
        }
    }
}

private val LANGS = listOf("", "sr", "hr", "bs", "en", "de")
private fun langLabel(l: String) = when (l) {
    "" -> "automatski"; "sr" -> "srpski"; "hr" -> "hrvatski"; "bs" -> "bosanski"; "en" -> "engleski"; "de" -> "nemački"; else -> l
}
