package home.iptv.tv.live

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import home.iptv.shared.groups.*
import kotlinx.coroutines.CompletableDeferred
import home.iptv.shared.catalog.CatalogDatabase
import home.iptv.shared.catalog.CatalogRepository
import home.iptv.shared.catalog.LiveSource
import home.iptv.shared.catalog.RefreshReport
import home.iptv.shared.sync.HiddenItem
import home.iptv.shared.sync.PairingState
import home.iptv.shared.sync.Profile
import home.iptv.shared.sync.ProfilePrefs
import home.iptv.shared.sync.PublishedCategory
import home.iptv.shared.sync.PublishedChannel
import home.iptv.shared.sync.SyncClient
import home.iptv.shared.xtream.LiveCategory
import home.iptv.shared.xtream.LiveStream
import home.iptv.shared.xtream.XtreamCreds
import home.iptv.tv.data.LocalStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val SCATS = listOf(LiveCategory("1", "Sport"), LiveCategory("2", "Cine"))
private val SSTREAMS = mapOf(
    "1" to listOf(LiveStream("10", "Chaine 10", "1", null, null, null), LiveStream("11", "Chaine 11", "1", null, null, null)),
    "2" to listOf(LiveStream("20", "Chaine 20", "2", null, null, null)),
)
private val PA = Profile("pa", "A", "#000", "live", "dark", 0)
private val PB = Profile("pb", "B", "#000", "live", "dark", 1)

private fun sp(rev: Long, vararg fav: String) = ProfilePrefs(rev, emptyList(), fav.toList(), emptyList(), null)

private class MapStore : LocalStore {
    override fun loadProgressQueue(): List<home.iptv.shared.progress.QueuedProgress> = emptyList()
    override fun persistNow(): Boolean = true
    override fun saveProgressQueue(queue: List<home.iptv.shared.progress.QueuedProgress>) {}
    override fun update(transform: (List<home.iptv.shared.progress.QueuedProgress>) -> List<home.iptv.shared.progress.QueuedProgress>) {}
    override fun audioLang(profileId: String): String? = null
    override fun subtitleLang(profileId: String): String? = null
    override fun saveTrackPrefs(profileId: String, audioLang: String?, subtitleLang: String?) {}
    override fun lastTab(profileId: String): home.iptv.tv.data.HomeTab? = null
    override fun saveLastTab(profileId: String, tab: home.iptv.tv.data.HomeTab) {}
    private val groupCache = mutableMapOf<String, home.iptv.shared.groups.GroupSnapshot>()
    private val sourceCache = mutableMapOf<Pair<String, String>, String>()
    override fun groupSnapshot(h: String) = groupCache[h]
    override fun saveGroupSnapshot(h: String, snapshot: home.iptv.shared.groups.GroupSnapshot) { groupCache[h] = snapshot }
    override fun lastGoodSource(profileId: String, groupId: String) = sourceCache[profileId to groupId]
    override fun saveLastGoodSource(profileId: String, groupId: String, sourceId: String) { sourceCache[profileId to groupId] = sourceId }

    val map = mutableMapOf<String, ProfilePrefs>()
    override fun householdId(): String? = "h1"
    override fun saveHouseholdId(id: String) {}
    override fun creds(): XtreamCreds? = null
    override fun saveCreds(c: XtreamCreds) {}
    override fun profiles(): List<Profile> = emptyList()
    override fun saveProfiles(p: List<Profile>) {}
    override fun prefs(profileId: String): ProfilePrefs? = map[profileId]
    override fun savePrefs(profileId: String, prefs: ProfilePrefs) { map[profileId] = prefs }
}

