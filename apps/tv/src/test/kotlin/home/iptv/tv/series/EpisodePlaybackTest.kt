package home.iptv.tv.series

import home.iptv.shared.progress.ProgressClient
import home.iptv.shared.progress.ProgressEntry
import home.iptv.shared.progress.ProgressQueue
import home.iptv.shared.progress.ProgressRow
import home.iptv.shared.progress.ProgressStore
import home.iptv.shared.progress.QueuedProgress
import home.iptv.shared.progress.WatchedItem
import home.iptv.shared.xtream.Episode
import home.iptv.shared.xtream.Season
import home.iptv.shared.xtream.SeriesInfo
import home.iptv.shared.xtream.SeriesSummary
import home.iptv.tv.data.SUBTITLES_OFF
import home.iptv.tv.data.TrackPrefsStore
import home.iptv.tv.player.PlaybackSnapshot
import home.iptv.tv.player.Player
import home.iptv.tv.player.PlayerController
import home.iptv.tv.player.PlayerTracks
import home.iptv.tv.player.TrackOption
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private fun ep(season: Int, n: Int, duration: Int? = 2400) = Episode("s${season}e$n", season, n, "Titre $n", duration, "mkv", null)

private fun infoOf(seasons: List<Season>, cat: String = "7") = SeriesInfo(
    SeriesSummary("42", cat, "Les Rivages", "https://img.example.test/c.jpg?token=secret", "2021", "Drame", "Resume", null), seasons,
)

private fun twoSeasons() = listOf(
    Season(1, "Saison 1", (1..3).map { ep(1, it) }),
    Season(2, "Saison 2", (1..2).map { ep(2, it) }),
)

private class Log { val lines = mutableListOf<String>() }

private class FakePlayer(val name: String, val log: Log, val tracksNow: PlayerTracks) : Player {
    val selected = mutableListOf<String>()
    override fun play(url: String) { log.lines += "play $name $url" }
    override fun play(url: String, startMs: Long) { log.lines += "play $name $url @$startMs" }
    override fun stop() { log.lines += "stop $name" }
    override fun release() { log.lines += "release $name" }
    override fun snapshot() = PlaybackSnapshot()
    override fun tracks() = tracksNow
    override fun selectAudio(id: String) { selected += "audio:$id" }
    override fun selectSubtitle(id: String?) { selected += "sub:$id" }
}

private class MemoryProgressStore : ProgressStore {
    var saved: List<QueuedProgress> = emptyList()
    @Synchronized override fun loadProgressQueue() = saved
    @Synchronized override fun saveProgressQueue(queue: List<QueuedProgress>) { saved = queue }
    @Synchronized override fun update(transform: (List<QueuedProgress>) -> List<QueuedProgress>) { saved = transform(saved) }
}

private class RecordingClient : ProgressClient {
    val sent = mutableListOf<Pair<String, ProgressEntry>>()
    var running = 0
    var maxRunning = 0
    var recordDelayMs = 0L
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    var failNext = 0
    var calls = 0
    override suspend fun record(p: String, entry: ProgressEntry): Boolean {
        calls++
        gate?.await()
        if (failNext > 0) { failNext--; throw java.io.IOException("hors ligne") }
        running++; maxRunning = maxOf(maxRunning, running)
        if (recordDelayMs > 0) delay(recordDelayMs)
        sent += p to entry
        running--
        return true
    }
    override suspend fun continueWatching(p: String, max: Int): List<ProgressRow> = emptyList()
    override suspend fun setWatched(p: String, items: List<WatchedItem>, watched: Boolean) {}
    override suspend fun setSeriesList(p: String, seriesId: String, on: Boolean) {}
    override suspend fun seriesProgress(p: String, seriesId: String): List<ProgressRow> = emptyList()
}

private class FakeTrackPrefs : TrackPrefsStore {
    val audio = mutableMapOf<String, String?>()
    val subs = mutableMapOf<String, String?>()
    override fun audioLang(profileId: String) = audio[profileId]
    override fun subtitleLang(profileId: String) = subs[profileId]
    override fun saveTrackPrefs(profileId: String, audioLang: String?, subtitleLang: String?) {
        audio[profileId] = audioLang; subs[profileId] = subtitleLang
    }
}

