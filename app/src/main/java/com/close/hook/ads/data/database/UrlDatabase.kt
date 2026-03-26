package com.close.hook.ads.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.close.hook.ads.data.dao.CloudRuleEntryDao
import com.close.hook.ads.data.dao.CloudRuleSourceDao
import com.close.hook.ads.data.dao.UrlDao
import com.close.hook.ads.data.model.CloudRuleEntry
import com.close.hook.ads.data.model.CloudRuleSource
import com.close.hook.ads.data.model.Url

@Database(
    entities = [Url::class, CloudRuleSource::class, CloudRuleEntry::class],
    version = 5,
    exportSchema = false
)
abstract class UrlDatabase : RoomDatabase() {
    abstract val urlDao: UrlDao
    abstract val cloudRuleSourceDao: CloudRuleSourceDao
    abstract val cloudRuleEntryDao: CloudRuleEntryDao

    companion object {
        @Volatile
        private var instance: UrlDatabase? = null

        private val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE url_info_new (id INTEGER NOT NULL, url TEXT NOT NULL, PRIMARY KEY(id))")
                db.execSQL("INSERT INTO url_info_new (id, url) SELECT id, url FROM url_info")
                db.execSQL("DROP TABLE url_info")
                db.execSQL("ALTER TABLE url_info_new RENAME TO url_info")
            }
        }

        private val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE url_info ADD COLUMN type TEXT NOT NULL DEFAULT 'url'")
            }
        }

        private val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_url_info_url ON url_info(url)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_url_info_type ON url_info(type)")
            }
        }

        private val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cloud_rule_source` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `url` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `auto_update_enabled` INTEGER NOT NULL,
                        `update_interval_hours` INTEGER NOT NULL,
                        `last_check_at` INTEGER,
                        `last_success_at` INTEGER,
                        `last_error_message` TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_cloud_rule_source_url` ON `cloud_rule_source` (`url`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cloud_rule_source_enabled` ON `cloud_rule_source` (`enabled`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cloud_rule_source_auto_update_enabled` ON `cloud_rule_source` (`auto_update_enabled`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cloud_rule_entry` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `source_id` INTEGER NOT NULL,
                        `type` TEXT NOT NULL,
                        `url` TEXT NOT NULL,
                        FOREIGN KEY(`source_id`) REFERENCES `cloud_rule_source`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cloud_rule_entry_source_id` ON `cloud_rule_entry` (`source_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cloud_rule_entry_type` ON `cloud_rule_entry` (`type`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cloud_rule_entry_url` ON `cloud_rule_entry` (`url`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_cloud_rule_entry_source_id_type_url` ON `cloud_rule_entry` (`source_id`, `type`, `url`)")
            }
        }

        fun getDatabase(context: Context): UrlDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    UrlDatabase::class.java,
                    "url_database"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build().also {
                    instance = it
                }
            }
    }
}
