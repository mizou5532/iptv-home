package home.iptv.shared.sync

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaViewTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun lu_depuis_les_reglages_du_profil_avec_valeurs_par_defaut() {
        val s = json.decodeFromString(ProfileSettings.serializer(), """{"media_view":{"series":{"banner_mode":"fixe","banner_seconds":15,
            "banner_pinned":[{"id":"45","category":"7"}],"rows":[{"key":"ma_liste"},{"key":"reprendre","on":false}],"autre":1}}}""")
        val v = s.seriesView()
        assertTrue(v.fixed); assertEquals(15_000, v.rotateMs); assertEquals(listOf(PinnedTitle("45", "7")), v.bannerPinned)
        assertEquals(listOf("ma_liste", "nouveaux", "categories"), v.visibleRows(HomeRows.SERIES))
        assertEquals(MediaKindView(), s.moviesView())
        assertEquals(HomeRows.MOVIES, s.moviesView().visibleRows(HomeRows.MOVIES))
    }

    @Test fun ancienne_serie_epinglee_reprise_si_rien_n_est_epingle() {
        val old = ProfileSettings(pinnedSeries = "45692", pinnedSeriesCategory = "519")
        assertEquals(listOf(PinnedTitle("45692", "519")), old.seriesView().bannerPinned)
        val both = old.copy(mediaView = MediaView(series = MediaKindView(bannerPinned = listOf(PinnedTitle("1")))))
        assertEquals(listOf(PinnedTitle("1")), both.seriesView().bannerPinned)
    }

    @Test fun sources_et_bornes() {
        assertEquals(BannerSources.SERIES, MediaKindView().sources(BannerSources.SERIES))
        assertEquals(listOf("ma_liste"), MediaKindView(bannerSources = listOf("ma_liste", "ma_liste", "x")).sources(BannerSources.SERIES))
        assertEquals(emptyList(), MediaKindView(bannerSources = emptyList()).sources(BannerSources.MOVIES))
        assertEquals(3_000, MediaKindView(bannerSeconds = 0).rotateMs)
        assertEquals(10, MediaKindView(bannerMax = 99).maxTitles)
    }
}