private val FR = TrackOption("a:0:0", "fre", "Français 5.1", false)
private val EN = TrackOption("a:0:1", "eng", "English", true)
private val SUB_FR = TrackOption("t:1:0", "fra", "Français", false)
private val SUB_EN = TrackOption("t:1:1", "en", "English", false)
private val TRACKS = PlayerTracks(listOf(FR, EN), listOf(SUB_FR, SUB_EN))

@OptIn(ExperimentalCoroutinesApi::class)
private class Fixture(
    val info: SeriesInfo = infoOf(twoSeasons()),
    var hidden: Set<String>? = emptySet(),
    profile: String = "pA",
    val cached: Map<String, String> = emptyMap(),
    coverFor: (String?) -> String? = { it },
) {
    var urlOk = true
    val test = TestScope()
    val dispatcher = StandardTestDispatcher(test.testScheduler)
    var now = Instant.parse("2026-11-15T10:00:00Z")
    val log = Log()
    val client = RecordingClient()
    val progressStore = MemoryProgressStore()
    val prefs = FakeTrackPrefs()
    val infoCalls = mutableListOf<Pair<String, String?>>()
    private var created = 0
    val controller = PlayerController { FakePlayer("P${++created}", log, TRACKS).also { log.lines += "create ${it.name}" } }
    val queue = ProgressQueue(client, progressStore) { now }
    val flusher = ProgressFlusher(queue, test, dispatcher, debounceMs = 1_000)
    val playback = EpisodePlayback(
        profileId = profile, controller = controller, queue = queue, flusher = flusher, store = prefs, clock = { now },
        scope = test, urlFor = { if (urlOk) "http://flux.invalide/${it.id}.${it.extension}" else null },
        loadInfo = { id, cat -> infoCalls += id to cat; info },
        hiddenCategories = { hidden }, io = dispatcher, cachedCategory = { cached[it] },
        coverFor = coverFor,
    )
    fun queued() = progressStore.saved
    fun advance(s: Int) { now += s.seconds }
    fun active() = controller.active.value as? FakePlayer
}

class EpisodePlaybackTest {
    @Test
    fun reprise_a_la_position_enregistree() {
        val f = Fixture()
        f.playback.start(f.info, ep(1, 2), 600)
        assertEquals(listOf("create P1", "play P1 http://flux.invalide/s1e2.mkv @600000"), f.log.lines)
        assertTrue(f.playback.state.value is EpisodePlaybackState.Playing)
    }

    @Test
    fun reprise_repart_de_zero_a_90_pour_cent_ou_a_moins_de_30_secondes_de_la_fin() {
        assertEquals(0, resumePosition(null, 2400))
        assertEquals(0, resumePosition(0, 2400))
        assertEquals(2000, resumePosition(2000, 2400))
        assertEquals(0, resumePosition(2160, 2400)) // 90 % pile
        assertEquals(0, resumePosition(2200, 2400))
        assertEquals(0, resumePosition(2370, 2400)) // a 30 s de la fin
        assertEquals(2000, resumePosition(2000, null)) // duree inconnue : on garde la position
        val f = Fixture()
        f.playback.start(f.info, ep(1, 2, duration = 2400), 2300)
        assertTrue(f.log.lines.last().endsWith("s1e2.mkv"), "doit demarrer sans position : ${f.log.lines.last()}")
    }

