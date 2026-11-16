package ls4tc421.simulator

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.time.LocalDateTime
import kotlin.time.Duration.Companion.milliseconds
import ls4tc421.protocol.AckCode
import ls4tc421.protocol.FrameReader
import ls4tc421.protocol.Frames
import ls4tc421.protocol.MacAddress
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Raw-datagram tests of the simulator itself: quirks, state, fault injection. */
class SimulatorTest {
    private val mac = MacAddress.parse("AABBCCDDEEFF")
    private lateinit var sim: Tc421Simulator
    private lateinit var socket: DatagramSocket

    /** Short thresholds, for fast tests (see [SimQuirks]). */
    private val quirks = SimQuirks(firstStepMinGap = 50.milliseconds, startIgnoredAfterClose = 400.milliseconds)

    @Before
    fun setUp() {
        sim = Tc421Simulator(mac, quirks = quirks).start()
        socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
    }

    @After
    fun tearDown() {
        socket.close()
        sim.close()
    }

    private fun send(frame: ByteArray) =
        socket.send(DatagramPacket(frame, frame.size, InetAddress.getLoopbackAddress(), sim.port))

    private fun receive(timeoutMs: Int = 300): ByteArray? {
        val packet = DatagramPacket(ByteArray(2048), 2048)
        socket.soTimeout = timeoutMs
        return try {
            socket.receive(packet)
            packet.data.copyOf(packet.length)
        } catch (_: SocketTimeoutException) {
            null
        }
    }

    @Test
    fun discoveryReplyCarriesTheMacAtBytes4To15() {
        send(Frames.search())
        val reply = receive()!!
        assertEquals(64, reply.size)
        assertEquals(mac, FrameReader.discovery(reply)!!.mac)
        assertEquals(0x17, reply[2].toInt())
        assertEquals("AABBCCDDEEFF", String(reply, 4, 12, Charsets.US_ASCII))
        assertTrue(reply.copyOfRange(16, 61).all { it.toInt() == 0 })
        assertEquals(Tc421Simulator.RESPONSE_CHECKSUM, reply[61].toInt() and 0xFF)
    }

    @Test
    fun timeAckReproducesTheObservedQuirks() {
        val time = LocalDateTime.of(2026, 11, 11, 14, 44, 26)
        send(Frames.timeSync(mac, time))
        val ack = receive()!!
        assertEquals(time, sim.time)
        assertEquals(0x88, ack[0].toInt() and 0xFF)
        assertEquals(0x03, ack[2].toInt()) // not 0x02: does not echo the command
        assertEquals(AckCode.TIME_SYNC, ack[4].toInt() and 0xFF)
        assertEquals("ABBCCDDEEFF", String(ack, 5, 11, Charsets.US_ASCII)) // MAC tail left at 5..15
        assertEquals("AABBCCDDEEFF", String(ack, 49, 12, Charsets.US_ASCII))
        assertEquals(AckCode.TIME_SYNC, FrameReader.ack(ack)!!.code)
        // The reply checksum is a constant that is not the request formula.
        assertEquals(Tc421Simulator.RESPONSE_CHECKSUM, ack[61].toInt() and 0xFF)
        assertTrue((ack[61].toInt() and 0xFF) != Frames.checksum(ack))
    }

    @Test
    fun stopAckUsesByte2Of0x11() {
        send(Frames.stopProcess(mac))
        val ack = receive()!!
        assertEquals(0x11, ack[2].toInt())
        assertEquals(AckCode.STOP, ack[4].toInt() and 0xFF)
    }

    @Test
    fun previewGetsNoReplyButChangesState() {
        send(Frames.previewStart(mac, "test"))
        send(Frames.previewValues(mac, listOf(10, 10, 10, 10, 10)))
        assertNull(receive(200))
        assertTrue(sim.previewActive)
        assertEquals("test", sim.previewName)
        assertEquals(listOf(listOf(10, 10, 10, 10, 10)), sim.previewValues)
        send(Frames.stopProcess(mac))
        assertNotNull(receive())
        assertFalse(sim.previewActive)
    }

