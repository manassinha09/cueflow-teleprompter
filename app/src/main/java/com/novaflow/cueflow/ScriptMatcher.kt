package com.novaflow.cueflow

import java.util.Locale

/** Aligns nearby speech to the script, keeping original offsets for Markdown and highlighting. */
internal class ScriptMatcher(script: String) {
    private val words = spokenWords(script)
    var position = 0
        private set
    private var lastHypothesis = emptyList<String>()
    private val costRows = Array(2) { IntArray(HYPOTHESIS_WORDS + 3) }

    fun seekTo(offset: Int) {
        position = words.indexOfFirst { it.end > offset }.let { if (it < 0) words.size else it }
        lastHypothesis = emptyList()
    }

    fun advance(hypothesis: String, finalized: Boolean = false): Int? {
        val heard = spokenWords(hypothesis).takeLast(HYPOTHESIS_WORDS).map { it.value }
        if (heard.isEmpty() || position >= words.size) return null
        val offset = advanceAligned(heard)
        // A final result can repeat the previous partial transcript verbatim. Recheck
        // its ending once the recognizer confirms that no more words are coming.
        return (if (finalized) recoverEnding(heard) else null) ?: offset
    }

    private fun advanceAligned(heard: List<String>): Int? {
        if (heard == lastHypothesis) return null
        lastHypothesis = heard
        val first = (position - 16).coerceAtLeast(0)
        val last = (position + 24).coerceAtMost(words.lastIndex)
        var bestEnd = position
        var bestScore = 0
        var runnerUp = 0
        for (end in first + 1..minOf(words.size, last + heard.size + 2)) {
            val score = if (heard.last() == words[end - 1].value) {
                scoreExactEnding(heard, end, first, last)
            } else {
                scoreCloseEnding(heard, end, first)
            }
            if (score > bestScore) {
                runnerUp = bestScore
                bestScore = score
                bestEnd = end
            } else {
                runnerUp = maxOf(runnerUp, score)
            }
        }
        // Repeated passages and competing alignments should hold, not jump to another occurrence.
        if (bestEnd <= position || (runnerUp > 0 && runnerUp >= bestScore - 1)) return null
        position = bestEnd
        return words[position - 1].end
    }

    private fun scoreExactEnding(heard: List<String>, end: Int, first: Int, last: Int): Int {
        val maxSpan = minOf(end - first, heard.size + 2)
        var previous = costRows[0]
        var current = costRows[1]
        for (span in 0..maxSpan) previous[span] = span * EDIT_COST
        var bestScore = 0
        // Work backwards from a matching end word. One alignment yields the cost
        // for every pair of suffix lengths instead of recalculating each phrase.
        for (length in 1..heard.size) {
            val token = heard[heard.size - length]
            current[0] = length * EDIT_COST
            for (span in 1..maxSpan) {
                val diagonal = if (token == words[end - span].value) -1 else EDIT_COST
                current[span] = minOf(
                    previous[span - 1] + diagonal,
                    previous[span] + EDIT_COST,
                    current[span - 1] + EDIT_COST,
                )
            }
            for (span in maxOf(1, length - 2, end - last)..minOf(maxSpan, length + 2)) {
                val start = end - span
                if (length < 3 && start != position) continue
                val cost = current[span]
                val edits = (cost + EDIT_COST - 1) / EDIT_COST
                val matches = edits * EDIT_COST - cost
                val shortContinuation = edits == 0 && start == position &&
                    (length == 2 || (length == 1 && position > 0))
                if (shortContinuation || (matches >= 3 && edits <= minOf(2, matches / 3))) {
                    bestScore = maxOf(bestScore, matches * 3 - edits * 4)
                }
            }
            val next = previous
            previous = current
            current = next
        }
        return bestScore
    }

    private fun scoreCloseEnding(heard: List<String>, end: Int, first: Int): Int {
        val anchorLength = 4
        if (end <= position || end - 1 - anchorLength > position) return 0
        val maxAnchor = minOf(heard.lastIndex, end - 1 - first)
        var matches = 0
        while (matches < maxAnchor && heard[heard.lastIndex - 1 - matches] == words[end - 2 - matches].value) {
            matches++
        }
        val start = end - 1 - matches
        if (matches < anchorLength || start > position ||
            !oneEditApart(words[end - 1].value, heard.last())) return 0
        return matches * 3 - 4
    }

    private fun oneEditApart(expected: String, heard: String): Boolean {
        if (expected.length < 5 || kotlin.math.abs(expected.length - heard.length) > 1 ||
            !expected.regionMatches(0, heard, 0, 2)) return false
        var common = 2
        while (common < minOf(expected.length, heard.length) && expected[common] == heard[common]) common++
        // A live transcript may still be filling in a word it has only partially decoded.
        if (common == heard.length && heard.length < expected.length) return false
        val expectedEnd = common + if (expected.length >= heard.length) 1 else 0
        val heardEnd = common + if (heard.length >= expected.length) 1 else 0
        return expected.regionMatches(expectedEnd, heard, heardEnd, expected.length - expectedEnd)
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

    private companion object {
        const val HYPOTHESIS_WORDS = 16
        // An edit outweighs every possible exact match combined.
        const val EDIT_COST = HYPOTHESIS_WORDS + 1
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