    @Test
    fun progression_mise_en_file_toutes_les_30_secondes_avec_les_champs_d_enrichissement() {
        val f = Fixture()
        f.playback.start(f.info, ep(1, 2), null, categoryId = "7")
        f.advance(10); f.playback.onTick(10, 2400)
        assertTrue(f.queued().isEmpty(), "pas avant 30 s")
        f.advance(20); f.playback.onTick(30, 2400)
        val e = f.queued().single()
        assertEquals("pA", e.profileId)
        assertEquals(30, e.entry.positionS)
        assertEquals("s1e2", e.entry.itemId)
        assertEquals("42", e.entry.seriesId)
        assertEquals(1, e.entry.season); assertEquals(2, e.entry.episode)
        assertEquals("Les Rivages", e.entry.seriesName)
        assertEquals("https://img.example.test/c.jpg", e.entry.seriesCover, "affiche nettoyee (pas de parametres)")
        assertEquals("7", e.entry.seriesCategory)
        f.advance(15); f.playback.onTick(45, 2400)
        assertEquals(30, f.queued().single().entry.positionS, "pas de nouvel enregistrement avant 30 s")
        f.advance(15); f.playback.onTick(60, 2400)
        assertEquals(60, f.queued().single().entry.positionS)
    }

    @Test
    fun pause_enregistre_et_arret_enregistre() {
        val f = Fixture()
        f.playback.start(f.info, ep(1, 2), null)
        f.advance(5); f.playback.onTick(5, 2400, playing = true)
        f.advance(5); f.playback.onTick(10, 2400, playing = false)
        assertEquals(10, f.queued().single().entry.positionS, "pause")
        f.advance(5); f.playback.onTick(14, 2400, playing = true)
        f.playback.stop()
        assertEquals(14, f.queued().single().entry.positionS, "arret")
        assertNull(f.controller.active.value)
        assertEquals(EpisodePlaybackState.Idle, f.playback.state.value)
    }

    @Test
    fun une_reprise_quittee_sans_lecture_n_ecrase_rien() {
        val f = Fixture()
        f.playback.start(f.info, ep(1, 2), 500)
        f.playback.stop()
        assertTrue(f.queued().isEmpty())
    }

    @Test
    fun fin_d_episode_decompte_de_10_secondes_puis_episode_suivant_fermeture_avant_ouverture() {
        val f = Fixture()
        f.playback.start(f.info, ep(1, 2), null)
        f.playback.onTick(2390, 2400)
        f.playback.onEnded()
        assertEquals(EpisodePlaybackState.NextCountdown(10, ep(1, 3)), f.playback.state.value)
        assertEquals(2400, f.queued().single().entry.positionS, "fin : position = duree, donc vu")
        f.test.testScheduler.advanceTimeBy(3_100); f.test.testScheduler.runCurrent()
        assertEquals(EpisodePlaybackState.NextCountdown(7, ep(1, 3)), f.playback.state.value)
        f.test.testScheduler.advanceTimeBy(7_100); f.test.testScheduler.runCurrent()
        assertEquals(
            listOf("create P1", "play P1 http://flux.invalide/s1e2.mkv", "stop P1", "release P1", "create P2", "play P2 http://flux.invalide/s1e3.mkv"),
            f.log.lines,
        )
        assertEquals(ep(1, 3), (f.playback.state.value as EpisodePlaybackState.Playing).episode)
    }

    @Test
    fun annuler_n_enchaine_pas() {
        val f = Fixture()
        f.playback.start(f.info, ep(1, 2), null)
        f.playback.onEnded()
        f.test.testScheduler.advanceTimeBy(3_000); f.test.testScheduler.runCurrent()
        f.playback.cancelNext()
        f.test.testScheduler.advanceTimeBy(30_000); f.test.testScheduler.runCurrent()
        assertEquals(EpisodePlaybackState.Finished(seriesEnded = false), f.playback.state.value)
        assertEquals(1, f.log.lines.count { it.startsWith("play") })
    }

    @Test
    fun dernier_episode_de_la_serie_termine_sans_decompte() {
        val f = Fixture()
        f.playback.start(f.info, ep(2, 2), null)
        f.playback.onEnded()
        assertEquals(EpisodePlaybackState.Finished(seriesEnded = true), f.playback.state.value)
        assertNull(f.controller.active.value, "le lecteur est libere")
        assertEquals(2400, f.queued().single().entry.positionS)
    }

