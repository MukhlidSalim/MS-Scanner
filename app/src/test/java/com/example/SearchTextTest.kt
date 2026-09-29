package com.example

import com.example.util.SearchText
import org.junit.Assert.*
import org.junit.Test

class SearchTextTest {

    @Test
    fun normalizesArabicLetterForms() {
        assertEquals(SearchText.normalize("احمد"), SearchText.normalize("أحمد"))
        assertEquals(SearchText.normalize("اسلام"), SearchText.normalize("إسلام"))
        assertEquals(SearchText.normalize("مدرسه"), SearchText.normalize("مدرسة"))
        assertEquals(SearchText.normalize("مصطفي"), SearchText.normalize("مصطفى"))
    }

    @Test
    fun removesDiacriticsAndTatweel() {
        assertEquals("محمد", SearchText.normalize("مُحَمَّد"))
        assertEquals("كتاب", SearchText.normalize("كتـــاب"))
    }

    @Test
    fun convertsArabicIndicDigits() {
        assertEquals("2026", SearchText.normalize("٢٠٢٦"))
        assertEquals("15", SearchText.normalize("۱۵"))
    }

    @Test
    fun matchesAllWordsInAnyOrder() {
        val hay = SearchText.normalize("فاتورة ضريبية رقم ١٢٣ شركة الخزان")
        assertTrue(SearchText.matches(hay, "فاتوره 123"))
        assertTrue(SearchText.matches(hay, "الخزان فاتورة"))
        assertFalse(SearchText.matches(hay, "عقد"))
        assertFalse(SearchText.matches(hay, "   "))
    }

    @Test
    fun caseInsensitiveLatin() {
        assertTrue(SearchText.matches(SearchText.normalize("Tax INVOICE 2026"), "invoice"))
    }
}
