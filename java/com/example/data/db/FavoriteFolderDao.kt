package com.example.data.db

import androidx.room.*
import com.example.data.model.FavoriteFolderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteFolderDao {
    @Query("SELECT * FROM favorite_folders ORDER BY createdAt DESC")
    fun getAllFavoriteFolders(): Flow<List<FavoriteFolderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: FavoriteFolderEntity)

    @Delete
    suspend fun deleteFolder(folder: FavoriteFolderEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_folders WHERE folderName = :name)")
    suspend fun isFolderFavorite(name: String): Boolean
}