    @Test
    fun episode_suivant_passe_a_la_saison_suivante_et_saute_les_saisons_vides() {
        val seasons = listOf(
            Season(1, "S1", listOf(ep(1, 1), ep(1, 2))),
            Season(2, "S2", emptyList()),
            Season(3, "S3", listOf(ep(3, 1))),
        )
        val info = infoOf(seasons)
        assertEquals(ep(1, 2), nextEpisode(info, ep(1, 1)))
        assertEquals(ep(3, 1), nextEpisode(info, ep(1, 2)))
        assertNull(nextEpisode(info, ep(3, 1)))
        // Saisons dans le desordre, numeros d'episode avec trous, episode courant absent de la fiche.
        val messy = infoOf(listOf(Season(2, "S2", listOf(ep(2, 5))), Season(1, "S1", listOf(ep(1, 9), ep(1, 3)))))
        assertEquals(ep(1, 9), nextEpisode(messy, ep(1, 3)))
        assertEquals(ep(2, 5), nextEpisode(messy, ep(1, 9)))
        assertEquals(ep(2, 5), nextEpisode(messy, ep(1, 20)))
        assertNull(nextEpisode(infoOf(emptyList()), ep(1, 1)))
    }

    @Test
    fun arret_enregistre_pour_le_bon_profil_et_rien_ne_reprend() {
        val f = Fixture(profile = "pA")
        f.playback.start(f.info, ep(1, 2), null)
        f.advance(40); f.playback.onTick(40, 2400)
        f.playback.onEnded()
        f.test.testScheduler.advanceTimeBy(2_000); f.test.testScheduler.runCurrent()
        f.playback.stop() // changement de profil / onStop pendant le decompte
        f.test.testScheduler.advanceTimeBy(60_000); f.test.testScheduler.runCurrent()
        // Tout ce qui a ete enregistre (en file ou deja envoye) l'a ete pour le profil qui lisait.
        assertEquals(listOf("pA"), (f.queued().map { it.profileId } + f.client.sent.map { it.first }).distinct())
        assertEquals(1, f.log.lines.count { it.startsWith("play") }, "aucune lecture ne reprend")
        assertNull(f.controller.active.value)
        // Un tick tardif apres l'arret n'enregistre rien.
        f.advance(60); f.playback.onTick(100, 2400)
        f.test.testScheduler.advanceUntilIdle()
        assertEquals(listOf(2400), f.client.sent.map { it.second.positionS }, "le tick tardif apres l arret n enregistre rien")
    }

    @Test
    fun begin_charge_la_fiche_puis_lit_a_la_position_demandee() {
        val f = Fixture()
        f.playback.begin(PlayEpisodeRequest("42", "7", "s1e2", 1, 2, 700, false))
        assertEquals(EpisodePlaybackState.Loading, f.playback.state.value)
        f.test.testScheduler.advanceUntilIdle()
        assertEquals(listOf<Pair<String, String?>>("42" to "7"), f.infoCalls)
        assertEquals("play P1 http://flux.invalide/s1e2.mkv @700000", f.log.lines.last())
    }

    @Test
    fun categorie_masquee_refuse_sans_rien_demander_ni_ouvrir() {
        val f = Fixture(hidden = setOf("7"))
        f.playback.begin(PlayEpisodeRequest("42", "7", "s1e2", 1, 2, 0, false))
        f.test.testScheduler.advanceUntilIdle()
        val s = f.playback.state.value as EpisodePlaybackState.Failed
        assertFalse(s.canRetry)
        assertTrue(s.message.contains("masquée"))
        assertTrue(f.infoCalls.isEmpty())
        assertTrue(f.log.lines.none { it.startsWith("create") })
    }

