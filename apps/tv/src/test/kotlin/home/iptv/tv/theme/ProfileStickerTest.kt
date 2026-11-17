package home.iptv.tv.theme

import androidx.compose.ui.unit.dp
import home.iptv.shared.sync.ProfileSettings
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileStickerTest {
    @Test fun pas_d_autocollant_sans_reglage() {
        assertNull(stickerLook(ProfileSettings()))
        assertNull(stickerLook(ProfileSettings(hasSticker = false, stickerVersion = 5)))
    }

    @Test fun coin_et_taille_lus_depuis_la_page() {
        val json = Json { ignoreUnknownKeys = true }
        val s = json.decodeFromString(ProfileSettings.serializer(),
            """{"sticker":true,"sticker_v":1700,"sticker_corner":"bas-gauche","sticker_size":"grand"}""")
        assertEquals(StickerLook(left = true, size = 200.dp), stickerLook(s))
        assertEquals(StickerLook(left = false, size = 140.dp), stickerLook(ProfileSettings(hasSticker = true)), "par défaut : bas à droite, moyen")
        assertEquals(96.dp, stickerLook(ProfileSettings(hasSticker = true, stickerSize = "petit"))!!.size)
        assertTrue(stickerUrl("p1", 1700).endsWith("/web/stickers/p1.webp?v=1700"))
    }

    @Test fun fond_d_ecran_et_son_voile() {
        assertNull(wallpaperVeil(ProfileSettings()))
        assertNull(wallpaperVeil(ProfileSettings(hasWallpaper = false)))
        assertEquals(0.4f, wallpaperVeil(ProfileSettings(hasWallpaper = true)), "voile par défaut 40 %")
        assertEquals(0.8f, wallpaperVeil(ProfileSettings(hasWallpaper = true, wallpaperDim = 95)), "au plus 80 %")
        assertEquals(0f, wallpaperVeil(ProfileSettings(hasWallpaper = true, wallpaperDim = 0)))
        assertTrue(wallpaperUrl("p1", 3).endsWith("/web/wallpapers/p1.webp?v=3"))
    }
}
