package home.iptv.tv.live

import home.iptv.shared.sync.HiddenItem
import home.iptv.shared.sync.PlaylistPrefs
import home.iptv.shared.sync.ProfilePrefs
import home.iptv.shared.xtream.LiveCategory
import home.iptv.shared.xtream.LiveStream
import kotlin.test.Test
import kotlin.test.assertEquals

private val CATS = listOf(
    LiveCategory("a", "Sport"), LiveCategory("b", "Cine"), LiveCategory("c", "Info"), LiveCategory("d", "Kids"),
)

private fun prefs(
    hidden: List<HiddenItem> = emptyList(),
    favorites: List<String> = emptyList(),
    playlists: List<PlaylistPrefs> = emptyList(),
    order: List<String>? = null,
) = ProfilePrefs(1, hidden, favorites, playlists, order)

private fun s(id: String, cat: String) = LiveStream(id, "Chaine $id", cat, null, null, null)

class SectionsTest {
    @Test
    fun sans_preferences_ordre_fournisseur_sans_favoris() {
        val expected = listOf("cat:a", "cat:b", "cat:c", "cat:d")
        assertEquals(expected, buildSections(CATS, null).map { it.key })
        assertEquals(expected, buildSections(CATS, prefs()).map { it.key })
        assertEquals(expected, buildSections(CATS, prefs(order = emptyList())).map { it.key })
    }

    @Test
    fun favoris_puis_playlists_puis_categories() {
        val p = prefs(
            favorites = listOf("1"),
            playlists = listOf(PlaylistPrefs("p2", "Soir", listOf("1")), PlaylistPrefs("p1", "Matin", emptyList())),
        )
        val sections = buildSections(CATS, p)
        assertEquals(listOf("fav", "pl:p2", "pl:p1", "cat:a", "cat:b", "cat:c", "cat:d"), sections.map { it.key })
        assertEquals(listOf("Favoris", "Soir", "Matin", "Sport"), sections.take(4).map { it.title })
    }

    @Test
    fun ordre_profil_et_nouvelles_categories_a_la_fin() {
        val p = prefs(order = listOf("c", "a"))
        assertEquals(listOf("cat:c", "cat:a", "cat:b", "cat:d"), buildSections(CATS, p).map { it.key })
    }

    @Test
    fun ordre_ignore_les_identifiants_inconnus_et_les_doublons() {
        val p = prefs(order = listOf("zz", "c", "c", "a"))
        assertEquals(listOf("cat:c", "cat:a", "cat:b", "cat:d"), buildSections(CATS, p).map { it.key })
    }

    @Test
    fun categorie_masquee_globale_ou_profil_absente() {
        val p = prefs(hidden = listOf(HiddenItem("live_category", "b"), HiddenItem("live_category", "d")), order = listOf("d", "c"))
        assertEquals(listOf("cat:c", "cat:a"), buildSections(CATS, p).map { it.key })
    }

    @Test
    fun les_elements_d_un_autre_type_sont_ignores() {
        val p = prefs(hidden = listOf(HiddenItem("vod_category", "a"), HiddenItem("channel", "a")))
        assertEquals(4, buildSections(CATS, p).size)
    }

    @Test
    fun chaine_masquee_absente_des_favoris_et_categories() {
        val p = prefs(hidden = listOf(HiddenItem("channel", "2"), HiddenItem("live_category", "z")))
        val streams = listOf(s("1", "a"), s("2", "a"), s("3", "b"))
        assertEquals(listOf("1", "3"), visibleStreams(streams, p).map { it.id })
        assertEquals(streams, visibleStreams(streams, null))
    }

    @Test
    fun chaine_d_une_categorie_masquee_est_absente() {
        val p = prefs(hidden = listOf(HiddenItem("live_category", "b")))
        assertEquals(listOf("1"), visibleStreams(listOf(s("1", "a"), s("3", "b")), p).map { it.id })
    }

    @Test
    fun repli_de_la_section_d_origine_favoris_sinon_premiere_categorie() {
        val cats = listOf(LiveSection.Playlist("p", "Ma liste"), LiveSection.Category("1", "Sport"), LiveSection.Category("2", "Films"))
        assertEquals("cat:1", fallbackSectionKey(cats))
        assertEquals("fav", fallbackSectionKey(listOf(LiveSection.Favorites()) + cats))
        assertEquals(null, fallbackSectionKey(emptyList()))
    }

    @Test
    fun cles_de_regroupement_gardees_donnent_le_meme_resultat_que_le_calcul_complet() {
        val names = listOf("US: ESPN HD", "US| ESPN FHD", "ESPN 4K", "RDS", "RDS 2 HD", "##### AT&T #####", "HD", "TSN 1 (Backup)",
            "TSN 1", "beIN Sports 1 ᴴᴰ", "CA FR: TVA Sports", "AT&T: AMC", "US: AMC HD", "Chaîne\u0001bizarre", "", "  ")
        val countries = listOf(null, "US", "CA", "FR")
        for (n in names) for (c in countries) repeat(2) {
            kotlin.test.assertEquals(home.iptv.shared.groups.autoKey(n, c), cachedAutoKey(n, c), "$n / $c")
        }
        val sources = names.mapIndexed { i, n -> home.iptv.shared.groups.GroupSource("s$i", "c${i % 3}", n, i) }
        val cats = mapOf("c0" to "US", "c1" to "CA")
        val snap = home.iptv.shared.groups.GroupSnapshot(0, 1, emptyList())
        val full = home.iptv.shared.groups.buildGroups(sources, snap) { cats[it.categoryId] }
        repeat(2) { kotlin.test.assertEquals(full.associateBy { it.id }, groupSourceIndex(sources, snap, true, cats).values.associateBy { it.id }) }
    }
}
