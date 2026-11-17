package home.iptv.tv.sport

import home.iptv.shared.sport.*
import home.iptv.shared.sync.SportVote
import home.iptv.shared.xtream.EpgEntry
import home.iptv.shared.xtream.LiveCategory
import home.iptv.shared.xtream.LiveStream
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class SportSourcesTest {
    private val now = Instant.parse("2026-11-17T00:45:00Z")
    private val montreal = -5 * 3600
    private fun dir() = kotlin.io.path.createTempDirectory("sport").toFile()

    @Test fun categories_par_defaut_gardees_une_heure_puis_relues() = runBlocking {
        var t = 1_000L; var calls = 0
        val d = dir()
        fun src(chosen: List<String>?) = AndroidSportSources(d,
            categories = { listOf(LiveCategory("1", "US| ESPN+ PPV"), LiveCategory("2", "CA| GENERAL FR"), LiveCategory("3", "US| NHL TEAM PPV")) },
            streams = { id -> calls++; listOf(LiveStream("s$id", "Chaîne\t$id", id, null, null, null)) },
            epg = { emptyList() }, chosen = { chosen }, readVotes = { emptyList() }, writeVote = { _, _, _ -> }, now = { t })
        val a = src(null).extraChannels()
        assertEquals(listOf("x:s1", "x:s3"), a.map { it.groupId })
        assertTrue(a.all { it.direct && it.anchorSourceId.startsWith("s") })
        assertEquals("Chaîne 1", a.first().title)
        // Nouvelle télé (nouvel objet) : lu dans le fichier, sans réseau.
        assertEquals(a, src(null).extraChannels()); assertEquals(2, calls)
        // Choix de l'iPhone différent : relu tout de suite.
        assertEquals(listOf("x:s2"), src(listOf("2")).extraChannels().map { it.groupId }); assertEquals(3, calls)
        t += EXTRA_MAX_AGE_MS + 1
        src(listOf("2")).extraChannels(); assertEquals(4, calls)
    }

    @Test fun resultat_vide_jamais_garde() = runBlocking {
        var calls = 0; var empty = true
        val d = dir()
        val src = AndroidSportSources(d, categories = { calls++; if (empty) emptyList() else listOf(LiveCategory("2", "US| ESPN+ PPV")) },
            streams = { listOf(LiveStream("s9", "Hockey: Canucks vs. Mammoth", "2", null, null, null)) },
            epg = { emptyList() }, chosen = { null }, readVotes = { emptyList() }, writeVote = { _, _, _ -> }, now = { 1L })
        assertTrue(src.extraChannels().isEmpty())
        empty = false
        assertEquals(listOf("x:s9"), src.extraChannels().map { it.groupId }); assertEquals(2, calls)
    }

    @Test fun evenement_propose_puis_ecarte_par_pas_le_bon_match() = runBlocking {
        val nhl = """{"gameWeek":[{"games":[{"id":1,"startTimeUTC":"2026-11-17T00:30:00Z","gameState":"LIVE",
            "awayTeam":{"abbrev":"MTL","placeName":{"default":"Montréal"},"commonName":{"default":"Canadiens"}},"homeTeam":{"abbrev":"BOS","placeName":{"default":"Boston"},"commonName":{"default":"Bruins"}}}]}]}"""
        val store = object : SportStore {
            override fun cached(key: String) = if ("nhle" in key) nhl else """{"events":[]}"""
            override fun saveCached(key: String, text: String, at: Long) {}
            override fun cachedAt() = now.toEpochMilliseconds()
            override fun learned(profileId: String, league: League) = emptyList<String>()
            override fun saveLearned(profileId: String, league: League, groups: List<String>) {}
        }
        val sent = mutableListOf<Triple<String, String, Int>>()
        val sources = object : SportSources {
            override suspend fun extraChannels() = listOf(SportChannel("x:9", "US (ESPN+ 9) | Hockey: Canadiens vs. Bruins (2026-11-16 19:30)", "US| ESPN+ PPV", "9", direct = true))
            override suspend fun votes() = emptyList<SportVote>()
            override suspend fun vote(teamKey: String, channelKey: String, delta: Int) { sent += Triple(teamKey, channelKey, delta) }
            override suspend fun guide(streamId: String) = emptyList<EpgEntry>()
        }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val vm = SportViewModel("p1", SportSchedule({ error("hors ligne") }, store) { now },
                { emptyList() }, { emptyList() }, store, kotlinx.coroutines.flow.MutableStateFlow(null), scope,
                kotlinx.coroutines.Dispatchers.Default, { now }, { montreal }, sources = sources)
            vm.start()
            val ui = kotlinx.coroutines.withTimeout(5_000) { vm.ui.first { u -> u.candidates.values.any { c -> c.likely.isNotEmpty() } } }
            val m = ui.strip.first()
            assertEquals(listOf("x:9"), ui.candidates[m.id]!!.likely.map { it.groupId })
            assertEquals(ChannelReason.EVENT, ui.candidates[m.id]!!.reasons["x:9"])
            vm.wrongChannel(m, ui.candidates[m.id]!!.likely.first())
            val after = kotlinx.coroutines.withTimeout(5_000) { vm.ui.first { u -> u.candidates[m.id]?.likely?.isEmpty() == true } }
            assertTrue(after.candidates[m.id]!!.all.none { it.groupId == "x:9" })
            assertEquals(setOf("nhl:MTL", "nhl:BOS"), sent.map { it.first }.toSet())
            assertTrue(sent.all { it.third == -3 })
        } finally { scope.cancel() }
    }

    @Test fun categorie_sans_reponse_garde_ses_chaines_et_liste_vide_jamais_gardee() = runBlocking {
        var t = 1_000L
        val d = dir()
        val down = mutableSetOf<String>()
        fun src() = AndroidSportSources(d,
            categories = { listOf(LiveCategory("1", "US| ESPN+ PPV"), LiveCategory("3", "US| NHL TEAM PPV")) },
            streams = { id -> if (id in down) throw java.io.IOException("panne") else listOf(LiveStream("s$id", "Chaine $id", id, null, null, null)) },
            epg = { emptyList() }, chosen = { null }, readVotes = { emptyList() }, writeVote = { _, _, _ -> }, now = { t })
        assertEquals(listOf("x:s1", "x:s3"), src().extraChannels().map { it.groupId })
        // Une heure plus tard, la categorie 3 ne repond pas : ses chaines de la derniere fois restent.
        t += EXTRA_MAX_AGE_MS + 1; down += "3"
        assertEquals(listOf("x:s1", "x:s3"), src().extraChannels().map { it.groupId })
        // Relue au bout de 10 min (pas 1 h) puisque une categorie manquait.
        t += EXTRA_RETRY_MS + 1; down.clear(); down += "1"; down += "3"
        // Plus rien ne repond : la liste gardee n'est pas remplacee par du vide.
        assertEquals(listOf("x:s1", "x:s3"), src().extraChannels().map { it.groupId })
        assertEquals(listOf("x:s1", "x:s3"), src().savedChannels().map { it.groupId })
    }
}
