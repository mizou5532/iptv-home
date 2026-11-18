package home.iptv.tv.ui

import androidx.compose.ui.test.hasText
import home.iptv.shared.sync.AccountClaim
import home.iptv.shared.sync.Occupant
import home.iptv.tv.player.AccountLeases
import home.iptv.tv.player.PlayerController
import home.iptv.tv.player.TakeOverDialog
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** « Quelqu'un regarde déjà » à la télécommande : Laisser regarder par défaut, Prendre la main, Retour, plusieurs télés. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(qualifiers = "w960dp-h540dp-xhdpi")
class TakeOverDpadTest {
    @get:Rule val rule = tvRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val log = mutableListOf<String>()
    private val claims = mutableListOf<String?>()
    @Volatile private var answer: AccountClaim = AccountClaim.Busy(listOf(Occupant("Télé du salon", "Maha", "Série · Lost · S1 É2", "s7")))
    private val leases = AccountLeases(scope, claim = { _, takeover -> claims += takeover; if (takeover != null) AccountClaim.Granted("s8", 1, replaced = takeover) else answer },
        heartbeat = { _, _ -> true }, release = {}, account = { null }, takeoverDone = { true })
    private val player = PlayerController(leases, { null }, scope) { FakePlayer(log) }
    private val refused = mutableListOf<String>()
    @After fun tearDown() { scope.cancel() }

    private fun count(text: String) = rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size
    private fun focusedHas(text: String) = rule.topFocusedTexts()?.any { it.contains(text) } == true
    private fun show() {
        player.onRefused("test") { refused += it }
        rule.setTvContent { TakeOverDialog(player) }
        rule.runOnUiThread { player.open("fake://direct", label = "Direct · RDS") }
        rule.until(what = "fenêtre") { count("Quelqu’un regarde déjà") > 0 }
    }

    @Test fun laisser_regarder_choisi_au_depart_ok_ne_coupe_personne() {
        show()
        assertTrue(count("Maha regarde « Série · Lost · S1 É2 » sur Télé du salon.") > 0)
        rule.until(what = "focus sur Laisser regarder") { focusedHas("Laisser regarder") }
        rule.dialogOk()
        rule.until(what = "fenêtre fermée") { count("Quelqu’un regarde déjà") == 0 }
        assertEquals(listOf<String?>(null), claims, "aucune prise de main")
        assertTrue(log.none { it == "play" }, "rien n'est lu")
        assertEquals(listOf("D’accord : Maha continue de regarder."), refused)
    }

    @Test fun prendre_la_main_lance_la_video() {
        show()
        rule.until(what = "focus") { focusedHas("Laisser regarder") }
        rule.dialogDown()
        assertTrue(focusedHas("Prendre la main"), "${rule.topFocusedTexts()}")
        rule.dialogOk()
        rule.until(what = "lecture") { log.contains("play") }
        assertEquals(listOf(null, "s7"), claims)
        assertTrue(count("Quelqu’un regarde déjà") == 0)
    }

    @Test fun retour_laisse_regarder() {
        show()
        rule.dialogBack()
        rule.until(what = "fermée") { count("Quelqu’un regarde déjà") == 0 }
        assertTrue(log.none { it == "play" })
        assertEquals(1, refused.size)
    }

    @Test fun plusieurs_teles_un_bouton_par_tele() {
        answer = AccountClaim.Busy(listOf(Occupant("Télé du salon", "Maha", "Film · Dune", "s7"), Occupant("Fire Stick", null, "", "s9")))
        show()
        assertTrue(count("Prendre la main sur Télé du salon") > 0 && count("Prendre la main sur Fire Stick") > 0)
        assertTrue(count("Quelqu’un regarde sur Fire Stick.") > 0)
        rule.until(what = "focus") { focusedHas("Laisser regarder") }
        rule.dialogDown(); rule.dialogDown()
        assertTrue(focusedHas("Fire Stick"), "${rule.topFocusedTexts()}")
        rule.dialogOk()
        rule.until(what = "lecture") { log.contains("play") }
        assertEquals("s9", claims.last())
    }
}
