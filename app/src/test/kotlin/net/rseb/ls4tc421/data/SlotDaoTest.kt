package net.rseb.ls4tc421.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The slots table (D31) on the real Room and SQLite (Robolectric, in memory, on the JVM). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SlotDaoTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val devices = db.deviceDao()
    private val slots = db.slotDao()

    @After fun close() = db.close()

    private suspend fun device(name: String = "Tank", mac: String = "AABBCCDDEEFF"): Long =
        devices.insert(DeviceEntity(name = name, address = "192.0.2.10", mac = mac))

    @Test fun writtenIsReadBack() = runBlocking {
        val tank = device()
        slots.recordWritten(tank, slot = 3, programId = 7, name = "aquarium", at = 100, levels = "x")
        assertEquals(listOf(SlotEntity(tank, 3, SlotKind.WRITTEN, 7, "aquarium", 100, "x")), slots.get(tank))
    }

    @Test fun emptiedReplacesWritten() = runBlocking {
        val tank = device()
        slots.recordWritten(tank, 3, 7, "aquarium", 100, "x")
        slots.recordEmptied(tank, 3, "Vide 04", 200)
        assertEquals(listOf(SlotEntity(tank, 3, SlotKind.EMPTIED, null, "Vide 04", 200, null)), slots.get(tank))
    }

    @Test fun declareAllEmptyGivesTwentyRowsAndOverwrites() = runBlocking {
        val tank = device()
        slots.recordWritten(tank, 3, 7, "aquarium", 100, "x")
        slots.recordEmptied(tank, 5, "Vide 06", 150)
        slots.declareAllEmpty(tank, 300)
        val rows = slots.get(tank)
        assertEquals((0..19).toList(), rows.map { it.slot })
        assertTrue(rows.all { it == SlotEntity(tank, it.slot, SlotKind.DECLARED_EMPTY, null, null, 300, null) })
    }

    @Test fun forgetMakesTheSlotUnknown() = runBlocking {
        val tank = device()
        slots.recordWritten(tank, 3, 7, "aquarium", 100, "x")
        assertEquals(1, slots.forget(tank, 3))
        assertTrue(slots.get(tank).isEmpty())
        assertEquals(SlotState.Unknown(3), slots.observeStates(tank).first()[3])
        assertEquals(0, slots.forget(tank, 3))
    }

    @Test fun deletingADeviceDeletesItsSlots() = runBlocking {
        val tank = device()
        val nano = device("Nano", "112233445566")
        slots.declareAllEmpty(tank, 100)
        slots.recordWritten(nano, 0, 7, "aquarium", 100, "x")
        devices.delete(tank)
        assertTrue(slots.get(tank).isEmpty())
        assertEquals(1, slots.get(nano).size)
    }

    @Test fun nothingIsWrittenForADeviceThatIsGone() = runBlocking {
        slots.recordWritten(42, 3, 7, "aquarium", 100, "x")
        slots.declareAllEmpty(42, 100)
        assertTrue(slots.get(42).isEmpty())
    }

    @Test fun activeSlotIsSetAndWithdrawn() = runBlocking {
        val tank = device()
        assertNull(devices.get(tank)!!.activeSlot)
        devices.setActiveSlot(tank, 5)
        assertEquals(5, devices.get(tank)!!.activeSlot)
        devices.setActiveSlot(tank, null)
        assertNull(devices.get(tank)!!.activeSlot)
    }

    @Test fun theTwentyStatesUnknownOnesIncluded() = runBlocking {
        val tank = device()
        val steps = List(48) { listOf(1, 2, 3, 4, 5) }
        slots.recordWritten(tank, 0, 7, "aquarium", 100, steps.joinToString(";") { it.joinToString(",") })
        slots.recordEmptied(tank, 1, "Vide 02", 200)
        slots.put(tank, 2, SlotKind.DECLARED_EMPTY, null, null, 300, null)
        val states = slots.observeStates(tank).first()
        assertEquals(20, states.size)
        assertEquals((0..19).toList(), states.map { it.slot })
        assertEquals(SlotState.Written(0, 7, "aquarium", 100, steps), states[0])
        assertEquals(SlotState.Emptied(1, "Vide 02", 200), states[1])
        assertEquals(SlotState.DeclaredEmpty(2, 300), states[2])
        assertTrue(states.drop(3).all { it is SlotState.Unknown })
    }

    @Test fun placementsListTheWrittenSlotsOfEachProgram() = runBlocking {
        val tank = device()
        val nano = device("Nano", "112233445566")
        slots.recordWritten(tank, 4, 7, "aquarium", 100, "x")
        slots.recordWritten(tank, 9, 7, "aquarium", 200, "x")
        slots.recordWritten(nano, 0, 8, "court", 300, "x")
        slots.recordWritten(nano, 1, null, "lost", 300, "x") // not from the bank
        slots.recordEmptied(nano, 2, "Vide 03", 400)
        assertEquals(
            listOf(
                ProgramPlacement(8, nano, "Nano", 0, 300),
                ProgramPlacement(7, tank, "Tank", 4, 100),
                ProgramPlacement(7, tank, "Tank", 9, 200),
            ),
            slots.observePlacements().first(),
        )
    }
}
