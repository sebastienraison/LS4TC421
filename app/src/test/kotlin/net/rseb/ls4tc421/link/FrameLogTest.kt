package net.rseb.ls4tc421.link

import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking
import ls4tc421.protocol.ClientConfig
import ls4tc421.protocol.Device
import ls4tc421.protocol.FrameDirection
import ls4tc421.protocol.FrameEvent
import ls4tc421.protocol.Frames
import ls4tc421.protocol.MacAddress
import ls4tc421.protocol.Tc421Client
import ls4tc421.protocol.WifiCipher
import ls4tc421.protocol.WifiSecurity
import ls4tc421.simulator.Tc421Simulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameLogTest {
    private val loopback = InetAddress.getLoopbackAddress()
    private val mac = MacAddress.parse("AABBCCDDEEFF")
    private val remote = InetSocketAddress(loopback, 5000)

    private fun event(bytes: ByteArray, at: Long, direction: FrameDirection = FrameDirection.SENT, discarded: Boolean = false) =
        FrameEvent(at, direction, remote, bytes, discarded)

    private fun ascii(text: String) = text.toByteArray().joinToString(" ") { "%02X".format(it) }

    @Test fun sentAndReceivedFramesOfTheClientAreLogged() = runBlocking {
        val log = FrameLog()
        Tc421Simulator(mac).start().use { sim ->
            Tc421Client(
                ClientConfig(
                    remotePort = sim.port, localPort = 0, localAddress = loopback,
                    ackTimeout = 300.milliseconds, attempts = 1, stepInterval = Duration.ZERO, frameListener = log,
                ),
            ).use { client -> client.endPreview(Device(loopback, mac)) }
        }
        val lines = log.lines.value
        assertEquals(2, lines.size)
        assertEquals(FrameDirection.SENT, lines[0].direction)
        assertEquals(FrameLog.render(Frames.stopProcess(mac)), lines[0].hex)
        assertEquals(FrameDirection.RECEIVED, lines[1].direction)
        assertTrue(lines[1].hex.startsWith("88 00"))
        assertTrue(lines.all { it.remote.startsWith("127.0.0.1:") })
    }

    @Test fun wifiFramesAreRedactedBeforeTheyAreStored() = runBlocking {
        val log = FrameLog()
        val ssid = "bench-network"
        val key = "secret-passphrase"
        Tc421Simulator(mac).start().use { sim ->
            Tc421Client(
                ClientConfig(
                    remotePort = sim.port, localPort = 0, localAddress = loopback,
                    ackTimeout = 300.milliseconds, attempts = 1, stepInterval = Duration.ZERO, frameListener = log,
                ),
            ).use { client -> client.joinWifi(Device(loopback, mac), ssid, key, WifiSecurity.WPA2, WifiCipher.AES) }
            assertEquals(key, sim.wifi?.password) // the controller did get the key
        }
        val sent = log.lines.value.filter { it.direction == FrameDirection.SENT }
        assertEquals(2, sent.size)
        val text = log.export(zone = ZoneOffset.UTC)
        for (line in sent) {
            assertTrue(line.hex, line.hex.startsWith("55 AA 18 00 00"))
            assertTrue(line.hex.contains(" REDACTED "))
        }
        assertFalse(text.contains(ascii(key)))
        assertFalse(text.contains(ascii(ssid)))
        assertFalse(text.contains(ascii("secret")))
        // The MAC (49..60) keeps only its last 4 characters.
        assertTrue(sent.all { it.hex.contains("XX XX XX XX XX XX XX XX ${ascii("EEFF")}") })
        assertFalse(text.contains(ascii(mac.text)))
    }

    @Test fun redactionCoversBytes6To48Exactly() {
        val frame = Frames.wifiPassword(mac, "abcdefgh")
        val rendered = FrameLog.render(frame)
        val macHidden = "XX XX XX XX XX XX XX XX"
        assertEquals("${FrameLog.hex(frame, 0, 6)} REDACTED $macHidden ${FrameLog.hex(frame, 57)}", rendered)
        // Any other command keeps its data; only the MAC is masked.
        val preview = Frames.previewValues(mac, listOf(1, 2, 3, 4, 5))
        assertEquals("${FrameLog.hex(preview, 0, 49)} $macHidden ${FrameLog.hex(preview, 57)}", FrameLog.render(preview))
    }

    @Test fun identicalConsecutiveFramesAreFolded() {
        val log = FrameLog()
        val values = Frames.previewValues(mac, listOf(100, 0, 0, 0, 0))
        val other = Frames.previewValues(mac, listOf(0, 100, 0, 0, 0))
        for (i in 0 until 5) log.onFrame(event(values, 1_000L + i))
        log.onFrame(event(other, 2_000L))
        log.onFrame(event(values, 3_000L))
        log.onFrame(event(values, 3_001L, direction = FrameDirection.RECEIVED))
        val lines = log.lines.value
        assertEquals(listOf(5, 1, 1, 1), lines.map { it.count })
        assertEquals(1_000L, lines[0].firstAt)
        assertEquals(1_004L, lines[0].lastAt)
        val formatted = FrameLog.format(lines[0], ZoneOffset.UTC)
        assertTrue(formatted, formatted.startsWith("00:00:01.000 TX 127.0.0.1:5000  55 AA 06 02"))
        assertTrue(formatted, formatted.endsWith("× 5 (last 00:00:01.004)"))
    }

    @Test fun discardedFramesAreMarked() {
        val log = FrameLog()
        log.onFrame(event(ByteArray(64), 0L, FrameDirection.RECEIVED, discarded = true))
        log.onFrame(event(ByteArray(64), 1L, FrameDirection.RECEIVED, discarded = false))
        assertEquals(2, log.lines.value.size) // not folded together
        assertTrue(FrameLog.format(log.lines.value[0], ZoneOffset.UTC).contains(" RX(discarded) "))
        assertTrue(FrameLog.format(log.lines.value[1], ZoneOffset.UTC).contains(" RX "))
    }

    @Test fun clearEmptiesTheLogAndOldLinesAreDropped() {
        val log = FrameLog(maxLines = 3)
        for (i in 0 until 5) log.onFrame(event(Frames.previewValues(mac, listOf(i, 0, 0, 0, 0)), i.toLong()))
        assertEquals(listOf(2L, 3L, 4L), log.lines.value.map { it.firstAt })
        log.clear()
        assertTrue(log.lines.value.isEmpty())
    }
}
