package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration

import com.example.data.model.DocumentEntity
import com.example.data.model.PageEntity
import com.example.data.model.SignatureEntity
import com.example.data.model.FavoriteFolderEntity

@Database(
    entities = [
        DocumentEntity::class,
        PageEntity::class,
        SignatureEntity::class,
        FavoriteFolderEntity::class
    ],
    version = 6,
    exportSchema = true   // ✅ يُمكِّن Room من التحقق من صحة الـ Migrations وقت التجميع
)
abstract class DocScanDatabase : RoomDatabase() {

    abstract fun documentDao(): DocumentDao
    abstract fun favoriteFolderDao(): FavoriteFolderDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE documents ADD COLUMN sizeBytes INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 1. Add new columns to documents table
                database.execSQL("ALTER TABLE documents ADD COLUMN suggestedTitle TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE documents ADD COLUMN tagsCsv TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE documents ADD COLUMN fileSizeFormatted TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE documents ADD COLUMN ocrText TEXT NOT NULL DEFAULT ''")

                // 2. Create favorite_folders table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `favorite_folders` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `folderName` TEXT NOT NULL, 
                        `createdAt` INTEGER NOT NULL
                    )
                """.trimIndent())

                // 3. Migrate pages table to add Foreign Key constraint
                database.execSQL("""
                    CREATE TABLE `pages_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `documentId` INTEGER NOT NULL, 
                        `pageIndex` INTEGER NOT NULL, 
                        `rawImagePath` TEXT NOT NULL, 
                        `processedImagePath` TEXT NOT NULL, 
                        `filterType` TEXT NOT NULL DEFAULT 'MAGIC', 
                        `rotationDegrees` INTEGER NOT NULL DEFAULT 0, 
                        `cropQuadJson` TEXT NOT NULL DEFAULT '', 
                        `ocrText` TEXT NOT NULL DEFAULT '', 
                        `ocrEntitiesJson` TEXT NOT NULL DEFAULT '', 
                        `qualityStatus` TEXT NOT NULL DEFAULT 'GOOD', 
                        `createdAt` INTEGER NOT NULL, 
                        FOREIGN KEY(`documentId`) REFERENCES `documents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                
                database.execSQL("""
                    INSERT INTO `pages_new` (`id`, `documentId`, `pageIndex`, `rawImagePath`, `processedImagePath`, `filterType`, `rotationDegrees`, `cropQuadJson`, `ocrText`, `ocrEntitiesJson`, `qualityStatus`, `createdAt`)
                    SELECT `id`, `documentId`, `pageIndex`, `rawImagePath`, `processedImagePath`, `filterType`, `rotationDegrees`, `cropQuadJson`, `ocrText`, `ocrEntitiesJson`, `qualityStatus`, `createdAt` FROM `pages`
                """.trimIndent())
                
                database.execSQL("DROP TABLE `pages`")
                database.execSQL("ALTER TABLE `pages_new` RENAME TO `pages`")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_pages_documentId` ON `pages` (`documentId`)")

                // 4. Create signatures table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `signatures` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `title` TEXT NOT NULL DEFAULT 'Signature', 
                        `imagePath` TEXT NOT NULL, 
                        `createdAt` INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `signatures` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `title` TEXT NOT NULL DEFAULT 'Signature', 
                        `imagePath` TEXT NOT NULL, 
                        `createdAt` INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 1. Fix documents table defaults by recreation
                database.execSQL("""
                    CREATE TABLE `documents_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `title` TEXT NOT NULL, 
                        `folderName` TEXT NOT NULL, 
                        `category` TEXT NOT NULL, 
                        `isFavorite` INTEGER NOT NULL, 
                        `isTrash` INTEGER NOT NULL, 
                        `createdAt` INTEGER NOT NULL, 
                        `updatedAt` INTEGER NOT NULL, 
                        `pageCount` INTEGER NOT NULL, 
                        `thumbnailPath` TEXT NOT NULL, 
                        `suggestedTitle` TEXT NOT NULL DEFAULT '', 
                        `tagsCsv` TEXT NOT NULL DEFAULT '', 
                        `fileSizeFormatted` TEXT NOT NULL DEFAULT '', 
                        `sizeBytes` INTEGER NOT NULL DEFAULT 0, 
                        `ocrText` TEXT NOT NULL DEFAULT ''
                    )
                """.trimIndent())
                database.execSQL("""
                    INSERT INTO `documents_new` (`id`, `title`, `folderName`, `category`, `isFavorite`, `isTrash`, `createdAt`, `updatedAt`, `pageCount`, `thumbnailPath`, `suggestedTitle`, `tagsCsv`, `fileSizeFormatted`, `sizeBytes`, `ocrText`)
                    SELECT `id`, `title`, `folderName`, `category`, `isFavorite`, `isTrash`, `createdAt`, `updatedAt`, `pageCount`, `thumbnailPath`, `suggestedTitle`, `tagsCsv`, `fileSizeFormatted`, `sizeBytes`, `ocrText` FROM `documents`
                """.trimIndent())
                database.execSQL("DROP TABLE `documents`")
                database.execSQL("ALTER TABLE `documents_new` RENAME TO `documents`")

                // 2. Fix pages table (ensure Foreign Keys and Indices are correctly applied)
                database.execSQL("""
                    CREATE TABLE `pages_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `documentId` INTEGER NOT NULL, 
                        `pageIndex` INTEGER NOT NULL, 
                        `rawImagePath` TEXT NOT NULL, 
                        `processedImagePath` TEXT NOT NULL, 
                        `filterType` TEXT NOT NULL DEFAULT 'MAGIC', 
                        `rotationDegrees` INTEGER NOT NULL DEFAULT 0, 
                        `cropQuadJson` TEXT NOT NULL DEFAULT '', 
                        `ocrText` TEXT NOT NULL DEFAULT '', 
                        `ocrEntitiesJson` TEXT NOT NULL DEFAULT '', 
                        `qualityStatus` TEXT NOT NULL DEFAULT 'GOOD', 
                        `createdAt` INTEGER NOT NULL, 
                        FOREIGN KEY(`documentId`) REFERENCES `documents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("""
                    INSERT INTO `pages_new` (`id`, `documentId`, `pageIndex`, `rawImagePath`, `processedImagePath`, `filterType`, `rotationDegrees`, `cropQuadJson`, `ocrText`, `ocrEntitiesJson`, `qualityStatus`, `createdAt`)
                    SELECT `id`, `documentId`, `pageIndex`, `rawImagePath`, `processedImagePath`, `filterType`, `rotationDegrees`, `cropQuadJson`, `ocrText`, `ocrEntitiesJson`, `qualityStatus`, `createdAt` FROM `pages`
                """.trimIndent())
                database.execSQL("DROP TABLE `pages`")
                database.execSQL("ALTER TABLE `pages_new` RENAME TO `pages`")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_pages_documentId` ON `pages` (`documentId`)")

                // 3. Fix signatures table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `signatures_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `title` TEXT NOT NULL DEFAULT 'Signature', 
                        `imagePath` TEXT NOT NULL, 
                        `createdAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                try {
                    database.execSQL("INSERT INTO `signatures_new` (`id`, `title`, `imagePath`, `createdAt`) SELECT `id`, `title`, `imagePath`, `createdAt` FROM `signatures`")
                } catch (e: Exception) {}
                database.execSQL("DROP TABLE IF EXISTS `signatures`")
                database.execSQL("ALTER TABLE `signatures_new` RENAME TO `signatures`")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 1. Recreate documents table with nullable thumbnailPath and no defaults
                database.execSQL("""
                    CREATE TABLE `documents_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `title` TEXT NOT NULL, 
                        `folderName` TEXT NOT NULL, 
                        `category` TEXT NOT NULL, 
                        `isFavorite` INTEGER NOT NULL, 
                        `isTrash` INTEGER NOT NULL, 
                        `createdAt` INTEGER NOT NULL, 
                        `updatedAt` INTEGER NOT NULL, 
                        `pageCount` INTEGER NOT NULL, 
                        `thumbnailPath` TEXT, 
                        `suggestedTitle` TEXT NOT NULL, 
                        `tagsCsv` TEXT NOT NULL, 
                        `fileSizeFormatted` TEXT NOT NULL, 
                        `sizeBytes` INTEGER NOT NULL, 
                        `ocrText` TEXT NOT NULL
                    )
                """.trimIndent())
                
                database.execSQL("""
                    INSERT INTO `documents_new` (`id`, `title`, `folderName`, `category`, `isFavorite`, `isTrash`, `createdAt`, `updatedAt`, `pageCount`, `thumbnailPath`, `suggestedTitle`, `tagsCsv`, `fileSizeFormatted`, `sizeBytes`, `ocrText`)
                    SELECT `id`, `title`, `folderName`, `category`, `isFavorite`, `isTrash`, `createdAt`, `updatedAt`, `pageCount`, `thumbnailPath`, `suggestedTitle`, `tagsCsv`, `fileSizeFormatted`, `sizeBytes`, `ocrText` FROM `documents`
                """.trimIndent())
                
                database.execSQL("DROP TABLE `documents`")
                database.execSQL("ALTER TABLE `documents_new` RENAME TO `documents`")

                // 2. Recreate pages table to remove defaults and ensure FK
                database.execSQL("""
                    CREATE TABLE `pages_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `documentId` INTEGER NOT NULL, 
                        `pageIndex` INTEGER NOT NULL, 
                        `rawImagePath` TEXT NOT NULL, 
                        `processedImagePath` TEXT NOT NULL, 
                        `filterType` TEXT NOT NULL, 
                        `rotationDegrees` INTEGER NOT NULL, 
                        `cropQuadJson` TEXT NOT NULL, 
                        `ocrText` TEXT NOT NULL, 
                        `ocrEntitiesJson` TEXT NOT NULL, 
                        `qualityStatus` TEXT NOT NULL, 
                        `createdAt` INTEGER NOT NULL, 
                        FOREIGN KEY(`documentId`) REFERENCES `documents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                
                database.execSQL("""
                    INSERT INTO `pages_new` (`id`, `documentId`, `pageIndex`, `rawImagePath`, `processedImagePath`, `filterType`, `rotationDegrees`, `cropQuadJson`, `ocrText`, `ocrEntitiesJson`, `qualityStatus`, `createdAt`)
                    SELECT `id`, `documentId`, `pageIndex`, `rawImagePath`, `processedImagePath`, `filterType`, `rotationDegrees`, `cropQuadJson`, `ocrText`, `ocrEntitiesJson`, `qualityStatus`, `createdAt` FROM `pages`
                """.trimIndent())
                
                database.execSQL("DROP TABLE `pages`")
                database.execSQL("ALTER TABLE `pages_new` RENAME TO `pages`")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_pages_documentId` ON `pages` (`documentId`)")

                // 3. Recreate signatures table to remove defaults
                database.execSQL("""
                    CREATE TABLE `signatures_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `title` TEXT NOT NULL, 
                        `imagePath` TEXT NOT NULL, 
                        `createdAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                
                database.execSQL("""
                    INSERT INTO `signatures_new` (`id`, `title`, `imagePath`, `createdAt`)
                    SELECT `id`, `title`, `imagePath`, `createdAt` FROM `signatures`
                """.trimIndent())
                
                database.execSQL("DROP TABLE `signatures`")
                database.execSQL("ALTER TABLE `signatures_new` RENAME TO `signatures`")
            }
        }

        @Volatile
        private var INSTANCE: DocScanDatabase? = null

        fun getInstance(context: Context): DocScanDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DocScanDatabase::class.java,
                    "docscan_master.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                 .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
