package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val folderName: String = "Default",
    val category: String = DocumentCategory.OTHER.name,
    val isFavorite: Boolean = false,
    val isTrash: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val pageCount: Int = 1,
    val thumbnailPath: String = "",
    val suggestedTitle: String = "",
    val tagsCsv: String = "",
    val fileSizeFormatted: String = "",
    val sizeBytes: Long = 0L
)

@Entity(tableName = "pages")
data class PageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val documentId: Long,
    val pageIndex: Int,
    val rawImagePath: String,
    val processedImagePath: String,
    val filterType: String = FilterType.MAGIC.name,
    val rotationDegrees: Int = 0,
    val cropQuadJson: String = "", // serialized corners
    val ocrText: String = "",
    val ocrEntitiesJson: String = "",
    val qualityStatus: String = "GOOD", // GOOD, BLURRY, LOW_CONTRAST
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "signatures")
data class SignatureEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String = "Signature",
    val imagePath: String,
    val createdAt: Long = System.currentTimeMillis()
)
