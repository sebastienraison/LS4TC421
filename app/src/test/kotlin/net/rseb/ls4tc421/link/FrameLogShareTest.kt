package net.rseb.ls4tc421.link

import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.ZoneOffset
import ls4tc421.protocol.FrameDirection
import ls4tc421.protocol.FrameEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The text shared from the exchange log (D28, Q140 A): zero runs compressed, length capped, newest lines kept. */
class FrameLogShareTest {
    private val header = "Light Scheduler for TC421 0.1.0 · Android 16 (API 36)"

    private fun line(at: Long, hex: String, remote: String = "127.0.0.1:5000", count: Int = 1, discarded: Boolean = false) =
        LogLine(at, at + count - 1, FrameDirection.RECEIVED, discarded, remote, hex, count)

    private fun cells(vararg parts: String) = parts.joinToString(" ")
    private fun zeros(n: Int) = List(n) { "00" }.joinToString(" ")

    @Test fun threeZerosStayFourAreCompressed() {
        assertEquals(cells("55", zeros(3), "AA"), FrameLog.compressZeros(cells("55", zeros(3), "AA")))
        assertEquals("55 00×4 AA", FrameLog.compressZeros(cells("55", zeros(4), "AA")))
        assertEquals("55 AA 00×38 01", FrameLog.compressZeros(cells("55", "AA", zeros(38), "01")))
        // A run at the start, at the end, and a dump made only of zeros.
        assertEquals("00×5 17", FrameLog.compressZeros(cells(zeros(5), "17")))
        assertEquals("88 00×63", FrameLog.compressZeros(cells("88", zeros(63))))
        assertEquals("00×64", FrameLog.compressZeros(zeros(64)))
        // Zeros around a single other byte: two runs.
        assertEquals("00×4 01 00×4", FrameLog.compressZeros(cells(zeros(4), "01", zeros(4))))
        assertEquals(cells(zeros(2), "01", zeros(2)), FrameLog.compressZeros(cells(zeros(2), "01", zeros(2))))
    }

    @Test fun maskedCellsAreNotTouched() {
        val masked = cells("55", "AA", "18", "00", "00", "00", "REDACTED", "XX", "XX", zeros(4), "41")
        assertEquals("55 AA 18 00 00 00 REDACTED XX XX 00×4 41", FrameLog.compressZeros(masked))
        // XX between zeros breaks the run.
        assertEquals(cells(zeros(3), "XX", zeros(3)), FrameLog.compressZeros(cells(zeros(3), "XX", zeros(3))))
    }

    @Test fun aLineWithoutZerosDoesNotChange() {
        val hex = List(64) { "%02X".format(it + 1) }.joinToString(" ")
        assertEquals(hex, FrameLog.compressZeros(hex))
        val l = line(1_000L, hex, count = 3)
        assertEquals(FrameLog.format(l, ZoneOffset.UTC), FrameLog.formatForShare(l, ZoneOffset.UTC))
    }

    @Test fun onlyTheDumpChangesInAFormattedLine() {
        val l = line(1_000L, cells("55", "AA", zeros(10), "06", "02"), count = 5)
        assertEquals(
            FrameLog.format(l, ZoneOffset.UTC).replace(zeros(10), "00×10"),
            FrameLog.formatForShare(l, ZoneOffset.UTC),
        )
    }

    @Test fun underTheCapEverythingGoesAndNothingIsAnnounced() {
        val lines = List(10) { line(it * 1000L, cells("55", "AA", zeros(60), "%02X".format(it))) }
        val text = FrameLog.shareText(lines, header, zone = ZoneOffset.UTC)
        val out = text.lines()
        assertEquals(header, out[0])
        assertEquals(FrameLog.ZERO_RUN_NOTE, out[1])
        assertEquals("", out[2])
        assertEquals(lines.map { FrameLog.formatForShare(it, ZoneOffset.UTC) }, out.subList(3, 13))
        assertEquals("", out[13]) // the text ends with a newline
        assertFalse(text.contains("left out"))
    }

    @Test fun withoutHeaderTheNoteComesFirst() {
        val text = FrameLog.shareText(listOf(line(0L, zeros(64))), header = null, zone = ZoneOffset.UTC)
        assertEquals(FrameLog.ZERO_RUN_NOTE, text.lines()[0])
    }

    @Test fun overTheCapTheNewestLinesGoAndTheOmissionIsAnnounced() {
        val lines = List(100) { line(it * 1000L, List(64) { "AB" }.joinToString(" ")) }
        val each = FrameLog.formatForShare(lines[0], ZoneOffset.UTC).length + 1
        val max = 5_000
        val text = FrameLog.shareText(lines, header, maxChars = max, zone = ZoneOffset.UTC)
        assertTrue("${text.length}", text.length <= max)
        val out = text.lines()
        val kept = out.drop(4).filter { it.isNotEmpty() }
        val omitted = 100 - kept.size
        assertTrue(omitted > 0)
        assertEquals(FrameLog.omittedNote(omitted), out[2])
        assertEquals("Oldest $omitted lines left out: log too long to share.", out[2])
        assertEquals("", out[3])
        // The kept lines are the most recent ones, oldest first.
        assertEquals(lines.takeLast(kept.size).map { FrameLog.formatForShare(it, ZoneOffset.UTC) }, kept)
        // And as many as fit: one more would go over.
        assertTrue(text.length + each > max)
    }

    @Test fun theOmissionSaysOneLine() {
        assertEquals("Oldest line left out: log too long to share.", FrameLog.omittedNote(1))
    }

    @Test fun aThousandWorstCaseLinesAreCutCleanlyUnderTheCap() {
        // Worst case: 64 bytes without any zero, the longest prefix (discarded reply, long address, folded).
        val hex = List(64) { "%02X".format(0x80 + it) }.joinToString(" ")
        val lines = List(1000) { line(it * 1000L, hex, remote = "172.16.200.200:65535", count = 1000, discarded = true) }
        val uncapped = lines.sumOf { FrameLog.formatForShare(it, ZoneOffset.UTC).length + 1 }
        val text = FrameLog.shareText(lines, header, zone = ZoneOffset.UTC)
        val out = text.lines()
        val kept = out.drop(4).count { it.isNotEmpty() }
        println("worst case: 1000 lines = $uncapped characters uncapped; shared ${text.length} characters, $kept lines kept, ${1000 - kept} left out")
        assertTrue(text.length <= FrameLog.SHARE_MAX_CHARS)
        assertEquals(FrameLog.omittedNote(1000 - kept), out[2])
        assertTrue(out.drop(4).filter { it.isNotEmpty() }.all { it.contains(" RX(discarded) 172.16.200.200:65535  ") })
        assertEquals(FrameLog.formatForShare(lines.last(), ZoneOffset.UTC), out.drop(4).last { it.isNotEmpty() })
    }

    @Test fun theLogInMemoryIsNotChanged() {
        val log = FrameLog()
        log.onFrame(FrameEvent(0L, FrameDirection.SENT, InetSocketAddress(InetAddress.getLoopbackAddress(), 5000), ByteArray(64), false))
        val before = log.lines.value
        val text = log.shareText(header, zone = ZoneOffset.UTC)
        assertEquals(before, log.lines.value)
        assertTrue(text, text.lines()[3].endsWith("  00×64"))
        assertTrue(log.export(header, ZoneOffset.UTC).contains(zeros(64)))
    }
}
