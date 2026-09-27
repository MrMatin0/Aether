package studio.cluvex.aether.ui

import kotlin.math.abs

/**
 * Length-preserving fold, one char in, one char out, so match ranges found in
 * the folded text point at the same characters in the original title and can
 * be highlighted without an index map.
 */
internal fun foldSettingsChar(c: Char): Char = when (c) {
    '\u064A', '\u0649' -> '\u06CC' // Arabic yeh, alef maksura -> Persian yeh
    '\u0643' -> '\u06A9' // Arabic kaf -> keheh
    in '\u06F0'..'\u06F9' -> '0' + (c - '\u06F0') // Persian digits
    in '\u0660'..'\u0669' -> '0' + (c - '\u0660') // Arabic-Indic digits
    '\u200C', '\u00A0', '\t', '\n' -> ' ' // ZWNJ and friends read as a space
    else -> c.lowercaseChar()
}

internal fun foldSettingsText(raw: String): String =
    buildString(raw.length) { raw.forEach { append(foldSettingsChar(it)) } }

internal enum class MatchKind(val weight: Int) {
    EXACT_WORD(40), PREFIX(30), SUBSTRING(20), TYPO(12), SUBSEQUENCE(6),
}

internal data class FieldMatch(val kind: MatchKind, val ranges: List<IntRange>)

internal object SettingsFuzzy {

    fun terms(query: String): List<String> =
        normalizeSettingsText(query).split(' ').filter { it.isNotEmpty() }

    /**
     * Best match of one already-folded [term] inside [field], or null.
     * Order of preference: whole word, word prefix, substring, one typo inside
     * a word (terms of 4+ chars only), then an anchored subsequence ("tnl"
     * finds "tunnel").
     */
    fun matchTerm(term: String, field: String): FieldMatch? {
        val hay = foldSettingsText(field)
        if (term.isEmpty() || hay.isEmpty()) return null

        var best: FieldMatch? = null
        var from = 0
        while (true) {
            val at = hay.indexOf(term, from)
            if (at < 0) break
            val end = at + term.length
            val startsWord = at == 0 || !hay[at - 1].isLetterOrDigit()
            val endsWord = end == hay.length || !hay[end].isLetterOrDigit()
            val kind = when {
                startsWord && endsWord -> MatchKind.EXACT_WORD
                startsWord -> MatchKind.PREFIX
                else -> MatchKind.SUBSTRING
            }
            if (best == null || kind.weight > best.kind.weight) best = FieldMatch(kind, listOf(at until end))
            if (kind == MatchKind.EXACT_WORD) return best
            from = at + 1
        }
        if (best != null) return best

        val words = wordSpans(hay)
        if (term.length >= 4) {
            for (span in words) {
                val word = hay.substring(span.first, span.last + 1)
                val candidate = if (word.length > term.length + 1) word.take(term.length) else word
                if (withinOneEdit(term, candidate)) return FieldMatch(MatchKind.TYPO, listOf(span))
            }
        }
        return subsequence(term, hay)?.let { FieldMatch(MatchKind.SUBSEQUENCE, it) }
    }

    /** Damerau distance <= 1: one insert, delete, substitute or adjacent swap. */
    fun withinOneEdit(a: String, b: String): Boolean {
        if (a == b) return true
        if (abs(a.length - b.length) > 1) return false
        if (a.length == b.length) {
            val diffs = a.indices.filter { a[it] != b[it] }
            return diffs.size == 1 ||
                (diffs.size == 2 && diffs[1] == diffs[0] + 1 &&
                    a[diffs[0]] == b[diffs[1]] && a[diffs[1]] == b[diffs[0]])
        }
        val (short, long) = if (a.length < b.length) a to b else b to a
        var i = 0
        var j = 0
        var skipped = false
        while (i < short.length && j < long.length) {
            if (short[i] == long[j]) { i++; j++ } else {
                if (skipped) return false
                skipped = true
                j++
            }
        }
        return true
    }

    private fun wordSpans(hay: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var i = 0
        while (i < hay.length) {
            if (!hay[i].isLetterOrDigit()) { i++; continue }
            val start = i
            while (i < hay.length && hay[i].isLetterOrDigit()) i++
            out += start until i
        }
        return out
    }

    /** Anchored at a word start and kept tight, so short noise does not match. */
    private fun subsequence(term: String, hay: String): List<IntRange>? {
        if (term.length < 3) return null
        for (i in hay.indices) {
            if (hay[i] != term[0] || !(i == 0 || !hay[i - 1].isLetterOrDigit())) continue
            val hits = ArrayList<Int>(term.length).apply { add(i) }
            var j = i + 1
            var k = 1
            while (j < hay.length && k < term.length) {
                if (hay[j] == term[k]) { hits += j; k++ }
                j++
            }
            if (k == term.length && hits.last() - i < term.length * 3) return mergeRanges(hits.map { it..it })
        }
        return null
    }
}

internal fun mergeRanges(ranges: List<IntRange>): List<IntRange> {
    if (ranges.isEmpty()) return ranges
    val sorted = ranges.sortedBy { it.first }
    val out = mutableListOf(sorted.first())
    for (r in sorted.drop(1)) {
        val last = out.last()
        if (r.first <= last.last + 1) out[out.lastIndex] = last.first..maxOf(last.last, r.last) else out += r
    }
    return out
}

internal data class SettingsHit(
    val entry: SettingsEntry,
    val score: Int,
    val titleRanges: List<IntRange>,
    val matchedTags: List<String>,
)

/**
 * Every term must land somewhere (title x3, tag x2, live value or note x1),
 * so "kill switch" narrows instead of widening. Stable sort: ties keep hub
 * order, which keeps results from jumping around while typing.
 */
internal fun searchSettings(query: String, entries: List<SettingsEntry>): List<SettingsHit> {
    val terms = SettingsFuzzy.terms(query)
    if (terms.isEmpty()) return emptyList()
    return entries.mapNotNull { entry ->
        var score = 0
        val titleRanges = mutableListOf<IntRange>()
        val tagHits = linkedSetOf<String>()
        for (term in terms) {
            val title = SettingsFuzzy.matchTerm(term, entry.title)
            val tag = entry.tags
                .mapNotNull { t -> SettingsFuzzy.matchTerm(term, t)?.let { t to it } }
                .maxByOrNull { it.second.kind.weight }
            val body = listOfNotNull(entry.state, entry.note)
                .mapNotNull { SettingsFuzzy.matchTerm(term, it) }
                .maxByOrNull { it.kind.weight }
            val weight = maxOf(
                (title?.kind?.weight ?: 0) * 3,
                (tag?.second?.kind?.weight ?: 0) * 2,
                body?.kind?.weight ?: 0,
            )
            if (weight == 0) return@mapNotNull null
            score += weight
            title?.let { titleRanges += it.ranges }
            tag?.let { tagHits += it.first }
        }
        SettingsHit(entry, score, mergeRanges(titleRanges), tagHits.toList())
    }.sortedByDescending { it.score }
}