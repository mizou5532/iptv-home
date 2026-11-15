package home.iptv.tv.diag

import android.view.KeyEvent.KEYCODE_0
import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import android.view.KeyEvent.KEYCODE_DPAD_LEFT
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import android.view.KeyEvent.KEYCODE_DPAD_UP
import android.view.KeyEvent.KEYCODE_INFO
import android.view.KeyEvent.KEYCODE_MENU
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretComboTest {
    @Test
    fun trois_appuis_info_rapproches_ouvrent_le_calque() {
        val c = SecretCombo()
        assertFalse(c.onKeyDown(KEYCODE_INFO, 0))
        assertFalse(c.onKeyDown(KEYCODE_INFO, 500))
        assertTrue(c.onKeyDown(KEYCODE_INFO, 1_000))
    }

    @Test
    fun trop_lents_ou_melanges_ne_comptent_pas() {
        val c = SecretCombo()
        c.onKeyDown(KEYCODE_MENU, 0); c.onKeyDown(KEYCODE_MENU, 1_500)
        assertFalse(c.onKeyDown(KEYCODE_MENU, 3_000), "plus de 2 s entre le premier et le troisième")
        c.onKeyDown(KEYCODE_0, 4_000); c.onKeyDown(KEYCODE_MENU, 4_100)
        assertFalse(c.onKeyDown(KEYCODE_0, 4_200), "touches mélangées")
        c.onKeyDown(KEYCODE_0, 8_000); c.onKeyDown(KEYCODE_0, 8_100)
        assertTrue(c.onKeyDown(KEYCODE_0, 8_200))
    }

    @Test
    fun la_suite_des_fleches_ouvre_aussi_le_calque_mais_pas_une_navigation_ordinaire() {
        val c = SecretCombo()
        val seq = listOf(KEYCODE_DPAD_UP, KEYCODE_DPAD_UP, KEYCODE_DPAD_DOWN, KEYCODE_DPAD_DOWN, KEYCODE_DPAD_LEFT, KEYCODE_DPAD_RIGHT, KEYCODE_DPAD_LEFT, KEYCODE_DPAD_RIGHT)
        val results = seq.mapIndexed { i, k -> c.onKeyDown(k, i * 300L) }
        assertEquals(listOf(false, false, false, false, false, false, false, true), results)
        // Navigation ordinaire : bas, bas, droite, droite… rien.
        val nav = listOf(KEYCODE_DPAD_DOWN, KEYCODE_DPAD_DOWN, KEYCODE_DPAD_RIGHT, KEYCODE_DPAD_RIGHT, KEYCODE_DPAD_UP, KEYCODE_DPAD_LEFT)
        assertTrue(nav.mapIndexed { i, k -> c.onKeyDown(k, 10_000 + i * 200L) }.none { it })
    }
}
