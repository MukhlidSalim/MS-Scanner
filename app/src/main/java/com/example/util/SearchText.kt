package com.example.util

/**
 * Text normalization for search, so that the way a word is typed does not decide whether it is found:
 *  - Arabic: hamza forms of alef -> ا, ى -> ي, ة -> ه, ؤ -> و, ئ -> ي; diacritics and tatweel removed
 *  - Arabic-Indic / Persian digits -> 0-9
 *  - lower-case, whitespace collapsed
 */
object SearchText {
    fun normalize(input: String): String {
        val sb = StringBuilder(input.length)
        var lastSpace = true
        for (raw in input) {
            val c = when (raw) {
                'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                'ى' -> 'ي'
                'ة' -> 'ه'
                'ؤ' -> 'و'
                'ئ' -> 'ي'
                in '\u0660'..'\u0669' -> '0' + (raw - '\u0660')
                in '\u06F0'..'\u06F9' -> '0' + (raw - '\u06F0')
                else -> raw.lowercaseChar()
            }
            // Diacritics (fatha, damma, kasra, tanween, shadda, sukun, superscript alef) and tatweel.
            if (c in '\u064B'..'\u0652' || c == '\u0670' || c == '\u0640') continue
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
}
