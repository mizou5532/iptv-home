package home.iptv.tv.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FrameRateMatchTest {
    private val m60 = DisplayModeInfo(1, 3840, 2160, 60f)
    private val m5994 = DisplayModeInfo(2, 3840, 2160, 59.94f)
    private val m24 = DisplayModeInfo(3, 3840, 2160, 24f)
    private val m23976 = DisplayModeInfo(4, 3840, 2160, 23.976f)
    private val m50 = DisplayModeInfo(5, 3840, 2160, 50f)
    private val m120 = DisplayModeInfo(6, 3840, 2160, 120f)
    private val hd24 = DisplayModeInfo(7, 1920, 1080, 24f)
    private val tcl = listOf(m60, m5994, m24, m23976, m50)

    @Test fun film_24_images_passe_en_24_hz_et_23_976_en_23_976() {
        assertEquals(m24, bestModeFor(24f, m60, tcl))
        assertEquals(m23976, bestModeFor(23.976f, m60, tcl))
    }

    @Test fun serie_europeenne_25_images_passe_en_50_hz() {
        assertEquals(m50, bestModeFor(25f, m60, tcl))
    }

    @Test fun video_a_60_ou_30_images_reste_en_60_hz() {
        assertNull(bestModeFor(60f, m60, tcl))
        assertNull(bestModeFor(30f, m60, tcl))
        assertNull(bestModeFor(29.97f, m5994, tcl))
    }

    @Test fun deja_adapte_ou_multiple_on_ne_touche_a_rien() {
        assertNull(bestModeFor(24f, m24, tcl))
        assertNull(bestModeFor(24f, m120, tcl + m120), "120 Hz contient 24 images entières")
    }

    @Test fun jamais_une_autre_definition_ni_une_cadence_inconnue() {
        assertNull(bestModeFor(24f, m60, listOf(m60, hd24)), "pas de passage en 1080p")
        assertNull(bestModeFor(0f, m60, tcl))
        assertNull(bestModeFor(Float.NaN, m60, tcl))
    }

    @Test fun ecran_sans_mode_24_hz_reste_tel_quel() {
        assertNull(bestModeFor(24f, m60, listOf(m60, m5994)))
    }
}