    /** A `01 01` with a [length]-byte name, built by hand: the library refuses to build one over 9. */
    private fun rawStartDownload(slot: Int, length: Int): ByteArray {
        val frame = Frames.startDownload(mac, slot, "n")
        frame[5] = length.toByte()
        for (i in 0 until length) frame[7 + i] = 'n'.code.toByte()
        frame[61] = Frames.checksum(frame).toByte()
        return frame
    }

    @Test
    fun nameOfTenBytesIsRefusedWith87AsTheController() {
        send(rawStartDownload(slot = 4, length = 10))
        val refused = receive()!!
        assertEquals(AckCode.REFUSED, FrameReader.ack(refused)!!.code)
        assertEquals(0x87, AckCode.REFUSED)
        // No write was opened: a step that follows is ignored, the commit stores nothing.
        send(Frames.downloadStep(mac, 0, listOf(1, 1, 1, 1, 1)))
        assertNull(receive(200))
        assertTrue(sim.programs.isEmpty())

        send(rawStartDownload(slot = 4, length = 9))
        assertEquals(AckCode.DOWNLOAD_START, FrameReader.ack(receive()!!)!!.code)
    }

    @Test
    fun badChecksumAndForeignMacAreIgnored() {
        val bad = Frames.stopProcess(mac).also { it[61] = (it[61] + 1).toByte() }
        send(bad)
        send(Frames.stopProcess(MacAddress.parse("112233445566")))
        assertNull(receive(200))
        assertEquals(2, sim.rejectedFrames)
        assertEquals(0, sim.received.size)
    }

    @Test
    fun stepWithoutOpenWriteIsIgnored() {
        send(Frames.downloadStep(mac, 0, listOf(1, 2, 3, 4, 5)))
        assertNull(receive(200))
    }

    @Test
    fun incompleteWriteIsNotStored() {
        send(Frames.startDownload(mac, 2, "x"))
        receive()
        Thread.sleep(80) // past the first-step gap
        send(Frames.downloadStep(mac, 0, listOf(1, 2, 3, 4, 5)))
        assertEquals(AckCode.DOWNLOAD_STEP, FrameReader.ack(receive()!!)!!.code)
        send(Frames.stopProcess(mac))
        assertEquals(AckCode.STOP, FrameReader.ack(receive()!!)!!.code)
        assertTrue(sim.programs.isEmpty())
        assertEquals(1, sim.incompleteCommits)
    }

    // ---- write sessions (D32) -----------------------------------------------------------

    private val steps = List(48) { step -> List(5) { channel -> (step + channel) % 101 } }

    /** Sends [frame] and returns the acknowledgement code, or `null` without an answer. */
    private fun ask(frame: ByteArray, timeoutMs: Int = 300): Int? {
        send(frame)
        return receive(timeoutMs)?.let { FrameReader.ack(it)?.code }
    }

    /** `01 01`, the pause, the 48 steps; each acknowledged. */
    private fun writeSteps(slot: Int) {
        assertEquals(AckCode.DOWNLOAD_START, ask(Frames.startDownload(mac, slot, "p$slot")))
        Thread.sleep(80)
        steps.forEachIndexed { index, levels -> assertEquals(AckCode.DOWNLOAD_STEP, ask(Frames.downloadStep(mac, index, levels))) }
    }

    @Test
    fun aFirstStepTooSoonAfterTheOpeningIsNotAcknowledged() {
        assertEquals(AckCode.DOWNLOAD_START, ask(Frames.startDownload(mac, 1, "early")))
        assertNull(ask(Frames.downloadStep(mac, 0, steps[0]), timeoutMs = 30)) // sent at once: dropped
        assertEquals(1, sim.ignoredFirstSteps)
        Thread.sleep(80)
        assertEquals(AckCode.DOWNLOAD_STEP, ask(Frames.downloadStep(mac, 0, steps[0]))) // same step, later
        assertEquals(AckCode.DOWNLOAD_STEP, ask(Frames.downloadStep(mac, 1, steps[1]))) // the next ones wait no more
        assertEquals(1, sim.ignoredFirstSteps)
    }

