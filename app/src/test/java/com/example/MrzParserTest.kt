package com.example

import com.example.engine.ocr.MrzParser
import org.junit.Assert.*
import org.junit.Test

/** ICAO 9303 specimen documents (official "UTO" samples). */
class MrzParserTest {

    private val td3 = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<\nL898902C36UTO7408122F1204159ZE184226B<<<<<10"
    private val td1 = "I<UTOD231458907<<<<<<<<<<<<<<<\n7408122F1204159UTO<<<<<<<<<<<6\nERIKSSON<<ANNA<MARIA<<<<<<<<<<"

    @Test
    fun checkDigits() {
        assertEquals(6, MrzParser.checkDigit("L898902C3"))
        assertEquals(2, MrzParser.checkDigit("740812"))
        assertEquals(9, MrzParser.checkDigit("120415"))
    }

    @Test
    fun parsesPassportTd3() {
        val m = MrzParser.parse("Some header text\n$td3\nfooter")!!
        assertEquals("TD3", m.format)
        assertTrue(m.isPassport)
        assertEquals("UTO", m.issuingCountry)
        assertEquals("ERIKSSON", m.surname)
        assertEquals("ANNA MARIA", m.givenNames)
        assertEquals("L898902C3", m.documentNumber)
        assertEquals("740812", m.birthDate)
        assertEquals("F", m.sex)
        assertEquals("120415", m.expiryDate)
        assertTrue(m.isValid)
    }

    @Test
    fun parsesIdCardTd1() {
        val m = MrzParser.parse(td1)!!
        assertEquals("TD1", m.format)
        assertEquals("D23145890", m.documentNumber)
        assertEquals("ANNA MARIA ERIKSSON", m.fullName)
        assertTrue(m.isValid)
    }

    @Test
    fun toleratesOcrNoise() {
        // spaces inside, lower case, one filler read as '«', an 'O' instead of '0' in a date
        val noisy = "p<utoERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<«\nL898902C36UTO74O8122F1204159ZE184226B<<<<<10"
        val m = MrzParser.parse(noisy)!!
        assertEquals("L898902C3", m.documentNumber)
        assertEquals("740812", m.birthDate)
        assertTrue(m.birthDateValid)
    }

    @Test
    fun detectsCorruptedCheckDigit() {
        val bad = td3.replace("L898902C36", "L898902C35")
        val m = MrzParser.parse(bad)!!
        assertFalse(m.documentNumberValid)
        assertFalse(m.isValid)
    }

    @Test
    fun ignoresNormalText() {
        assertNull(MrzParser.parse("Invoice number 12345\nTotal: 50 OMR\nشكراً لكم"))
    }

    @Test
    fun formatsDates() {
        assertEquals("1974-08-12", MrzParser.formatDate("740812", isBirthDate = true, currentYear = 2026))
        assertEquals("2031-04-15", MrzParser.formatDate("310415", isBirthDate = false, currentYear = 2026))
        assertEquals("2005-01-01", MrzParser.formatDate("050101", isBirthDate = true, currentYear = 2026))
    }
}
