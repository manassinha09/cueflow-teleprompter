package com.novaflow.cueflow

import java.util.Locale

/** Aligns nearby speech to the script, keeping original offsets for Markdown and highlighting. */
internal class ScriptMatcher(script: String) {
    private val words = spokenWords(script)
    var position = 0
        private set
    private var lastHypothesis = emptyList<String>()

    fun seekTo(offset: Int) {
        position = words.indexOfFirst { it.end > offset }.let { if (it < 0) words.size else it }
        lastHypothesis = emptyList()
    }

    fun advance(hypothesis: String, finalized: Boolean = false): Int? {
        val heard = spokenWords(hypothesis).map { it.value }.takeLast(16)
        if (heard.isEmpty() || position >= words.size) return null
        val offset = advanceAligned(heard)
        // A final result can repeat the previous partial transcript verbatim. Recheck
        // its ending once the recognizer confirms that no more words are coming.
        return (if (finalized) recoverEnding(heard) else null) ?: offset
    }

    private fun advanceAligned(heard: List<String>): Int? {
        if (heard == lastHypothesis) return null
        lastHypothesis = heard
        val candidates = mutableMapOf<Int, Candidate>()
        val first = (position - 16).coerceAtLeast(0)
        val last = (position + 24).coerceAtMost(words.lastIndex)
        for (start in first..last) {
            for (length in 1..heard.size) {
                val phrase = heard.takeLast(length)
                val maxSpan = minOf(length + 2, words.size - start)
                // One or two words may extend the current reading position, but cannot seek ahead.
                if (length < 3 && start != position) continue
                var previous = Array(maxSpan + 1) { Alignment(it, 0) }
                for (i in phrase.indices) {
                    val current = Array(maxSpan + 1) { Alignment(i + 1, 0) }
                    for (j in 1..maxSpan) {
                        val equal = phrase[i] == words[start + j - 1].value
                        val diagonal = previous[j - 1].let {
                            Alignment(it.errors + if (equal) 0 else 1, it.matches + if (equal) 1 else 0)
                        }
                        current[j] = listOf(diagonal, previous[j].error(), current[j - 1].error())
                            .minWith(compareBy<Alignment> { it.errors }.thenByDescending { it.matches })
                    }
                    previous = current
                }
                for (span in maxOf(1, length - 2)..maxSpan) {
                    val end = start + span
                    // Live partial hypotheses must match the final word exactly.
                    if (words[end - 1].value != phrase.last()) continue
                    val alignment = previous[span]
                    val shortContinuation = alignment.errors == 0 && start == position &&
                        (length == 2 || (length == 1 && position > 0))
                    if (!shortContinuation && (alignment.matches < 3 ||
                            alignment.errors > minOf(2, alignment.matches / 3))) continue
                    val score = alignment.matches * 3 - alignment.errors * 4
                    if (score <= 0) continue
                    val candidate = Candidate(end, score)
                    if (score > (candidates[end]?.score ?: -1)) candidates[end] = candidate
                }
            }
        }
        val ranked = candidates.values.sortedByDescending { it.score }
        val best = ranked.firstOrNull() ?: return null
        // Repeated passages and competing alignments should hold, not jump to another occurrence.
        if (ranked.drop(1).any { it.score >= best.score - 1 }) return null
        if (best.end <= position) return null
        position = best.end
        return words[position - 1].end
    }

    private fun recoverEnding(heard: List<String>): Int? {
        val anchorLength = 5
        if (position < anchorLength) return null
        for (tailLength in minOf(2, words.size - position) downTo 1) {
            val anchorStart = heard.size - tailLength - anchorLength
            if (anchorStart < 0) continue
            val anchored = (0 until anchorLength).all { index ->
                heard[anchorStart + index] == words[position - anchorLength + index].value
            }
            if (!anchored) continue
            if ((0 until tailLength).all { index ->
                closeEndingWord(words[position + index].value, heard[heard.size - tailLength + index])
            }) {
                position += tailLength
                return words[position - 1].end
            }
        }
        return null
    }

    private fun closeEndingWord(expected: String, heard: String): Boolean {
        if (expected == heard) return true
        if (expected.length !in 5..24 || heard.length < 4 || !heard.startsWith(expected.take(2)) ||
            kotlin.math.abs(expected.length - heard.length) > 2) return false
        // The shared prefix already matches. Compare the remainder with at most two edits.
        val columns = heard.length - 2
        var previous = IntArray(columns + 1) { it }
        for (i in 2 until expected.length) {
            val row = IntArray(columns + 1)
            row[0] = i - 1
            for (j in 1..columns) {
                val substitution = if (expected[i] == heard[j + 1]) 0 else 1
                row[j] = minOf(previous[j] + 1, row[j - 1] + 1, previous[j - 1] + substitution)
            }
            previous = row
        }
        return previous[columns] <= 2
    }

    private data class Candidate(val end: Int, val score: Int)
    private data class Alignment(val errors: Int, val matches: Int) {
        fun error() = copy(errors = errors + 1)
    }
}

private data class SpokenWord(val value: String, val end: Int)
private val WORD = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}]+)?")

private fun spokenWords(text: String): List<SpokenWord> = WORD.findAll(text).flatMap { match ->
    val raw = match.value.lowercase(Locale.ROOT).replace('’', '\'')
    val normalized = when {
        raw == "can't" || raw == "cannot" -> "can not"
        raw == "won't" -> "will not"
        raw == "i'm" -> "i am"
        raw == "let's" -> "let us"
        raw in listOf("it's", "that's", "there's", "here's", "what's") -> raw.removeSuffix("'s") + " is"
        raw.endsWith("n't") -> raw.removeSuffix("n't") + " not"
        raw.endsWith("'re") -> raw.removeSuffix("'re") + " are"
        raw.endsWith("'ve") -> raw.removeSuffix("'ve") + " have"
        raw.endsWith("'ll") -> raw.removeSuffix("'ll") + " will"
        raw.toIntOrNull() in 0..9999 -> numberWords(raw.toInt())
        else -> raw
    }
    normalized.split(' ').map { SpokenWord(it, match.range.last + 1) }
}.toList()

private val UNITS = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
    "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
private val TENS = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
private fun numberWords(value: Int): String = when {
    value < 20 -> UNITS[value]
    value < 100 -> TENS[value / 10] + if (value % 10 == 0) "" else " ${UNITS[value % 10]}"
    value < 1000 -> "${UNITS[value / 100]} hundred" + if (value % 100 == 0) "" else " ${numberWords(value % 100)}"
    else -> "${UNITS[value / 1000]} thousand" + if (value % 1000 == 0) "" else " ${numberWords(value % 1000)}"
}
