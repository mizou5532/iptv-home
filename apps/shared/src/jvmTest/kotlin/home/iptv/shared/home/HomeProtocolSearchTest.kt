package home.iptv.shared.home

import home.iptv.shared.search.PersonCredit
import home.iptv.shared.search.PersonCredits
import home.iptv.shared.search.PersonInfo
import home.iptv.shared.search.SearchResult
import home.iptv.shared.versions.Lang
import home.iptv.shared.versions.LanguagePrefs
import home.iptv.shared.versions.Version
import home.iptv.shared.versions.VersionTag
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeProtocolSearchTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun searchRequestAndReplyRoundTrip() {
        val req = SearchRequest(
            section = "movies",
            query = "dune",
            hiddenCategories = listOf("99"),
            hiddenChannels = listOf("c1"),
            groupCopies = false,
            languages = LanguagePrefs(order = listOf("qc", "fr"), subtitles = "off", prefer4k = true, onlyMine = true),
            limit = 40,
            personCredits = PersonCredits(
                person = PersonInfo("Denis Villeneuve", "Directing"),
                credits = listOf(PersonCredit("movie", "Dune", "Dune", 2021, "realisateur")),
            ),
        )
        assertEquals(req, json.decodeFromString(SearchRequest.serializer(), json.encodeToString(SearchRequest.serializer(), req)))

        val v = Version("101", "10", "QC - Dune (2021)", VersionTag(Lang.QC, uhd = true), "http://img/1.jpg", "2021")
        val res = SearchResult("v:dune|2021", "Dune", "http://img/1.jpg", "10", null, listOf(v), "Réalisé par Denis Villeneuve")
        val reply = SearchReply("rev-s1", "acct1", listOf(res.toSnapSearchResult()))
        val decoded = json.decodeFromString(SearchReply.serializer(), json.encodeToString(SearchReply.serializer(), reply))
        assertEquals(reply, decoded)
        assertEquals(res, decoded.results.single().toSearchResult())
    }
}
