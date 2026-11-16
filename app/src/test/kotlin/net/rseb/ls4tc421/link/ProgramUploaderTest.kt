package net.rseb.ls4tc421.link

import java.util.Collections
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import ls4tc421.protocol.ClientConfig
import ls4tc421.protocol.WriteStage
import net.rseb.ls4tc421.TestEnv
import net.rseb.ls4tc421.await
import net.rseb.ls4tc421.data.ClientSource
import net.rseb.ls4tc421.data.DeviceEntity
import net.rseb.ls4tc421.data.SlotEntity
import net.rseb.ls4tc421.data.SlotKind
import net.rseb.ls4tc421.data.SlotState
import net.rseb.ls4tc421.model.Aquarium
import net.rseb.ls4tc421.model.StoredForm
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The upload with the real client against the simulator, on 127.0.0.1. Nothing leaves the machine. */
class ProgramUploaderTest {
    private var env = TestEnv(attempts = 3)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val seen: MutableList<UploadPhase> = Collections.synchronizedList(ArrayList())
    private val program = Aquarium.RISE_ONLY.toProgram()

    @After fun tearDown() {
        scope.cancel()
        env.close()
    }

    private fun uploader(): ProgramUploader =
        ProgramUploader(ClientSource { env.client }, env.dao, env.slots, env.frames, env.guard, scope, now = { 77_000L }).also { up ->
            scope.launch(Dispatchers.Unconfined) { up.phase.collect { seen += it } }
        }

    private suspend fun device(): DeviceEntity = env.store(name = "Tank")

    private fun count(cmd: Int, sub: Int) =
        env.sim.received.count { (it[2].toInt() and 0xFF) == cmd && (it[3].toInt() and 0xFF) == sub }

    @Test fun uploadSucceedsAndRecordsTheWrittenSlot() = runBlocking {
        val tank = device()
        val programId = env.programs.create(Aquarium.RISE_ONLY)
        val up = uploader()
        up.start(UploadRequest(tank.id, programId, program, slot = 3)).join()

        assertEquals(UploadPhase.Done(77_000L, "aqua", 3), up.phase.value)
        assertEquals(Aquarium.STEPS, env.sim.programs.getValue(3).steps)
        assertEquals("aqua", env.sim.programs.getValue(3).name)
        val stored = env.slots.all.single()
        assertEquals(SlotEntity(tank.id, 3, SlotKind.WRITTEN, programId, "aqua", 77_000L, StoredForm.levels(Aquarium.STEPS)), stored)
        assertEquals(
            SlotState.Written(3, programId, "aqua", 77_000L, Aquarium.STEPS),
            env.repository.slots(tank.id).first()[3],
        )
        // The bank shows where the program is.
        assertEquals(listOf("Tank"), env.programs.bank.first().single().devices)
        // Progress was reported frame by frame, up to 51 / 51 (D32: 01 01, 48 steps, 03 08, 04 0A).
        assertTrue(seen.contains(UploadPhase.Sending(51, 51)))
        assertEquals(1, count(0x03, 0x08))
        assertEquals(1, count(0x04, 0x0A))
        assertTrue(env.guard.inUse == null)
    }

    @Test fun lostAckIsRetriedAndTheRetryIsShown() = runBlocking {
        val tank = device()
        env.sim.faults.dropNth(5) // ack of step 3 (ack 1 = 01 01)
        env.sim.faults.delayNth(6, 150) // the retry's ack comes late enough to be seen
        val up = uploader()
        up.start(UploadRequest(tank.id, null, program, slot = 0)).join()

        assertTrue(up.phase.value is UploadPhase.Done)
        assertEquals(49, count(0x01, 0x02)) // 48 steps + 1 resend
        val retries = seen.filterIsInstance<UploadPhase.Sending>().mapNotNull { it.retry }
        assertEquals(Retry(WriteStage.Step(3), attempt = 2, attempts = 3), retries.single())
    }

