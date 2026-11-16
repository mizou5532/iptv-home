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

/** Damerau restreint (transposition adjacente), bande bornée ; dernier mot sur préfixe. */
private fun withinDistance(query: String, word: String, bound: Int, prefix: Boolean): Boolean {
    val width = if (prefix) min(word.length, query.length + bound) else word.length
    val infinity = bound + 1
    var older = IntArray(width + 1) { infinity }
    var previous = IntArray(width + 1) { min(it, infinity) }
    var current = IntArray(width + 1) { infinity }
    for (i in 1..query.length) {
        current.fill(infinity)
        current[0] = min(i, infinity)
        var rowMinimum = infinity
        for (j in maxOf(1, i - bound)..min(width, i + bound)) {
            var distance = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (query[i - 1] == word[j - 1]) 0 else 1)
            if (i > 1 && j > 1 && query[i - 1] == word[j - 2] && query[i - 2] == word[j - 1]) distance = min(distance, older[j - 2] + 1)
            current[j] = distance
            rowMinimum = min(rowMinimum, distance)
        }
        if (rowMinimum > bound) return false
        val reusable = older
        older = previous
        previous = current
        current = reusable
    }
    return if (prefix) (maxOf(0, query.length - bound)..width).any { previous[it] <= bound }
        else previous[width] <= bound
}
