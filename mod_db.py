import re

with open('app/src/main/java/com/example/data/db/DocScanDatabase.kt', 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace('version = 1,', 'version = 2,')
content = content.replace('exportSchema = false', 'exportSchema = true')

imports = """import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration
"""

content = content.replace('import androidx.room.RoomDatabase', 'import androidx.room.RoomDatabase\n' + imports)

migration_code = """
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE documents ADD COLUMN sizeBytes INTEGER NOT NULL DEFAULT 0")
            }
        }
"""

content = content.replace('companion object {', 'companion object {' + migration_code)

old_builder = """                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DocScanDatabase::class.java,
                    "docscan_master.db"
                ).fallbackToDestructiveMigration(dropAllTables = true)
                 .build()"""

new_builder = """                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DocScanDatabase::class.java,
                    "docscan_master.db"
                ).addMigrations(MIGRATION_1_2)
                 .build()"""

content = content.replace(old_builder, new_builder)

with open('app/src/main/java/com/example/data/db/DocScanDatabase.kt', 'w', encoding='utf-8') as f:
    f.write(content)
