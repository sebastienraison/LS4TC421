package net.rseb.ls4tc421

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import ls4tc421.protocol.WriteStage
import net.rseb.ls4tc421.data.ClientSource
import net.rseb.ls4tc421.data.SlotState
import net.rseb.ls4tc421.link.ProgramUploader
import net.rseb.ls4tc421.link.UploadFailure
import net.rseb.ls4tc421.link.UploadPhase
import net.rseb.ls4tc421.model.Aquarium
import net.rseb.ls4tc421.model.EmptySlot
import net.rseb.ls4tc421.model.EmptySlotNaming
import net.rseb.ls4tc421.ui.detail.ContentsAnswer
import net.rseb.ls4tc421.ui.detail.DeviceDetailViewModel
import net.rseb.ls4tc421.ui.send.SendViewModel
import net.rseb.ls4tc421.ui.send.onlyCloseFailed
import net.rseb.ls4tc421.ui.send.showsRestartHint
import net.rseb.ls4tc421.ui.slots.EmptySlotsViewModel
import net.rseb.ls4tc421.ui.slots.EmptyingRun
import net.rseb.ls4tc421.ui.slots.InService
import net.rseb.ls4tc421.ui.slots.TileLook
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val FRENCH = "Vide %1\$02d"

private fun TestEnv.uploaderFactory(): (CoroutineScope) -> ProgramUploader =
    { scope -> ProgramUploader(ClientSource { client }, dao, slots, frames, guard, scope, now = { clock }) }

/** The write frames received by the simulator, one letter each: O = `01 01`, S = `01 02`, E = `03 08`, C = `04 0A`. */
private fun TestEnv.writeFrames(): String = sim.received.joinToString("") { frame ->
    val cmd = frame[2].toInt() and 0xFF
    val sub = frame[3].toInt() and 0xFF
    when {
        cmd == 0x01 && sub == 0x01 -> "O"
        cmd == 0x01 && sub == 0x02 -> "S"
        cmd == 0x03 && sub == 0x08 -> "E"
        cmd == 0x04 && sub == 0x0A -> "C"
        else -> ""
    }
}

/** One slot of a write session (D32): its `04 0A` comes once, after the last slot. */
private val ONE_WRITE = "O" + "S".repeat(48) + "E"

/** « Empty… » by selection (D30, D31), with the real client against the simulator. Nothing leaves the machine. */
@OptIn(ExperimentalCoroutinesApi::class)
class EmptySlotsViewModelTest {
    private lateinit var env: TestEnv
    private lateinit var vm: EmptySlotsViewModel
    private var tankId = 0L

