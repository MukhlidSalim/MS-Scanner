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

enum class FilterType {
    ORIGINAL,
    MAGIC,
    DOCUMENT,
    BLACK_WHITE,
    GRAYSCALE,
    VIBRANT
}

enum class PageSizePreset {
    A4,
    LETTER,
    FIT_ORIGINAL
}

enum class CompressionPreset(val qualityPercent: Int) {
    MAXIMUM(100),
    HIGH(85),
    MEDIUM(65),
    LOW(40)
}
