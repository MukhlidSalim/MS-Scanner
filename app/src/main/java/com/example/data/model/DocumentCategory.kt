package com.example.data.model

enum class DocumentCategory(val displayNameEn: String, val displayNameAr: String) {
    ALL("All", "الكل"),
    RECEIPT("Receipt", "إيصال"),
    INVOICE("Invoice", "فاتورة"),
    ID_CARD("ID Card", "بطاقة هوية"),
    CONTRACT("Contract", "عقد"),
    BOOK("Book", "كتاب"),
    NOTE("Note", "ملاحظة"),
    OTHER("Document", "مستند")
}

enum class FilterType(val displayNameEn: String, val displayNameAr: String) {
    ORIGINAL("Original", "الأصلي"),
    AUTO("Auto", "تلقائي"),
    MAGIC("Magic", "سحري"),
    COLOR("Color", "ملون"),
    ENHANCED("Enhanced", "محسّن"),
    DOCUMENT("Document", "مستند"),
    TEXT("Text", "نص"),
    BLACK_WHITE("B&W", "أبيض وأسود"),
    GRAYSCALE("Grayscale", "تدرج رمادي"),
    VIBRANT("Vibrant", "حيوي")
}

enum class PageSizePreset {
    A4,
    LETTER,
    FIT_ORIGINAL,
    LEGAL
}

enum class CompressionPreset(val qualityPercent: Int) {
    MAXIMUM(100),
    HIGH(85),
    MEDIUM(65),
    LOW(40)
}

enum class SortMode {
    NEWEST, OLDEST, NAME_AZ, NAME_ZA, SIZE_LARGEST, PAGE_COUNT
}