    @Test fun failureStopsAtTheFailedFrameAndResumeContinuesThere() = runBlocking {
        val tank = device()
        env.sim.faults.dropNth(12, 13, 14) // step 10, three attempts
        val up = uploader()
        up.start(UploadRequest(tank.id, 5L, program, slot = 7)).join()

        val failed = up.phase.value as UploadPhase.Failed
        assertEquals(WriteStage.Step(10), failed.stage)
        assertEquals(12, failed.number) // frame 12 of 50
        assertEquals(UploadFailure.TimedOut(3), failed.reason)
        assertTrue(env.slots.all.isEmpty()) // nothing recorded

        up.resume().join()
        assertTrue(up.phase.value is UploadPhase.Done)
        assertEquals(1, count(0x01, 0x01)) // the opening frame was not resent
        assertEquals(13 + 38, count(0x01, 0x02)) // steps 10..47 once more, not 0..9
        assertEquals(Aquarium.STEPS, env.sim.programs.getValue(7).steps)
        assertEquals(listOf(7), env.slots.all.map { it.slot })
    }

    @Test fun slowFirstStepSucceedsWithoutRetryWithTheAppSettings() = runBlocking {
        env.close()
        val defaults = ClientConfig()
        env = TestEnv(ackTimeout = defaults.ackTimeout, attempts = defaults.attempts)
        val tank = device()
        env.sim.faults.delayNth(2, 1_300) // first step after 01 01, as on the real controller
        val up = uploader()
        up.start(UploadRequest(tank.id, null, program, slot = 0)).join()

        assertTrue(up.phase.value is UploadPhase.Done)
        assertEquals(51, env.sim.received.size)
        assertTrue(seen.filterIsInstance<UploadPhase.Sending>().all { it.retry == null })
    }

    /** D32: the pause before the first step is expected; it is not shown as a retry. */
    @Test fun theFirstStepPauseIsNotShownAsARetry() = runBlocking {
        env.close()
        env = TestEnv(attempts = 3, firstStepDelay = 600.milliseconds) // longer than the 200 ms ack wait
        val tank = device()
        val up = uploader()
        up.start(UploadRequest(tank.id, null, program, slot = 2)).join()

        assertTrue(up.phase.value is UploadPhase.Done)
        assertEquals(51, env.sim.received.size)
        assertEquals(0, env.sim.ignoredFirstSteps)
        assertTrue(seen.filterIsInstance<UploadPhase.Sending>().all { it.retry == null })
    }

    @Test fun aResentEndOfProgramIsShownAsARetry() = runBlocking {
        val tank = device()
        env.sim.faults.dropNth(50) // ack of 03 08
        env.sim.faults.delayNth(51, 150)
        val up = uploader()
        up.start(UploadRequest(tank.id, null, program, slot = 0)).join()

        assertTrue(up.phase.value is UploadPhase.Done)
        val retries = seen.filterIsInstance<UploadPhase.Sending>().mapNotNull { it.retry }
        assertEquals(Retry(WriteStage.EndProgram, attempt = 2, attempts = 3), retries.single())
    }

    /** D32: the slot is recorded at the acknowledgement of its 03 08; a failed 04 0A does not undo it. */
    @Test fun aFailedCloseLeavesTheSlotRecordedAndResumeSendsOnlyTheClose() = runBlocking {
        val tank = device()
        env.sim.faults.dropNth(51, 52, 53) // 04 0A, three attempts
        val up = uploader()
        up.start(UploadRequest(tank.id, 5L, program, slot = 7)).join()

        val failed = up.phase.value as UploadPhase.Failed
        assertEquals(WriteStage.Finish, failed.stage)
        assertEquals(51, failed.number)
        assertEquals(51, failed.total)
        assertEquals(SlotEntity(tank.id, 7, SlotKind.WRITTEN, 5L, "aqua", 77_000L, StoredForm.levels(Aquarium.STEPS)), env.slots.all.single())

        up.resume().join()
        assertEquals(UploadPhase.Done(77_000L, "aqua", 7), up.phase.value)
        assertEquals(1, count(0x01, 0x01))
        assertEquals(1, count(0x03, 0x08))
        assertEquals(4, count(0x04, 0x0A))
        assertEquals(1, env.slots.all.size)
    }

