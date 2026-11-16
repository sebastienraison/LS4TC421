package ls4tc421.protocol

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FramesTest {
    private val mac = Vectors.mac

    private fun buildFromVector(v: Vector): ByteArray = when (v.call) {
        "search" -> Frames.search()
        "timeSync" -> Frames.timeSync(
            mac,
            LocalDateTime.of(v.int("year"), v.int("month"), v.int("day"), v.int("hour"), v.int("minute"), v.int("second")),
        )
        "startDownload" -> Frames.startDownload(mac, v.int("slot"), v.string("name"))
        "downloadStep" -> Frames.downloadStep(mac, v.int("step"), v.ints("channels"))
        "stopAllProcess" -> Frames.stopAllProcess(mac)
        "stopProcess" -> Frames.stopProcess(mac)
        "previewStart" -> Frames.previewStart(mac, v.string("name"))
        "previewValues" -> Frames.previewValues(mac, v.ints("channels"))
        "wifiSsid" -> Frames.wifiSsid(mac, v.string("ssid"), WifiSecurity.valueOf(v.string("security")), WifiCipher.valueOf(v.string("cipher")))
        "wifiPassword" -> Frames.wifiPassword(mac, v.string("password"))
        "wifiOpenSsid" -> Frames.wifiOpenSsid(mac, v.string("ssid"))
        else -> fail("Unknown call '${v.call}' in vectors.json").let { ByteArray(0) }
    }

    @Test
    fun everyVectorHasTheDocumentedShape() {
        assertTrue(Vectors.all.size >= 15)
        for (v in Vectors.all) {
            assertEquals(v.name, 64, v.bytes.size)
            assertTrue(v.name, v.status == "observed" || v.status == "derived-unverified")
            assertTrue(v.name, v.direction == "request" || v.direction == "response")
        }
        assertEquals(Vectors.all.size, Vectors.all.map { it.name }.toSet().size)
    }

    @Test
    fun everyRequestVectorMatchesTheBuilder() {
        val requests = Vectors.all.filter { it.direction == "request" }
        assertTrue(requests.isNotEmpty())
        for (v in requests) {
            assertEquals("vector ${v.name}", v.hex, buildFromVector(v).toHex())
        }
    }

    @Test
    fun everyCommandOfTheTableHasAVector() {
        val calls = Vectors.all.mapNotNull { it.call }.toSet()
        val expected = setOf(
            "search", "timeSync", "startDownload", "downloadStep", "stopAllProcess", "stopProcess",
            "previewStart", "previewValues", "wifiSsid", "wifiPassword", "wifiOpenSsid",
        )
        assertEquals(expected, calls)
    }

    @Test
    fun requestVectorsStartWithHeaderAndEndWithValidChecksum() {
        for (v in Vectors.all.filter { it.direction == "request" }) {
            val b = v.bytes
            assertEquals(v.name, 0x55, b[0].toInt() and 0xFF)
            assertEquals(v.name, 0xAA, b[1].toInt() and 0xFF)
            assertEquals(v.name, Frames.checksum(b), b[61].toInt() and 0xFF)
            assertEquals(v.name, 0, b[62].toInt())
            assertEquals(v.name, 0, b[63].toInt())
        }
    }

    @Test
    fun checksumSumsBytes0To48ModuloAndIgnoresTheMac() {
        val frame = ByteArray(64)
        for (i in 0 until 49) frame[i] = 0xFF.toByte()
        // 49 * 255 = 12495 = 0x30CF
        assertEquals(0xCF, Frames.checksum(frame))
        for (i in 49 until 64) frame[i] = 0x7B // outside the range: no effect
        assertEquals(0xCF, Frames.checksum(frame))
    }

    @Test
    fun macIsWrittenAsAsciiAtOffset49ExceptInDiscovery() {
        val step = Frames.downloadStep(mac, 0, listOf(0, 0, 0, 0, 0))
        assertEquals("AABBCCDDEEFF", String(step, 49, 12, Charsets.US_ASCII))
        assertTrue(Frames.search().copyOfRange(49, 61).all { it.toInt() == 0 })
    }

    @Test
    fun stepNumberMapsToHourAndMinute() {
        for (step in 0 until 48) {
            val f = Frames.downloadStep(mac, step, listOf(1, 2, 3, 4, 5))
            assertEquals(step, f[6].toInt())
            assertEquals(step / 2, f[7].toInt())
            assertEquals(if (step % 2 == 0) 0 else 30, f[8].toInt())
            assertEquals(listOf(1, 2, 3, 4, 5), (9..13).map { f[it].toInt() })
        }
    }

    @Test
    fun validationRejectsOutOfRangeValues() {
        fun rejects(block: () -> Unit) {
            try {
                block()
                fail("Expected IllegalArgumentException")
            } catch (_: IllegalArgumentException) {
            }
        }
        val ok = List(5) { 50 }
        rejects { Frames.downloadStep(mac, 48, ok) }
        rejects { Frames.downloadStep(mac, -1, ok) }
        rejects { Frames.downloadStep(mac, 0, listOf(0, 0, 0, 0, 101)) }
        rejects { Frames.downloadStep(mac, 0, listOf(0, 0, 0, 0, -1)) }
        rejects { Frames.downloadStep(mac, 0, listOf(0, 0, 0, 0)) }
        rejects { Frames.previewValues(mac, List(6) { 0 }) }
        rejects { Frames.startDownload(mac, 20, "x") }
        rejects { Frames.startDownload(mac, -1, "x") }
        rejects { Frames.startDownload(mac, 0, "") }
        rejects { Frames.startDownload(mac, 0, "x".repeat(10)) }
        rejects { Frames.wifiSsid(mac, "x".repeat(33), WifiSecurity.WPA2, WifiCipher.AES) }
        rejects { Frames.wifiOpenSsid(mac, "") }
        rejects { Frames.wifiPassword(mac, "é".repeat(17)) } // 34 bytes in UTF-8
        rejects { Frames.timeSync(mac, LocalDateTime.of(70000, 1, 1, 0, 0)) }
        rejects { Program("p", List(47) { ok }) }
        rejects { Program("p", List(49) { ok }) }
        rejects { Program("p", List(48) { if (it == 30) listOf(0, 0, 0, 0, 101) else ok }) }
        rejects { MacAddress.parse("AABBCC") }
        rejects { MacAddress.parse("GGBBCCDDEEFF") }
    }

    @Test
    fun boundaryValuesAreAccepted() {
        Frames.downloadStep(mac, 47, listOf(0, 100, 0, 100, 0))
        Frames.startDownload(mac, 19, "x".repeat(9))
        Frames.startDownload(mac, 0, "n")
        Frames.wifiSsid(mac, "s".repeat(32), WifiSecurity.WPA, WifiCipher.TKIP)
        Frames.wifiPassword(mac, "k".repeat(32))
        Program("p", List(48) { List(5) { 100 } })
    }

    @Test
    fun programNamesArePrintableAsciiOfOneToNineCharacters() {
        fun rejected(name: String) {
            try {
                Frames.startDownload(mac, 0, name)
                fail("Expected IllegalArgumentException for \"$name\"")
            } catch (_: IllegalArgumentException) {
            }
            try {
                Frames.previewStart(mac, name)
                fail("Expected IllegalArgumentException for \"$name\" (preview)")
            } catch (_: IllegalArgumentException) {
            }
        }
        // Accepted: 9 characters, space and tilde (the ends of the printable range).
        for (name in listOf("123456789", "a b", "~", " ", "!az09~")) {
            assertNull(name, ProgramName.check(name))
            Frames.startDownload(mac, 0, name)
            Frames.previewStart(mac, name)
            Program(name, List(48) { List(5) { 0 } })
        }
        for (name in listOf("1234567890", "\u00e9t\u00e9", "\u2600", "a\tb", "a\u007Fb", "")) rejected(name)

        assertEquals(ProgramName.Problem.Empty, ProgramName.check(""))
        assertEquals(ProgramName.Problem.TooLong(10), ProgramName.check("1234567890"))
        assertEquals(ProgramName.Problem.Refused("\u00e9"), ProgramName.check("\u00e9t\u00e9"))
        assertEquals(ProgramName.Problem.Refused("\u2600"), ProgramName.check("a\u2600"))
        assertEquals(ProgramName.Problem.Refused("\t"), ProgramName.check("a\tb"))
        assertEquals(ProgramName.Problem.Refused("\u007F"), ProgramName.check("\u007F"))
        // A character outside the BMP is reported whole, not as half a surrogate pair.
        assertEquals(ProgramName.Problem.Refused("\uD83D\uDCA1"), ProgramName.check("a\uD83D\uDCA1"))
        // The refused character wins over the length.
        assertEquals(ProgramName.Problem.Refused("\u00e9"), ProgramName.check("\u00e9".repeat(12)))
    }

    @Test
    fun namesAreSentAsAsciiOneBytePerCharacter() {
        val frame = Frames.startDownload(mac, 2, "abc ~9")
        assertEquals(6, frame[5].toInt())
        assertEquals("abc ~9", String(frame, 7, 6, Charsets.US_ASCII))
        assertEquals(0, frame[13].toInt())
        val preview = Frames.previewStart(mac, "123456789")
        assertEquals("123456789", String(preview, 6, 9, Charsets.US_ASCII))
    }

    @Test
    fun macParsingNormalizes() {
        assertEquals("AABBCCDDEEFF", MacAddress.parse("aa:bb:cc:dd:ee:ff").text)
        assertNull(MacAddress.parseOrNull("not a mac"))
    }

    @Test
    fun responseVectorsAreReadWithoutCheckingTheChecksum() {
        val replies = Vectors.all.filter { it.direction == "response" }
        assertTrue(replies.size >= 3)
        for (v in replies) {
            val bytes = v.bytes
            when (v.expected["kind"]) {
                "discovery" -> {
                    val r = FrameReader.discovery(bytes)
                    assertNotNull(v.name, r)
                    assertEquals(v.name, v.expected["mac"], r!!.mac.text)
                }
                "ack" -> {
                    val r = FrameReader.ack(bytes)
                    assertNotNull(v.name, r)
                    assertEquals(v.name, (v.expected["ackCode"] as Long).toInt(), r!!.code)
                    assertEquals(v.name, v.expected["mac"], r.mac.text)
                }
                else -> fail("Unknown kind in ${v.name}")
            }
            // Whatever the checksum byte holds, the reading is unchanged.
            for (junk in listOf(0x00, 0x5A, 0xFF)) {
                val tampered = bytes.copyOf().also { it[61] = junk.toByte() }
                assertEquals(v.name, FrameReader.ack(bytes), FrameReader.ack(tampered))
                assertEquals(v.name, FrameReader.discovery(bytes), FrameReader.discovery(tampered))
            }
        }
    }

    @Test
    fun ackIsReadFromByte4WhateverByte2Holds() {
        val reply = ByteArray(64)
        reply[0] = 0x88.toByte()
        mac.toAscii().copyInto(reply, 49)
        reply[4] = 0x81.toByte()
        for (b2 in listOf(0x01, 0x02, 0x03, 0x11, 0x7E)) {
            reply[2] = b2.toByte()
            assertEquals(0x81, FrameReader.ack(reply)!!.code)
        }
    }

    @Test
    fun malformedRepliesAreRejected() {
        val good = Vectors.all.first { it.name == "ack_time_sync" }.bytes
        assertNotNull(FrameReader.ack(good))
        assertNull(FrameReader.ack(good.copyOf(63)))
        assertNull(FrameReader.ack(good.copyOf(65)))
        assertNull(good.copyOf().also { it[0] = 0x55 }.let(FrameReader::ack))
        assertNull(good.copyOf().also { it[1] = 1 }.let(FrameReader::ack))
        assertNull(good.copyOf().also { it[49] = 'Z'.code.toByte() }.let(FrameReader::ack))
        assertFalse(FrameReader.isReply(Frames.search()))
        assertNull(FrameReader.discovery(good)) // byte 2 is 0x03, not 0x17
    }

    @Test
    fun builtFramesAreIndependentCopies() {
        val a = Frames.search()
        a[0] = 0
        assertEquals(0x55, Frames.search()[0].toInt())
    }
}
