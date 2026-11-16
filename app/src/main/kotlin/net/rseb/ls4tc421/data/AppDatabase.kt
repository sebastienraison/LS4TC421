package net.rseb.ls4tc421.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 1: devices (lot 2a). Version 2: programs, and the last known upload of each device.
 * Version 3 (D31): one row per known slot of a device (`slots`) instead of the last known upload, and
 * the slot declared in service (`devices.activeSlot`).
 * The app is in use: every schema change needs an explicit migration that keeps the data
 * (schemas exported to app/schemas, migration tested on the JVM).
 */
@Database(entities = [DeviceEntity::class, ProgramEntity::class, SlotEntity::class], version = 3, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun programDao(): ProgramDao
    abstract fun slotDao(): SlotDao

    companion object {
        const val NAME = "ls4tc421.db"

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addMigrations(*Migrations.ALL)
                .build()
    }
}

object Migrations {
    /** Statements of 1 → 2, kept as plain SQL so that they can be read and tested. */
    val SQL_1_2 = listOf(
        "ALTER TABLE `devices` ADD COLUMN `lastUploadProgramId` INTEGER",
        "ALTER TABLE `devices` ADD COLUMN `lastUploadName` TEXT",
        "ALTER TABLE `devices` ADD COLUMN `lastUploadSlot` INTEGER",
        "ALTER TABLE `devices` ADD COLUMN `lastUploadAt` INTEGER",
        "ALTER TABLE `devices` ADD COLUMN `lastUploadLevels` TEXT",
        "CREATE TABLE IF NOT EXISTS `programs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`name` TEXT NOT NULL, `points` TEXT NOT NULL, `symmetryAxis` INTEGER, `links` TEXT NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
    )

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            SQL_1_2.forEach(db::execSQL)
        }
    }

    /** `devices` as in version 3; the migration creates it as [DEVICES_NEW] first. */
    const val DEVICES_NEW = "devices_new"

    private fun createDevices(table: String) =
        "CREATE TABLE IF NOT EXISTS `$table` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
            "`address` TEXT NOT NULL, `mac` TEXT NOT NULL, `lastSeenAt` INTEGER, `timeSetAt` INTEGER, `activeSlot` INTEGER)"

    /**
     * Statements of 2 → 3 (D31), plain SQL. SQLite before 3.35 (minSdk 26) has no `DROP COLUMN`, so
     * `devices` is rebuilt the way SQLite documents it (https://www.sqlite.org/lang_altertable.html,
     * « Making Other Kinds Of Table Schema Changes »: create the new table, copy, drop the old one,
     * rename the new one, recreate the indexes). Room runs a migration inside a transaction, where
     * `PRAGMA foreign_keys` cannot be changed: the last known uploads are therefore kept in a temporary
     * table while `devices` is rebuilt, and `slots` (whose foreign key points at `devices`) is created
     * only once the new `devices` is in place, so that dropping the old table can cascade into nothing.
     * Each last known upload becomes one WRITTEN slot; no slot is declared in service.
     */
    val SQL_2_3 = listOf(
        createDevices(DEVICES_NEW),
        "INSERT INTO `$DEVICES_NEW` (`id`, `name`, `address`, `mac`, `lastSeenAt`, `timeSetAt`, `activeSlot`) " +
            "SELECT `id`, `name`, `address`, `mac`, `lastSeenAt`, `timeSetAt`, NULL FROM `devices`",
        "CREATE TEMP TABLE `uploads_v2` AS SELECT `id` AS `deviceId`, `lastUploadSlot` AS `slot`, " +
            "`lastUploadProgramId` AS `programId`, `lastUploadName` AS `name`, `lastUploadAt` AS `at`, " +
            "`lastUploadLevels` AS `levels` FROM `devices` WHERE `lastUploadSlot` IS NOT NULL",
        "DROP TABLE `devices`",
        "ALTER TABLE `$DEVICES_NEW` RENAME TO `devices`",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_devices_mac` ON `devices` (`mac`)",
        "CREATE TABLE IF NOT EXISTS `slots` (`deviceId` INTEGER NOT NULL, `slot` INTEGER NOT NULL, " +
            "`state` TEXT NOT NULL, `programId` INTEGER, `name` TEXT, `at` INTEGER, `levels` TEXT, " +
            "PRIMARY KEY(`deviceId`, `slot`), FOREIGN KEY(`deviceId`) REFERENCES `devices`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_slots_programId` ON `slots` (`programId`)",
        "INSERT INTO `slots` (`deviceId`, `slot`, `state`, `programId`, `name`, `at`, `levels`) " +
            "SELECT `deviceId`, `slot`, 'WRITTEN', `programId`, `name`, `at`, `levels` FROM `temp`.`uploads_v2`",
        "DROP TABLE `temp`.`uploads_v2`",
    )

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            SQL_2_3.forEach(db::execSQL)
        }
    }

    val ALL = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