    @Test fun aProgramOfALongerSessionHas50FramesAndTheSessionIsClosedApart() = runBlocking {
        val tank = device()
        val up = uploader()
        up.start(UploadRequest(tank.id, null, program, slot = 1, endSession = false)).join()
        assertTrue(up.phase.value is UploadPhase.Done)
        assertTrue(seen.contains(UploadPhase.Sending(50, 50)))
        assertEquals(0, count(0x04, 0x0A))
        assertEquals(listOf(1), env.slots.all.map { it.slot })

        assertEquals(SessionClose.Closed, up.closeSession(tank.id))
        assertEquals(1, count(0x04, 0x0A))
        assertTrue(env.guard.inUse == null)
    }

    @Test fun closingASessionWhileAPreviewRunsSendsNothing() = runBlocking {
        val tank = device()
        assertTrue(env.guard.tryAcquire(LinkGuard.Use.PREVIEW))
        assertEquals(SessionClose.Busy, uploader().closeSession(tank.id))
        assertEquals(0, env.sim.received.size)
    }

    @Test fun cancelledUploadCanBeResumed() = runBlocking {
        val tank = device()
        env.sim.faults.delayAll(20)
        val up = uploader()
        val job = up.start(UploadRequest(tank.id, null, program, slot = 1))
        up.phase.await { it is UploadPhase.Sending && it.completed >= 5 }
        up.cancel()
        job.join()
        val failed = up.phase.value as UploadPhase.Failed
        assertEquals(UploadFailure.Cancelled, failed.reason)
        assertTrue(env.guard.inUse == null)

        env.sim.faults.clear()
        up.resume().join()
        assertTrue(up.phase.value is UploadPhase.Done)
        assertEquals(Aquarium.STEPS, env.sim.programs.getValue(1).steps)
    }

    @Test fun noUploadWhileAPreviewRuns() = runBlocking {
        val tank = device()
        assertTrue(env.guard.tryAcquire(LinkGuard.Use.PREVIEW))
        val up = uploader()
        up.start(UploadRequest(tank.id, null, program, slot = 0)).join()
        assertEquals(UploadPhase.Busy, up.phase.value)
        assertEquals(0, env.sim.received.size)
    }

    @Test fun unknownDeviceFailsWithoutSending() = runBlocking {
        val up = uploader()
        up.start(UploadRequest(999L, null, program, slot = 0)).join()
        assertEquals(UploadFailure.UnknownDevice, (up.phase.value as UploadPhase.Failed).reason)
        assertEquals(0, env.sim.received.size)
    }

    /**
     * D23: a program saved under a name the controller refuses (13 characters) is saved and read back,
     * but its upload stops before any frame; once renamed to 7 characters it is sent.
     */
    @Test fun aNameThatCannotBeSentIsRefusedBeforeAnyFrame() = runBlocking {
        val tank = device()
        val id = env.programs.create(Aquarium.RISE_ONLY.renamed("court_basique"))
        val stored = env.programs.get(id)!!
        assertEquals("court_basique", stored.name)
        assertEquals(Aquarium.STEPS, stored.levels())

        val up = uploader()
        up.start(tank.id, id, stored, slot = 19).join()
        assertEquals(UploadPhase.NameRefused, up.phase.value)
        assertEquals(0, env.sim.received.size)
        assertTrue(env.sim.programs.isEmpty())
        assertTrue(env.slots.all.isEmpty())

        env.programs.rename(id, "court_b")
        up.start(tank.id, id, env.programs.get(id)!!, slot = 19).join()
        assertEquals(UploadPhase.Done(77_000L, "court_b", 19), up.phase.value)
        assertEquals("court_b", env.sim.programs.getValue(19).name)
    }
}
