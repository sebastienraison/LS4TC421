package net.rseb.ls4tc421.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.rseb.ls4tc421.model.Json
import net.rseb.ls4tc421.model.ProgramSpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Migrations 1 → 2 → 3 and 2 → 3 on a real SQLite (Robolectric, on the JVM). The old database is
 * created from its exported schema (app/schemas/.../N.json), i.e. exactly as the installed app created
 * it; Room then opens it at the current version, runs the migrations and checks the result against the
 * current entities (it throws if a table, column, index or foreign key differs).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"

    @Before fun clean() {
        context.deleteDatabase(name)
    }

    @After fun cleanAfter() {
        context.deleteDatabase(name)
    }

    private fun schema(version: Int): Map<*, *> {
        val schema = Json.parse(File("schemas/net.rseb.ls4tc421.data.AppDatabase/$version.json").readText()) as Map<*, *>
        return schema["database"] as Map<*, *>
    }

    private fun createSql(version: Int, table: String, renamedTo: String = table): String {
        val entity = (schema(version)["entities"] as List<*>).map { it as Map<*, *> }.first { it["tableName"] == table }
        return (entity["createSql"] as String).replace("\${TABLE_NAME}", renamedTo)
    }

    private fun createVersion1(fill: (SupportSQLiteDatabase) -> Unit) = createVersion(1, fill)

    private fun createVersion(version: Int, fill: (SupportSQLiteDatabase) -> Unit) {
        val database = schema(version)
        assertEquals(version.toLong(), database["version"])
        val statements = buildList {
            for (entity in database["entities"] as List<*>) {
                entity as Map<*, *>
                val table = entity["tableName"] as String
                add((entity["createSql"] as String).replace("\${TABLE_NAME}", table))
                for (index in entity["indices"] as List<*>? ?: emptyList<Any>()) {
                    add(((index as Map<*, *>)["createSql"] as String).replace("\${TABLE_NAME}", table))
                }
            }
            addAll((database["setupQueries"] as List<*>).map { it as String })
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) = statements.forEach(db::execSQL)
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        fill(helper.writableDatabase)
        helper.close()
    }

    private fun openCurrent(withMigration: Boolean = true): AppDatabase {
        val builder = Room.databaseBuilder(context, AppDatabase::class.java, name).allowMainThreadQueries()
        if (withMigration) builder.addMigrations(*Migrations.ALL)
        return builder.build()
    }

    @Test fun devicesSurviveTheMigrationAndProgramsCanBeStored() = runBlocking {
        createVersion1 { db ->
            db.execSQL(
                "INSERT INTO devices (name, address, mac, lastSeenAt, timeSetAt) " +
                    "VALUES ('Tank', '192.0.2.10', 'AABBCCDDEEFF', 1000, 2000)",
            )
        }
        val db = openCurrent()
        try {
            val devices = db.deviceDao().getByMac("AABBCCDDEEFF")
            assertEquals("Tank", devices?.name)
            assertEquals("192.0.2.10", devices?.address)
            assertEquals(1000L, devices?.lastSeenAt)
            assertEquals(2000L, devices?.timeSetAt)
            assertNull(devices?.activeSlot)
            assertTrue(db.slotDao().get(devices!!.id).isEmpty())

            val repo = ProgramRepository(db.programDao(), db.slotDao(), now = { 5L })
            val id = repo.create(ProgramSpec("aquarium").withPoint(0, 20, 100))
            db.slotDao().recordWritten(devices.id, 3, id, "aquarium", 6L, "x")
            assertEquals(listOf(SlotEntity(devices.id, 3, SlotKind.WRITTEN, id, "aquarium", 6L, "x")), db.slotDao().get(devices.id))
            assertEquals(ProgramSpec("aquarium").withPoint(0, 20, 100), repo.get(id))
        } finally {
            db.close()
        }
    }

    @Test fun withoutTheMigrationTheDatabaseIsNotWipedButRefused() {
        createVersion1 { it.execSQL("INSERT INTO devices (name, address, mac) VALUES ('Tank', '192.0.2.10', 'AABBCCDDEEFF')") }
        val db = openCurrent(withMigration = false)
        try {
            db.openHelper.writableDatabase
            fail("a missing migration must not go unnoticed")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("migration", ignoreCase = true))
        } finally {
            db.close()
        }
    }

    @Test fun migrationStatementsMatchTheExportedVersion2Schema() {
        val schema = Json.parse(File("schemas/net.rseb.ls4tc421.data.AppDatabase/2.json").readText()) as Map<*, *>
        val programs = ((schema["database"] as Map<*, *>)["entities"] as List<*>)
            .map { it as Map<*, *> }
            .first { it["tableName"] == "programs" }
        val create = (programs["createSql"] as String).replace("\${TABLE_NAME}", "programs")
        assertEquals(create, Migrations.SQL_1_2.last())
    }

    // ---- 2 → 3 (D31) -------------------------------------------------------------------------

    private val levels = "0,0,0,0,0;" + List(47) { "10,20,30,40,50" }.joinToString(";")

    private fun fillVersion2(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO programs (id, name, points, symmetryAxis, links, createdAt, updatedAt) " +
                "VALUES (7, 'aquarium', '0:20||||', NULL, '||||', 100, 200), (8, 'court', '||||', 24, '||||', 300, 400)",
        )
        db.execSQL(
            "INSERT INTO devices (id, name, address, mac, lastSeenAt, timeSetAt, lastUploadProgramId, lastUploadName, " +
                "lastUploadSlot, lastUploadAt, lastUploadLevels) " +
                "VALUES (1, 'Tank', '192.0.2.10', 'AABBCCDDEEFF', 1000, 2000, 7, 'aquarium', 4, 3000, '$levels')",
        )
        db.execSQL(
            "INSERT INTO devices (id, name, address, mac, lastSeenAt, timeSetAt) " +
                "VALUES (2, 'Nano', '192.0.2.11', '112233445566', NULL, 4000)",
        )
    }

    @Test fun version2_devicesProgramsAndTheLastUploadSurviveAsOneWrittenSlot() = runBlocking {
        createVersion(2, ::fillVersion2)
        val db = openCurrent()
        try {
            val devices = db.deviceDao().observeAll().first()
            assertEquals(
                listOf(
                    DeviceEntity(2, "Nano", "192.0.2.11", "112233445566", lastSeenAt = null, timeSetAt = 4000, activeSlot = null),
                    DeviceEntity(1, "Tank", "192.0.2.10", "AABBCCDDEEFF", lastSeenAt = 1000, timeSetAt = 2000, activeSlot = null),
                ),
                devices,
            )
            // One WRITTEN row, copied as it was; nothing for the device without an upload.
            assertEquals(listOf(SlotEntity(1, 4, SlotKind.WRITTEN, 7, "aquarium", 3000, levels)), db.slotDao().get(1))
            assertTrue(db.slotDao().get(2).isEmpty())
            assertEquals(listOf(ProgramPlacement(7, 1, "Tank", 4, 3000)), db.slotDao().observePlacements().first())

            // Programs untouched.
            val programs = db.programDao().observeAll().first()
            assertEquals(listOf(7L to "aquarium", 8L to "court"), programs.map { it.id to it.name })
            assertEquals(ProgramEntity(8, "court", "||||", 24, "||||", 300, 400), db.programDao().get(8))

            // The MAC is still unique.
            try {
                db.deviceDao().insert(DeviceEntity(name = "Copy", address = "192.0.2.12", mac = "AABBCCDDEEFF"))
                fail("the unique index on mac must be back")
            } catch (_: SQLiteConstraintException) {
            }

            // Nothing left over, and the foreign key holds.
            val sql = db.openHelper.writableDatabase
            sql.query("SELECT name FROM sqlite_master WHERE name IN ('devices_new', 'uploads_v2')").use { assertEquals(0, it.count) }
            sql.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
            db.deviceDao().delete(1)
            assertTrue(db.slotDao().get(1).isEmpty())
        } finally {
            db.close()
        }
    }

    @Test fun version2_withoutTheMigrationIsRefused() {
        createVersion(2, ::fillVersion2)
        val db = openCurrent(withMigration = false)
        try {
            db.openHelper.writableDatabase
            fail("a missing migration must not go unnoticed")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("migration", ignoreCase = true))
        } finally {
            db.close()
        }
    }

    @Test fun migrationStatementsMatchTheExportedVersion3Schema() {
        assertEquals(createSql(3, "devices", renamedTo = Migrations.DEVICES_NEW), Migrations.SQL_2_3[0])
        assertTrue(Migrations.SQL_2_3.contains(createSql(3, "slots")))
        val indices = (schema(3)["entities"] as List<*>).map { it as Map<*, *> }.flatMap { entity ->
            (entity["indices"] as List<*>? ?: emptyList<Any>()).map {
                ((it as Map<*, *>)["createSql"] as String).replace("\${TABLE_NAME}", entity["tableName"] as String)
            }
        }
        assertEquals(2, indices.size)
        indices.forEach { assertTrue(it, Migrations.SQL_2_3.contains(it)) }
    }
}
