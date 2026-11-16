package net.rseb.ls4tc421.bench

import java.net.InetAddress
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import ls4tc421.protocol.ClientConfig
import ls4tc421.protocol.Device
import ls4tc421.protocol.MacAddress
import ls4tc421.protocol.Tc421Client
import ls4tc421.protocol.WriteStage
import ls4tc421.simulator.Tc421Simulator
import net.rseb.ls4tc421.data.ClientSource
import net.rseb.ls4tc421.link.FrameLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The bench logic with the real client against the simulator, on 127.0.0.1. Nothing leaves the machine. */
class BenchControllerTest {
    private val loopback: InetAddress = InetAddress.getLoopbackAddress()
    private val mac = MacAddress.parse("AABBCCDDEEFF")
    private lateinit var sim: Tc421Simulator
    private lateinit var client: Tc421Client
    private lateinit var scope: CoroutineScope
    private lateinit var bench: BenchController
    private val log = FrameLog()

    @Before fun setUp() {
        sim = Tc421Simulator(mac).start()
        client = Tc421Client(
            ClientConfig(
                remotePort = sim.port,
                localPort = 0,
                localAddress = loopback,
                ackTimeout = 200.milliseconds,
                attempts = 2,
                stepInterval = Duration.ZERO,
                broadcastAddress = loopback,
                frameListener = log,
            ),
        )
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        bench = BenchController(
            clients = ClientSource { client },
            device = { Device(loopback, mac) },
            scope = scope,
            now = { 42_000L },
            resendInterval = 50.milliseconds,
        )
    }

    @After fun tearDown() {
        scope.cancel()
        client.close()
        sim.close()
    }

