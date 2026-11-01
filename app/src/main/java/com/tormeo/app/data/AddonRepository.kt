package com.tormeo.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class InstalledAddon(val url: String, val manifest: Manifest) {
    val base: String get() = url.removeSuffix("/manifest.json").trimEnd('/')
}

data class AddonStreams(val addon: InstalledAddon, val streams: List<Stream>, val error: String? = null)

/**
 * Talks the Stremio addon HTTP protocol:
 *   {base}/manifest.json
 *   {base}/catalog/{type}/{id}[/{extra}].json
 *   {base}/meta/{type}/{id}.json
 *   {base}/stream/{type}/{id}.json
 */
class AddonRepository(context: Context, private val http: OkHttpClient) {

    private val prefs = context.getSharedPreferences("addons", Context.MODE_PRIVATE)
    private val _addons = MutableStateFlow(load())
    val addons: StateFlow<List<InstalledAddon>> = _addons

    private val metaCache = ConcurrentHashMap<String, Meta>()
    private val catalogCache = ConcurrentHashMap<String, List<MetaPreview>>()

    private fun load(): List<InstalledAddon> {
        val raw = prefs.getString("list", null) ?: return emptyList()
        return runCatching { StremioJson.decodeFromString(ListSerializer(InstalledAddon.serializer()), raw) }
            .getOrDefault(emptyList())
    }

    private fun save(list: List<InstalledAddon>) {
        _addons.value = list
        prefs.edit().putString("list", StremioJson.encodeToString(ListSerializer(InstalledAddon.serializer()), list)).apply()
    }

    /** Installs the default addons on first launch and refreshes manifests. */
    suspend fun bootstrap() {
        if (!prefs.getBoolean("bootstrapped", false)) {
            for (url in DEFAULT_ADDONS) runCatching { install(url) }
            if (_addons.value.isNotEmpty()) prefs.edit().putBoolean("bootstrapped", true).apply()
        } else {
            refreshManifests()
        }
    }

    private suspend fun refreshManifests() = coroutineScope {
        val fresh = _addons.value.map { a ->
            async { runCatching { a.copy(manifest = fetchManifest(a.url)) }.getOrDefault(a) }
        }.awaitAll()
        save(fresh)
    }

    suspend fun install(rawUrl: String): InstalledAddon {
        val url = normalizeUrl(rawUrl)
        val manifest = fetchManifest(url)
        val addon = InstalledAddon(url, manifest)
        val list = _addons.value.filterNot { it.url == url || it.manifest.id == manifest.id } + addon
        save(list)
        catalogCache.clear()
        return addon
    }

    fun remove(addon: InstalledAddon) {
        save(_addons.value.filterNot { it.url == addon.url })
        catalogCache.clear()
    }

    fun move(addon: InstalledAddon, delta: Int) {
        val list = _addons.value.toMutableList()
        val i = list.indexOfFirst { it.url == addon.url }
        val j = (i + delta).coerceIn(0, list.lastIndex)
        if (i < 0 || i == j) return
        list.add(j, list.removeAt(i))
        save(list)
    }

    private suspend fun fetchManifest(url: String): Manifest =
        StremioJson.decodeFromString(Manifest.serializer(), get(url))

    // ---------------------------------------------------------------- catalogs

    data class CatalogRef(val addon: InstalledAddon, val catalog: CatalogDef) {
        val key: String get() = "${addon.manifest.id}/${catalog.type}/${catalog.id}"
        val title: String
            get() {
                val t = when (catalog.type) { "movie" -> "Filmovi"; "series" -> "Serije"; else -> catalog.type }
                return "${catalog.name ?: catalog.id} · $t"
            }
    }

    fun homeCatalogs(): List<CatalogRef> =
        _addons.value.flatMap { a ->
            a.manifest.catalogs.filter { it.isBrowsable }
                .map { CatalogRef(a, it) }
        }

    fun searchCatalogs(): List<CatalogRef> =
        _addons.value.flatMap { a -> a.manifest.catalogs.filter { it.supportsSearch }.map { CatalogRef(a, it) } }

    fun cachedCatalog(ref: CatalogRef): List<MetaPreview>? = catalogCache[ref.key]

    suspend fun catalog(ref: CatalogRef, extra: Map<String, String> = emptyMap()): List<MetaPreview> {
        val key = if (extra.isEmpty()) ref.key else ref.key + extra.toString()
        catalogCache[key]?.let { return it }
        val extraPath = if (extra.isEmpty()) "" else "/" + extra.entries.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        val url = "${ref.addon.base}/catalog/${ref.catalog.type}/${enc(ref.catalog.id)}$extraPath.json"
        val res = StremioJson.decodeFromString(CatalogResponse.serializer(), get(url)).metas
        catalogCache[key] = res
        return res
    }

    /** Searches all searchable catalogs in parallel; returns movies first, then series. */
    suspend fun search(query: String): List<MetaPreview> = coroutineScope {
        searchCatalogs().map { ref -> async { runCatching { catalog(ref, mapOf("search" to query)) }.getOrDefault(emptyList()) } }
            .awaitAll().flatten().distinctBy { it.type + it.id }
    }

    // ---------------------------------------------------------------- meta

    suspend fun meta(type: String, id: String): Meta {
        metaCache["$type/$id"]?.let { return it }
        var lastError: Exception? = null
        for (a in _addons.value.filter { it.manifest.supports("meta", type, id) }) {
            try {
                val m = StremioJson.decodeFromString(MetaResponse.serializer(), get("${a.base}/meta/$type/${enc(id)}.json")).meta
                if (m != null) {
                    metaCache["$type/$id"] = m
                    return m
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("Nijedan addon ne daje podatke za $id")
    }

    // ---------------------------------------------------------------- streams

    /** Queries every stream addon in parallel and emits each result as soon as it arrives. */
    fun streams(type: String, videoId: String): Flow<AddonStreams> = channelFlow {
        val targets = _addons.value.filter { it.manifest.supports("stream", type, videoId) }
        for (a in targets) launch {
            val r = withTimeoutOrNull(25_000) {
                try {
                    val body = get("${a.base}/stream/$type/${enc(videoId)}.json")
                    AddonStreams(a, StremioJson.decodeFromString(StreamResponse.serializer(), body).streams)
                } catch (e: Exception) {
                    AddonStreams(a, emptyList(), e.message ?: e.javaClass.simpleName)
                }
            } ?: AddonStreams(a, emptyList(), "timeout")
            send(r)
        }
        awaitClose { }
    }

    // ---------------------------------------------------------------- http

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}: $url")
            r.body?.string() ?: throw IOException("Prazan odgovor")
        }
    }

    companion object {
        val DEFAULT_ADDONS = listOf(
            "https://v3-cinemeta.strem.io/manifest.json",
            "https://torrentio.strem.fun/manifest.json",
        )

        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        fun normalizeUrl(raw: String): String {
            var u = raw.trim()
            if (u.startsWith("stremio://")) u = "https://" + u.removePrefix("stremio://")
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
            u = u.trimEnd('/')
            if (u.endsWith("/configure")) u = u.removeSuffix("/configure")
            if (!u.endsWith("/manifest.json")) u += "/manifest.json"
            return u
        }
    }
}
