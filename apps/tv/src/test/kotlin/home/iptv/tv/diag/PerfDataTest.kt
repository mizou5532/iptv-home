package home.iptv.tv.diag

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Rapports de lenteur : mesures gardées, résumé, et JAMAIS d'adresse ni d'identifiant. */
class PerfDataTest {
    private var now = 1_795_000_000_000L
    private fun data() = PerfData { now }

    @Test fun types_de_demandes_sans_jamais_l_adresse_du_fournisseur() {
        assertEquals("fournisseur" to "get_live_streams", PerfData.classify("monfournisseur.example.test", "/player_api.php", "get_live_streams"))
        assertEquals("fournisseur" to "video", PerfData.classify("monfournisseur.example.test", "/live/utilisateur/motdepasse/123.ts", null))
        assertEquals("fournisseur" to "video", PerfData.classify("x.test", "/movie/u/p/9.MKV", null))
        assertEquals("fournisseur" to "image", PerfData.classify("logos.test", "/a/b/logo.png", null))
        assertEquals("fournisseur" to "autre", PerfData.classify("x.test", "/get.php", null))
        assertEquals("serveur" to "/rest/v1/rpc/prefs_rev", PerfData.classify("abc.supabase.co", "/rest/v1/rpc/prefs_rev", null))
        assertEquals("serveur", PerfData.classify("abc.supabase.co", "/storage/v1/object/public/web/avatars/0e3c5a2b-1111-2222-3333-444455556666.webp", null).first)
        assertFalse(PerfData.classify("abc.supabase.co", "/storage/v1/object/public/web/avatars/0e3c5a2b-1111-2222-3333-444455556666.webp", null).second.contains("0e3c5a2b"))
        assertEquals("sport", PerfData.classify("site.api.espn.com", "/apis/site/v2/sports/hockey/nhl/scoreboard", null).first)
        assertEquals("images" to "-", PerfData.classify("image.tmdb.org", "/t/p/w500/x.jpg", null))
        assertEquals("fiches" to "-", PerfData.classify("api.themoviedb.org", "/3/search/movie", null))
    }