    private fun count(cmd: Int, sub: Int) =
        sim.received.count { (it[2].toInt() and 0xFF) == cmd && (it[3].toInt() and 0xFF) == sub }

    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "condition not reached in $timeoutMs ms" }
            Thread.sleep(10)
        }
    }

    private val all100 = BenchPrograms.FIXED_STATES.first()

    // ---- part A ---------------------------------------------------------------------------

    @Test fun fixedStatesFollowTheBrief() {
        val states = BenchPrograms.FIXED_STATES
        assertEquals(16, states.size)
        assertEquals(StateLabel.All(100), states[0].label)
        assertEquals(listOf(100, 100, 100, 100, 0), states[0].levels)
        assertEquals(listOf(0, 0, 0, 0, 0), states[1].levels)
        for (channel in 1..5) assertEquals(List(5) { if (it == channel - 1) 100 else 0 }, states[1 + channel].levels)
        assertEquals(listOf(0, 0, 0, 25, 0), states[7].levels)
        assertEquals(listOf(0, 0, 0, 75, 0), states[9].levels)
        assertEquals(listOf(25, 0, 0, 0, 0), states[10].levels)
        assertEquals(listOf(75, 0, 0, 0, 0), states[12].levels)
        assertEquals(listOf(0, 0, 0, 1, 0), states[13].levels)
        assertEquals(listOf(0, 0, 0, 10, 0), states[15].levels)
        assertEquals(StateLabel.Single(4, 10), states[15].label)
    }

    @Test fun activeStateIsStartedOnceAndResentRepeatedly() = runBlocking {
        bench.select(all100).join()
        waitUntil { sim.previewValues.size >= 4 }
        assertTrue(sim.previewActive)
        assertEquals("test", sim.previewName)
        assertEquals(1, count(0x06, 0x01))
        assertTrue(sim.previewValues.all { it == listOf(100, 100, 100, 100, 0) })
        assertEquals(all100, bench.preview.value.active)
        assertEquals(42_000L, bench.preview.value.since)
    }

    @Test fun switchingStateKeepsThePreviewOpenAndSendsTheNewLevels() = runBlocking {
        bench.select(all100).join()
        waitUntil { sim.previewValues.isNotEmpty() }
        val c4 = BenchPrograms.FIXED_STATES[7]
        bench.select(c4).join()
        waitUntil { sim.previewValues.count { it == c4.levels } >= 2 }
        assertEquals(1, count(0x06, 0x01))
        assertEquals(c4.levels, sim.previewValues.last())
    }

    @Test fun endPreviewStopsTheResendAndSends0308() = runBlocking {
        bench.select(all100).join()
        waitUntil { sim.previewValues.size >= 2 }
        bench.endPreview().join()
        assertEquals(1, count(0x03, 0x08))
        assertEquals(EndStatus.Acknowledged(42_000L), bench.preview.value.end)
        assertNull(bench.preview.value.active)
        assertTrue(!sim.previewActive)
        val sent = sim.previewValues.size
        Thread.sleep(250)
        assertEquals("no resend after the end", sent, sim.previewValues.size)
    }

    @Test fun endPreviewWithoutAnswerReportsTheTimeout() = runBlocking {
        sim.faults.dropEvery(1)
        bench.endPreview().join()
        assertEquals(EndStatus.NoReply(2), bench.preview.value.end)
    }

    @Test fun leavingTheScreenEndsAnActivePreview() = runBlocking {
        bench.select(BenchPrograms.FIXED_STATES[2]).join()
        waitUntil { sim.previewValues.isNotEmpty() }
        bench.exit().join()
        assertEquals(1, count(0x03, 0x08))
        assertTrue(!sim.previewActive)
    }

    @Test fun leavingTheScreenWithoutPreviewSendsNothing() = runBlocking {
        bench.exit().join()
        assertEquals(0, sim.received.size)
    }

    // ---- part B ---------------------------------------------------------------------------

    @Test fun j0aReachesTheSimulatorStepForStep() = runBlocking {
        assertEquals(0, bench.writes.value.getValue(BenchProgram.J0A).slot)
        bench.write(BenchProgram.J0A).join()
        assertEquals(WritePhase.Done(42_000L), bench.writes.value.getValue(BenchProgram.J0A).phase)
        val stored = sim.programs.getValue(0)
        assertEquals("aquarium", stored.name)
        val expected = buildList {
            repeat(17) { add(listOf(0, 0, 0, 0, 0)) }                    // 0..16, 00:00..08:00
            add(listOf(0, 0, 15, 5, 0))                                  // 17, 08:30
            add(listOf(20, 0, 40, 20, 0))                                // 18
            add(listOf(40, 20, 60, 40, 0))                               // 19
            add(listOf(100, 40, 80, 80, 0))                              // 20
            add(listOf(100, 80, 100, 90, 0))                             // 21
            repeat(19) { add(listOf(100, 90, 100, 100, 0)) }             // 22..40, 11:00..20:00
            add(listOf(100, 80, 100, 90, 0))                             // 41
            add(listOf(100, 40, 80, 80, 0))                              // 42
            add(listOf(40, 20, 60, 40, 0))                               // 43
            add(listOf(20, 0, 40, 20, 0))                                // 44
            add(listOf(0, 0, 15, 5, 0))                                  // 45, 22:30
            repeat(2) { add(listOf(0, 0, 0, 0, 0)) }                     // 46..47
        }
        assertEquals(48, expected.size)
        assertEquals(expected, stored.steps)
        // The steps named by the brief, explicitly.
        assertEquals(listOf(0, 0, 15, 5, 0), stored.steps[17])
        assertEquals(listOf(100, 90, 100, 100, 0), stored.steps[23]) // 11:30, C2 corrected to 90
        assertEquals(listOf(100, 80, 100, 90, 0), stored.steps[41])
        assertEquals(listOf(0, 0, 15, 5, 0), stored.steps[45])
        assertEquals(1, count(0x01, 0x01))
        assertEquals(48, count(0x01, 0x02))
        assertEquals(1, count(0x03, 0x08)) // end of the program (D32)
        assertEquals(1, count(0x04, 0x0A))
        assertEquals(0, count(0x05, 0xFE)) // never an erase
    }

    @Test fun j0bGoesToSlot19ByDefault() = runBlocking {
        assertEquals(19, bench.writes.value.getValue(BenchProgram.J0B).slot)
        bench.write(BenchProgram.J0B).join()
        val stored = sim.programs.getValue(19)
        assertEquals("j0b-test", stored.name)
        assertEquals(List(48) { listOf(10, 10, 10, 10, 0) }, stored.steps)
        assertEquals(setOf(19), sim.programs.keys)
    }

    @Test fun slotSelectorChangesTheTargetSlot() = runBlocking {
        bench.setSlot(BenchProgram.J0A, 7)
        bench.setSlot(BenchProgram.J0A, 20) // out of range: ignored
        bench.write(BenchProgram.J0A).join()
        assertEquals(setOf(7), sim.programs.keys)
    }

    @Test fun failedWriteNamesTheStageAndResumesThere() = runBlocking {
        // Acks are numbered from 1: 1 = 01 01, 2..49 = steps 0..47. Lose both tries of step 16.
        sim.faults.dropNth(18, 19)
        bench.write(BenchProgram.J0A).join()
        val failed = bench.writes.value.getValue(BenchProgram.J0A).phase as WritePhase.Failed
        assertEquals(WriteStage.Step(16), failed.stage)
        assertEquals(18, failed.number)
        assertEquals(51, failed.total)
        assertEquals(FailureReason.TimedOut(2), failed.reason)
        assertTrue(sim.programs.isEmpty())

        sim.faults.clear()
        bench.resume(BenchProgram.J0A).join()
        assertEquals(WritePhase.Done(42_000L), bench.writes.value.getValue(BenchProgram.J0A).phase)
        assertEquals(BenchPrograms.J0A.steps, sim.programs.getValue(0).steps)
        assertEquals("resume does not reopen the write", 1, count(0x01, 0x01))
    }

    @Test fun writeIsRefusedWhilePreviewing() = runBlocking {
        bench.select(all100).join()
        waitUntil { sim.previewValues.isNotEmpty() }
        bench.write(BenchProgram.J0A).join()
        assertEquals(0, count(0x01, 0x01))
        assertEquals(WritePhase.Idle, bench.writes.value.getValue(BenchProgram.J0A).phase)
    }

    // ---- part C, through the bench ---------------------------------------------------------

    @Test fun repeatedPreviewFramesAreFoldedInTheLog() = runBlocking {
        bench.select(all100).join()
        waitUntil { sim.previewValues.size >= 5 }
        bench.endPreview().join()
        val lines = log.lines.value
        val repeats = lines.filter { it.hex.startsWith("55 AA 06 02") }
        assertEquals(1, repeats.size)
        assertTrue(repeats.single().count >= 5)
        assertTrue(lines.any { it.hex.startsWith("55 AA 03 08") })
        assertTrue(lines.any { it.direction == ls4tc421.protocol.FrameDirection.RECEIVED })
    }
}
