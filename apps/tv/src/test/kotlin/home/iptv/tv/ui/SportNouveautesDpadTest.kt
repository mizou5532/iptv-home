package home.iptv.tv.ui

import androidx.compose.foundation.focusable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onRoot
import androidx.tv.material3.Text
import home.iptv.shared.sport.*
import home.iptv.shared.sync.ProfilePrefs
import home.iptv.shared.sync.ProfileSettings
import home.iptv.shared.sync.SportView
import home.iptv.shared.sync.SPORT_LAYOUT_GRID
import home.iptv.tv.player.PlayerController
import home.iptv.tv.sport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * 0.39.0 à la télécommande, rendu réel 960 × 540 : compte à rebours, classements sous les équipes, scores cachés
 * (touche Info, « Voir le score »), rangées de la grille, proposition d'un autre match à la fin de celui qu'on regarde.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@org.robolectric.annotation.Config(qualifiers = "w960dp-h540dp-xhdpi")
class SportNouveautesDpadTest {
    @get:Rule val rule = tvRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val now = Instant.parse("2026-11-17T00:45:00Z") // lundi 16 nov., 19 h 45 à Montréal
    private val log = mutableListOf<String>()
    private val opened = mutableListOf<String>()

    private fun game(id: Int, start: String, state: String, away: String, home: String, aScore: Int? = null, hScore: Int? = null, period: Int? = null) =
        """{"id":$id,"startTimeUTC":"$start","gameState":"$state"${period?.let { ",\"periodDescriptor\":{\"number\":$it}" } ?: ""},
        "awayTeam":{"abbrev":"$away","commonName":{"default":"${NAMES[away]}"}${aScore?.let { ",\"score\":$it" } ?: ""}},
        "homeTeam":{"abbrev":"$home","commonName":{"default":"${NAMES[home]}"}${hScore?.let { ",\"score\":$it" } ?: ""}},
        "tvBroadcasts":[{"network":"TVAS","countryCode":"CA"},{"network":"SN","countryCode":"CA"}]}"""
    private fun week(vararg games: String) = """{"gameWeek":[{"games":[${games.joinToString(",")}]}]}"""

    @Volatile private var nhl = week(
        game(1, "2026-11-17T00:30:00Z", "LIVE", "MTL", "BOS", 2, 1, period = 2),
        game(2, "2026-11-17T03:00:00Z", "FUT", "EDM", "SEA"),
        game(3, "2026-11-19T00:00:00Z", "FUT", "TOR", "MTL"),
    )
    private val standings = """{"standings":[
        {"teamAbbrev":{"default":"MTL"},"divisionName":"Atlantic","divisionSequence":3,"points":28,"streakCode":"W","streakCount":4},
        {"teamAbbrev":{"default":"BOS"},"divisionName":"Atlantic","divisionSequence":5,"points":24,"streakCode":"L","streakCount":2}]}"""

    private val store = object : SportStore {
        val cache = java.util.concurrent.ConcurrentHashMap<String, String>()
        override fun cached(key: String) = cache[key]
        override fun saveCached(key: String, text: String, at: Long) { cache[key] = text }
        override fun cachedAt() = 0L
        override fun learned(profileId: String, league: League) = emptyList<String>()
        override fun saveLearned(profileId: String, league: League, groups: List<String>) {}
    }
    private val channels = listOf(SportChannel("g-tvas", "TVA Sports HD", "CA| SPORTS FR"), SportChannel("g-sn", "Sportsnet HD", "CA| SPORTS EN"))
    private val player = PlayerController { FakePlayer(log) }
    @After fun tearDown() { scope.cancel() }

    private fun vm(view: SportView) = SportViewModel("p1",
        SportSchedule({ url -> when { "standings" in url -> standings; "nhle" in url -> nhl; else -> """{"events":[]}""" } }, store) { now },
        { channels }, { emptyList() }, store, MutableStateFlow<ProfilePrefs?>(NO_PREFS), scope, Dispatchers.IO, { now }, { -5 * 3600 },
        view = { view })

    private fun show(vm: SportViewModel, view: SportView) = rule.setTvContent {
        val bar = remember { FocusRequester() }
        LaunchedEffect(Unit) { withFrameNanos { }; bar.requestFocus() }
        SportTab(vm, PROFILE.copy(settings = ProfileSettings(sportView = view)), player, { ch -> opened += ch.groupId; "fake://${ch.groupId}" },
            { Text("Sport", Modifier.focusRequester(bar).focusable()) }, bar, {})
    }

