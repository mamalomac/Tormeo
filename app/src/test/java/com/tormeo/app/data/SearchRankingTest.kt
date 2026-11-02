package com.tormeo.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRankingTest {
    private fun load(name: String) = StremioJson.decodeFromString(
        CatalogResponse.serializer(),
        javaClass.classLoader!!.getResource(name)!!.readText(),
    ).metas

    @Test fun gameOfThronesSeriesComesFirst() {
        // real Cinemeta responses: 24 movies (documentaries, specials…) and 5 series
        val movies = load("cinemeta_search_got_movie.json")
        val series = load("cinemeta_search_got_series.json")
        assertTrue(movies.size > 10)
        val r = SearchRanking.rank("game of thrones", listOf(movies, series))
        assertEquals("tt0944947", r.first().id)
        assertEquals("series", r.first().type)
        assertEquals(r.size, r.distinctBy { it.type + it.id }.size)
    }

    @Test fun rowsAreSeriesThenMovies() {
        val rows = SearchRanking.byType("game of thrones", listOf(load("cinemeta_search_got_movie.json"), load("cinemeta_search_got_series.json")))
        assertEquals(listOf("series", "movie"), rows.map { it.first })
        assertEquals("tt0944947", rows[0].second.first().id)
        // in the movie row, titles containing the query come before unrelated ones
        assertTrue(rows[1].second.first().name.lowercase().contains("game of thrones"))
    }

    @Test fun normalizesCaseDiacriticsAndPunctuation() {
        assertEquals("ziveti brzo", SearchRanking.normalize("Živeti, brzo!"))
        val list = listOf(
            MetaPreview("1", "movie", "Lord of War"),
            MetaPreview("2", "movie", "The Lord of the Rings: The Fellowship of the Ring"),
            MetaPreview("3", "series", "Lord of the Rings: The Rings of Power"),
        )
        val r = SearchRanking.rank("lord of the rings", listOf(list))
        assertEquals("3", r.first().id) // starts with the query
        assertEquals("1", r.last().id)  // doesn't contain all words
    }
}