    @Before fun setUp() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        // A `01 01` within a second of a `04 0A` is ignored: one session per emptying, or the simulator goes silent.
        env = TestEnv(attempts = 2, quirks = TestEnv.SESSION)
        tankId = env.store("Tank").id
        vm = EmptySlotsViewModel(tankId, flowOf(EmptySlotNaming.NUMBERED), env.uploaderFactory())
    }

    @After fun tearDown() {
        vm.viewModelScope.cancel()
        env.close()
        Dispatchers.resetMain()
    }

    private suspend fun states() = env.repository.slots(tankId).first()

    @Test fun selection_openToggleSelectAllClose() {
        assertNull(vm.selection.value)
        vm.open()
        assertEquals(emptySet<Int>(), vm.selection.value)
        vm.toggle(3); vm.toggle(5); vm.toggle(3)
        assertEquals(setOf(5), vm.selection.value)
        vm.selectAll()
        assertEquals((0..19).toSet(), vm.selection.value)
        vm.close()
        assertNull(vm.selection.value)
        vm.open(setOf(6)) // « Empty this slot »
        assertEquals(setOf(6), vm.selection.value)
    }

    @Test fun threeSlots_threeSuccessiveWritesAndThreeEmptied() = runBlocking {
        env.clock = 5_000L
        vm.open(setOf(6, 3, 4))
        vm.empty(FRENCH)
        assertEquals(EmptyingRun.Done(3), vm.run.await { it is EmptyingRun.Done })

        // D32: one write session, one slot after the other in slot order, a single 04 0A at the end.
        assertEquals(ONE_WRITE.repeat(3) + "C", env.writeFrames())
        assertFalse(env.sim.silent)
        assertEquals(setOf(3, 4, 6), env.sim.programs.keys)
        assertEquals("Vide 04", env.sim.programs.getValue(3).name)
        assertEquals("Vide 07", env.sim.programs.getValue(6).name)
        assertTrue(env.sim.programs.values.all { it.steps == EmptySlot.levels })
        val states = states()
        assertEquals(SlotState.Emptied(3, "Vide 04", 5_000L), states[3])
        assertEquals(SlotState.Emptied(4, "Vide 05", 5_000L), states[4])
        assertEquals(SlotState.Emptied(6, "Vide 07", 5_000L), states[6])
        assertEquals(17, states.count { it is SlotState.Unknown })
        assertEquals(null, env.guard.inUse)
    }

    @Test fun failureOnTheSecond_firstEmptiedOthersUnchanged_resumeFinishes() = runBlocking {
        // Slot 2 was written before: it must keep that state until it is really emptied.
        val programId = env.programs.create(Aquarium.RISE_ONLY)
        env.slots.recordWritten(tankId, 2, programId, "aquarium", 1L, "x")
        // 50 acknowledgements per slot: acks 60 and 61 are step 8 of the second slot, both attempts.
        env.sim.faults.dropNth(60, 61)
        vm.open(setOf(1, 2, 3))
        vm.empty(FRENCH)

        val failed = vm.run.await { it is EmptyingRun.Failed } as EmptyingRun.Failed
        assertEquals(2, failed.slot)
        assertEquals(2, failed.number)
        assertEquals(3, failed.total)
        assertEquals(WriteStage.Step(8), failed.failure.stage)
        assertEquals(UploadFailure.TimedOut(2), failed.failure.reason)
        assertFalse(onlyCloseFailed(failed.failure)) // the usual failure text, with the restart advice
        var states = states()
        assertTrue(states[1] is SlotState.Emptied)
        assertTrue(states[2] is SlotState.Written) // unchanged
        assertEquals(SlotState.Unknown(3), states[3]) // unchanged
        assertEquals(setOf(1), env.sim.programs.keys)

        // « Resume »: from the failed frame of slot 2, then slot 3.
        vm.resume()
        assertEquals(EmptyingRun.Done(3), vm.run.await { it is EmptyingRun.Done })
        states = states()
        assertTrue((1..3).all { states[it] is SlotState.Emptied })
        assertEquals(setOf(1, 2, 3), env.sim.programs.keys)
        val frames = env.writeFrames()
        assertEquals(3, frames.count { it == 'O' }) // slot 2 was not opened again
        assertEquals(3, frames.count { it == 'E' })
        assertEquals(1, frames.count { it == 'C' }) // nothing was sent on the failure, one close at the end
        assertTrue(frames.endsWith("EC"))
    }

    @Test fun failureThenClose_nothingMoreIsSent() = runBlocking {
        env.sim.faults.dropNth(60, 61) // step 8 of the second slot, both attempts
        vm.open(setOf(1, 2, 3))
        vm.empty(FRENCH)
        vm.run.await { it is EmptyingRun.Failed }
        val sent = env.sim.received.size
        vm.close()
        Thread.sleep(300)
        assertEquals(sent, env.sim.received.size)
        assertFalse(env.writeFrames().contains('C'))
    }

    /** D32: when only the 04 0A fails, the slots stay emptied and « Resume » sends the 04 0A alone. */
    @Test fun failedClose_slotsStayEmptied_resumeClosesOnly() = runBlocking {
        env.sim.faults.dropNth(101, 102) // 2 slots × 50 acks, then 04 0A: both attempts
        vm.open(setOf(4, 9))
        vm.empty(FRENCH)

        val failed = vm.run.await { it is EmptyingRun.Failed } as EmptyingRun.Failed
        assertEquals(9, failed.slot)
        assertEquals(2, failed.number)
        assertEquals(2, failed.total)
        assertEquals(WriteStage.Finish, failed.failure.stage)
        assertEquals(UploadFailure.TimedOut(2), failed.failure.reason)
        assertTrue(onlyCloseFailed(failed.failure)) // « The 2 slots are emptied… », no restart advice
        var states = states()
        assertTrue(states[4] is SlotState.Emptied)
        assertTrue(states[9] is SlotState.Emptied)
        assertEquals(ONE_WRITE.repeat(2) + "CC", env.writeFrames())

        vm.resume()
        assertEquals(EmptyingRun.Done(2), vm.run.await { it is EmptyingRun.Done })
        assertEquals(ONE_WRITE.repeat(2) + "CCC", env.writeFrames())
        states = states()
        assertTrue(states[4] is SlotState.Emptied && states[9] is SlotState.Emptied)
        assertEquals(null, env.guard.inUse)
    }

    @Test fun cancel_stopsAfterTheSlotInHand() = runBlocking {
        env.sim.faults.delayAll(5) // the first write lasts long enough to be cancelled
        vm.open(setOf(0, 1, 2))
        vm.empty(FRENCH)
        vm.run.await { it is EmptyingRun.Writing && it.frames.completed >= 2 }
        vm.cancel()
        assertTrue((vm.run.value as EmptyingRun.Writing).stopping)
        vm.close() // refused while writing
        assertEquals(setOf(0, 1, 2), vm.selection.value)

        assertEquals(EmptyingRun.Stopped(1, 3), vm.run.await { it is EmptyingRun.Stopped })
        assertEquals(setOf(0), env.sim.programs.keys) // the slot in hand was finished, not the others
        assertEquals(ONE_WRITE, env.writeFrames()) // no 04 0A on a cancel (D32)
        assertTrue(states()[0] is SlotState.Emptied)

        env.sim.faults.clear()
        vm.resume() // the session goes on
        assertEquals(EmptyingRun.Done(3), vm.run.await { it is EmptyingRun.Done })
        assertEquals(setOf(0, 1, 2), env.sim.programs.keys)
        assertEquals(ONE_WRITE.repeat(3) + "C", env.writeFrames())
    }

    @Test fun cancelOnTheLastSlot_noClose_resumeCloses() = runBlocking {
        env.sim.faults.delayAll(5)
        vm.open(setOf(7))
        vm.empty(FRENCH)
        vm.run.await { it is EmptyingRun.Writing && it.frames.completed >= 2 }
        vm.cancel()
        assertEquals(EmptyingRun.Stopped(1, 1), vm.run.await { it is EmptyingRun.Stopped })
        assertEquals(ONE_WRITE, env.writeFrames())
        assertTrue(states()[7] is SlotState.Emptied)

        env.sim.faults.clear()
        vm.resume()
        assertEquals(EmptyingRun.Done(1), vm.run.await { it is EmptyingRun.Done })
        assertEquals(ONE_WRITE + "C", env.writeFrames())
    }

    @Test fun nothingChecked_nothingSent() = runBlocking {
        vm.open()
        vm.empty(FRENCH)
        assertEquals(EmptyingRun.Idle, vm.run.value)
        assertTrue(env.sim.received.isEmpty())
    }
}

