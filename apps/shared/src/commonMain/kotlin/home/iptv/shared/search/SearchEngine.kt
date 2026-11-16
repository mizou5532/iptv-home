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
    private val vocabulary: Map<Char, Map<Int, Map<String, List<Int>>>>

    init {
        val postings = mutableMapOf<String, MutableList<Int>>()
        this.items.forEachIndexed { index, item ->
            searchWords(item.title).distinct().forEach { postings.getOrPut(it) { mutableListOf() }.add(index) }
        }
        vocabulary = postings.entries.groupBy { it.key.first() }.mapValues { (_, entries) ->
            entries.groupBy { it.key.length }.mapValues { (_, words) -> words.associate { it.key to it.value.toList() } }
        }
    }

    fun search(query: String, limit: Int = SEARCH_LIMIT): List<SearchItem> {
        val words = searchWords(query)
        if (words.isEmpty() || limit <= 0) return emptyList()
        var candidates: MutableMap<Int, Int>? = null
        words.forEachIndexed { position, word ->
            val matches = mutableMapOf<Int, Int>()
            for (variant in (listOf(word) + aliases[word].orEmpty()).distinct()) {
                val tolerance = when { variant.length < 4 -> 0; variant.length < 8 -> 1; else -> 2 }
                val prefix = position == words.lastIndex
                // All first-letter groups remain eligible: a typo may affect the first letter.
                for (lengths in vocabulary.values) for ((length, entries) in lengths) {
                    if (length < variant.length - tolerance) continue
                    for ((indexedWord, postings) in entries) {
                        val score = when {
                            indexedWord == variant || indexedWord.startsWith(variant) -> 1
                            indexedWord.contains(variant) -> 2
                            tolerance > 0 && (prefix || abs(length - variant.length) <= tolerance) &&
                                withinDistance(variant, indexedWord, tolerance, prefix) -> 3
                            else -> continue
                        }
                        for (id in postings) if (score < (matches[id] ?: Int.MAX_VALUE)) matches[id] = score
                    }
                }
            }
            val previous = candidates
            if (previous == null) candidates = matches else {
                val iterator = previous.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    val score = matches[entry.key]
                    if (score == null) iterator.remove() else entry.setValue(maxOf(entry.value, score))
                }
            }
            if (candidates.isNullOrEmpty()) return emptyList()
        }
        val normalizedQuery = words.joinToString(" ")
        val scores = candidates ?: return emptyList()
        return scores.keys.sortedWith(compareBy<Int>(
            { if (titles[it] == normalizedQuery) 0 else scores.getValue(it) },
            { items[it].title.length }, { titles[it] }, { items[it].title }, { items[it].id },
        )).take(min(limit, SEARCH_LIMIT)).map { items[it] }
    }
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