    @Test fun rapport_complet_et_rien_de_secret() {
        val d = data()
        d.screen = "Sport · lecture"
        d.frame(16); d.frame(40); d.frame(150); d.frame(900)
        d.stall(3_200, listOf("home.iptv.tv.sport.SportViewModel.rebuild(SportData.kt:612)", "android.os.Handler.dispatchMessage"))
        d.key("DPAD_DOWN", 1_400, 120)
        d.key("DPAD_DOWN", 20, 5)
        d.network("fournisseur", "get_live_streams", 3_500, "ok")
        d.network("fournisseur", "video", 120, "SocketTimeoutException")
        d.network("serveur", "/rest/v1/rpc/prefs_rev", 40, "ok")
        val w = d.workStart("Index des versions (films)"); now += 2_500; d.workEnd(w)
        d.workStart("Mise à jour des chaînes du Direct")
        d.event("Mémoire réclamée par la télé (niveau 15)")
        d.memory(PerfData.Memory(now, 180, 256, 40, 300, false, 12, 900, 2, 60))
        val r = d.report("manuel", "Signalé sur la télé", mapOf("appareil" to JsonObject(mapOf("modele" to JsonPrimitive("TCL")))))
        val text = r.toString()
        for (secret in listOf("monfournisseur", "utilisateur", "motdepasse", "player_api", "http")) assertFalse(secret in text, "jamais « $secret » dans un rapport")
        assertEquals("manuel", r["rapport"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("TCL", r["appareil"]!!.jsonObject["modele"]!!.jsonPrimitive.content)
        val images = r["images"]!!.jsonObject
        assertEquals(4, images["total"]!!.jsonPrimitive.long); assertEquals(3, images["plus_33ms"]!!.jsonPrimitive.long)
        assertEquals(2, images["plus_100ms"]!!.jsonPrimitive.long); assertEquals(1, images["plus_500ms"]!!.jsonPrimitive.long)
        assertEquals(900, images["pire_ms"]!!.jsonPrimitive.long)
        val stall = r["blocages"]!!.jsonArray.single().jsonObject
        assertEquals(3_200, stall["duree_ms"]!!.jsonPrimitive.long)
        assertEquals("Sport · lecture", stall["ecran"]!!.jsonPrimitive.content)
        assertTrue(stall["pile"]!!.jsonArray.first().jsonPrimitive.content.contains("SportViewModel"))
        val keys = r["touches"]!!.jsonObject
        assertEquals(2, keys["nombre"]!!.jsonPrimitive.long)
        assertEquals(1, keys["lentes"]!!.jsonArray.size, "seule la touche lente est détaillée")
        val net = r["reseau"]!!.jsonObject
        val provider = net["par_type"]!!.jsonObject["fournisseur"]!!.jsonObject
        assertEquals(2, provider["demandes"]!!.jsonPrimitive.long); assertEquals(1, provider["erreurs"]!!.jsonPrimitive.long)
        assertEquals(1, provider["lentes_2s"]!!.jsonPrimitive.long); assertEquals(3_500, provider["max_ms"]!!.jsonPrimitive.long)
        assertEquals(2, net["lentes_ou_en_erreur"]!!.jsonArray.size, "la lente et celle en erreur ; pas la rapide")
        val works = r["travaux"]!!.jsonObject
        assertEquals("Mise à jour des chaînes du Direct", works["en_cours"]!!.jsonArray.single().jsonObject["nom"]!!.jsonPrimitive.content)
        assertEquals(2_500, works["recents"]!!.jsonArray.single().jsonObject["ms"]!!.jsonPrimitive.long)
        assertEquals(1, r["memoire_historique"]!!.jsonArray.size)
        assertTrue(r["evenements"]!!.jsonArray.any { it.jsonObject["texte"]!!.jsonPrimitive.content.contains("Mémoire réclamée") })
    }

    @Test fun resume_d_une_ligne_et_remise_a_zero_apres_envoi() {
        val d = data()
        d.screen = "Direct · liste"
        d.stall(1_200, emptyList()); d.stall(4_100, emptyList())
        d.frame(800)
        repeat(20) { d.key("DPAD_DOWN", 10, 5) }
        val s = d.summary("Signalé sur la télé")
        assertTrue(s.startsWith("Signalé sur la télé"))
        assertTrue("2 blocage(s), pire 4100 ms" in s, s)
        assertTrue("1 image(s) > 0,5 s" in s, s)
        assertTrue(s.endsWith("Direct · liste"), s)
        d.resetCounters()
        val r = d.report("manuel", "x", emptyMap())
        assertEquals(0, r["blocages"]!!.jsonArray.size)
        assertEquals(0, r["images"]!!.jsonObject["total"]!!.jsonPrimitive.long)
        assertEquals(0, r["touches"]!!.jsonObject["nombre"]!!.jsonPrimitive.long)
    }

    @Test fun listes_bornees() {
        val d = data()
        repeat(100) { d.stall(800, List(100) { i -> "frame$i" }) }
        repeat(1000) { d.key("DPAD_DOWN", 600, 0) }
        repeat(500) { d.event("e$it") }
        val r = d.report("auto", "x", emptyMap())
        assertEquals(20, r["blocages"]!!.jsonArray.size)
        assertEquals(PerfData.STACK_MAX, (r["blocages"]!!.jsonArray.first().jsonObject["pile"] as JsonArray).size)
        assertEquals(300, r["touches"]!!.jsonObject["nombre"]!!.jsonPrimitive.long)
        assertEquals(40, r["touches"]!!.jsonObject["lentes"]!!.jsonArray.size)
        assertEquals(120, r["evenements"]!!.jsonArray.size)
        assertTrue(r.toString().length < 800_000, "toujours sous la limite du serveur")
    }

    @Test fun message_d_erreur_sans_adresse_ni_identifiants() {
        assertNull(safeMessage("Unable to resolve host monfournisseur.test: http://monfournisseur.test/get.php", emptyList()))
        assertNull(safeMessage("utilisateur@monfournisseur", emptyList()))
        assertNull(safeMessage("/player_api.php?username=a&password=b", emptyList()))
        assertEquals("Failed to allocate a 1048576 byte allocation", safeMessage("Failed to allocate a 1048576 byte allocation", emptyList()))
        assertEquals("compte *** refusé", safeMessage("compte jean123 refusé", listOf("jean123")))
        assertNull(safeMessage(null, emptyList()))
        assertEquals(200, safeMessage("x".repeat(500), emptyList())!!.length)
    }

    @Test fun libelles_du_bouton() {
        assertEquals("Signaler une lenteur", home.iptv.tv.live.reportLabel(ReportState.Idle))
        assertEquals("Envoi du rapport…", home.iptv.tv.live.reportLabel(ReportState.Sending))
        assertEquals("Rapport n° 12 envoyé ✓", home.iptv.tv.live.reportLabel(ReportState.Sent(12)))
        assertEquals("Rapport gardé, envoyé plus tard", home.iptv.tv.live.reportLabel(ReportState.Queued))
    }
}