    private fun count(text: String) = rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size
    private fun focusedHas(text: String) = rule.focusedTexts()?.any { it.contains(text) } == true
    private fun dialogFocusedHas(text: String) = rule.topFocusedTexts()?.any { it.contains(text) } == true
    private fun toWatch() {
        rule.until(what = "affiche") { count("Canadiens") > 0 }
        repeat(5) { if (!focusedHas("Regarder")) rule.down() }
        assertTrue(focusedHas("Regarder"), "${rule.focusedTexts()}")
    }

    @Test fun affiche_classements_sous_les_equipes() {
        val v = SportView()
        show(vm(v), v)
        rule.until(what = "classement des Canadiens") { count("3e Atlantique · 28 pts · 4 victoires de suite") > 0 }
        rule.until(what = "classement des Bruins") { count("5e Atlantique · 24 pts · 2 défaites de suite") > 0 }
        assertTrue(count("2 : 1") > 0, "score visible")
        assertTrue(count(HIDDEN_SCORE_NOTE) == 0)
    }

    @Test fun compte_a_rebours_sur_l_affiche_d_un_match_a_venir() {
        nhl = week(game(2, "2026-11-17T03:00:00Z", "FUT", "EDM", "SEA"), game(3, "2026-11-19T00:00:00Z", "FUT", "TOR", "MTL"))
        val v = SportView()
        show(vm(v), v)
        // Prochain match de mes équipes (mercredi 19 h à Montréal ; jeudi 0 h dans le fuseau UTC des tests) : jour et heure.
        rule.until(what = "compte à rebours") { count(WEDNESDAY_7PM) > 0 }
        toWatch()
        // Le focus sur l'autre match (ce soir 22 h) : « Dans 2 h 15 ».
        rule.down()
        repeat(4) { if (!focusedHas("Oilers")) rule.right() }
        rule.until(what = "compte à rebours de ce soir", detail = { "${rule.focusedTexts()}" }) { count("Dans 2 h 15") > 0 }
    }

    @Test fun sans_resultat_score_cache_info_le_montre_et_le_recache() {
        val v = SportView(hideScores = true)
        val model = vm(v)
        show(model, v)
        toWatch()
        rule.until(what = "note du score caché") { count(HIDDEN_SCORE_NOTE) > 0 }
        assertTrue(count("2 : 1") == 0, "aucun score")
        assertTrue(count("Atlantique") == 0, "aucun classement")
        rule.press(android.view.KeyEvent.KEYCODE_INFO)
        rule.until(what = "score montré") { count("2 : 1") > 0 }
        assertTrue(count(HIDDEN_SCORE_NOTE) == 0)
        rule.press(android.view.KeyEvent.KEYCODE_INFO)
        rule.until(what = "score recaché") { count("2 : 1") == 0 && count(HIDDEN_SCORE_NOTE) > 0 }
        assertTrue(focusedHas("Regarder"), "le focus reste sur Regarder (${rule.focusedTexts()})")
    }

    @Test fun sans_resultat_voir_le_score_dans_le_choix_de_la_chaine() {
        val v = SportView(hideScores = true)
        show(vm(v), v)
        toWatch()
        rule.longOk()
        rule.until(what = "choix de la chaîne") { count("Choisir la chaîne") > 0 }
        assertTrue(count("Voir le score") > 0)
        repeat(4) { if (!focusedHas("Voir le score")) rule.up() }
        assertTrue(focusedHas("Voir le score"), "${rule.focusedTexts()}")
        rule.ok()
        rule.until(what = "score dans le panneau") { count("2 – 1") > 0 }
        rule.until(what = "bouton retiré") { count("Voir le score") == 0 }
        assertTrue(opened.isEmpty(), "rien n'est lancé")
        rule.back()
        rule.until(what = "affiche avec le score") { count("2 : 1") > 0 }
    }

    @Test fun grille_en_rangees_avec_titres_toutes_atteignables_dans_l_ecran() {
        val v = SportView(layout = SPORT_LAYOUT_GRID)
        show(vm(v), v)
        rule.until(what = "rangées") { count("EN DIRECT MAINTENANT") > 0 && count("CE SOIR") > 0 && count("CETTE SEMAINE POUR MES ÉQUIPES") > 0 }
        assertTrue(count("DEMAIN") == 0, "rangée vide retirée")
        rule.until(what = "match du haut") { count("OÙ REGARDER") > 0 }
        repeat(4) { if (!focusedHas("Regarder")) rule.down() }
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        // Descendre jusqu'au match de la semaine (Maple Leafs – Canadiens) : chaque tuile reste dans l'écran.
        repeat(6) {
            if (!focusedHas("Maple Leafs")) rule.down()
            val f = rule.onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull()?.boundsInRoot
            if (f != null) assertTrue(f.top >= 0f && f.bottom <= root.bottom + 1f, "dans l'écran : $f")
        }
        assertTrue(focusedHas("Maple Leafs"), "${rule.focusedTexts()}")
        rule.until(what = "le match de la semaine passe en haut") { count(WEDNESDAY_7PM) > 0 }
    }

