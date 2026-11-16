package net.rseb.ls4tc421.link

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.rseb.ls4tc421.TestEnv
import net.rseb.ls4tc421.data.ClientSource
import net.rseb.ls4tc421.data.DeviceEntity
import net.rseb.ls4tc421.data.SlotEntity
import net.rseb.ls4tc421.data.SlotKind
import net.rseb.ls4tc421.data.SlotState
import net.rseb.ls4tc421.model.Aquarium
import net.rseb.ls4tc421.model.EmptySlot
import net.rseb.ls4tc421.model.EmptySlotNaming
import net.rseb.ls4tc421.model.StoredForm
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** « Empty a slot » (D30) with the real client against the simulator, on 127.0.0.1. Nothing leaves the machine. */
class EmptySlotUploadTest {
    private val env = TestEnv(attempts = 3)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val french = "Vide %1\$02d"
    private var clock = 10_000L

    @After fun tearDown() {
        scope.cancel()
        env.close()
    }

    private fun uploader() = ProgramUploader(ClientSource { env.client }, env.dao, env.slots, env.frames, env.guard, scope, now = { clock })

    private suspend fun tank(): DeviceEntity = env.store(name = "Tank")

    private suspend fun ProgramUploader.sendFromBank(deviceId: Long, programId: Long, slot: Int) {
        val spec = env.programs.get(programId)!!
        start(deviceId, programId, spec, slot).join()
        assertEquals(UploadPhase.Done(clock, spec.name, slot), phase.value)
    }

    @Test fun emptyingWritesZeroInTheChosenSlotUnderTheNumberedName() = runBlocking {
        val tank = tank()
        val up = uploader()
        up.empty(tank.id, slot = 2, program = EmptySlot.program(2, EmptySlotNaming.NUMBERED, french)).join()

        assertEquals(UploadPhase.Done(clock, "Vide 03", 2), up.phase.value)
        val written = env.sim.programs.getValue(2)
        assertEquals("Vide 03", written.name)
        assertEquals(EmptySlot.levels, written.steps)
        assertEquals(setOf(2), env.sim.programs.keys)
        assertEquals(null, env.guard.inUse)
        // The slot is now known as emptied by the app (D31), under the name written.
        assertEquals(listOf(SlotEntity(tank.id, 2, SlotKind.EMPTIED, null, "Vide 03", clock, null)), env.slots.all)
        assertEquals(SlotState.Emptied(2, "Vide 03", clock), env.repository.slots(tank.id).first()[2])
    }

    @Test fun emptyingAsNewWritesOneSpace() = runBlocking {
        val tank = tank()
        val up = uploader()
        up.empty(tank.id, slot = 2, program = EmptySlot.program(2, EmptySlotNaming.AS_NEW, french)).join()

        assertEquals(" ", env.sim.programs.getValue(2).name)
        assertEquals(EmptySlot.levels, env.sim.programs.getValue(2).steps)
    }

    @Test fun emptyingAWrittenSlotReplacesItsRow() = runBlocking {
        val tank = tank()
        val programId = env.programs.create(Aquarium.RISE_ONLY)
        val up = uploader()
        up.sendFromBank(tank.id, programId, slot = 4)
        assertEquals(SlotKind.WRITTEN, env.slots.all.single().state)

        clock = 20_000L
        up.empty(tank.id, slot = 4, program = EmptySlot.program(4, EmptySlotNaming.NUMBERED, french)).join()

        assertEquals(UploadPhase.Done(20_000L, "Vide 05", 4), up.phase.value)
        assertEquals(listOf(SlotEntity(tank.id, 4, SlotKind.EMPTIED, null, "Vide 05", 20_000L, null)), env.slots.all)
        assertEquals(tank.copy(lastSeenAt = env.dao.get(tank.id)!!.lastSeenAt), env.dao.get(tank.id)) // the device row is untouched
        // The bank no longer says the program is on this device.
        assertEquals(emptyList<String>(), env.programs.bank.first().single().devices)
    }

