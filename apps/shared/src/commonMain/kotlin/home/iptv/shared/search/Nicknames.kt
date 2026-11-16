package home.iptv.shared.search

/** Petits équivalents éditoriaux ; clés et valeurs sont normalisées par le moteur. */
val NICKNAMES: Map<String, List<String>> = mapOf(
    "habs" to listOf("canadiens"), "ch" to listOf("canadiens"), "canadien" to listOf("canadiens"),
    "barca" to listOf("barcelona", "barcelone"), "fcb" to listOf("barcelona", "barcelone"),
    "barça" to listOf("barcelona", "barcelone"), "psg" to listOf("paris"),
    "mtl" to listOf("montréal"), "qc" to listOf("québec"), "nhl" to listOf("lnh"), "lnh" to listOf("nhl"),
)