    @Test fun fin_du_match_regarde_un_autre_match_est_propose_regarder_le_lance() {
        nhl = week(
            game(1, "2026-11-17T00:30:00Z", "LIVE", "MTL", "BOS", 2, 1, period = 3),
            game(4, "2026-11-17T00:00:00Z", "LIVE", "EDM", "SEA", 0, 0, period = 2),
        )
        val v = SportView()
        val model = vm(v)
        show(model, v)
        toWatch()
        rule.ok()
        rule.until(what = "lecture") { log.contains("play") }
        // Le match des Canadiens se termine ; celui des Oilers continue.
        nhl = week(
            game(1, "2026-11-17T00:30:00Z", "OFF", "MTL", "BOS", 3, 1),
            game(4, "2026-11-17T00:00:00Z", "LIVE", "EDM", "SEA", 1, 0, period = 3),
        )
        model.refresh()
        rule.until(timeoutMs = 8_000, what = "proposition") { count("Match terminé. Oilers – Kraken est en direct (1-0") > 0 }
        rule.until(what = "focus sur Regarder", detail = { "${rule.focusedTexts()}" }) { focusedHas("Regarder") }
        val before = opened.size
        rule.ok()
        rule.until(what = "autre match lancé") { count("Match terminé") == 0 && opened.size > before }
    }

    @Test fun fin_du_match_non_merci_rien_ne_change_et_plus_de_proposition() {
        nhl = week(
            game(1, "2026-11-17T00:30:00Z", "LIVE", "MTL", "BOS", 2, 1, period = 3),
            game(4, "2026-11-17T00:00:00Z", "LIVE", "EDM", "SEA", 0, 0, period = 2),
        )
        val v = SportView()
        val model = vm(v)
        show(model, v)
        toWatch()
        rule.ok()
        rule.until(what = "lecture") { log.contains("play") }
        nhl = week(game(1, "2026-11-17T00:30:00Z", "OFF", "MTL", "BOS", 3, 1), game(4, "2026-11-17T00:00:00Z", "LIVE", "EDM", "SEA", 1, 0, period = 3))
        model.refresh()
        rule.until(timeoutMs = 8_000, what = "proposition") { count("Match terminé") > 0 }
        val before = opened.size
        rule.until(what = "focus sur Regarder", detail = { "${rule.focusedTexts()}" }) { focusedHas("Regarder") }
        rule.right()
        assertTrue(focusedHas("Non merci"), "${rule.focusedTexts()}")
        rule.ok()
        rule.until(what = "proposition fermée") { count("Match terminé") == 0 }
        assertTrue(opened.size == before, "rien n'a été lancé")
        // La lecture garde la main : ▶ change de chaîne comme avant.
        rule.until(what = "focus revenu à la lecture") { rule.focusedTexts() != null && !focusedHas("Non merci") }
        val zap = opened.size
        rule.right()
        rule.until(what = "chaîne suivante") { opened.size > zap }
        model.refresh()
        Thread.sleep(500)
        rule.waitForIdle()
        assertTrue(count("Match terminé") == 0, "une seule proposition par match")
    }

    @Test fun fin_du_match_sans_resultat_pas_de_score_dans_la_proposition() {
        nhl = week(
            game(1, "2026-11-17T00:30:00Z", "LIVE", "MTL", "BOS", 2, 1, period = 3),
            game(4, "2026-11-17T00:00:00Z", "LIVE", "EDM", "SEA", 0, 0, period = 2),
        )
        val v = SportView(hideScores = true)
        val model = vm(v)
        show(model, v)
        toWatch()
        rule.ok()
        rule.until(what = "lecture") { log.contains("play") }
        nhl = week(game(1, "2026-11-17T00:30:00Z", "OFF", "MTL", "BOS", 3, 1), game(4, "2026-11-17T00:00:00Z", "LIVE", "EDM", "SEA", 1, 0, period = 3))
        model.refresh()
        rule.until(timeoutMs = 8_000, what = "proposition") { count("Match terminé") > 0 }
        assertTrue(count("1-0") == 0, "aucun score")
        rule.until(what = "focus sur Regarder", detail = { "${rule.focusedTexts()}" }) { focusedHas("Regarder") }
        rule.back()
        rule.until(what = "fermée par Retour") { count("Match terminé") == 0 }
        assertTrue(log.count { it == "release" } == 0 || count("Canadiens") >= 0, "Retour ferme la proposition, pas la lecture")
        assertTrue(rule.onAllNodes(hasText("OÙ REGARDER")).fetchSemanticsNodes().isEmpty() && count("Regarder sur") == 0, "toujours dans la lecture")
    }

