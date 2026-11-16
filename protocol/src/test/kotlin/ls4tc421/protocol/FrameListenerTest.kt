package ls4tc421.protocol

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The frame listener of [ClientConfig], against a bare UDP peer on 127.0.0.1 that plays the
 * controller by hand. Nothing leaves the machine.
 */
class FrameListenerTest {
    private val loopback = InetAddress.getLoopbackAddress()
    private val mac = MacAddress.parse("AABBCCDDEEFF")
    private lateinit var peer: DatagramSocket
    private val events: MutableList<FrameEvent> = Collections.synchronizedList(ArrayList())

    @Before fun setUp() {
        peer = DatagramSocket(InetSocketAddress(loopback, 0)).apply { soTimeout = 2_000 }
    }

    @After fun tearDown() = peer.close()

    private fun client(listener: FrameListener?) = Tc421Client(
        ClientConfig(
            remotePort = peer.localPort,
            localPort = 0,
            localAddress = loopback,
            ackTimeout = 300.milliseconds,
            attempts = 1,
            stepInterval = Duration.ZERO,
            frameListener = listener,
        ),
    )

    /** An acknowledgement as the controller sends it: `88 00`, code at byte 4, MAC at 49..60. */
    private fun ack(code: Int): ByteArray = ByteArray(FrameLayout.SIZE).also {
        it[0] = 0x88.toByte()
        it[FrameLayout.ACK_CODE] = code.toByte()
        mac.toAscii().copyInto(it, FrameLayout.MAC)
    }

    @Test fun defaultConfigHasNoListener() {
        assertEquals(null, ClientConfig().frameListener)
    }

    @Test fun sentAndReceivedDatagramsAreReported() = runBlocking {
        client(events::add).use { client ->
            val device = Device(loopback, mac)
            val answering = Thread {
                val packet = DatagramPacket(ByteArray(2048), 2048)
                peer.receive(packet)
                peer.send(DatagramPacket(ack(AckCode.STOP), FrameLayout.SIZE, packet.socketAddress))
            }.apply { start() }
            assertEquals(CommandResult.Success, client.endPreview(device))
            answering.join()

            assertEquals(2, events.size)
            val sent = events[0]
            assertEquals(FrameDirection.SENT, sent.direction)
            assertEquals(InetSocketAddress(loopback, peer.localPort), sent.remote)
            assertArrayEquals(Frames.stopProcess(mac), sent.bytes)
            assertFalse(sent.discarded)
            val received = events[1]
            assertEquals(FrameDirection.RECEIVED, received.direction)
            assertEquals(InetSocketAddress(loopback, peer.localPort), received.remote)
            assertArrayEquals(ack(AckCode.STOP), received.bytes)
            assertFalse(received.discarded)
            assertTrue(sent.timeMillis <= received.timeMillis)
        }
    }

    @Test fun datagramsDrainedBeforeACommandAreReportedAsDiscarded() = runBlocking {
        client(events::add).use { client ->
            val stale = ack(0x42)
            peer.send(DatagramPacket(stale, stale.size, InetSocketAddress(loopback, client.localPort)))
            Thread.sleep(100) // let it reach the client's queue
            client.sendPreviewValues(Device(loopback, mac), listOf(1, 2, 3, 4, 5)) // no drain: nothing read
            assertTrue(events.none { it.direction == FrameDirection.RECEIVED })

            client.endPreview(Device(loopback, mac)) // drains, sends, nobody answers
            val discarded = events.single { it.direction == FrameDirection.RECEIVED }
            assertTrue(discarded.discarded)
            assertArrayEquals(stale, discarded.bytes)
            assertEquals(2, events.count { it.direction == FrameDirection.SENT })
        }
    }
}
