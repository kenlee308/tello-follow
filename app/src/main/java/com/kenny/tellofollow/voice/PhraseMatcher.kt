package com.kenny.tellofollow.voice

/**
 * Pure-Kotlin matcher (no Android deps, unit-tested).
 * Scores every recognizer alternative against every phrase:
 *  1.00 exact (after normalization), 0.92 whole-word contains,
 *  up to 0.85 fuzzy (Levenshtein over a sliding word window, ratio >= FUZZY_MIN).
 * Later alternatives get a small rank penalty; longer phrases win ties
 * ("stop recording" beats "stop").
 */
data class MatchResult(
    val intent: VoiceIntent,
    val confidence: Float,
    val matchedPhrase: String,
    val transcript: String,
)

class PhraseMatcher(
    private val book: PhraseBook,
    private val threshold: Float = DEFAULT_THRESHOLD,
) {
    fun match(alternatives: List<String>): MatchResult? {
        var best: MatchResult? = null
        var bestRank = -1f
        alternatives.forEachIndexed { idx, raw ->
            val text = normalize(raw)
            if (text.isEmpty()) return@forEachIndexed
            val altPenalty = 1f - ALT_PENALTY * idx
            for (entry in book.entries) {
                for (phrase in entry.phrases) {
                    val p = normalize(phrase)
                    if (p.isEmpty()) continue
                    val score = scorePhrase(text, p) * altPenalty
                    if (score < threshold) continue
                    // tie-break by phrase length (more specific wins)
                    val rank = score + 0.005f * p.split(' ').size
                    if (rank > bestRank) {
                        bestRank = rank
                        best = MatchResult(entry.intent, score.coerceAtMost(1f), phrase, raw)
                    }
                }
            }
        }
        return best
    }

    companion object {
        const val DEFAULT_THRESHOLD = 0.6f
        const val FUZZY_MIN = 0.75f
        const val ALT_PENALTY = 0.04f

        fun normalize(s: String): String =
            s.lowercase()
                .replace("'", "")
                .replace(Regex("[^a-z0-9 ]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

        fun scorePhrase(text: String, phrase: String): Float {
            if (text == phrase) return 1f
            if (" $text ".contains(" $phrase ")) return 0.92f
            val tWords = text.split(' ')
            val pWords = phrase.split(' ')
            var bestRatio = 0f
            // windows of n-1..n+1 words to tolerate a dropped/inserted word
            for (n in maxOf(1, pWords.size - 1)..(pWords.size + 1)) {
                if (n > tWords.size) continue
                for (i in 0..tWords.size - n) {
                    val window = tWords.subList(i, i + n).joinToString(" ")
                    bestRatio = maxOf(bestRatio, similarity(window, phrase))
                }
            }
            return if (bestRatio >= FUZZY_MIN) 0.85f * bestRatio else 0f
        }

        fun similarity(a: String, b: String): Float {
            val maxLen = maxOf(a.length, b.length)
            if (maxLen == 0) return 1f
            return 1f - levenshtein(a, b).toFloat() / maxLen
        }

        private fun levenshtein(a: String, b: String): Int {
            var prev = IntArray(b.length + 1) { it }
            var cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
                }
                val t = prev; prev = cur; cur = t
            }
            return prev[b.length]
        }
    }
}