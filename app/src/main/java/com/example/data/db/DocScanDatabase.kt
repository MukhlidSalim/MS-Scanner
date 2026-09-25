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

@Database(
    entities = [DocumentEntity::class, PageEntity::class, SignatureEntity::class],
    version = 2,
    exportSchema = true
)
abstract class DocScanDatabase : RoomDatabase() {

    abstract fun documentDao(): DocumentDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE documents ADD COLUMN sizeBytes INTEGER NOT NULL DEFAULT 0")
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
                ).addMigrations(MIGRATION_1_2)
                 .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
