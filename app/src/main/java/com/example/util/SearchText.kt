package com.example.util

/**
 * Text normalization for search, so that the way a word is typed does not decide whether it is found:
 *  - Arabic: hamza forms of alef -> ا, ى -> ي, ة -> ه, ؤ -> و, ئ -> ي; diacritics and tatweel removed
 *  - Arabic-Indic / Persian digits -> 0-9
 *  - lower-case, whitespace collapsed
 */
object SearchText {
    private fun isSkippedMark(c: Char): Boolean =
        c in '\u064B'..'\u0652' || c == '\u0670' || c == '\u0640'

    private fun normalizeChar(raw: Char): Char = when (raw) {
        'أ', 'إ', 'آ', 'ٱ' -> 'ا'
        'ى' -> 'ي'
        'ة' -> 'ه'
        'ؤ' -> 'و'
        'ئ' -> 'ي'
        in '\u0660'..'\u0669' -> '0' + (raw - '\u0660')
        in '\u06F0'..'\u06F9' -> '0' + (raw - '\u06F0')
        else -> raw.lowercaseChar()
    }

    fun normalize(input: String): String {
        val sb = StringBuilder(input.length)
        var lastSpace = true
        for (raw in input) {
            val c = normalizeChar(raw)
            // Diacritics (fatha, damma, kasra, tanween, shadda, sukun, superscript alef) and tatweel.
            if (isSkippedMark(c)) continue
            if (c.isWhitespace()) {
                if (!lastSpace) sb.append(' ')
                lastSpace = true
            } else {
                sb.append(c)
                lastSpace = false
            }
        }
        return sb.toString().trim()
    }

    /** True when every word of [query] appears in [normalizedHaystack] (already normalized). */
    fun matches(normalizedHaystack: String, query: String): Boolean {
        val tokens = normalize(query).split(' ').filter { it.isNotBlank() }
        return tokens.isNotEmpty() && tokens.all { normalizedHaystack.contains(it) }
    }

    /**
     * Maps normalized search query token matches back to character index ranges in the original [text].
     * Ensures that searching "احمد" or "فاتوره" or "123" highlights "أحمد" or "فاتورة" or "١٢٣" in UI cards.
     */
    fun findMatchRanges(text: String, query: String): List<IntRange> {
        if (text.isEmpty() || query.isBlank()) return emptyList()
        val tokens = normalize(query).split(' ').filter { it.isNotBlank() }.distinct()
        if (tokens.isEmpty()) return emptyList()

        val normBuilder = StringBuilder(text.length)
        val normToOrig = IntArray(text.length)
        var normLen = 0
        var lastSpace = true

        for (i in text.indices) {
            val raw = text[i]
            if (isSkippedMark(raw)) continue
            val c = normalizeChar(raw)
            if (c.isWhitespace()) {
                if (!lastSpace) {
                    normBuilder.append(' ')
                    normToOrig[normLen++] = i
                    lastSpace = true
                }
            } else {
                normBuilder.append(c)
                normToOrig[normLen++] = i
                lastSpace = false
            }
        }
        val normText = normBuilder.toString()
        if (normText.isEmpty()) return emptyList()

        val rawRanges = ArrayList<IntRange>()
        for (token in tokens) {
            var searchFrom = 0
            while (searchFrom < normText.length) {
                val idx = normText.indexOf(token, searchFrom)
                if (idx < 0) break
                val startOrig = normToOrig[idx]
                var endOrig = normToOrig[idx + token.length - 1]
                // Include any trailing combining diacritics or tatweel attached to the matched character
                while (endOrig + 1 < text.length && isSkippedMark(text[endOrig + 1])) {
                    endOrig++
                }
                rawRanges.add(startOrig..endOrig)
                searchFrom = idx + token.length
            }
        }
        if (rawRanges.isEmpty()) return emptyList()

        // Sort and merge overlapping or adjacent ranges
        val sorted = rawRanges.sortedBy { it.first }
        val merged = ArrayList<IntRange>(sorted.size)
        var current = sorted[0]
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (next.first <= current.last + 1) {
                current = current.first..maxOf(current.last, next.last)
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
    }
}
