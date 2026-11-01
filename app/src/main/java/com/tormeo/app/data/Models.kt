package com.tormeo.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Stremio addon protocol models (only the fields we use). */

val StremioJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

@Serializable
data class Manifest(
    val id: String,
    val name: String = "",
    val version: String = "",
    val description: String? = null,
    val logo: String? = null,
    val resources: List<JsonElement> = emptyList(),
    val types: List<String> = emptyList(),
    val idPrefixes: List<String>? = null,
    val catalogs: List<CatalogDef> = emptyList(),
) {
    /** Does this addon serve [resource] (catalog/meta/stream/subtitles) for [type] and [id]? */
    fun supports(resource: String, type: String, id: String?): Boolean {
        for (r in resources) {
            when (r) {
                is JsonPrimitive -> if (r.contentOrNull == resource) {
                    return type in types && prefixOk(idPrefixes, id)
                }
                is JsonObject -> if ((r["name"] as? JsonPrimitive)?.contentOrNull == resource) {
                    val rTypes = (r["types"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: types
                    val rPrefixes = (r["idPrefixes"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        ?: idPrefixes
                    return type in rTypes && prefixOk(rPrefixes, id)
                }
                else -> Unit
            }
        }
        return false
    }

    private fun prefixOk(prefixes: List<String>?, id: String?): Boolean =
        id == null || prefixes.isNullOrEmpty() || prefixes.any { id.startsWith(it) }
}

@Serializable
data class CatalogDef(
    val type: String,
    val id: String,
    val name: String? = null,
    val extra: List<ExtraDef> = emptyList(),
    val extraSupported: List<String>? = null,
    val extraRequired: List<String>? = null,
) {
    val supportsSearch: Boolean
        get() = extra.any { it.name == "search" } || extraSupported?.contains("search") == true

    /** Catalog can be shown on the home screen without user input. */
    val isBrowsable: Boolean
        get() = extra.none { it.isRequired } && extraRequired.isNullOrEmpty()
}

@Serializable
data class ExtraDef(val name: String, val isRequired: Boolean = false, val options: List<String>? = null)

@Serializable
data class MetaPreview(
    val id: String,
    val type: String,
    val name: String = "",
    val poster: String? = null,
    val posterShape: String? = null,
    val background: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: String? = null,
)

@Serializable
data class Meta(
    val id: String,
    val type: String,
    val name: String = "",
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val runtime: String? = null,
    val genres: List<String> = emptyList(),
    val imdbRating: String? = null,
    val videos: List<Video> = emptyList(),
) {
    /** Runtime in minutes if parseable ("142 min", "1h 30min"). */
    val runtimeMinutes: Int?
        get() {
            val r = runtime ?: return null
            val h = Regex("(\\d+)\\s*h").find(r)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val m = Regex("(\\d+)\\s*m").find(r)?.groupValues?.get(1)?.toIntOrNull()
                ?: if (h == 0) Regex("(\\d+)").find(r)?.groupValues?.get(1)?.toIntOrNull() ?: 0 else 0
            return (h * 60 + m).takeIf { it > 0 }
        }
}

@Serializable
data class Video(
    val id: String,
    val title: String? = null,
    val name: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val number: Int? = null,
    val released: String? = null,
    val thumbnail: String? = null,
    val overview: String? = null,
    val description: String? = null,
) {
    val ep: Int? get() = episode ?: number
    val label: String get() = title ?: name ?: "Epizoda ${ep ?: ""}"
}

@Serializable
data class Stream(
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val url: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val externalUrl: String? = null,
    val sources: List<String>? = null,
    val behaviorHints: StreamHints? = null,
)

@Serializable
data class StreamHints(
    val bingeGroup: String? = null,
    val filename: String? = null,
    val videoSize: Long? = null,
    val notWebReady: Boolean? = null,
    val proxyHeaders: ProxyHeaders? = null,
)

@Serializable
data class ProxyHeaders(val request: Map<String, String>? = null)

@Serializable data class CatalogResponse(val metas: List<MetaPreview> = emptyList())
@Serializable data class MetaResponse(val meta: Meta? = null)
@Serializable data class StreamResponse(val streams: List<Stream> = emptyList())

/** Everything the player needs to start a stream. Also persisted for "continue watching". */
@Serializable
data class PlayRequest(
    val title: String,
    val subtitle: String? = null,
    val metaId: String,
    val type: String,
    val videoId: String,
    val poster: String? = null,
    val url: String? = null,
    val headers: Map<String, String>? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val sources: List<String>? = null,
    val filename: String? = null,
    val sizeBytes: Long? = null,
    val bitrateBps: Long? = null,
)
