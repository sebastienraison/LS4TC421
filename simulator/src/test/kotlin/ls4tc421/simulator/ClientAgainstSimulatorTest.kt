package ls4tc421.simulator

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.time.LocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import ls4tc421.protocol.ClientConfig
import ls4tc421.protocol.CommandResult
import ls4tc421.protocol.Device
import ls4tc421.protocol.DiscoveryResult
import ls4tc421.protocol.FrameDirection
import ls4tc421.protocol.FrameEvent
import ls4tc421.protocol.FrameListener
import ls4tc421.protocol.FrameLayout
import ls4tc421.protocol.Frames
import ls4tc421.protocol.MacAddress
import ls4tc421.protocol.Program
import ls4tc421.protocol.ProgramWriteSequence
import ls4tc421.protocol.Tc421Client
import ls4tc421.protocol.WifiCipher
import ls4tc421.protocol.WifiSecurity
import ls4tc421.protocol.WriteProgress
import ls4tc421.protocol.WriteResult
import ls4tc421.protocol.WriteStage
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The real client against the simulator, on 127.0.0.1 with free ports. Nothing leaves the machine. */
class ClientAgainstSimulatorTest {
    private val mac = MacAddress.parse("AABBCCDDEEFF")
    private val loopback = InetAddress.getLoopbackAddress()
    private lateinit var sim: Tc421Simulator
    private lateinit var client: Tc421Client
    private val device get() = Device(loopback, mac)

    private val program = Program("Aquarium", List(48) { step -> List(5) { channel -> (step * 2 + channel) % 101 } })

    /** Short thresholds, for fast tests; the client's first-step pause stays above [SimQuirks.firstStepMinGap]. */
    private val fastQuirks = SimQuirks(firstStepMinGap = 50.milliseconds, startIgnoredAfterClose = 1.seconds)
    private val fastFirstStep = 100.milliseconds

    @Before
    fun setUp() {
        sim = Tc421Simulator(mac, quirks = fastQuirks).start()
        client = newClient(ackTimeout = 200.milliseconds)
    }

    @After
    fun tearDown() {
        client.close()
        sim.close()
    }

    private fun newClient(
        ackTimeout: Duration,
        attempts: Int = 3,
        firstStepDelay: Duration = fastFirstStep,
        listener: ((FrameEvent) -> Unit)? = null,
    ) = Tc421Client(
        ClientConfig(
            remotePort = sim.port,
            localPort = 0,
            localAddress = loopback,
            ackTimeout = ackTimeout,
            attempts = attempts,
            stepInterval = Duration.ZERO,
            firstStepDelay = firstStepDelay,
            discoveryInterval = 50.milliseconds,
            frameListener = listener?.let { f -> FrameListener { event -> f(event) } },
        ),
    )

    private fun count(sub: Int, cmd: Int = 0x01) =
        sim.received.count { (it[2].toInt() and 0xFF) == cmd && (it[3].toInt() and 0xFF) == sub }

    @Test
    fun discoveryByAddressFindsTheSimulator() = runBlocking {
        val result = client.discover(loopback)
        assertEquals(DiscoveryResult.Found(listOf(device)), result)
    }

    @Test
    fun discoveryOfNothingTimesOutWithoutException() = runBlocking {
        val quick = newClient(ackTimeout = 100.milliseconds)
        sim.close() // nobody listens any more
        try {
            assertEquals(DiscoveryResult.NoReply, quick.discover(loopback))
        } finally {
            quick.close()
        }
    }

    @Test
    fun broadcastDiscoveryFrameIsBuiltWithoutMacAndNothingIsSent() {
        // The broadcast itself is never exercised in tests: only the frame is checked.
        val frame = Frames.search()
        assertEquals(0x17, frame[2].toInt())
        assertTrue(frame.copyOfRange(FrameLayout.MAC, FrameLayout.CHECKSUM).all { it.toInt() == 0 })
    }

    @Test
    fun setTimeReachesTheSimulator() = runBlocking {
        val time = LocalDateTime.of(2026, 11, 11, 14, 44, 26)
        assertEquals(CommandResult.Success, client.setTime(device, time))
        assertEquals(time, sim.time)
    }