private class SessionSync : SyncClient {
    val server = mutableMapOf<String, ProfilePrefs>()
    var rev = 1L
    var revCalls = 0
    val fetched = mutableListOf<String>()
    override suspend fun publishSeriesCategories(householdId: String, cats: List<PublishedCategory>) {}
    override suspend fun ensureSignedIn() {}
    override suspend fun currentHousehold(): String? = "h1"
    override suspend fun createPairingCode(deviceName: String): PairingState.Waiting = error("inutile")
    override suspend fun credentials(householdId: String): XtreamCreds? = null
    override suspend fun profiles(householdId: String): List<Profile> = emptyList()
    var globalHidden: List<String> = emptyList()
    var hiddenDown = false
    override suspend fun globalHiddenCategories(householdId: String): List<HiddenItem> {
        if (hiddenDown) throw java.io.IOException("hors ligne")
        return globalHidden.map { HiddenItem("live_category", it) }
    }
    override suspend fun publishLiveCategories(householdId: String, cats: List<PublishedCategory>): Map<String, String?> = emptyMap()
    override suspend fun publishLiveChannels(householdId: String, categoryId: String, fingerprint: String, chunk: Int, isLast: Boolean, channels: List<PublishedChannel>) {}
    override suspend fun profilePrefs(profileId: String): ProfilePrefs { fetched += profileId; return server.getValue(profileId) }
    override suspend fun prefsRevision(householdId: String): Long { revCalls++; return rev }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LiveSessionsTest {
    private val sync = SessionSync()
    private val store = MapStore()
    private var refreshCalls = 0
    private var publishCalls = 0
    private var refreshBody: suspend () -> Unit = {}

    private suspend fun cachedRepo(): CatalogRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CatalogDatabase.Schema.create(driver)
        val repo = CatalogRepository(CatalogDatabase(driver))
        repo.refreshLive(object : LiveSource {
            override suspend fun liveCategories() = SCATS
            override suspend fun liveStreams(categoryId: String) = SSTREAMS[categoryId].orEmpty()
        }, emptySet())
        return repo
    }

    private suspend fun TestScope.sessions() = LiveSessions(
        backgroundScope, sync, store, cachedRepo(), { "h1" },
        refresh = { refreshCalls++; refreshBody(); RefreshReport(2, 3, emptyList(), SCATS) },
        publish = { publishCalls++ },
        io = StandardTestDispatcher(testScheduler),
    ).also {
        sync.server["pa"] = sp(1, "10"); sync.server["pb"] = sp(1, "20")
    }

    private fun LiveSession.favorites() = vm.streams.value.map { it.id }

    @Test
    fun stopping_screen_cancels_group_network_and_resume_retries() = runTest {
        var cancelled = false
        var blocked = true
        val client = GroupsClient { _, _, _ ->
            if (blocked) try { awaitCancellation() } finally { cancelled = true }
            GroupPage(1, 1, emptyList())
        }
        sync.server["pa"] = sp(1)
        val sessions = LiveSessions(backgroundScope, sync, store, cachedRepo(), { "h1" },
            { RefreshReport(2, 3, emptyList()) }, {}, StandardTestDispatcher(testScheduler), groupsClient = client)
        val a = sessions.open(PA); runCurrent()
        sessions.stop(); runCurrent()
        assertTrue(cancelled)
        blocked = false; sessions.resume(); runCurrent()
        assertEquals(1, a.groupsWatcher?.snapshot?.value?.rev)
    }

    @Test
    fun slow_group_network_never_delays_new_hidden_preferences() = runTest {
        store.map["pa"] = sp(1, "10")
        sync.server["pa"] = ProfilePrefs(2, listOf(HiddenItem("channel", "10")), listOf("10"), emptyList(), null)
        sync.rev = 2
        val gate = CompletableDeferred<Unit>()
        val client = GroupsClient { _, _, _ -> gate.await(); GroupPage(1, 1, emptyList()) }
        val sessions = LiveSessions(backgroundScope, sync, store, cachedRepo(), { "h1" },
            { RefreshReport(2, 3, emptyList()) }, {}, StandardTestDispatcher(testScheduler), groupsClient = client)
        val a = sessions.open(PA); a.vm.start(); runCurrent()
        assertEquals(listOf(HiddenItem("channel", "10")), a.watcher.prefs.value?.hidden)
        assertTrue(a.vm.streams.value.isEmpty())
        gate.complete(Unit); runCurrent()
    }

