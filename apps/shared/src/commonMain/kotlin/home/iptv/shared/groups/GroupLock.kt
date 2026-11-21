package home.iptv.shared.groups

/** Verrou minimal (le code commun n'a pas `synchronized`) : protège les caches mémoire du regroupement. */
internal expect fun <T> withGroupLock(lock: Any, block: () -> T): T
