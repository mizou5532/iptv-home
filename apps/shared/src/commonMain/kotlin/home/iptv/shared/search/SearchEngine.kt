package home.iptv.shared.search

import home.iptv.shared.text.searchNorm
import kotlin.math.abs
import kotlin.math.min

const val SEARCH_LIMIT = 60

data class SearchItem(val id: String, val title: String, val categoryIds: Set<String>)

/** Les séparateurs du catalogue ont la même signification que les espaces. */
fun searchWords(text: String): List<String> = searchNorm(text)
    .split(' ', '|', ':', '-', '.', '/', '(', ')', '[', ']', ',', '\t', '\n', '\r')
    .filter { it.isNotEmpty() }

private val aliases = NICKNAMES.entries.groupBy { searchWords(it.key).single() }
    .mapValues { (_, entries) -> entries.flatMap { it.value }.flatMap(::searchWords).distinct() }

/** Index immuable : seuls les mots distincts sont comparés, puis leurs listes sont intersectées. */
class SearchIndex(items: List<SearchItem>) {
    private val items = items.groupBy { it.id }.values.map { copies ->
        copies.first().copy(categoryIds = copies.flatMap { it.categoryIds }.toSet())
    }
    val size: Int get() = items.size
    private val titles = this.items.map { searchWords(it.title).joinToString(" ") }
    // Vocabulaire à plat (mots distincts) : parcours rapide, sans cartes imbriquées.
    private val words: Array<String>
    private val masks: IntArray
    private val postings: Array<IntArray>

    init {
        val byWord = mutableMapOf<String, MutableList<Int>>()
        this.items.forEachIndexed { index, item ->
            searchWords(item.title).distinct().forEach { byWord.getOrPut(it) { mutableListOf() }.add(index) }
        }
        val entries = byWord.entries.sortedBy { it.key }
        words = Array(entries.size) { entries[it].key }
        masks = IntArray(entries.size) { letterMask(entries[it].key) }
        postings = Array(entries.size) { entries[it].value.toIntArray() }
    }

    fun search(query: String, limit: Int = SEARCH_LIMIT): List<SearchItem> {
        val queryWords = searchWords(query)
        if (queryWords.isEmpty() || limit <= 0) return emptyList()
        // Meilleur score par titre (1 début de mot, 2 contenu, 3 faute) ; 0 = pas (encore) trouvé.
        var candidates: IntArray? = null
        queryWords.forEachIndexed { position, word ->
            val matches = IntArray(items.size)
            for (variant in (listOf(word) + aliases[word].orEmpty()).distinct()) {
                val tolerance = when { variant.length < 4 -> 0; variant.length < 8 -> 1; else -> 2 }
                val prefix = position == queryWords.lastIndex
                val variantMask = letterMask(variant)
                // Tous les mots restent éligibles : une faute peut toucher la première lettre.
                for (w in words.indices) {
                    val indexedWord = words[w]
                    val length = indexedWord.length
                    if (length < variant.length - tolerance) continue
                    val score = when {
                        indexedWord.startsWith(variant) -> 1
                        indexedWord.contains(variant) -> 2
                        tolerance > 0 && (prefix || abs(length - variant.length) <= tolerance) &&
                            (variantMask and masks[w].inv()).countOneBits() <= tolerance &&
                            bagWithin(variant, indexedWord, tolerance, prefix) &&
                            withinDistance(variant, indexedWord, tolerance, prefix) -> 3
                        else -> continue
                    }
                    for (id in postings[w]) if (matches[id] == 0 || score < matches[id]) matches[id] = score
                }
            }
            val previous = candidates
            if (previous == null) candidates = matches else {
                var any = false
                for (id in previous.indices) {
                    val score = matches[id]
                    if (previous[id] == 0 || score == 0) previous[id] = 0
                    else { previous[id] = maxOf(previous[id], score); any = true }
                }
                if (!any) return emptyList()
            }
        }
        val normalizedQuery = queryWords.joinToString(" ")
        val scores = candidates ?: return emptyList()
        val found = scores.indices.filter { scores[it] != 0 }
        return found.sortedWith(compareBy<Int>(
            { if (titles[it] == normalizedQuery) 0 else scores[it] },
            { items[it].title.length }, { titles[it] }, { items[it].title }, { items[it].id },
        )).take(min(limit, SEARCH_LIMIT)).map { items[it] }
    }
}

/** Lettres a–z présentes (un bit par lettre) ; les autres caractères sont ignorés, ce qui garde le filtre exact. */
private fun letterMask(word: String): Int {
    var mask = 0
    for (c in word) if (c in 'a'..'z') mask = mask or (1 shl (c - 'a'))
    return mask
}

/**
 * Filtre rapide et exact : chaque édition (y compris une transposition) laisse au plus une lettre de la requête
 * sans partenaire. Plus de [bound] lettres sans partenaire dans le mot (ou dans le début de mot admissible)
 * prouvent une distance supérieure à [bound] ; on évite alors le calcul complet.
 */
private fun bagWithin(query: String, word: String, bound: Int, prefix: Boolean): Boolean {
    val width = if (prefix) min(word.length, query.length + bound) else word.length
    val used = BooleanArray(width)
    var missing = 0
    for (c in query) {
        var found = false
        for (j in 0 until width) if (!used[j] && word[j] == c) { used[j] = true; found = true; break }
        if (!found && ++missing > bound) return false
    }
    return true
}

/**
 * Damerau-Levenshtein sans restriction : une lettre transposée peut être éditée à nouveau.
 * La récurrence garde la dernière occurrence de chaque lettre (Lowrance-Wagner).
 * Un préfixe admissible ne peut dépasser longueur de requête + budget d'éditions.
 */
private fun withinDistance(query: String, word: String, bound: Int, prefix: Boolean): Boolean {
    val width = if (prefix) min(word.length, query.length + bound) else word.length
    val infinity = query.length + width
    val distance = Array(query.length + 2) { IntArray(width + 2) }
    distance[0][0] = infinity
    for (i in 0..query.length) {
        distance[i + 1][0] = infinity
        distance[i + 1][1] = i
    }
    for (j in 0..width) {
        distance[0][j + 1] = infinity
        distance[1][j + 1] = j
    }
    val lastRow = mutableMapOf<Char, Int>()
    for (i in 1..query.length) {
        var lastColumn = 0
        for (j in 1..width) {
            val matchingRow = lastRow[word[j - 1]] ?: 0
            val matchingColumn = lastColumn
            val cost = if (query[i - 1] == word[j - 1]) {
                lastColumn = j
                0
            } else 1
            distance[i + 1][j + 1] = minOf(
                distance[i][j] + cost,
                distance[i + 1][j] + 1,
                distance[i][j + 1] + 1,
                distance[matchingRow][matchingColumn] +
                    (i - matchingRow - 1) + 1 + (j - matchingColumn - 1),
            )
        }
        lastRow[query[i - 1]] = i
    }
    return if (prefix) (maxOf(0, query.length - bound)..width).any {
        distance[query.length + 1][it + 1] <= bound
    } else distance[query.length + 1][width + 1] <= bound
}
