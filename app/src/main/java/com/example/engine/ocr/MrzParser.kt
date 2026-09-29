package com.example.engine.ocr

/**
 * ICAO 9303 Machine Readable Zone parser (pure Kotlin, no Android dependency -> unit-testable).
 *  TD3 = passport (2 lines x 44), TD1 = ID card (3 lines x 30).
 * OCR noise is tolerated: spaces are removed, lowercase is upper-cased, '«' / '‹' / 'K' runs at the
 * filler position are normalized to '<'. Every field protected by a check digit is validated; the
 * result reports which checks passed so the caller never presents unverified data as reliable.
 */
data class MrzData(
    val format: String,            // "TD3" (passport) or "TD1" (ID card)
    val documentType: String,      // e.g. "P", "ID", "I"
    val issuingCountry: String,
    val surname: String,
    val givenNames: String,
    val documentNumber: String,
    val nationality: String,
    val birthDate: String,         // YYMMDD as printed
    val sex: String,
    val expiryDate: String,        // YYMMDD as printed
    val optionalData: String,
    val documentNumberValid: Boolean,
    val birthDateValid: Boolean,
    val expiryDateValid: Boolean
) {
    val fullName: String get() = listOf(givenNames, surname).filter { it.isNotBlank() }.joinToString(" ")
    val isValid: Boolean get() = documentNumberValid && birthDateValid && expiryDateValid
    val isPassport: Boolean get() = format == "TD3"
}

object MrzParser {

    private val WEIGHTS = intArrayOf(7, 3, 1)

    fun parse(text: String): MrzData? {
        val candidates = text.lineSequence()
            .map { clean(it) }
            .filter { it.length >= 28 && it.count { c -> c == '<' } >= 2 }
            .toList()
        for (i in candidates.indices) {
            // TD3: two consecutive lines of ~44 chars, first starting with P
            if (i + 1 < candidates.size) {
                val l1 = fit(candidates[i], 44)
                val l2 = fit(candidates[i + 1], 44)
                if (l1 != null && l2 != null && l1[0] == 'P') parseTd3(l1, l2)?.let { return it }
            }
            // TD1: three consecutive lines of ~30 chars
            if (i + 2 < candidates.size) {
                val a = fit(candidates[i], 30)
                val b = fit(candidates[i + 1], 30)
                val c = fit(candidates[i + 2], 30)
                if (a != null && b != null && c != null && a[0] in "ACI") parseTd1(a, b, c)?.let { return it }
            }
        }
        return null
    }

    private fun clean(line: String): String =
        line.trim().uppercase()
            .replace(" ", "")
            .replace('«', '<').replace('‹', '<')
            .filter { it.isLetterOrDigit() || it == '<' }

    /** Accepts lines up to 2 chars short (padded) or long (trimmed): typical OCR errors at the edges. */
    private fun fit(line: String, length: Int): String? = when {
        line.length == length -> line
        line.length in (length - 2) until length -> line.padEnd(length, '<')
        line.length in (length + 1)..(length + 2) -> line.take(length)
        else -> null
    }

    fun checkDigit(value: String): Int {
        var sum = 0
        value.forEachIndexed { i, ch ->
            val v = when (ch) {
                in '0'..'9' -> ch - '0'
                in 'A'..'Z' -> ch - 'A' + 10
                else -> 0 // '<'
            }
            sum += v * WEIGHTS[i % 3]
        }
        return sum % 10
    }

    private fun checkOk(value: String, digit: Char): Boolean =
        digit.isDigit() && checkDigit(value) == digit - '0'

    /** Common OCR confusions inside numeric fields. */
    private fun digits(s: String): String = s.map {
        when (it) { 'O', 'Q', 'D' -> '0'; 'I', 'L' -> '1'; 'Z' -> '2'; 'S' -> '5'; 'B' -> '8'; 'G' -> '6'; else -> it }
    }.joinToString("")

    private fun names(field: String): Pair<String, String> {
        val parts = field.trimEnd('<').split("<<", limit = 2)
        val surname = parts.getOrElse(0) { "" }.replace('<', ' ').trim()
        val given = parts.getOrElse(1) { "" }.replace('<', ' ').trim().replace(Regex("\\s+"), " ")
        return surname to given
    }

    private fun parseTd3(l1: String, l2: String): MrzData? {
        val (surname, given) = names(l1.substring(5, 44))
        val docNumber = l2.substring(0, 9)
        val birth = digits(l2.substring(13, 19))
        val expiry = digits(l2.substring(21, 27))
        val data = MrzData(
            format = "TD3",
            documentType = l1.substring(0, 2).trimEnd('<'),
            issuingCountry = l1.substring(2, 5).trimEnd('<'),
            surname = surname,
            givenNames = given,
            documentNumber = docNumber.trimEnd('<'),
            nationality = l2.substring(10, 13).trimEnd('<'),
            birthDate = birth,
            sex = l2.substring(20, 21).replace("<", ""),
            expiryDate = expiry,
            optionalData = l2.substring(28, 42).trimEnd('<'),
            documentNumberValid = checkOk(docNumber, digits(l2.substring(9, 10))[0]),
            birthDateValid = checkOk(birth, digits(l2.substring(19, 20))[0]),
            expiryDateValid = checkOk(expiry, digits(l2.substring(27, 28))[0])
        )
        return data.takeIf { it.documentNumber.isNotBlank() && (it.birthDateValid || it.expiryDateValid || it.documentNumberValid) }
    }

    private fun parseTd1(a: String, b: String, c: String): MrzData? {
        val docNumber = a.substring(5, 14)
        val birth = digits(b.substring(0, 6))
        val expiry = digits(b.substring(8, 14))
        val (surname, given) = names(c)
        val data = MrzData(
            format = "TD1",
            documentType = a.substring(0, 2).trimEnd('<'),
            issuingCountry = a.substring(2, 5).trimEnd('<'),
            surname = surname,
            givenNames = given,
            documentNumber = docNumber.trimEnd('<'),
            nationality = b.substring(15, 18).trimEnd('<'),
            birthDate = birth,
            sex = b.substring(7, 8).replace("<", ""),
            expiryDate = expiry,
            optionalData = a.substring(15, 30).trimEnd('<'),
            documentNumberValid = checkOk(docNumber, digits(a.substring(14, 15))[0]),
            birthDateValid = checkOk(birth, digits(b.substring(6, 7))[0]),
            expiryDateValid = checkOk(expiry, digits(b.substring(14, 15))[0])
        )
        return data.takeIf { it.documentNumber.isNotBlank() && (it.birthDateValid || it.expiryDateValid || it.documentNumberValid) }
    }

    /** YYMMDD -> YYYY-MM-DD. Birth dates in the future are moved to the previous century. */
    fun formatDate(yymmdd: String, isBirthDate: Boolean, currentYear: Int = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)): String {
        if (yymmdd.length != 6 || !yymmdd.all { it.isDigit() }) return yymmdd
        val yy = yymmdd.substring(0, 2).toInt()
        val century = currentYear / 100 * 100
        var year = century + yy
        if (isBirthDate && year > currentYear) year -= 100
        if (!isBirthDate && year < currentYear - 50) year += 100
        return "%04d-%s-%s".format(year, yymmdd.substring(2, 4), yymmdd.substring(4, 6))
    }
}