/** Send sheet from a device page (D31): device fixed, slot on the grid or fixed, « Done, it is in service ». */
@OptIn(ExperimentalCoroutinesApi::class)
class SendFromDevicePageTest {
    private lateinit var env: TestEnv

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        env = TestEnv()
    }

    @After fun tearDown() {
        env.close()
        Dispatchers.resetMain()
    }

    @Test fun deviceIsFixed_slotChosenOnTheGrid_thenDeclaredInService() = runBlocking {
        val tank = env.store("Tank")
        val nano = env.store("Nano", mac = "112233445566")
        val programId = env.programs.create(Aquarium.RISE_ONLY)
        val vm = SendViewModel(programId, tank.id, null, env.repository, env.programs, env.uploaderFactory())
        vm.devices.await { it?.size == 2 }
        assertTrue(vm.deviceLocked)
        vm.selectDevice(nano) // refused: the device is fixed
        assertEquals(tank.id, vm.deviceId.value)
        vm.slots.await { it?.size == 20 }

        vm.selectSlot(5)
        vm.send()
        val done = vm.phase.await { it is UploadPhase.Done } as UploadPhase.Done
        assertEquals(5, done.slot)
        assertEquals(setOf(5), env.sim.programs.keys)
        assertTrue(env.repository.slots(tank.id).first()[5] is SlotState.Written)
        assertNull(env.dao.get(tank.id)!!.activeSlot)

        // « Done, it is in service ».
        vm.declareInService()
        assertEquals(5, vm.declared.await { it != null })
        assertEquals(5, env.dao.get(tank.id)!!.activeSlot)
        vm.viewModelScope.cancel()
    }

    @Test fun fromASlotSheet_theSlotIsFixed() = runBlocking {
        val tank = env.store("Tank")
        val programId = env.programs.create(Aquarium.RISE_ONLY)
        // Written elsewhere before: the default slot would be 9, the fixed slot wins.
        env.slots.recordWritten(tank.id, 9, programId, "aquarium", 1L, "x")
        val vm = SendViewModel(programId, tank.id, 3, env.repository, env.programs, env.uploaderFactory())
        vm.devices.await { it?.size == 1 }
        assertEquals(3, vm.slot.value)
        vm.selectSlot(6) // refused
        assertEquals(3, vm.slot.value)
        vm.send()
        vm.phase.await { it is UploadPhase.Done }
        assertEquals(setOf(3), env.sim.programs.keys)
        vm.viewModelScope.cancel()
    }

    /** D32: only the 04 0A of a send fails: the program is written, the failure names it and its slot. */
    @Test fun failedClose_programWritten_failureNamesTheProgramAndTheSlot() = runBlocking {
        val tank = env.store("Tank")
        val programId = env.programs.create(Aquarium.RISE_ONLY)
        env.sim.faults.dropNth(51, 52) // 50 acks for the program, then its 04 0A: both attempts
        val vm = SendViewModel(programId, tank.id, 3, env.repository, env.programs, env.uploaderFactory())
        vm.devices.await { it?.size == 1 }
        vm.send()

        val failed = vm.phase.await { it is UploadPhase.Failed } as UploadPhase.Failed
        assertEquals(UploadPhase.Failed(WriteStage.Finish, 51, 51, UploadFailure.TimedOut(2)), failed)
        assertTrue(onlyCloseFailed(failed)) // « “aqua” is written in slot 4… », no restart advice
        assertEquals("aqua", vm.upload!!.program.name)
        assertEquals(3, vm.upload!!.slot)
        assertEquals(setOf(3), env.sim.programs.keys)
        assertTrue(env.repository.slots(tank.id).first()[3] is SlotState.Written)
        vm.viewModelScope.cancel()
    }

    /** A failure before the 04 0A keeps the usual failure text and its restart advice. */
    @Test fun failedStep_isNotACloseOnlyFailure() = runBlocking {
        val tank = env.store("Tank")
        val programId = env.programs.create(Aquarium.RISE_ONLY)
        env.sim.faults.dropNth(10, 11) // step 9, both attempts
        val vm = SendViewModel(programId, tank.id, 3, env.repository, env.programs, env.uploaderFactory())
        vm.devices.await { it?.size == 1 }
        vm.send()

        val failed = vm.phase.await { it is UploadPhase.Failed } as UploadPhase.Failed
        assertEquals(WriteStage.Step(8), failed.stage)
        assertFalse(onlyCloseFailed(failed))
        assertTrue(showsRestartHint(failed.reason))
        vm.viewModelScope.cancel()
    }

    @Test fun fromTheProgramsTab_theDeviceCanBeChosen() = runBlocking {
        val tank = env.store("Tank")
        val nano = env.store("Nano", mac = "112233445566")
        val programId = env.programs.create(Aquarium.RISE_ONLY)
        val vm = SendViewModel(programId, null, null, env.repository, env.programs, env.uploaderFactory())
        vm.devices.await { it?.size == 2 }
        assertFalse(vm.deviceLocked)
        vm.selectDevice(tank)
        assertEquals(tank.id, vm.deviceId.value)
        vm.selectDevice(nano)
        assertEquals(nano.id, vm.deviceId.value)
        vm.viewModelScope.cancel()
    }
}

