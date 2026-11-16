package net.rseb.ls4tc421.link

import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ls4tc421.protocol.Cmd
import ls4tc421.protocol.FrameDirection
import ls4tc421.protocol.FrameEvent
import ls4tc421.protocol.FrameLayout
import ls4tc421.protocol.FrameListener
import ls4tc421.protocol.MacAddress

/** One line of the log: one datagram, or [count] identical consecutive ones. */
data class LogLine(
    val firstAt: Long,
    val lastAt: Long,
    val direction: FrameDirection,
    val discarded: Boolean,
    /** Address and port, already masked (see [FrameLog.address]). */
    val remote: String,
    /** Hex dump, already masked (see [FrameLog.render]). */
    val hex: String,
    val count: Int = 1,
)

/**
 * The exchange log (D28): every frame of the protocol client, in memory only, in both build types.
 * It is never written to a file and never sent by the app; the user may share it from Settings.
 *
 * Masking is applied before a line is stored, so the clear data never enters the log:
 *  - Wi-Fi frames (`18`): bytes 6 to 48 (sub-command, network name or key) become `REDACTED`;
 *  - the controller's MAC (12 ASCII hex characters): its first 8 characters are shown as `XX`,
 *    the last 4 (2 bytes) stay readable; see [hiddenBytes] for the positions;
 *  - IP addresses: private ones are shown, any other is `public`; the port is kept.
 *
 * Identical consecutive datagrams (the `06 02` resent every second) are folded into one line
 * « × n ». The oldest lines go beyond [maxLines].
 */