    @Test fun fin_du_match_retour_lent_ferme_la_proposition_mais_pas_la_lecture() {
        nhl = week(
            game(1, "2026-11-17T00:30:00Z", "LIVE", "MTL", "BOS", 2, 1, period = 3),
            game(4, "2026-11-17T00:00:00Z", "LIVE", "EDM", "SEA", 0, 0, period = 2),
        )
        val v = SportView()
        val model = vm(v)
        show(model, v)
        toWatch()
        rule.ok()
        rule.until(what = "lecture") { log.contains("play") }
        nhl = week(game(1, "2026-11-17T00:30:00Z", "OFF", "MTL", "BOS", 3, 1), game(4, "2026-11-17T00:00:00Z", "LIVE", "EDM", "SEA", 1, 0, period = 3))
        model.refresh()
        rule.until(timeoutMs = 8_000, what = "proposition") { count("Match terminé") > 0 }
        rule.until(what = "focus sur Regarder") { focusedHas("Regarder") }
        rule.slowPress(android.view.KeyEvent.KEYCODE_BACK)
        rule.until(what = "proposition fermée") { count("Match terminé") == 0 }
        Thread.sleep(200); rule.waitForIdle()
        assertTrue(count("OÙ REGARDER") == 0 && count("Regarder sur") == 0, "toujours dans la lecture")
        // Un deuxième Retour ferme la lecture, comme avant.
        rule.slowPress(android.view.KeyEvent.KEYCODE_BACK)
        rule.until(what = "retour à l'affiche") { count("Regarder sur") > 0 }
    }

    @Test fun fin_du_match_les_fleches_restent_dans_la_proposition() {
        nhl = week(
            game(1, "2026-11-17T00:30:00Z", "LIVE", "MTL", "BOS", 2, 1, period = 3),
            game(4, "2026-11-17T00:00:00Z", "LIVE", "EDM", "SEA", 0, 0, period = 2),
        )
        val v = SportView()
        val model = vm(v)
        show(model, v)
        toWatch()
        rule.ok()
        rule.until(what = "lecture") { log.contains("play") }
        nhl = week(game(1, "2026-11-17T00:30:00Z", "OFF", "MTL", "BOS", 3, 1), game(4, "2026-11-17T00:00:00Z", "LIVE", "EDM", "SEA", 1, 0, period = 3))
        model.refresh()
        rule.until(timeoutMs = 8_000, what = "proposition") { count("Match terminé") > 0 }
        rule.until(what = "focus sur Regarder") { focusedHas("Regarder") }
        val before = opened.size
        rule.down(); rule.up(); rule.left()
        assertTrue(focusedHas("Regarder"), "${rule.focusedTexts()}")
        rule.right(); rule.right()
        assertTrue(focusedHas("Non merci"), "${rule.focusedTexts()}")
        rule.left()
        assertTrue(focusedHas("Regarder"), "${rule.focusedTexts()}")
        assertTrue(opened.size == before, "les flèches ne changent pas de chaîne pendant la proposition")
        rule.slowPress(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        rule.until(what = "autre match lancé") { count("Match terminé") == 0 && opened.size > before }
    }

    private companion object {
        /** Mercredi 18 nov. 19 h à Montréal (= 19 nov. 0 h UTC), écrit dans le fuseau de la machine de test. */
        val WEDNESDAY_7PM: String = Instant.parse("2026-11-19T00:00:00Z").let { t ->
            countdownLabel(t, Instant.parse("2026-11-17T00:45:00Z"), java.util.TimeZone.getDefault().getOffset(t.toEpochMilliseconds()) / 1000)
        }
        val NAMES = mapOf("MTL" to "Canadiens", "BOS" to "Bruins", "EDM" to "Oilers", "SEA" to "Kraken", "TOR" to "Maple Leafs")
    }
}