/** The device page's view of the slots and the question asked when a device is added (D31). */
@OptIn(ExperimentalCoroutinesApi::class)
class DevicePageSlotsTest {
    private lateinit var env: TestEnv

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        env = TestEnv()
    }

    @After fun tearDown() {
        env.close()
        Dispatchers.resetMain()
    }

    @Test fun cardAndTilesFollowTheSlotsAndTheDeclaration() = runBlocking {
        val tank = env.store("Tank")
        val vm = DeviceDetailViewModel(tank.id, env.repository)
        var view = vm.slots.await { it != null }!!
        assertEquals(InService.NotDeclared, view.inService)
        assertTrue(view.tiles.all { it.look == TileLook.UNKNOWN })

        env.slots.recordWritten(tank.id, 0, null, "aquarium", 10L, "x")
        vm.setActiveSlot(0)
        view = vm.slots.await { it?.inService is InService.Declared && it.tiles[0].look == TileLook.WRITTEN }!!
        assertEquals(0, (view.inService as InService.Declared).slot)
        assertTrue(view.tiles[0].active)

        vm.forgetSlot(0)
        view = vm.slots.await { it?.tiles?.get(0)?.look == TileLook.UNKNOWN }!!
        assertTrue(view.tiles[0].active) // still declared in service, content now unknown
        vm.setActiveSlot(null) // « I don't know »
        vm.slots.await { it?.inService == InService.NotDeclared }
        vm.viewModelScope.cancel()
    }

    @Test fun contents_keep_twentyUnknownNothingSent() = runBlocking {
        val tank = env.store("Tank")
        val vm = DeviceDetailViewModel(tank.id, env.repository)
        vm.answerContents(ContentsAnswer.KEEP)
        assertTrue(env.repository.slots(tank.id).first().all { it is SlotState.Unknown })
        assertTrue(env.sim.received.isEmpty())
        vm.viewModelScope.cancel()
    }

    @Test fun contents_newOrEmptied_twentyDeclaredEmptyNothingSent() = runBlocking {
        val tank = env.store("Tank")
        env.clock = 42L
        val vm = DeviceDetailViewModel(tank.id, env.repository)
        vm.answerContents(ContentsAnswer.NEW_OR_EMPTIED)
        val view = vm.slots.await { v -> v != null && v.tiles.all { it.look == TileLook.DECLARED_EMPTY } }!!
        assertEquals(20, view.tiles.size)
        assertEquals(List(20) { SlotState.DeclaredEmpty(it, 42L) }, env.repository.slots(tank.id).first())
        assertTrue(env.sim.received.isEmpty())
        vm.viewModelScope.cancel()
    }

    @Test fun contents_emptyNow_opensEmptyingWithTheTwentyCheckedAndWritesNothingYet() = runBlocking {
        val tank = env.store("Tank")
        val vm = DeviceDetailViewModel(tank.id, env.repository)
        val emptying = EmptySlotsViewModel(tank.id, flowOf(EmptySlotNaming.NUMBERED), env.uploaderFactory())
        vm.answerContents(ContentsAnswer.EMPTY_NOW)
        // What the page does with this answer.
        emptying.open((0..19).toSet())
        assertEquals((0..19).toSet(), emptying.selection.value)
        assertEquals(EmptyingRun.Idle, emptying.run.value)
        assertTrue(env.repository.slots(tank.id).first().all { it is SlotState.Unknown })
        assertTrue(env.sim.received.isEmpty())
        vm.viewModelScope.cancel()
        emptying.viewModelScope.cancel()
    }
}