class FrameLog(private val maxLines: Int = 1000) : FrameListener {
    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())

    /** Oldest first. */
    val lines: StateFlow<List<LogLine>> = _lines

    override fun onFrame(event: FrameEvent) {
        val hex = render(event.bytes)
        val remote = address(event.remote)
        synchronized(this) {
            val current = _lines.value
            val last = current.lastOrNull()
            _lines.value = if (
                last != null && last.direction == event.direction && last.discarded == event.discarded &&
                last.remote == remote && last.hex == hex
            ) {
                current.dropLast(1) + last.copy(lastAt = event.timeMillis, count = last.count + 1)
            } else {
                (current + LogLine(event.timeMillis, event.timeMillis, event.direction, event.discarded, remote, hex))
                    .takeLast(maxLines)
            }
        }
    }

    fun clear() = synchronized(this) { _lines.value = emptyList() }

    /** Plain-text export, oldest first, one line per entry, after [header] when there is one. */
    fun export(header: String? = null, zone: ZoneId = ZoneId.systemDefault()): String {
        val body = _lines.value.joinToString(separator = "\n", postfix = "\n") { format(it, zone) }
        return if (header == null) body else "$header\n\n$body"
    }

    /** The text handed to Android's share sheet: see [FrameLog.Companion.shareText]. */
    fun shareText(header: String? = null, maxChars: Int = SHARE_MAX_CHARS, zone: ZoneId = ZoneId.systemDefault()): String =
        shareText(_lines.value, header, maxChars, zone)

    companion object {
        private const val REDACT_FROM = FrameLayout.DATA
        private const val REDACT_TO = FrameLayout.CHECKSUM_END - 1 // 48, inclusive

        /** Characters of the MAC that are hidden: the first 8 of 12. */
        private const val MAC_HIDDEN = MacAddress.LENGTH - 4

        /** In an acknowledgement, the code at byte 4 overwrites the first MAC character: the MAC goes on at 5. */
        private const val ACK_MAC_TAIL = FrameLayout.ACK_CODE + 1

        private const val HIDDEN = "XX"
        private const val PUBLIC = "public"
        private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

        fun hex(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): String =
            (from until to).joinToString(" ") { "%02X".format(bytes[it].toInt() and 0xFF) }

        /**
         * Hex dump with the masks applied: hidden MAC bytes are `XX` (the length of the dump does not
         * change); for a Wi-Fi frame (`18` at byte 2), bytes 6 to 48 are replaced by `REDACTED`.
         */
        fun render(bytes: ByteArray): String {
            val hidden = hiddenBytes(bytes)
            val cells = bytes.indices.map { if (it in hidden) HIDDEN else "%02X".format(bytes[it].toInt() and 0xFF) }
            return if (isRequest(bytes) && command(bytes) == Cmd.WIFI) {
                (cells.subList(0, REDACT_FROM) + "REDACTED" + cells.subList(REDACT_TO + 1, cells.size)).joinToString(" ")
            } else {
                cells.joinToString(" ")
            }
        }

        /**
         * Offsets of the MAC characters to hide (docs/PROTOCOL.md, `Frames`, `FrameReader`):
         *  - request (`55 AA`), every command but `17`: MAC at 49..60, hide 49..56;
         *  - discovery request `17`: no MAC (zeros at 49..60), nothing hidden;
         *  - discovery reply (`88 00`, byte 2 = `17`): MAC at 4..15, hide 4..11;
         *  - acknowledgement (`88 00`, any other byte 2): MAC at 49..60, hide 49..56; the end of the
         *    MAC also sits at 5..15 (byte 4 is the code), hide 5..11 so that only the last 4 remain;
         *  - any other datagram (wrong size or header): every run of 12 or more hexadecimal
         *    characters is taken for a MAC, and all but its last 4 characters are hidden.
         * A span made only of zeros is left as it is: it holds no MAC.
         */
        fun hiddenBytes(bytes: ByteArray): Set<Int> {
            val hidden = sortedSetOf<Int>()
            fun hide(from: Int, count: Int) {
                val span = from until from + count
                if (span.any { bytes[it].toInt() != 0 }) hidden += span
            }
            when {
                isRequest(bytes) -> hide(FrameLayout.MAC, MAC_HIDDEN)
                isReply(bytes) && command(bytes) == Cmd.SEARCH -> hide(FrameLayout.DISCOVERY_MAC, MAC_HIDDEN)
                isReply(bytes) -> {
                    hide(FrameLayout.MAC, MAC_HIDDEN)
                    hide(ACK_MAC_TAIL, MAC_HIDDEN - 1)
                }
                else -> hidden += hexRuns(bytes)
            }
            return hidden
        }

        /** In a datagram of unknown layout: all but the last 4 bytes of each run of 12+ hex characters. */
        private fun hexRuns(bytes: ByteArray): Set<Int> {
            val out = mutableSetOf<Int>()
            var start = 0
            while (start < bytes.size) {
                var end = start
                while (end < bytes.size && isHexChar(bytes[end])) end++
                if (end - start >= MacAddress.LENGTH) out += start until end - (MacAddress.LENGTH - MAC_HIDDEN)
                start = if (end == start) start + 1 else end
            }
            return out
        }

        private fun isHexChar(b: Byte): Boolean {
            val c = (b.toInt() and 0xFF).toChar()
            return c in '0'..'9' || c in 'A'..'F' || c in 'a'..'f'
        }

        private fun isRequest(bytes: ByteArray) =
            bytes.size == FrameLayout.SIZE && bytes[0] == 0x55.toByte() && bytes[1] == 0xAA.toByte()

        private fun isReply(bytes: ByteArray) =
            bytes.size == FrameLayout.SIZE && bytes[0] == 0x88.toByte() && bytes[1] == 0.toByte()

        private fun command(bytes: ByteArray) = bytes[FrameLayout.COMMAND].toInt() and 0xFF

        /**
         * « address:port » for the log: a private IPv4 address (10/8, 172.16/12, 192.168/16,
         * 169.254/16, 127/8) or the broadcast address is shown, any other address is `public`.
         */
        fun address(remote: InetSocketAddress): String {
            val inet = remote.address
            val host = if (inet != null && isShown(inet)) inet.hostAddress else PUBLIC
            return "$host:${remote.port}"
        }

        private fun isShown(inet: InetAddress): Boolean {
            if (inet !is Inet4Address) return false
            val b = inet.address.map { it.toInt() and 0xFF }
            return b[0] == 10 ||
                (b[0] == 172 && b[1] in 16..31) ||
                (b[0] == 192 && b[1] == 168) ||
                (b[0] == 169 && b[1] == 254) ||
                b[0] == 127 ||
                b.all { it == 255 } // the broadcast of the search: not anyone's address
        }

        /** First line of a shared log: app, version, Android version. */
        fun exportHeader(appName: String, versionName: String, androidRelease: String, sdk: Int): String =
            "$appName $versionName · Android $androidRelease (API $sdk)"

        /**
         * Most characters of a shared log (D28, Q140 A). The text crosses to the share sheet inside an
         * Intent, through the Binder transaction buffer (1 MB, shared by the whole process); 100 000
         * characters are about 200 KB in UTF-16, a fifth of it. 683 KB in one Intent froze the app.
         */
        const val SHARE_MAX_CHARS = 100_000

        /** Second line of a shared log: how to read the compressed zeros. */
        const val ZERO_RUN_NOTE = "Runs of 4 or more zero bytes are written 00×n (n zero bytes)."

        /** Shortest run of `00` bytes written as `00×n` in a shared log. */
        private const val ZERO_RUN_MIN = 4

        /** The line that says the oldest [count] lines were left out of a shared log. */
        fun omittedNote(count: Int): String =
            if (count == 1) "Oldest line left out: log too long to share." else "Oldest $count lines left out: log too long to share."

        /**
         * A hex dump with every run of at least 4 `00` bytes written `00×n` (38 zero bytes: `00×38`).
         * Anything else, `XX` and `REDACTED` included, is left as it is.
         */
        fun compressZeros(hex: String): String {
            if (hex.isEmpty()) return hex
            val out = mutableListOf<String>()
            var zeros = 0
            fun flush() {
                if (zeros >= ZERO_RUN_MIN) out += "00×$zeros" else repeat(zeros) { out += "00" }
                zeros = 0
            }
            for (cell in hex.split(' ')) {
                if (cell == "00") {
                    zeros++
                } else {
                    flush()
                    out += cell
                }
            }
            flush()
            return out.joinToString(" ")
        }

        /** A line as [format] writes it, its dump with the zero runs compressed. */
        fun formatForShare(line: LogLine, zone: ZoneId = ZoneId.systemDefault()): String =
            format(line.copy(hex = compressZeros(line.hex)), zone)

        /**
         * The shared log (D28): [header] if any, the [ZERO_RUN_NOTE], a blank line, then [lines] oldest
         * first with their zero runs compressed. It never exceeds [maxChars]: beyond, only the most recent
         * lines are kept and a line under the note says how many of the oldest were left out. The log in
         * memory is not changed.
         */
        fun shareText(
            lines: List<LogLine>,
            header: String?,
            maxChars: Int = SHARE_MAX_CHARS,
            zone: ZoneId = ZoneId.systemDefault(),
        ): String {
            fun head(omitted: Int) =
                listOfNotNull(header, ZERO_RUN_NOTE, if (omitted > 0) omittedNote(omitted) else null).joinToString("\n") + "\n\n"

            val formatted = lines.map { formatForShare(it, zone) }
            val all = formatted.sumOf { it.length + 1 }
            val fullHead = head(0)
            if (fullHead.length + all <= maxChars) {
                return fullHead + formatted.joinToString(separator = "") { it + "\n" }
            }
            // The announcement never gets longer than with every line left out: budget against it.
            val budget = maxChars - head(lines.size).length
            if (budget < 0) return head(lines.size).take(maxChars)
            var used = 0
            var kept = 0
            for (i in formatted.indices.reversed()) {
                val size = formatted[i].length + 1
                if (used + size > budget) break
                used += size
                kept++
            }
            val omitted = lines.size - kept
            return head(omitted) + formatted.takeLast(kept).joinToString(separator = "") { it + "\n" }
        }

        fun time(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
            TIME.format(Instant.ofEpochMilli(millis).atZone(zone))

        fun format(line: LogLine, zone: ZoneId = ZoneId.systemDefault()): String {
            val direction = when {
                line.direction == FrameDirection.SENT -> "TX"
                line.discarded -> "RX(discarded)"
                else -> "RX"
            }
            val repeat = if (line.count > 1) "  × ${line.count} (last ${time(line.lastAt, zone)})" else ""
            return "${time(line.firstAt, zone)} $direction ${line.remote}  ${line.hex}$repeat"
        }
    }
}