    @Test
    fun masque_decouvert_dans_la_fiche_ou_au_moment_de_l_episode_suivant_refuse() {
        // Categorie inconnue de la demande, connue de la fiche.
        val f = Fixture(hidden = setOf("7"))
        f.playback.begin(PlayEpisodeRequest("42", null, "s1e2", 1, 2, 0, false))
        f.test.testScheduler.advanceUntilIdle()
        assertTrue(f.playback.state.value is EpisodePlaybackState.Failed)
        assertTrue(f.log.lines.none { it.startsWith("create") })
        // Masquee pendant le decompte : l'enchainement est refuse.
        val g = Fixture()
        g.playback.start(g.info, ep(1, 2), null)
        g.playback.onEnded()
        g.hidden = setOf("7")
        g.test.testScheduler.advanceTimeBy(11_000); g.test.testScheduler.runCurrent()
        assertTrue(g.playback.state.value is EpisodePlaybackState.Failed)
        assertEquals(1, g.log.lines.count { it.startsWith("play") })
    }

    @Test
    fun preferences_inconnues_ne_lisent_rien() {
        val f = Fixture(hidden = null)
        f.playback.begin(PlayEpisodeRequest("42", "7", "s1e2", 1, 2, 0, false))
        f.test.testScheduler.advanceUntilIdle()
        assertTrue(f.playback.state.value is EpisodePlaybackState.Failed)
        assertTrue(f.infoCalls.isEmpty())
    }

    @Test
    fun erreur_de_lecture_message_clair_sans_adresse_et_reessayer_reprend_a_la_position() {
        val f = Fixture()
        f.playback.start(f.info, ep(1, 2), null)
        f.advance(50); f.playback.onTick(120, 2400)
        f.playback.onFailed()
        val s = f.playback.state.value as EpisodePlaybackState.Failed
        assertTrue(s.canRetry)
        assertFalse(s.message.contains("http") || s.message.contains("flux.invalide"))
        assertEquals(120, f.queued().single().entry.positionS)
        assertNull(f.controller.active.value)
        f.playback.retry()
        assertEquals("play P2 http://flux.invalide/s1e2.mkv @120000", f.log.lines.last())
    }

    @Test
    fun fiche_introuvable_propose_de_reessayer() {
        val f = Fixture()
        val playback = EpisodePlayback(
            "pA", f.controller, f.queue, f.flusher, f.prefs, { f.now }, f.test, { "x" }, { _, _ -> null }, { emptySet() }, f.dispatcher,
        )
        playback.begin(PlayEpisodeRequest("42", "7", "s1e2", 1, 2, 0, false))
        f.test.testScheduler.advanceUntilIdle()
        val s = playback.state.value as EpisodePlaybackState.Failed
        assertTrue(s.canRetry)
    }

    @Test
    fun choix_de_langue_memorise_par_profil_et_reapplique_a_l_episode_suivant() {
        val f = Fixture()
        f.playback.start(f.info, ep(1, 2), null)
        f.playback.onTracks(TRACKS)
        f.playback.chooseAudio(FR)
        f.playback.chooseSubtitle(SUB_EN)
        assertEquals("fr", f.prefs.audio["pA"])
        assertEquals("en", f.prefs.subs["pA"])
        assertEquals(listOf("audio:a:0:0", "sub:t:1:1"), f.active()!!.selected)
        // Episode suivant : un nouveau lecteur, les pistes arrivent, la langue est reappliquee toute seule.
        f.playback.onEnded()
        f.test.testScheduler.advanceTimeBy(11_000); f.test.testScheduler.runCurrent()
        f.playback.onTracks(TRACKS)
        assertEquals(listOf("audio:a:0:0", "sub:t:1:1"), f.active()!!.selected)
        f.playback.onTracks(TRACKS) // une seule fois par episode
        assertEquals(2, f.active()!!.selected.size)
    }

    @Test
    fun aucun_sous_titre_est_memorise_et_les_autres_profils_ne_sont_pas_touches() {
        val f = Fixture()
        f.prefs.saveTrackPrefs("pB", "en", "en")
        f.playback.start(f.info, ep(1, 2), null)
        f.playback.onTracks(TRACKS)
        f.playback.chooseSubtitle(null)
        assertEquals(SUBTITLES_OFF, f.prefs.subs["pA"])
        assertEquals("en", f.prefs.subs["pB"])
        assertEquals("en", f.prefs.audio["pB"])
        // Reapplique : « Aucun » desactive les sous-titres.
        f.playback.stop()
        f.playback.start(f.info, ep(1, 3), null)
        f.playback.onTracks(TRACKS)
        assertEquals(listOf("sub:null"), f.active()!!.selected)
    }