    @Test
    fun group_revision_changes_under_five_seconds_without_refetch_when_unchanged() = runTest {
        var revision = 1L
        var fullLoads = 0
        val client = GroupsClient { _, _, limit ->
            if (limit > 1) fullLoads++
            GroupPage(revision, 1, listOf(GroupPageEntry("m", "Sport $revision", listOf("10", "20"), emptyList())))
        }
        sync.server["pa"] = sp(1, "10")
        val sessions = LiveSessions(backgroundScope, sync, store, cachedRepo(), { "h1" },
            { RefreshReport(2, 3, emptyList()) }, {}, StandardTestDispatcher(testScheduler), groupsClient = client)
        val a = sessions.open(PA); a.setFastPoll(true); a.vm.start(); runCurrent()
        a.vm.selectSection("fav"); runCurrent()
        assertEquals("Sport 1", a.vm.loaded.value.rows.single().group.title)
        assertEquals(1, fullLoads)
        advanceTimeBy(4_001); runCurrent()
        assertEquals(1, fullLoads)
        revision = 2; sync.rev = 2; sync.server["pa"] = sp(2, "10")
        advanceTimeBy(4_001); runCurrent()
        assertEquals("Sport 2", a.vm.loaded.value.rows.single().group.title)
        assertEquals(2, fullLoads)
    }

    @Test
    fun profile_change_during_group_response_cancels_old_delivery() = runTest {
        val gate = CompletableDeferred<Unit>()
        var requests = 0
        val client = GroupsClient { _, _, _ ->
            if (++requests == 1) gate.await()
            GroupPage(2, 1, listOf(GroupPageEntry("m", "Sport", listOf("10", "20"), emptyList())))
        }
        sync.server["pa"] = sp(1, "10"); sync.server["pb"] = sp(1, "20")
        val sessions = LiveSessions(backgroundScope, sync, store, cachedRepo(), { "h1" },
            { RefreshReport(2, 3, emptyList()) }, {}, StandardTestDispatcher(testScheduler), groupsClient = client)
        val a = sessions.open(PA); a.vm.start(); runCurrent()
        val b = sessions.open(PB); b.vm.start(); runCurrent()
        gate.complete(Unit); runCurrent()
        b.vm.selectSection("fav"); runCurrent()
        assertFalse(a.isActive)
        assertEquals(null, a.groupsWatcher?.snapshot?.value)
        assertTrue(a.vm.loaded.value.rows.isEmpty(), "ancien index global libere")
        assertEquals(listOf("20"), b.vm.streams.value.map { it.id })
        assertEquals("manual:m", b.vm.loaded.value.rows.single().group.id)
    }

    @Test
    fun deux_profils_chacun_ses_preferences() = runTest {
        val s = sessions()
        store.map["pa"] = sp(1, "10"); store.map["pb"] = sp(1, "20")
        val a = s.open(PA); a.vm.start(); runCurrent()
        assertEquals(listOf("fav", "cat:1", "cat:2"), a.vm.sections.value.map { it.key })
        assertEquals(listOf("10"), a.favorites())

        val b = s.open(PB); b.setFastPoll(true); b.vm.start(); runCurrent()
        assertEquals(listOf("20"), b.favorites()) // les favoris locaux de B, pas ceux de A
        sync.server["pb"] = sp(2, "11", "20"); sync.rev = 2
        advanceTimeBy(4_001); runCurrent()
        assertEquals(listOf("11", "20"), b.favorites())
        assertEquals(listOf("10"), a.favorites())
    }

    @Test
    fun un_seul_veilleur_actif_apres_changement() = runTest {
        val s = sessions()
        val a = s.open(PA); val b = s.open(PB).also { it.setFastPoll(true) } // liste du Direct a l'ecran : 4 s
        s.close(a) // l'ancien ecran se ferme APRES l'ouverture du nouveau : B reste actif
        runCurrent()
        val before = sync.revCalls
        advanceTimeBy(20_000); runCurrent()
        assertEquals(5, sync.revCalls - before, "un seul veilleur interroge : B")
        sync.server["pb"] = sp(2, "11"); sync.server["pa"] = sp(2, "10", "11"); sync.rev = 2
        sync.fetched.clear()
        advanceTimeBy(4_001); runCurrent()
        assertEquals(listOf("pb"), sync.fetched)
        s.close(b)
        val after = sync.revCalls
        advanceTimeBy(20_000); runCurrent()
        assertEquals(after, sync.revCalls)
    }

