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

    fun bind(prefs: StateFlow<ProfilePrefs?>?, section: SearchSection) {
        this.prefs = prefs
        this.section = section
    }

    fun start() {
        pending?.cancel()
        pending = scope.launch {
            delay(startupDelayMs)
            filler.start(
                seriesHidden = { prefs?.value?.seriesHidden() },
                movieHidden = { prefs?.value?.movieHidden() },
                priority = { section },
            )
        }
    }

    fun stop() {
        pending?.cancel()
        pending = null
        filler.stop()
    }
}
