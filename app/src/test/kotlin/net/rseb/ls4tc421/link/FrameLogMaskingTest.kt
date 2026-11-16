package net.rseb.ls4tc421.link

import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking
import ls4tc421.protocol.ClientConfig
import ls4tc421.protocol.CommandResult
import ls4tc421.protocol.Device
import ls4tc421.protocol.DiscoveryResult
import ls4tc421.protocol.FrameDirection
import ls4tc421.protocol.FrameEvent
import ls4tc421.protocol.MacAddress
import ls4tc421.protocol.Program
import ls4tc421.protocol.Tc421Client
import ls4tc421.protocol.WifiCipher
import ls4tc421.protocol.WifiSecurity
import ls4tc421.protocol.WriteResult
import ls4tc421.simulator.Tc421Simulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D28: what the shared exchange log may contain. Every frame type goes through the real client and
 * the simulator (on 127.0.0.1); the MAC keeps only its last 4 characters, the Wi-Fi name and key are
 * absent, private addresses stay readable and any other address becomes `public`.
 */
class FrameLogMaskingTest {
    private val loopback = InetAddress.getLoopbackAddress()

    /** Distinct characters, so that a leak of any part of the hidden prefix is visible. */
    private val mac = MacAddress.parse("12345678ABCD")
    private val hiddenPrefix = "12345678".map { "%02X".format(it.code) } // 31 .. 38
    private val keptTail = "ABCD".map { "%02X".format(it.code) }.joinToString(" ") // 41 42 43 44

    private fun cells(line: LogLine) = line.hex.split(" ")

    private fun client(sim: Tc421Simulator, log: FrameLog) = Tc421Client(
        ClientConfig(
            remotePort = sim.port, localPort = 0, localAddress = loopback, broadcastAddress = loopback,
            ackTimeout = 300.milliseconds, attempts = 1, stepInterval = Duration.ZERO,
            discoveryInterval = 50.milliseconds, frameListener = log,
        ),
    )

    /** No three consecutive bytes of the hidden prefix appear anywhere in [text]. */
    private fun assertNoHiddenPart(text: String) {
        for (i in 0..hiddenPrefix.size - 3) {
            val window = hiddenPrefix.subList(i, i + 3).joinToString(" ")
            assertFalse("$window leaked in:\n$text", text.contains(window))
        }
    }

    @Test fun everyFrameTypeShowsOnlyTheLastFourCharactersOfTheMac() = runBlocking {
        val log = FrameLog()
        val ssid = "lab-network"
        val key = "lab-passphrase"
        // Levels 0 and 100 only: no data byte falls in 0x31..0x38, the hidden characters.
        val program = Program("Test", List(48) { step -> List(5) { if (step % 2 == 0) 0 else 100 } })
        Tc421Simulator(mac).start().use { sim ->
            client(sim, log).use { client ->
                val found = client.discover(null) as DiscoveryResult.Found // 17 and its reply
                val device = Device(loopback, found.devices.single().mac)
                assertEquals(CommandResult.Success, client.setTime(device, LocalDateTime.of(2026, 1, 1, 0, 0, 0))) // 02
                assertEquals(WriteResult.Success, client.writeProgram(device, 0, program)) // 01 01, 01 02, 04 0A
                assertEquals(CommandResult.Success, client.joinWifi(device, ssid, key, WifiSecurity.WPA2, WifiCipher.AES)) // 18
                assertEquals(CommandResult.Success, client.joinWifi(device, ssid, null, WifiSecurity.WPA2, WifiCipher.AES)) // 18 open
                client.startPreview(device, "Test") // 06 01
                client.sendPreviewValues(device, listOf(0, 0, 0, 0, 0)) // 06 02
                assertEquals(CommandResult.Success, client.endPreview(device)) // 03 08
            }
        }
        val lines = log.lines.value
        val sent = lines.filter { it.direction == FrameDirection.SENT }
        val received = lines.filter { it.direction == FrameDirection.RECEIVED }
        fun sentCommand(code: String, sub: String? = null) =
            sent.filter { cells(it)[2] == code && (sub == null || cells(it)[3] == sub) }

        // Search request: no MAC at all, nothing hidden.
        val search = sentCommand("17")
        assertTrue(search.isNotEmpty())
        search.forEach { assertFalse(it.hex.contains("XX")) }

        // Requests carrying the MAC at 49..60.
        val withMac = listOf(
            sentCommand("02"), sentCommand("01", "01"), sentCommand("01", "02"), sentCommand("04", "0A"),
            sentCommand("06", "01"), sentCommand("06", "02"), sentCommand("03", "08"),
        )
        withMac.forEach { group ->
            assertTrue(group.isNotEmpty())
            group.forEach { line ->
                val c = cells(line)
                assertEquals(64, c.size)
                assertEquals(List(8) { "XX" }, c.subList(49, 57))
                assertEquals(keptTail, c.subList(57, 61).joinToString(" "))
            }
        }
        // Wi-Fi requests: data redacted, MAC masked in the same way after it.
        val wifi = sentCommand("18")
        assertEquals(3, wifi.size)
        wifi.forEach { line ->
            assertTrue(line.hex, line.hex.startsWith("55 AA 18 00 "))
            assertTrue(line.hex, line.hex.contains(" REDACTED XX XX XX XX XX XX XX XX $keptTail "))
        }

        // Discovery reply: MAC at 4..15.
        val discovery = received.filter { cells(it)[2] == "17" }
        assertTrue(discovery.isNotEmpty())
        discovery.forEach { line ->
            val c = cells(line)
            assertEquals(List(8) { "XX" }, c.subList(4, 12))
            assertEquals(keptTail, c.subList(12, 16).joinToString(" "))
        }
        // Acknowledgements: code at 4 kept, MAC tail at 5..15 and MAC at 49..60.
        val acks = received.filter { cells(it)[2] != "17" }
        assertEquals(setOf("85", "80", "81", "91", "70", "72", "74", "83"), acks.map { cells(it)[4] }.toSet())
        acks.forEach { line ->
            val c = cells(line)
            assertEquals(List(7) { "XX" }, c.subList(5, 12))
            assertEquals(keptTail, c.subList(12, 16).joinToString(" "))
            assertEquals(List(8) { "XX" }, c.subList(49, 57))
            assertEquals(keptTail, c.subList(57, 61).joinToString(" "))
        }

        val text = log.export("header", ZoneOffset.UTC)
        assertNoHiddenPart(text)
        for (secret in listOf(key, ssid, "lab-")) {
            assertFalse(text.contains(secret.toByteArray().joinToString(" ") { "%02X".format(it) }))
        }
        assertTrue(lines.all { it.remote.startsWith("127.0.0.1:") })
    }

