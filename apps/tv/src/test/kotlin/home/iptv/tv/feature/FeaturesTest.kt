package home.iptv.tv.feature

import home.iptv.shared.sync.MediaKindView
import home.iptv.shared.xtream.MovieSummary
import home.iptv.shared.xtream.SeriesInfo
import home.iptv.shared.xtream.SeriesSummary
import home.iptv.tv.movies.MovieCard
import home.iptv.tv.movies.MovieHome
import home.iptv.tv.series.EpisodeRef
import home.iptv.tv.series.ResumeItem
import home.iptv.tv.series.ResumeState
import home.iptv.tv.series.SeriesCard
import home.iptv.tv.series.SeriesHome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeaturesTest {
    private fun s(id: String, cat: String = "c", rating: String? = null, backdrop: String? = null) =
        SeriesSummary(id, cat, "Série $id", "http://img/$id.jpg", "2024", "Drame, Policier", "Résumé $id", rating, backdrop)
    private fun m(id: String, cat: String = "c", rating: String? = null) =
        MovieSummary(id, cat, "Film $id", "http://img/$id.jpg", "2023", "Action", "Résumé", rating, null, "mkv", 6300)

    private val resume = ResumeItem("a", "c", "Série a", "http://img/a.jpg", ResumeState.InProgress, EpisodeRef("e1", 2, 4, "La serre"), 600, 2400, null)

    @Test fun series_order_resume_new_picks_list_without_duplicates() {
        val home = SeriesHome(resume, listOf(resume), listOf(SeriesCard("a", "c", "Série a", null), SeriesCard("l", "c", "Série l", null)), listOf(SeriesCard("n", "c", "Série n", null)), emptyList())
        val infos = mapOf("a" to SeriesInfo(s("a", backdrop = "http://img/fond-a.jpg"), emptyList()))
        val f = buildSeriesFeatures(home, listOf(Pick(s("p"), "Série a")), infoOf = infos::get)
        assertEquals(listOf("a", "n", "p", "l"), f.map { it.itemId })
        assertEquals("REPRENDRE", f[0].reason)
        assertEquals("http://img/fond-a.jpg", f[0].backdrop)
        assertEquals("S2 · É4 · 30 min restantes", f[0].meta)
        assertEquals(0.25f, f[0].progress)
        assertEquals("PARCE QUE TU REGARDES « SÉRIE A »", f[2].reason)
        assertEquals("2024 · Drame", f[2].meta)
    }

    @Test fun series_up_to_date_is_not_a_banner_and_cap_is_five() {
        val upToDate = resume.copy(state = ResumeState.UpToDate, episode = null)
        val home = SeriesHome(upToDate, listOf(upToDate), (1..9).map { SeriesCard("l$it", "c", "L$it", null) }, emptyList(), emptyList())
        val f = buildSeriesFeatures(home, emptyList()) { null }
        assertEquals(MAX_FEATURES, f.size)
        assertTrue(f.none { it.itemId == "a" })
        assertTrue(f.all { it.resume == null && it.reason == "DANS TA LISTE" })
    }

    @Test fun movies_resume_then_picks_then_list_then_recent() {
        val cont = MovieCard("r", "c", "Film r", null, positionS = 1800, durationS = 7200)
        val home = MovieHome(cont, listOf(cont), listOf(MovieCard("l", "c", "Film l", null)), listOf(MovieCard("n", "c", "Film n", null)), emptyList())
        val f = buildMovieFeatures(home, listOf(Pick(m("p", rating = "7.5"), "Film r"))) { null }
        assertEquals(listOf("r", "p", "l", "n"), f.map { it.itemId })
        assertEquals(1800, f[0].startS)
        assertEquals("90 min restantes", f[0].meta)
        assertEquals("2023 · 1 h 45 · Action · Note 7,5", f[1].meta)
        assertTrue(f.all { it.isMovie })
        assertNull(f[2].progress)
    }

    @Test fun picks_take_best_rated_of_watched_categories_and_skip_started() {
        val lists = mapOf("x" to listOf(s("1", "x", "6"), s("2", "x", "9"), s("3", "x", "8")), "y" to listOf(s("4", "y", "5")))
        val p = pickFrom(listOf("x" to "Série a", "y" to "Série b"), { lists[it].orEmpty() }, { it.id }, { ratingOf(it.rating) }, setOf("2"), null)
        assertEquals(listOf("3", "1", "4"), p.map { it.item.id })
        assertEquals(listOf("Série a", "Série a", "Série b"), p.map { it.because })
        // Rien en cours : la première catégorie de l'accueil, sans « parce que ».
        val q = pickFrom(emptyList<Pair<String, String>>(), { lists[it].orEmpty() }, { it.id }, { ratingOf(it.rating) }, emptySet(), "y")
        assertEquals(listOf("4"), q.map { it.item.id }); assertNull(q.single().because)
    }

    @Test fun pinned_series_shows_progress_or_its_page() {
        val home = SeriesHome(resume, listOf(resume), emptyList(), emptyList(), emptyList())
        val info = SeriesInfo(s("a", backdrop = "http://img/fond-a.jpg"), emptyList())
        val f = pinnedSeriesFeature(home, "a", "c", info)!!
        assertEquals("TU EN ES À", f.reason)
        assertEquals("Saison 2 · Épisode 4 · 30 min restantes", f.meta)
        assertEquals(0.25f, f.progress)
        assertEquals("Résumé a", f.plot)
        // Pas encore commencée : sa fiche, sans progression.
        val g = pinnedSeriesFeature(SeriesHome.EMPTY, "z", "c", SeriesInfo(s("z"), emptyList()))!!
        assertEquals("TA SÉRIE", g.reason); assertNull(g.progress); assertNull(g.resume)
        // Rien de connu : null (la bannière normale reste).
        assertNull(pinnedSeriesFeature(SeriesHome.EMPTY, "z", "c", null))
    }

    @Test fun banner_follows_profile_sources_order_max_and_never() {
        val home = SeriesHome(resume, listOf(resume), listOf(SeriesCard("l", "c", "Série l", null), SeriesCard("l2", "c", "Série l2", null)), listOf(SeriesCard("n", "c", "Série n", null)), emptyList())
        val picks = listOf(Pick(s("p"), "Série a"), Pick(s("q"), "Série a"))
        // Ma liste d'abord, puis reprendre ; pas de suggestions ni de nouveaux épisodes ; « l2 » à ne plus proposer.
        val view = MediaKindView(bannerSources = listOf("ma_liste", "reprendre", "inconnu"), never = listOf("l2"))
        assertEquals(listOf("l", "a"), buildSeriesFeatures(home, picks, view) { null }.map { it.itemId })
        // Au plus 2 titres, sources par défaut.
        assertEquals(listOf("a", "n"), buildSeriesFeatures(home, picks, MediaKindView(bannerMax = 2)) { null }.map { it.itemId })
        // Films : nouveautés seules.
        val cont = MovieCard("r", "c", "Film r", null, positionS = 1800, durationS = 7200)
        val mh = MovieHome(cont, listOf(cont), listOf(MovieCard("l", "c", "Film l", null)), listOf(MovieCard("n", "c", "Film n", null)), emptyList())
        assertEquals(listOf("n"), buildMovieFeatures(mh, emptyList(), MediaKindView(bannerSources = listOf("nouveautes"))) { null }.map { it.itemId })
    }

    @Test fun pinned_movie_resumes_or_plays() {
        val cont = MovieCard("r", "c", "Film r", null, positionS = 1800, durationS = 7200)
        val mh = MovieHome(cont, listOf(cont), listOf(MovieCard("l", "c", "Film l", null)), emptyList(), emptyList())
        val r = pinnedMovieFeature(mh, "r", null)!!
        assertEquals("REPRENDRE", r.reason); assertEquals(1800, r.startS); assertEquals(0.25f, r.progress)
        val l = pinnedMovieFeature(mh, "l", m("l"))!!
        assertEquals("TON FILM", l.reason); assertEquals(0, l.startS); assertEquals("Résumé", l.plot)
        assertNull(pinnedMovieFeature(mh, "inconnu", null))
    }
}
