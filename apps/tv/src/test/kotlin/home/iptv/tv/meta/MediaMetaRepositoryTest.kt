package home.iptv.tv.meta

import home.iptv.shared.meta.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Fiches TMDB : demandes regroupées, réponses gardées, « en attente » redemandées, rien de redemandé inutilement. */
class MediaMetaRepositoryTest {
    private class MemStore(var saved: Map<String, MediaMeta> = emptyMap()) : MediaMetaStore {
        override fun load() = saved
        override fun save(all: Map<String, MediaMeta>) { saved = all }
    }
    private val dune = MediaRef(MediaKind.MOVIE, "FR| Dune (2021)")
    private val bb = MediaRef(MediaKind.SERIES, "Breaking Bad")

    @Test fun lot_unique_puis_memoire_et_disque() = runBlocking {
        val calls = mutableListOf<List<String>>()
        val store = MemStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = MediaMetaRepository({ refs -> calls += refs.map { it.title }; refs.map { MediaMeta("ok", title = it.title.uppercase(), imdbRating = 8.0) } }, store, scope, debounceMs = 10)
            repo.request(dune); repo.request(bb); repo.request(dune)
            withTimeout(3000) { repo.state.first { it.size == 2 } }
            assertEquals(1, calls.size, "un seul appel pour les deux titres")
            assertEquals("BREAKING BAD", repo.state.value[bb.key]?.title)
            assertEquals(2, store.saved.size, "gardées sur le disque")
            repo.request(dune); delay(100)
            assertEquals(1, calls.size, "fiche récente : pas redemandée")
            // Une nouvelle télé (redémarrage) relit le disque sans rien demander.
            val again = MediaMetaRepository({ error("pas d'appel") }, store, scope, debounceMs = 10)
            again.request(bb); delay(100)
            assertEquals("BREAKING BAD", again.state.value[bb.key]?.title)
        } finally { scope.cancel() }
    }

    @Test fun en_attente_redemandee_puis_trouvee() = runBlocking {
        var n = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = MediaMetaRepository({ refs -> n++; refs.map { if (n == 1) MediaMeta("pending") else MediaMeta("ok", backdrop = "/b.jpg") } },
                MemStore(), scope, debounceMs = 10, retryMs = 50)
            repo.request(dune)
            val m = withTimeout(3000) { repo.state.first { it[dune.key] != null } }[dune.key]!!
            assertEquals("/b.jpg", m.backdrop); assertTrue(n >= 2)
            assertEquals("https://image.tmdb.org/t/p/w780/b.jpg", tmdbImage(m.backdrop, "w780"))
            assertEquals("IMDb 8,4", imdbLabel(8.4))
        } finally { scope.cancel() }
    }

    @Test fun lecture_immediate_du_disque_des_la_premiere_image_et_zero_remplacement() {
        val saved = mapOf(bb.key to MediaMeta("ok", title = "Breaking Bad", poster = "/bb.jpg", backdrop = "/bb-bg.jpg"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = MediaMetaRepository({ error("aucun appel réseau attendu") }, MemStore(saved), scope, debounceMs = 10)
            // Dès le tout premier appel synchrone (avant tout LaunchedEffect), la fiche du disque est déjà disponible.
            assertEquals("/bb.jpg", repo.peek(bb.key)?.poster)
            assertTrue(bb.key in repo.tried.value)
        } finally { scope.cancel() }

        // 1. Tant qu'on attend la réponse officielle (useFallback = false), l'image fournisseur n'est PAS chargée.
        val waiting = MetaChoice(meta = null, useFallback = false, fallbackLocked = false)
        assertEquals(null, waiting.poster("w342") { "http://fournisseur/affiche.jpg" })
        assertEquals(null, waiting.backdrop("w780") { "http://fournisseur/fond.jpg" })

        // 2. Quand l'image officielle TMDB est là, seule l'image officielle est choisie.
        val official = MetaChoice(meta = MediaMeta("ok", poster = "/off.jpg", backdrop = "/off-bg.jpg"), useFallback = true, fallbackLocked = false)
        assertEquals("https://image.tmdb.org/t/p/w342/off.jpg", official.poster("w342") { "http://fournisseur/affiche.jpg" })
        assertEquals("https://image.tmdb.org/t/p/w780/off-bg.jpg", official.backdrop("w780") { "http://fournisseur/fond.jpg" })

        // 3. Si TMDB n'a pas d'image (« not_found »), l'image du fournisseur prend le relais une seule fois.
        val notFound = MetaChoice(meta = MediaMeta("not_found"), useFallback = true, fallbackLocked = false)
        assertEquals("http://fournisseur/affiche.jpg", notFound.poster("w342") { "http://fournisseur/affiche.jpg" })

        // 4. Si le secours fournisseur a déjà été affiché (délai dépassé) et qu'une réponse TMDB arrive en retard,
        // l'image déjà affichée est verrouillée (pas d'échange visible sous les yeux), sauf s'il n'y avait aucune image fournisseur.
        val lockedWithProvider = MetaChoice(meta = MediaMeta("ok", poster = "/tard.jpg"), useFallback = true, fallbackLocked = true)
        assertEquals("http://fournisseur/affiche.jpg", lockedWithProvider.poster("w342") { "http://fournisseur/affiche.jpg" })
        assertEquals("https://image.tmdb.org/t/p/w342/tard.jpg", lockedWithProvider.poster("w342") { null })
    }
}

