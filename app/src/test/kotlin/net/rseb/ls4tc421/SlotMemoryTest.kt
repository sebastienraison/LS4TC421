package net.rseb.ls4tc421

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.rseb.ls4tc421.data.DeviceEntity
import net.rseb.ls4tc421.data.ProgramPlacement
import net.rseb.ls4tc421.data.SlotState
import net.rseb.ls4tc421.model.Aquarium
import net.rseb.ls4tc421.ui.send.defaultDevice
import net.rseb.ls4tc421.ui.send.defaultSlot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One memory per slot (D31) through the repositories, and the defaults of the send sheet. */
class SlotMemoryTest {
    private val env = TestEnv()

    @After fun tearDown() = env.close()

    @Test fun sentToFollowsTheWrittenSlots() = runBlocking {
        val tank = env.store(name = "Tank")
        val nano = env.store(name = "Nano", mac = "112233445566")
        val day = env.programs.create(Aquarium.RISE_ONLY)
        val night = env.programs.create(Aquarium.RISE_ONLY.renamed("night"))
        env.slots.recordWritten(tank.id, 4, day, "aquarium", 10, "x")
        env.slots.recordWritten(tank.id, 9, day, "aquarium", 20, "x")
        env.slots.recordWritten(nano.id, 0, day, "aquarium", 30, "x")
        env.slots.recordEmptied(nano.id, 1, "Vide 02", 40)

        val bank = env.programs.bank.first().associateBy { it.id }
        assertEquals(listOf("Nano", "Tank"), bank.getValue(day).devices) // each device once
        assertEquals(listOf(0, 4, 9), bank.getValue(day).placements.map { it.slot })
        assertEquals(emptyList<String>(), bank.getValue(night).devices)
        assertEquals(listOf(4, 9), env.programs.placements(day).first().filter { it.deviceId == tank.id }.map { it.slot })

        // Overwritten by another program, or forgotten: no longer « sent to ».
        env.slots.recordWritten(tank.id, 4, night, "night", 50, "x")
        env.repository.forgetSlot(tank.id, 9)
        val after = env.programs.bank.first().associateBy { it.id }
        assertEquals(listOf("Nano"), after.getValue(day).devices)
        assertEquals(listOf("Tank"), after.getValue(night).devices)

        // A deleted device takes its slots with it.
        env.repository.delete(nano.id)
        assertEquals(emptyList<String>(), env.programs.bank.first().first { it.id == day }.devices)
    }

    @Test fun repositoryDeclaresForgetsAndSetsTheActiveSlot() = runBlocking {
        val tank = env.store(name = "Tank")
        env.clock = 500L
        env.repository.declareAllEmpty(tank.id)
        assertEquals(List(20) { SlotState.DeclaredEmpty(it, 500L) }, env.repository.slots(tank.id).first())

        env.repository.forgetSlot(tank.id, 3)
        assertEquals(SlotState.Unknown(3), env.repository.slots(tank.id).first()[3])

        env.repository.setActiveSlot(tank.id, 3)
        assertEquals(3, env.dao.get(tank.id)!!.activeSlot)
        env.repository.setActiveSlot(tank.id, 20) // out of range: ignored
        assertEquals(3, env.dao.get(tank.id)!!.activeSlot)
        env.repository.setActiveSlot(tank.id, null)
        assertNull(env.dao.get(tank.id)!!.activeSlot)
    }

    @Test fun aNewDeviceHasTwentyUnknownSlots() = runBlocking {
        val tank = env.store(name = "Tank")
        val states = env.repository.slots(tank.id).first()
        assertEquals(20, states.size)
        assertTrue(states.all { it is SlotState.Unknown })
    }

    // ---- defaults of the send sheet ---------------------------------------------------------

    private val tank = DeviceEntity(id = 1, name = "Tank", address = "192.0.2.10", mac = "AABBCCDDEEFF")
    private val nano = DeviceEntity(id = 2, name = "Nano", address = "192.0.2.11", mac = "112233445566")
    private val devices = listOf(nano, tank) // in list order: Nano first

    @Test fun defaultDevice_presetFirst() {
        val placements = listOf(ProgramPlacement(7, 2, "Nano", 3, 100))
        assertEquals(tank, defaultDevice(devices, placements, presetId = 1))
    }

    @Test fun defaultDevice_whereTheProgramIsWrittenElseTheFirst() {
        assertEquals(tank, defaultDevice(devices, listOf(ProgramPlacement(7, 1, "Tank", 3, 100)), presetId = null))
        // Written on both: the most recent write.
        val both = listOf(ProgramPlacement(7, 2, "Nano", 0, 100), ProgramPlacement(7, 1, "Tank", 3, 200))
        assertEquals(tank, defaultDevice(devices, both, presetId = null))
        assertEquals(nano, defaultDevice(devices, emptyList(), presetId = null))
        assertEquals(nano, defaultDevice(devices, emptyList(), presetId = 99)) // unknown preset
        assertNull(defaultDevice(emptyList(), emptyList(), presetId = null))
    }

    @Test fun defaultSlot_whereTheProgramIsWrittenOnThatDeviceElseZero() {
        val placements = listOf(
            ProgramPlacement(7, 1, "Tank", 3, 100),
            ProgramPlacement(7, 1, "Tank", 11, 300),
            ProgramPlacement(7, 2, "Nano", 5, 400),
        )
        assertEquals(11, defaultSlot(1, placements)) // most recent on Tank
        assertEquals(5, defaultSlot(2, placements))
        assertEquals(0, defaultSlot(3, placements))
    }
}