    @Test
    fun langue_inconnue_dans_le_fichier_ne_change_rien() {
        val f = Fixture()
        f.prefs.saveTrackPrefs("pA", "de", null)
        f.playback.start(f.info, ep(1, 2), null)
        f.playback.onTracks(TRACKS)
        assertTrue(f.active()!!.selected.isEmpty())
    }

    @Test
    fun codes_et_titres_de_pistes() {
        assertEquals("fr", trackLanguageCode("fre"))
        assertEquals("fr", trackLanguageCode("fra"))
        assertEquals("fr", trackLanguageCode("fr-CA"))
        assertEquals("en", trackLanguageCode("ENG"))
        assertNull(trackLanguageCode("und"))
        assertNull(trackLanguageCode(null))
        assertNull(trackLanguageCode(" "))
        assertEquals("Français 5.1", trackTitle(FR, 0), "le nom de la langue n'est pas repete")
        assertEquals("Anglais · English", trackTitle(EN, 1))
        assertEquals("Espagnol", trackTitle(TrackOption("x", "spa", "", false), 0))
        assertEquals("Piste 3", trackTitle(TrackOption("x", null, "", false), 2))
        assertEquals("Commentaires", trackTitle(TrackOption("x", null, "Commentaires", false), 0))
    }

    @Test
    fun chaque_enregistrement_demande_un_envoi_hors_du_fil_principal_un_seul_a_la_fois() {
        val f = Fixture()
        f.client.recordDelayMs = 500
        f.playback.start(f.info, ep(1, 2), null)
        repeat(4) { i -> f.advance(30); f.playback.onTick(30 * (i + 1), 2400) }
        f.test.testScheduler.advanceUntilIdle()
        assertEquals(listOf(120), f.client.sent.map { it.second.positionS }.takeLast(1))
        assertEquals(1, f.client.maxRunning)
        assertTrue(f.queued().isEmpty(), "file videe apres l'envoi")
    }

    @Test
    fun begin_sans_categorie_connue_du_cache_masquee_refuse_sans_demander_la_fiche() {
        val f = Fixture(hidden = setOf("7"), cached = mapOf("42" to "7"))
        f.playback.begin(PlayEpisodeRequest("42", null, "s1e2", 1, 2, 0, false))
        f.test.testScheduler.advanceUntilIdle()
        val s = f.playback.state.value as EpisodePlaybackState.Failed
        assertFalse(s.canRetry)
        assertTrue(s.message.contains("masquée"))
        assertTrue(f.infoCalls.isEmpty(), "le fournisseur n'est pas appele")
        assertTrue(f.log.lines.none { it.startsWith("create") })
    }

    @Test
    fun reessayer_apres_une_erreur_d_enchainement_repart_de_zero() {
        val f = Fixture()
        f.playback.start(f.info, ep(1, 2), null)
        f.advance(50); f.playback.onTick(1500, 2400)
        f.playback.onEnded()
        f.urlOk = false
        f.test.testScheduler.advanceTimeBy(11_000); f.test.testScheduler.runCurrent()
        assertTrue(f.playback.state.value is EpisodePlaybackState.Failed)
        f.urlOk = true
        f.playback.retry()
        assertEquals("play P2 http://flux.invalide/s1e3.mkv", f.log.lines.last(), "l'episode suivant s'ouvre a 0")
    }

