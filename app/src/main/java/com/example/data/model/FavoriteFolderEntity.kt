package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorite_folders")
data class FavoriteFolderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val folderName: String,
    val createdAt: Long = System.currentTimeMillis()
)