    @Test
    fun onStop_arrete_et_onStart_reprend_le_veilleur_courant() = runTest {
        val s = sessions()
        s.open(PA); runCurrent()
        s.stop()
        val n = sync.revCalls
        advanceTimeBy(20_000); runCurrent()
        assertEquals(n, sync.revCalls)
        s.resume(); runCurrent()
        assertTrue(sync.revCalls > n)
    }

    @Test
    fun ancienne_session_annulee() = runTest {
        var cancelled = false
        refreshBody = { try { awaitCancellation() } finally { cancelled = true } }
        val s = sessions()
        store.map["pa"] = sp(1, "10")
        val a = s.open(PA); a.vm.start(); runCurrent()
        assertTrue(a.isActive)
        refreshBody = {}
        val b = s.open(PB); b.vm.start(); runCurrent()
        assertFalse(a.isActive)
        // Le rafraichissement du catalogue n'appartient pas au profil : il continue, B le suit (essai TCL 0.4.0).
        assertFalse(cancelled, "le rafraichissement du catalogue survit au changement de profil")
        assertEquals(1, refreshCalls)
        assertTrue(b.vm.refreshing.value)
        // Le collecteur de A est arrete : plus aucune mise a jour.
        val seen = a.favorites()
        store.map["pa"] = sp(1, "10")
        sync.server["pa"] = sp(5, "11"); sync.rev = 5
        advanceTimeBy(10_000); runCurrent()
        assertEquals(seen, a.favorites())
        assertTrue(b.isActive)
    }

    @Test
    fun changer_de_profil_pendant_le_rafraichissement_ne_le_relance_pas() = runTest {
        // Essai TCL 0.4.0 : « Mise à jour » revenait après un changement de profil, le catalogue repartait de zéro.
        val gate = CompletableDeferred<Unit>()
        refreshBody = { gate.await() }
        val s = sessions()
        val a = s.open(PA); a.vm.start(); runCurrent()
        assertTrue(a.vm.refreshing.value)
        s.close(a)
        val b = s.open(PB); b.vm.start(); runCurrent()
        assertTrue(b.vm.refreshing.value, "le nouveau profil suit le rafraichissement en cours")
        gate.complete(Unit); runCurrent()
        assertEquals(1, refreshCalls)
        assertFalse(b.vm.refreshing.value)
        assertEquals(1, publishCalls)
        val c = s.open(PA); c.vm.start(); runCurrent()
        assertFalse(c.vm.refreshing.value, "catalogue déjà à jour : plus de « Mise à jour »")
        assertEquals(1, refreshCalls)
    }

    @Test
    fun changer_de_profil_pendant_la_publication_ne_relance_pas_le_rafraichissement() = runTest {
        // 0.4.0 : le catalogue n'était « fait » qu'une fois la copie publiée vers le téléphone (longue au premier
        // lancement) ; changer de profil avant relançait tout le rafraichissement.
        val publishGate = CompletableDeferred<Unit>()
        val s = LiveSessions(
            backgroundScope, sync, store, cachedRepo(), { "h1" },
            refresh = { refreshCalls++; RefreshReport(2, 3, emptyList(), SCATS) },
            publish = { publishCalls++; publishGate.await() },
            io = StandardTestDispatcher(testScheduler),
        ).also { sync.server["pa"] = sp(1, "10"); sync.server["pb"] = sp(1, "20") }
        val a = s.open(PA); a.vm.start(); runCurrent()
        assertFalse(a.vm.refreshing.value)
        assertEquals(1, publishCalls)
        s.close(a)
        val b = s.open(PB); b.vm.start(); runCurrent()
        assertFalse(b.vm.refreshing.value, "« Mise à jour » ne revient pas")
        assertEquals(1, refreshCalls, "le catalogue n'est pas relu chez le fournisseur")
        publishGate.complete(Unit); runCurrent()
        assertEquals(1, publishCalls, "la publication commencée continue, sans doublon")
    }