    @Test fun aDatagramOfUnknownLayoutHidesAnyMacLikeText() {
        val bytes = "junk 12345678ABCD end".toByteArray()
        val rendered = FrameLog.render(bytes)
        assertNoHiddenPart(rendered)
        assertTrue(rendered, rendered.contains("XX XX XX XX XX XX XX XX $keptTail"))
        assertEquals(bytes.size, rendered.split(" ").size) // same length as an unmasked dump
    }

    @Test fun privateAddressesAreShownAndOthersArePublic() {
        fun shown(text: String, port: Int = 5000) =
            FrameLog.address(InetSocketAddress(InetAddress.getByName(text), port))
        for (private in listOf("10.1.2.3", "172.16.0.1", "172.31.255.254", "192.168.1.20", "169.254.7.8", "127.0.0.1")) {
            assertEquals("$private:5000", shown(private))
        }
        assertEquals("255.255.255.255:5000", shown("255.255.255.255"))
        for (public in listOf("8.8.8.8", "172.32.0.1", "172.15.0.1", "192.169.0.1", "100.64.0.1", "203.0.113.9")) {
            assertEquals("public:5000", shown(public))
        }
        assertEquals("public:41234", shown("203.0.113.9", 41234)) // port kept
        val v6 = InetSocketAddress(Inet6Address.getByName("2001:db8::1"), 5000)
        assertEquals("public:5000", FrameLog.address(v6))
    }

    @Test fun theLogStoresTheMaskedAddress() {
        val log = FrameLog()
        val bytes = ByteArray(64)
        log.onFrame(FrameEvent(0L, FrameDirection.SENT, InetSocketAddress(InetAddress.getByName("192.168.4.1"), 5000), bytes, false))
        log.onFrame(FrameEvent(1L, FrameDirection.SENT, InetSocketAddress(InetAddress.getByName("198.51.100.7"), 5000), bytes, false))
        assertEquals(listOf("192.168.4.1:5000", "public:5000"), log.lines.value.map { it.remote })
    }

    @Test fun theExportStartsWithTheHeader() {
        val log = FrameLog()
        log.onFrame(FrameEvent(0L, FrameDirection.SENT, InetSocketAddress(loopback, 5000), ByteArray(64), false))
        val header = FrameLog.exportHeader("Light Scheduler for TC421", "0.1.0", "16", 36)
        assertEquals("Light Scheduler for TC421 0.1.0 · Android 16 (API 36)", header)
        val text = log.export(header, ZoneOffset.UTC)
        val lines = text.lines()
        assertEquals(header, lines[0])
        assertEquals("", lines[1])
        assertTrue(lines[2], lines[2].startsWith("00:00:00.000 TX 127.0.0.1:5000  00 00"))
    }
}
