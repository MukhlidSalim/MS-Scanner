package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.model.DocumentEntity
import com.example.data.model.PageEntity
import com.example.data.model.SignatureEntity

@Database(
    entities = [DocumentEntity::class, PageEntity::class, SignatureEntity::class],
    version = 1,
    exportSchema = false
)
abstract class DocScanDatabase : RoomDatabase() {

    abstract fun documentDao(): DocumentDao

    companion object {
        @Volatile
        private var INSTANCE: DocScanDatabase? = null

        fun getInstance(context: Context): DocScanDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DocScanDatabase::class.java,
                    "docscan_master.db"
                ).fallbackToDestructiveMigration(dropAllTables = true)
                 .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