    @Test
    fun un_rafraichissement_bloque_est_abandonne_et_la_session_suivante_reessaie() = runTest {
        refreshBody = { awaitCancellation() }
        val s = sessions()
        val a = s.open(PA); a.vm.start(); runCurrent()
        advanceTimeBy(10 * 60_000L + 10_000L); runCurrent()
        assertFalse(a.vm.refreshing.value, "« Mise à jour » s'efface même si le fournisseur ne répond jamais")
        assertEquals(REFRESH_TOO_LONG, a.vm.refreshNotice.value)
        refreshBody = {}
        s.close(a)
        val b = s.open(PB); b.vm.start(); runCurrent()
        assertEquals(2, refreshCalls, "un rafraichissement abandonné est retenté par la session suivante")
        assertFalse(b.vm.refreshing.value)
        assertEquals(1, publishCalls)
    }

    @Test
    fun rafraichissement_une_seule_fois_par_processus() = runTest {
        val s = sessions()
        val a = s.open(PA); a.vm.start(); runCurrent()
        s.close(a)
        val b = s.open(PB); b.vm.start(); runCurrent()
        assertEquals(1, refreshCalls)
        assertEquals(1, publishCalls)
        assertEquals(2, b.vm.categories.value.size) // B affiche le cache
    }

    // --- Stabilisation zone 2 : liste remise a jour, rythme des reglages ---

    private var clock = 0L
    private var hiddenAtRefresh: Set<String>? = null

    private suspend fun TestScope.timedSessions() = LiveSessions(
        backgroundScope, sync, store, cachedRepo(), { "h1" },
        refresh = { refreshCalls++; RefreshReport(2, 3, emptyList(), SCATS) },
        publish = { publishCalls++ },
        io = StandardTestDispatcher(testScheduler),
        clockMs = { clock },
        hiddenAtRefresh = { hiddenAtRefresh },
    ).also { sync.server["pa"] = sp(1, "10"); sync.server["pb"] = sp(1, "20") }

    @Test
    fun retour_de_veille_apres_6_h_remet_la_liste_a_jour_une_seule_fois() = runTest {
        val s = timedSessions()
        val a = s.open(PA); a.vm.start(); runCurrent()
        assertEquals(1, refreshCalls)
        s.stop(); clock += 5 * 3_600_000L; s.resume(); runCurrent()
        assertEquals(1, refreshCalls, "5 h : rien")
        s.stop(); clock += 3_600_000L; s.resume(); runCurrent()
        assertEquals(2, refreshCalls, "6 h : nouvelle mise a jour")
        assertEquals(2, publishCalls, "et nouvelle copie pour l'iPhone")
        s.stop(); clock += 60_000L; s.resume(); runCurrent()
        assertEquals(2, refreshCalls, "la liste vient d'etre remise a jour")
    }

    @Test
    fun liste_ancienne_pendant_qu_on_regarde_une_serie_mise_a_jour_a_l_ouverture_du_direct() = runTest {
        val s = timedSessions()
        val a = s.open(PA); a.vm.start(); runCurrent()
        val b = s.open(PB); runCurrent() // profil B dans Series : son Direct n'est pas ouvert
        s.stop(); clock += 7 * 3_600_000L; s.resume(); runCurrent()
        assertEquals(1, refreshCalls, "rien tant que le Direct n'est pas ouvert")
        b.vm.start(); runCurrent()
        assertEquals(2, refreshCalls)
    }

