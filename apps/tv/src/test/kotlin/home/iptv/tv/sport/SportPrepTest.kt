package home.iptv.tv.sport

import home.iptv.shared.sport.*
import home.iptv.shared.sync.Profile
import kotlinx.coroutines.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** G : l'onglet Sport est préparé pendant « Qui regarde ? » pour les profils qui s'ouvrent dessus. */
class SportPrepTest {
    private var now = Instant.parse("2026-11-17T00:45:00Z")
    private val montreal = -5 * 3600

    private fun store(saved: MutableMap<String, String>, at: java.util.concurrent.atomic.AtomicLong) = object : SportStore {
        override fun cached(key: String) = saved[key]
        override fun saveCached(key: String, text: String, at0: Long) { saved[key] = text; at.set(at0) }
        override fun cachedAt() = at.get()
        override fun learned(profileId: String, league: League) = emptyList<String>()
        override fun saveLearned(profileId: String, league: League, groups: List<String>) {}
    }

    private fun profile(id: String, home: String) = Profile(id, id, "#8FB4FF", home, "sombre", 0)

    @Test fun prepare_horaires_et_chaines_pour_un_profil_sport_puis_l_ecran_n_attend_pas_le_reseau() = runBlocking {
        val saved = java.util.concurrent.ConcurrentHashMap<String, String>()
        val at = java.util.concurrent.atomic.AtomicLong(0)
        val asked = java.util.Collections.synchronizedList(mutableListOf<String>())
        var extras = 0
        val sources = object : SportSources { override suspend fun extraChannels(): List<SportChannel> { extras++; return emptyList() } }
        val prep = SportPrep(store(saved, at), { url -> asked += url; if ("nhle" in url) """{"gameWeek":[]}""" else """{"events":[]}""" }, sources, { now }, { montreal })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            fun noSport(p: Profile) = p.copy(settings = p.settings.copy(sections = listOf("direct", "series")))
            assertNull(prep.prewarm(listOf(noSport(profile("a", "direct")), noSport(profile("b", "series"))), scope), "aucun profil avec la section Sport : rien")
            prep.prewarm(listOf(profile("a", "direct"), profile("m", "sport")), scope)!!.join()
            assertTrue(asked.any { "nhle" in it } && asked.any { "espn" in it }, "horaires lus : $asked")
            assertEquals(1, extras, "chaînes d'événements lues une fois")
            assertTrue(at.get() > 0, "horaires gardés : l'écran les trouvera récents")
            // Retour sur « Qui regarde ? » peu après : pas de nouvelle préparation.
            assertNull(prep.prewarm(listOf(profile("m", "sport")), scope))
            assertEquals(1, prep.runs)
        } finally { scope.cancel() }
    }
}
