package com.tormeo.app.data

import java.text.Normalizer

/** Orders merged search results from several catalogs (e.g. Cinemeta movies + series) by relevance. */
object SearchRanking {

    fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")      // strip diacritics (č → c)
            .replace("đ", "dj")
            .replace("&", " and ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    /**
     * Exact title first, then titles starting with the query, then titles containing all query words;
     * within the same relevance, the catalogs' own popularity order is interleaved (movie #1, series #1, movie #2…).
     */
    fun rank(query: String, perCatalog: List<List<MetaPreview>>): List<MetaPreview> {
        val q = normalize(query)
        val words = q.split(' ').filter { it.isNotEmpty() }
        data class Scored(val m: MetaPreview, val score: Int, val pos: Int)
        val scored = perCatalog.flatMap { list ->
            list.mapIndexed { i, m ->
                val n = normalize(m.name)
                val relevance = when {
                    n == q -> 4
                    n.startsWith("$q ") || n.startsWith(q) -> 3
                    words.isNotEmpty() && words.all { w -> n.split(' ').any { it.startsWith(w) } } -> 2
                    else -> 0
                }
                Scored(m, relevance, i)
            }
        }
        return scored.sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.pos })
            .map { it.m }
            .distinctBy { it.type + it.id }
    }

    /** Results split into rows by type: series, movies, then other types; each row ranked with [rank]. */
    fun byType(query: String, perCatalog: List<List<MetaPreview>>): List<Pair<String, List<MetaPreview>>> {
        val ranked = rank(query, perCatalog)
        val order = listOf("series", "movie")
        return ranked.groupBy { it.type }.toList()
            .sortedBy { (type, _) -> order.indexOf(type).let { if (it < 0) order.size else it } }
    }
}
