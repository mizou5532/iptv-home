package home.iptv.shared.xtream

/** Source du guide ; permet de tester sans réseau. */
fun interface EpgSource {
    suspend fun shortEpg(streamId: String, limit: Int): List<EpgEntry>

    /** Tout le guide de la chaîne (secours, plus lourd) ; vide si la source ne sait pas le donner. */
    suspend fun fullEpg(streamId: String): List<EpgEntry> = emptyList()

    companion object {
        /** [api] est rappelé à chaque demande : les identifiants sont lus au moment de l'appel. */
        fun of(api: () -> XtreamApi): EpgSource = object : EpgSource {
            override suspend fun shortEpg(streamId: String, limit: Int) = api().shortEpg(streamId, limit)
            override suspend fun fullEpg(streamId: String) = api().fullEpg(streamId)
        }
    }
}