    @Test
    fun episode_suivant_avec_numeros_en_double_est_le_meme_pour_la_lecture_et_l_accueil() {
        val e5a = Episode("a5", 1, 5, "A", 2400, "mkv", null)
        val e5b = Episode("b5", 1, 5, "B", 2400, "mkv", null)
        val seasons = listOf(Season(1, "S1", listOf(ep(1, 1), e5a, e5b, ep(1, 6))))
        val info = infoOf(seasons)
        assertEquals(e5b, nextEpisode(info, e5a))
        assertEquals(ep(1, 6), nextEpisode(info, e5b))
        // L'accueil annonce le meme « Ensuite » apres l'episode vu.
        val row = home.iptv.shared.progress.ProgressRow(
            itemId = "a5", seriesId = "42", season = 1, episode = 5, positionS = 0, durationS = 2400, watched = true,
            clientUpdatedAt = Instant.parse("2026-11-15T10:00:00Z"), seriesName = "Les Rivages", seriesCategory = "7",
        )
        val h = buildSeriesHome(listOf(row), emptyList(), emptyList(), null) { info }
        assertEquals("b5", h.resume?.episode?.id)
    }

    @Test
    fun deux_profils_sur_la_meme_file_chacun_n_enregistre_que_pour_lui() {
        val a = Fixture(profile = "pA")
        val b = EpisodePlayback(
            "pB", a.controller, a.queue, a.flusher, a.prefs, { a.now }, a.test, { "http://flux.invalide/x" },
            { _, _ -> a.info }, { emptySet() }, a.dispatcher,
        )
        a.playback.start(a.info, ep(1, 2), null)
        a.advance(40); a.playback.onTick(40, 2400)
        b.start(a.info, ep(2, 1), null)
        a.advance(20); b.onTick(15, 2400)
        // On arrete pA pendant que pB lit : la file ne contient que pA.
        a.playback.stop()
        assertEquals(listOf("pA" to 40), a.queued().map { it.profileId to it.entry.positionS })
        b.stop()
        assertEquals(setOf("pA" to 40, "pB" to 15), a.queued().map { it.profileId to it.entry.positionS }.toSet())
    }

    @Test
    fun duree_inconnue_la_position_est_quand_meme_mise_en_file_et_envoyee() {
        val f = Fixture()
        val noDuration = ep(1, 2, duration = null)
        f.playback.start(f.info, noDuration, null)
        f.advance(40); f.playback.onTick(300, 0)
        f.playback.stop()
        val e = f.queued().single().entry
        assertEquals(300, e.positionS)
        assertEquals(0, e.durationS)
        f.test.testScheduler.advanceUntilIdle()
        assertEquals(listOf(300 to 0), f.client.sent.map { it.second.positionS to it.second.durationS })
        assertTrue(f.queued().isEmpty())
    }

    @Test
    fun une_demande_d_envoi_arrivee_pendant_un_envoi_en_cours_declenche_un_second_passage() {
        val f = Fixture()
        f.client.gate = kotlinx.coroutines.CompletableDeferred()
        f.client.failNext = 1
        f.playback.start(f.info, ep(1, 2), null)
        f.advance(40); f.playback.onTick(40, 2400)
        f.playback.stop() // enregistre, demande un envoi
        f.test.testScheduler.advanceTimeBy(1_500); f.test.testScheduler.runCurrent()
        assertEquals(1, f.client.calls, "l'envoi est en cours, bloque")
        f.flusher.request() // arrive pendant l'envoi
        f.client.gate!!.complete(Unit) // l'envoi en cours echoue : la file reste
        f.test.testScheduler.advanceUntilIdle()
        assertEquals(2, f.client.calls, "un second passage a eu lieu")
        assertTrue(f.queued().isEmpty())
        assertEquals(40, f.client.sent.single().second.positionS)
    }

    @Test
    fun l_affiche_hebergee_chez_le_fournisseur_est_envoyee_sans_son_adresse() {
        val f = Fixture(info = infoOf(twoSeasons()).let { i -> i.copy(summary = i.summary.copy(cover = "https://img.example.test/c.jpg")) },
            coverFor = { it?.removePrefix("https://img.example.test") })
        f.playback.start(f.info, ep(1, 2), null)
        f.advance(40); f.playback.onTick(40, 2400)
        f.playback.stop()
        assertEquals("/c.jpg", f.queued().single().entry.seriesCover)
    }
}