    @Test
    fun theProgramIsStoredAtItsEndAndTheCloseOnlyClosesTheSession() {
        writeSteps(4)
        assertTrue(sim.programs.isEmpty())
        assertEquals(AckCode.STOP, ask(Frames.stopProcess(mac)))
        assertEquals(steps, sim.programs.getValue(4).steps)
        assertEquals(AckCode.STOP_ALL, ask(Frames.stopAllProcess(mac)))
        assertEquals(setOf(4), sim.programs.keys)

        // A write closed by 04 0A without its 03 08 is dropped (stricter than the controller, on purpose).
        Thread.sleep(450) // past the window after 04 0A
        writeSteps(5)
        assertEquals(AckCode.STOP_ALL, ask(Frames.stopAllProcess(mac)))
        assertEquals(setOf(4), sim.programs.keys)
        assertEquals(1, sim.droppedWrites)
    }

    /** The sequence used before D32 (`04 0A` after each program), repeated: the simulator goes silent. */
    @Test
    fun theOldSequenceRepeatedSilencesTheSimulatorUntilReset() {
        writeSteps(0)
        assertEquals(AckCode.STOP_ALL, ask(Frames.stopAllProcess(mac)))
        // The next program's 01 01, right after the 04 0A, retried as the client does: ignored three times.
        repeat(3) { assertNull(ask(Frames.startDownload(mac, 1, "p1"), timeoutMs = 60)) }
        assertEquals(3, sim.ignoredStarts)
        assertTrue(sim.silent)
        // Nothing gets an answer any more, not even discovery, 04 0A or 03 08; even after the window.
        Thread.sleep(450)
        send(Frames.search())
        assertNull(receive(200))
        assertNull(ask(Frames.stopAllProcess(mac), timeoutMs = 100))
        assertNull(ask(Frames.stopProcess(mac), timeoutMs = 100))
        assertNull(ask(Frames.startDownload(mac, 1, "p1"), timeoutMs = 100))

        sim.reset() // the power cycle
        assertFalse(sim.silent)
        send(Frames.search())
        assertNotNull(receive())
        assertEquals(AckCode.DOWNLOAD_START, ask(Frames.startDownload(mac, 1, "p1")))
    }

    /** The sequence of D32: 20 programs in one session, each ended by 03 08, a single 04 0A. */
    @Test
    fun theNewSequencePasses20ProgramsInOneSession() {
        for (slot in 0..19) {
            writeSteps(slot)
            assertEquals(AckCode.STOP, ask(Frames.stopProcess(mac)))
        }
        assertEquals(AckCode.STOP_ALL, ask(Frames.stopAllProcess(mac)))
        assertEquals((0..19).toSet(), sim.programs.keys)
        assertTrue(sim.programs.values.all { it.steps == steps })
        assertEquals(0, sim.ignoredStarts)
        assertEquals(0, sim.ignoredFirstSteps)
        assertFalse(sim.silent)
    }

    @Test
    fun dropNthLosesOnlyTheReplyNotTheEffect() {
        sim.faults.dropNth(1)
        send(Frames.timeSync(mac, LocalDateTime.of(2026, 1, 2, 3, 4, 5)))
        assertNull(receive(200))
        assertEquals(LocalDateTime.of(2026, 1, 2, 3, 4, 5), sim.time)
        send(Frames.stopProcess(mac))
        assertNotNull(receive())
        assertEquals(2, sim.ackCount)
        assertEquals(1, sim.droppedAckCount)
    }

    @Test
    fun dropEveryKLosesOneInK() {
        sim.faults.dropEvery(3)
        var replies = 0
        repeat(6) {
            send(Frames.stopProcess(mac))
            if (receive(150) != null) replies++
        }
        assertEquals(4, replies)
        assertEquals(2, sim.droppedAckCount)
    }

    @Test
    fun delayNthDelaysTheReply() {
        sim.faults.delayNth(1, 250)
        val start = System.nanoTime()
        send(Frames.stopProcess(mac))
        assertNull(receive(100))
        assertNotNull(receive(500))
        assertTrue((System.nanoTime() - start) / 1_000_000 >= 240)
    }

    @Test
    fun clearRemovesFaults() {
        sim.faults.dropNth(1, 2, 3)
        sim.faults.clear()
        send(Frames.stopProcess(mac))
        assertNotNull(receive())
    }

    @Test
    fun receivedFramesAreRecordedWithRetries() {
        val frame = Frames.stopProcess(mac)
        send(frame)
        send(frame)
        receive()
        receive()
        assertEquals(2, sim.received.size)
        assertArrayEquals(frame, sim.received[0])
    }
}