    @Test
    fun protectedWifiSendsSsidThenKey() = runBlocking {
        val result = client.joinWifi(device, "TestNet", "secret-pass", WifiSecurity.WPA2, WifiCipher.AES)
        assertEquals(CommandResult.Success, result)
        assertEquals(SimWifi("TestNet", false, WifiSecurity.WPA2, WifiCipher.AES, "secret-pass"), sim.wifi)
    }

    @Test
    fun openWifiSendsSsidOnly() = runBlocking {
        assertEquals(CommandResult.Success, client.joinWifi(device, "OpenNet", null))
        assertEquals(SimWifi("OpenNet", true, null, null, null), sim.wifi)
        assertEquals(1, sim.received.size)
    }

    @Test
    fun wifiValidationHappensBeforeAnythingIsSent() = runBlocking {
        try {
            client.joinWifi(device, "s".repeat(32), "k".repeat(33))
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(0, sim.received.size)
    }

    @Test
    fun previewStartValuesAndEnd() = runBlocking {
        client.startPreview(device, "test")
        client.sendPreviewValues(device, listOf(10, 20, 30, 40, 50))
        assertEquals(CommandResult.Success, client.endPreview(device))
        assertEquals("test", sim.previewName)
        assertEquals(listOf(listOf(10, 20, 30, 40, 50)), sim.previewValues)
        assertTrue(!sim.previewActive)
    }

    @Test
    fun fullWriteDeliversAll48StepsAndReportsProgress() = runBlocking {
        val progress = ArrayList<WriteProgress>()
        val result = client.writeProgram(device, 7, program) { progress += it }
        assertEquals(WriteResult.Success, result)
        assertEquals(program.name, sim.programs.getValue(7).name)
        assertEquals(program.steps, sim.programs.getValue(7).steps)
        assertEquals(1, count(0x01))
        assertEquals(48, count(0x02))
        assertEquals(1, count(0x08, cmd = 0x03))
        assertEquals(1, count(0x0A, cmd = 0x04))
        assertEquals(51, progress.size)
        assertEquals(WriteProgress(1, 51, WriteStage.Start), progress.first())
        assertEquals(WriteStage.Step(0), progress[1].stage)
        assertEquals(WriteProgress(50, 51, WriteStage.EndProgram), progress[49])
        assertEquals(WriteProgress(51, 51, WriteStage.Finish), progress.last())
    }

    /**
     * D32, with the default client settings against the default simulator: the 51 frames in their
     * exact order, the one-second pause between the acknowledgement of `01 01` and the first step, and
     * the first step sent once.
     */
    @Test
    fun oneProgramIsOneSessionOf51FramesWithTheFirstStepPause() = runBlocking {
        sim.close()
        sim = Tc421Simulator(mac).start() // default thresholds
        val events = java.util.Collections.synchronizedList(ArrayList<FrameEvent>())
        val defaults = ClientConfig()
        val patient = newClient(defaults.ackTimeout, defaults.attempts, defaults.firstStepDelay, events::add)
        try {
            assertEquals(WriteResult.Success, patient.writeProgram(device, 5, program))
        } finally {
            patient.close()
        }
        val expected = ProgramWriteSequence.plan(mac, 5, program).map { it.frame }
        assertEquals(51, expected.size)
        assertEquals(51, sim.received.size)
        expected.zip(sim.received).forEach { (want, got) -> assertArrayEquals(want, got) }
        assertEquals(0, sim.ignoredFirstSteps)
        assertEquals(program.steps, sim.programs.getValue(5).steps)

        val startAck = events.first { it.direction == FrameDirection.RECEIVED && !it.discarded && (it.bytes[4].toInt() and 0xFF) == 0x80 }
        val firstStep = events.filter { it.direction == FrameDirection.SENT && it.bytes[2].toInt() == 0x01 && it.bytes[3].toInt() == 0x02 }
        assertEquals(48, firstStep.size) // no step sent twice, the first one included
        assertTrue("pause ${firstStep.first().timeMillis - startAck.timeMillis} ms", firstStep.first().timeMillis - startAck.timeMillis >= 1_000)
    }

    /** D32: several programs in one session, a single `04 0A` at the end. */
    @Test
    fun severalProgramsInOneSessionEndWithASingleClose() = runBlocking {
        val other = Program("Other", List(48) { step -> List(5) { channel -> (step + channel * 3) % 101 } })
        for (slot in 0..2) {
            assertEquals(WriteResult.Success, client.writeProgram(device, slot, if (slot == 1) other else program, endSession = false))
        }
        assertEquals(0, count(0x0A, cmd = 0x04))
        val progress = ArrayList<WriteProgress>()
        assertEquals(WriteResult.Success, client.closeSession(device) { progress += it })
        assertEquals(listOf(WriteProgress(1, 1, WriteStage.Finish)), progress)
        assertEquals(3, count(0x01))
        assertEquals(3 * 48, count(0x02))
        assertEquals(3, count(0x08, cmd = 0x03))
        assertEquals(1, count(0x0A, cmd = 0x04))
        assertEquals(setOf(0, 1, 2), sim.programs.keys)
        assertEquals(other.steps, sim.programs.getValue(1).steps)
        assertEquals(0, sim.ignoredStarts)
        assertFalse(sim.silent)
    }

    @Test
    fun aProgramWrittenWithoutClosingTheSessionHas50Frames() = runBlocking {
        val progress = ArrayList<WriteProgress>()
        assertEquals(WriteResult.Success, client.writeProgram(device, 3, program, endSession = false) { progress += it })
        assertEquals(50, sim.received.size)
        assertEquals(WriteProgress(50, 50, WriteStage.EndProgram), progress.last())
        assertEquals(program.steps, sim.programs.getValue(3).steps) // stored at its 03 08
        try {
            client.writeProgram(device, 3, program, resumeAt = WriteStage.Finish, endSession = false)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun resumeAtTheOpeningFrameSendsItAgainThenPauses() = runBlocking {
        sim.faults.dropNth(1, 2, 3) // the three attempts of 01 01
        val failed = client.writeProgram(device, 2, program) as WriteResult.Failed
        assertEquals(WriteStage.Start, failed.stage)
        assertEquals(WriteResult.Success, client.writeProgram(device, 2, program, resumeAt = failed.stage))
        assertEquals(4, count(0x01))
        assertEquals(48, count(0x02))
        assertEquals(0, sim.ignoredFirstSteps)
        assertEquals(program.steps, sim.programs.getValue(2).steps)
    }

    @Test
    fun resumeAtTheFirstStepDoesNotWaitTheFirstStepPause() = runBlocking {
        val slowStart = newClient(ackTimeout = 200.milliseconds, firstStepDelay = 1.seconds)
        try {
            sim.faults.dropNth(2, 3, 4) // the three attempts of step 0
            val failed = slowStart.writeProgram(device, 6, program) as WriteResult.Failed
            assertEquals(WriteStage.Step(0), failed.stage)
            val start = System.nanoTime()
            assertEquals(WriteResult.Success, slowStart.writeProgram(device, 6, program, resumeAt = failed.stage))
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            assertTrue("resume took $elapsedMs ms", elapsedMs < 900)
        } finally {
            slowStart.close()
        }
        assertEquals(1, count(0x01)) // not resent
        assertEquals(3 + 48, count(0x02))
        assertEquals(program.steps, sim.programs.getValue(6).steps)
    }

    @Test
    fun resumeAtTheEndOfTheProgramSendsItThenCloses() = runBlocking {
        sim.faults.dropNth(50, 51, 52) // ack 50 = 03 08, three attempts
        val failed = client.writeProgram(device, 8, program) as WriteResult.Failed
        assertEquals(WriteStage.EndProgram, failed.stage)
        assertEquals(0, count(0x0A, cmd = 0x04)) // nothing more was sent after the failure
        assertEquals(WriteResult.Success, client.writeProgram(device, 8, program, resumeAt = failed.stage))
        assertEquals(1, count(0x01))
        assertEquals(48, count(0x02)) // the steps were not resent
        assertEquals(4, count(0x08, cmd = 0x03))
        assertEquals(1, count(0x0A, cmd = 0x04))
        assertEquals(program.steps, sim.programs.getValue(8).steps)
    }

    @Test
    fun resumeAtTheClosingFrameSendsOnlyIt() = runBlocking {
        sim.faults.dropNth(51, 52, 53) // ack 51 = 04 0A, three attempts
        val failed = client.writeProgram(device, 9, program) as WriteResult.Failed
        assertEquals(WriteStage.Finish, failed.stage)
        assertEquals(program.steps, sim.programs.getValue(9).steps) // already stored at its 03 08
        val progress = ArrayList<WriteProgress>()
        assertEquals(WriteResult.Success, client.writeProgram(device, 9, program, resumeAt = failed.stage) { progress += it })
        assertEquals(listOf(WriteProgress(51, 51, WriteStage.Finish)), progress)
        assertEquals(1, count(0x01))
        assertEquals(1, count(0x08, cmd = 0x03))
        assertEquals(4, count(0x0A, cmd = 0x04))
    }

    @Test
    fun aFailedCloseOfASessionCanBeSentAgain() = runBlocking {
        assertEquals(WriteResult.Success, client.writeProgram(device, 1, program, endSession = false))
        sim.faults.dropNth(51, 52, 53) // the three attempts of 04 0A
        assertEquals(WriteResult.TimedOut(WriteStage.Finish, 3), client.closeSession(device))
        assertEquals(WriteResult.Success, client.closeSession(device))
        assertEquals(4, count(0x0A, cmd = 0x04))
        assertEquals(program.steps, sim.programs.getValue(1).steps)
    }

    @Test
    fun lostAckIsRetriedAndTheWriteSucceeds() = runBlocking {
        sim.faults.dropNth(5) // ack of step 3
        assertEquals(WriteResult.Success, client.writeProgram(device, 0, program))
        assertEquals(program.steps, sim.programs.getValue(0).steps)
        assertEquals(52, sim.received.size) // 51 frames + one retry
        assertEquals(1, sim.droppedAckCount)
    }

    @Test
    fun oneAckLostInSevenStillCompletes() = runBlocking {
        sim.faults.dropEvery(7)
        assertEquals(WriteResult.Success, client.writeProgram(device, 1, program))
        assertEquals(program.steps, sim.programs.getValue(1).steps)
        assertTrue(sim.droppedAckCount >= 7)
    }

    @Test
    fun delayedAckWithinTheTimeoutIsAccepted() = runBlocking {
        sim.faults.delayNth(1, 80)
        assertEquals(CommandResult.Success, client.setTime(device))
        assertEquals(1, sim.received.size)
    }

    @Test
    fun ackDelayedBeyondTheTimeoutCostsARetry() = runBlocking {
        sim.faults.delayNth(1, 500) // first ack too late; the retry's ack (#2) is immediate
        assertEquals(CommandResult.Success, client.setTime(device))
        assertEquals(2, sim.received.size)
    }

    @Test
    fun defaultTimeoutIsTwoSeconds() {
        assertEquals(2.seconds, ClientConfig().ackTimeout)
    }

    @Test
    fun slowFirstStepIsAcceptedWithoutRetryWithTheDefaultTimeout() = runBlocking {
        // Real controller, 2026-11-14: the first step after `01 01` is acknowledged in 1.1 to 1.3 s.
        sim.faults.delayNth(2, 1_300) // ack #1 = 01 01, ack #2 = step 0
        val defaults = ClientConfig()
        val patient = newClient(ackTimeout = defaults.ackTimeout, attempts = defaults.attempts)
        try {
            assertEquals(WriteResult.Success, patient.writeProgram(device, 0, program))
        } finally {
            patient.close()
        }
        assertEquals("no frame resent", 51, sim.received.size)
        assertEquals(48, count(0x02))
        assertEquals(program.steps, sim.programs.getValue(0).steps)
    }

    @Test
    fun threeFailuresStopTheWriteAtTheFailingStepAndItResumesThere() = runBlocking {
        sim.faults.dropNth(12, 13, 14) // acks of step 10, three attempts
        val failed = client.writeProgram(device, 4, program)
        assertEquals(WriteResult.TimedOut(WriteStage.Step(10), 3), failed)
        assertEquals(1, count(0x01))
        assertEquals(10 + 3, count(0x02))
        assertTrue(sim.programs.isEmpty()) // not committed yet

        val resumed = client.writeProgram(device, 4, program, resumeAt = (failed as WriteResult.Failed).stage)
        assertEquals(WriteResult.Success, resumed)
        assertEquals(1, count(0x01)) // the opening frame was not resent
        assertEquals(13 + 38, count(0x02)) // steps 10..47 sent once more, not 0..9
        assertEquals(program.steps, sim.programs.getValue(4).steps)
    }

    @Test
    fun progressAfterResumeStartsWhereTheFailureWas() = runBlocking {
        sim.faults.dropNth(3, 4, 5) // step 1 fails
        val failed = client.writeProgram(device, 0, program) as WriteResult.Failed
        assertEquals(WriteStage.Step(1), failed.stage)
        val progress = ArrayList<WriteProgress>()
        client.writeProgram(device, 0, program, failed.stage) { progress += it }
        assertEquals(WriteProgress(3, 51, WriteStage.Step(1)), progress.first())
        assertEquals(49, progress.size) // steps 1..47, 03 08, 04 0A
    }

    @Test
    fun writeValidationHappensBeforeAnythingIsSent() = runBlocking {
        try {
            client.writeProgram(device, 20, program)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(0, sim.received.size)
    }

    @Test
    fun unexpectedAckIsReportedWithoutRetry() = runBlocking {
        // A stub that acknowledges with the wrong code.
        val stub = DatagramSocket(0, loopback)
        val thread = Thread {
            val packet = DatagramPacket(ByteArray(2048), 2048)
            stub.receive(packet)
            val reply = ByteArray(64)
            reply[0] = 0x88.toByte()
            reply[4] = 0x99.toByte()
            mac.toAscii().copyInto(reply, 49)
            stub.send(DatagramPacket(reply, 64, packet.address, packet.port))
        }.apply { isDaemon = true; start() }
        val stubClient = Tc421Client(
            ClientConfig(remotePort = stub.localPort, localPort = 0, localAddress = loopback, ackTimeout = 300.milliseconds),
        )
        try {
            assertEquals(CommandResult.UnexpectedAck(0x99, 0x85), stubClient.setTime(device))
        } finally {
            stubClient.close()
            thread.join(1000)
            stub.close()
        }
    }

    @Test
    fun repliesFromAnotherMacAreIgnored() = runBlocking {
        val other = Device(loopback, MacAddress.parse("112233445566"))
        // The simulator ignores frames for another MAC, so no ack ever arrives: plain timeout.
        val result = client.setTime(other)
        assertEquals(CommandResult.TimedOut(3), result)
    }

    /**
     * D21: two clients with the default local port, on the same machine and at the same time, each
     * get their own replies. With the old default (both on port 5000 with `SO_REUSEADDR`), the
     * replies reached only one socket and the other client timed out.
     */
    @Test
    fun twoDefaultClientsOnTheSameMachineEachGetTheirOwnReplies() = runBlocking {
        fun defaultPortClient() = Tc421Client(
            ClientConfig(
                remotePort = sim.port,
                localAddress = loopback,
                ackTimeout = 500.milliseconds,
                attempts = 1, // no retry: a reply lost to the other socket fails the test
                stepInterval = Duration.ZERO,
                firstStepDelay = fastFirstStep,
            ),
        )
        val first = defaultPortClient()
        val second = defaultPortClient()
        try {
            assertTrue(first.localPort != second.localPort)
            assertEquals(DiscoveryResult.Found(listOf(device)), first.discover(loopback))
            assertEquals(DiscoveryResult.Found(listOf(device)), second.discover(loopback))
            val other = Program("Other", List(48) { step -> List(5) { channel -> (step + channel * 3) % 101 } })
            // One write after the other (the simulator holds a single open write), both sockets open,
            // in one write session (D32): a `01 01` right after a `04 0A` would be ignored.
            assertEquals(WriteResult.Success, first.writeProgram(device, 3, program, endSession = false))
            assertEquals(WriteResult.Success, second.writeProgram(device, 4, other))
            assertEquals(program.steps, sim.programs.getValue(3).steps)
            assertEquals(other.steps, sim.programs.getValue(4).steps)
            assertEquals(0, sim.droppedAckCount)
        } finally {
            first.close()
            second.close()
        }
    }

    /** The simulator answers to the source port of each request, like the real controller. */
    @Test
    fun simulatorRepliesToTheSourcePort() {
        val peer = DatagramSocket(0, loopback).apply { soTimeout = 2_000 }
        try {
            val search = Frames.search()
            peer.send(DatagramPacket(search, search.size, loopback, sim.port))
            val reply = DatagramPacket(ByteArray(2048), 2048)
            peer.receive(reply) // on the peer's own port (not 5000); throws on timeout
            assertEquals(sim.port, reply.port)
            assertTrue(peer.localPort != 5000)
        } finally {
            peer.close()
        }
    }
}