    @Test fun emptyingAnotherSlotLeavesTheWrittenSlotAsItWas() = runBlocking {
        val tank = tank()
        val programId = env.programs.create(Aquarium.RISE_ONLY)
        val up = uploader()
        up.sendFromBank(tank.id, programId, slot = 4)
        val before = env.slots.all.single()

        clock = 20_000L
        up.empty(tank.id, slot = 2, program = EmptySlot.program(2, EmptySlotNaming.NUMBERED, french)).join()

        assertEquals(UploadPhase.Done(20_000L, "Vide 03", 2), up.phase.value)
        assertEquals(before, env.slots.all.single { it.slot == 4 })
        assertEquals(SlotKind.EMPTIED, env.slots.all.single { it.slot == 2 }.state)
        assertEquals(Aquarium.STEPS, env.sim.programs.getValue(4).steps)
        assertEquals(EmptySlot.levels, env.sim.programs.getValue(2).steps)
    }

    @Test fun theBankIsLeftIntactAndItsProgramSendsAgain() = runBlocking {
        val tank = tank()
        val programId = env.programs.create(Aquarium.RISE_ONLY)
        val rowBefore = env.programDao.get(programId)!!
        val specBefore = env.programs.get(programId)!!
        val countBefore = env.programDao.all.size
        val up = uploader()
        up.sendFromBank(tank.id, programId, slot = 4)

        clock = 20_000L
        up.empty(tank.id, slot = 4, program = EmptySlot.program(4, EmptySlotNaming.NUMBERED, french)).join()
        assertEquals(EmptySlot.levels, env.sim.programs.getValue(4).steps)

        // Same identifier, name, points and 48 steps; no program added.
        assertEquals(rowBefore, env.programDao.get(programId))
        val specAfter = env.programs.get(programId)!!
        assertEquals(specBefore, specAfter)
        assertEquals("aqua", specAfter.name)
        assertEquals(Aquarium.STEPS, specAfter.levels())
        assertEquals(countBefore, env.programDao.all.size)

        // It sends again as usual.
        clock = 30_000L
        up.sendFromBank(tank.id, programId, slot = 4)
        assertEquals("aqua", env.sim.programs.getValue(4).name)
        assertEquals(Aquarium.STEPS, env.sim.programs.getValue(4).steps)
        val stored = env.slots.all.single()
        assertEquals(SlotKind.WRITTEN, stored.state)
        assertEquals(programId, stored.programId)
        assertEquals(4, stored.slot)
        assertEquals(30_000L, stored.at)
    }

    // ---- one memory per slot (D31) --------------------------------------------------------

    @Test fun twoUploadsOnTwoSlotsGiveTwoRows() = runBlocking {
        val tank = tank()
        val first = env.programs.create(Aquarium.RISE_ONLY)
        val second = env.programs.create(Aquarium.RISE_ONLY.renamed("second"))
        val up = uploader()
        up.sendFromBank(tank.id, first, slot = 4)
        clock = 20_000L
        up.sendFromBank(tank.id, second, slot = 9)

        // The former model kept only the last upload: slot 4 was lost.
        assertEquals(
            listOf(
                SlotEntity(tank.id, 4, SlotKind.WRITTEN, first, "aqua", 10_000L, StoredForm.levels(Aquarium.STEPS)),
                SlotEntity(tank.id, 9, SlotKind.WRITTEN, second, "second", 20_000L, StoredForm.levels(Aquarium.STEPS)),
            ),
            env.slots.all.sortedBy { it.slot },
        )
        val bank = env.programs.bank.first().associateBy { it.id }
        assertEquals(listOf("Tank"), bank.getValue(first).devices)
        assertEquals(listOf(4), bank.getValue(first).placements.map { it.slot })
        assertEquals(listOf(9), bank.getValue(second).placements.map { it.slot })
    }
}