    @Test
    fun categorie_reaffichee_sur_l_iphone_relance_la_mise_a_jour() = runTest {
        val s = timedSessions()
        hiddenAtRefresh = setOf("2", "3")
        sync.globalHidden = listOf("2", "3")
        val a = s.open(PA); a.setFastPoll(true); a.vm.start(); runCurrent()
        assertEquals(1, refreshCalls)
        // Autre reglage modifie (revision nouvelle), masquees inchangees : rien.
        sync.rev = 2; sync.server["pa"] = sp(2, "10")
        advanceTimeBy(4_001); runCurrent()
        assertEquals(1, refreshCalls)
        // La categorie 3 est reaffichee pour tout le monde.
        sync.globalHidden = listOf("2"); sync.rev = 3; sync.server["pa"] = sp(3, "10")
        advanceTimeBy(4_001); runCurrent()
        assertEquals(2, refreshCalls)
        // Une nouvelle categorie masquee ne relance rien (elle sera retiree a la prochaine mise a jour).
        hiddenAtRefresh = setOf("2")
        sync.globalHidden = listOf("1", "2"); sync.rev = 4; sync.server["pa"] = sp(4, "10")
        advanceTimeBy(4_001); runCurrent()
        assertEquals(2, refreshCalls)
    }

    @Test
    fun reglages_verifies_toutes_les_4_s_sur_la_liste_et_toutes_les_30_s_ailleurs() = runTest {
        val s = timedSessions()
        val a = s.open(PA); runCurrent()
        val start = sync.revCalls
        advanceTimeBy(29_000); runCurrent()
        assertEquals(start, sync.revCalls, "hors de la liste : pas avant 30 s")
        advanceTimeBy(1_001); runCurrent()
        assertEquals(start + 1, sync.revCalls)
        a.setFastPoll(true); runCurrent()
        assertEquals(start + 2, sync.revCalls, "la liste apparait : verification immediate")
        advanceTimeBy(4_001); runCurrent()
        assertEquals(start + 3, sync.revCalls)
        a.setFastPoll(false)
        advanceTimeBy(8_000); runCurrent()
        assertEquals(start + 4, sync.revCalls, "la verification deja prevue a lieu, puis le rythme lent reprend")
        advanceTimeBy(20_000); runCurrent()
        assertEquals(start + 4, sync.revCalls)
    }

    @Test
    fun horloge_pile_a_la_minute() {
        assertEquals(60_050L, msToNextMinute(0L))
        assertEquals(51L, msToNextMinute(59_999L), "une milliseconde avant la minute")
        assertEquals(30_050L, msToNextMinute(90_000L))
    }

    @Test
    fun categorie_reaffichee_pendant_une_coupure_reseau_est_reverifiee_au_tour_suivant() = runTest {
        val s = timedSessions()
        hiddenAtRefresh = setOf("2"); sync.globalHidden = listOf("2")
        val a = s.open(PA); a.setFastPoll(true); a.vm.start(); runCurrent()
        sync.hiddenDown = true
        sync.globalHidden = emptyList(); sync.rev = 2; sync.server["pa"] = sp(2, "10")
        advanceTimeBy(4_001); runCurrent()
        assertEquals(1, refreshCalls, "verification ratee")
        sync.hiddenDown = false
        advanceTimeBy(4_001); runCurrent()
        assertEquals(2, refreshCalls, "reverifiee sans autre changement de reglage")
    }

    @Test
    fun en_arriere_plan_aucune_mise_a_jour_ne_demarre() = runTest {
        val s = timedSessions()
        hiddenAtRefresh = setOf("2"); sync.globalHidden = listOf("2")
        val a = s.open(PA); a.setFastPoll(true); a.vm.start(); runCurrent()
        sync.globalHidden = emptyList(); sync.rev = 2; sync.server["pa"] = sp(2, "10")
        s.stop() // la tele passe en veille avant le tour suivant
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, refreshCalls)
        s.resume(); runCurrent()
        advanceTimeBy(4_001); runCurrent()
        assertEquals(2, refreshCalls, "au retour, la categorie reaffichee est bien rattrapee")
    }

    @Test
    fun profil_ouvert_quand_la_liste_est_vieille_la_remet_a_jour_a_l_ouverture_du_direct() = runTest {
        val s = timedSessions()
        val a = s.open(PA); a.vm.start(); runCurrent()
        clock += 7 * 3_600_000L
        val b = s.open(PB); b.vm.start(); runCurrent()
        assertEquals(2, refreshCalls)
    }
}
