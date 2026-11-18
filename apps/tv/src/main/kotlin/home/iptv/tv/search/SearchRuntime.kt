package home.iptv.tv.search

import home.iptv.shared.search.CatalogFiller
import home.iptv.shared.search.SearchSection
import home.iptv.shared.sync.ProfilePrefs
import home.iptv.tv.movies.movieHidden
import home.iptv.tv.series.seriesHidden
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Cycle de vie du remplisseur, un seul par processus : [start] à l'affichage de l'app (après un court délai, pour
 * ne pas ralentir l'ouverture), [stop] à `onStop`. [bind] suit le profil et la section affichés ; sans profil ou
 * sans préférences connues, rien n'est demandé au fournisseur.
 */
class SearchRuntime(
    val filler: CatalogFiller,
    private val scope: CoroutineScope,
    private val startupDelayMs: Long = 3000,
) {
    @Volatile private var prefs: StateFlow<ProfilePrefs?>? = null
    @Volatile private var section: SearchSection = SearchSection.LIVE
    private var pending: Job? = null
    /** App à l'écran ([start]/[stop]) et vidéo en cours ([setPlaying]) : le remplisseur ne tourne que si l'app est à l'écran SANS vidéo. */
    private var foreground = false
    private var playing = false
    private var running = false

    fun bind(prefs: StateFlow<ProfilePrefs?>?, section: SearchSection) {
        this.prefs = prefs
        this.section = section
    }

    @Synchronized
    fun start() { foreground = true; apply() }

    @Synchronized
    fun stop() { foreground = false; apply() }

    /**
     * Une vidéo est en cours (Direct, Sport, film, épisode) : le remplissage s'arrête (téléchargements et écritures
     * sur le disque pendant la lecture faisaient sauter des images sur le Fire Stick) ; il reprend après [startupDelayMs].
     */
    @Synchronized
    fun setPlaying(on: Boolean) { if (playing != on) { playing = on; apply() } }

    private fun apply() {
        val wanted = foreground && !playing
        if (wanted == running) return
        running = wanted
        pending?.cancel(); pending = null
        if (!wanted) { filler.stop(); return }
        pending = scope.launch {
            delay(startupDelayMs)
            val me = coroutineContext[Job]
            // Revérifié sous le verrou : une vidéo lancée pile à la fin de l'attente ne laisse jamais le remplisseur repartir.
            synchronized(this@SearchRuntime) {
                if (running && pending === me) filler.start(
                    seriesHidden = { prefs?.value?.seriesHidden() },
                    movieHidden = { prefs?.value?.movieHidden() },
                    priority = { section },
                )
            }
        }
    }
}
