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
}
