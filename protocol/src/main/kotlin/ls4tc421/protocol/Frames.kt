package ls4tc421.protocol

import java.time.LocalDateTime

/** Byte offsets of the 64-byte frame, see docs/PROTOCOL.md. */
object FrameLayout {
    const val SIZE = 64
    const val COMMAND = 2
    const val SUBCOMMAND = 3
    const val ACK_CODE = 4
    const val LENGTH = 5
    const val DATA = 6
    const val CHECKSUM_END = 49 // checksum covers bytes 0 until 49
    const val MAC = 49
    const val CHECKSUM = 61
    const val DISCOVERY_MAC = 4 // in a discovery reply only
    const val WIFI_SECURITY = 39
    const val WIFI_CIPHER = 40
    const val WIFI_TEXT = 7 // byte 6 holds the Wi-Fi sub-command
    const val MAX_NAME_FIELD = 42 // bytes 7..48, after the slot byte
}

/** Command and sub-command codes of requests (app to controller). */
object Cmd {
    const val SEARCH = 0x17
    const val TIME_SYNC = 0x02
    const val DOWNLOAD = 0x01 // sub-commands below
    const val DOWNLOAD_START = 0x01
    const val DOWNLOAD_STEP = 0x02
    const val STOP_ALL = 0x04 // sub-command 0x0A
    const val STOP_ALL_SUB = 0x0A
    const val STOP = 0x03 // sub-command 0x08
    const val STOP_SUB = 0x08
    const val PREVIEW = 0x06 // sub-commands below
    const val PREVIEW_START = 0x01
    const val PREVIEW_VALUES = 0x02
    const val WIFI = 0x18
}

/** Acknowledgement codes found at byte 4 of a reply. */
object AckCode {
    const val TIME_SYNC = 0x85
    const val DOWNLOAD_START = 0x80
    const val DOWNLOAD_STEP = 0x81
    const val STOP_ALL = 0x91
    const val STOP = 0x83
    const val WIFI_SSID = 0x70
    const val WIFI_PASSWORD = 0x72
    const val WIFI_OPEN_SSID = 0x74

    /** Reply to a `01 01` whose name is 10 bytes or more: the write is refused. */
    const val REFUSED = 0x87
}

enum class WifiSecurity(val code: Int) { WPA(1), WPA2(2) }

enum class WifiCipher(val code: Int) { AES(1), TKIP(2) }

/**
 * Builders for request frames (app to controller). Pure functions: each returns a fresh
 * 64-byte array. `05 FE` (erase all programs) is deliberately not implemented.
 */
object Frames {
    private const val WIFI_SSID_PROTECTED = 1
    private const val WIFI_PASSWORD = 2
    private const val WIFI_SSID_OPEN = 3

    /** Discovery request (`17`). No MAC: the frame is sent before the MAC is known. */
    fun search(): ByteArray =
        build(Cmd.SEARCH, 0, byteArrayOf(0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte()), mac = null)

    /** Clock setting (`02`). */
    fun timeSync(mac: MacAddress, time: LocalDateTime): ByteArray {
        require(time.year in 0..0xFFFF) { "Year out of range: ${time.year}" }
        val data = byteArrayOf(
            (time.year shr 8).toByte(), time.year.toByte(),
            time.monthValue.toByte(), time.dayOfMonth.toByte(),
            time.hour.toByte(), time.minute.toByte(), time.second.toByte(),
        )
        return build(Cmd.TIME_SYNC, 0, data, mac)
    }

    /** Opens the write of a program (`01 01`): slot, then name. */
    fun startDownload(mac: MacAddress, slot: Int, name: String): ByteArray {
        Validation.slot(slot)
        Validation.name(name)
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        // ASSUMPTION: the length byte (offset 5) is the name length, as the specification says;
        // the slot byte is not counted.
        return build(Cmd.DOWNLOAD, Cmd.DOWNLOAD_START, byteArrayOf(slot.toByte()) + nameBytes, mac, length = nameBytes.size)
    }

    /** Writes one 30-minute step (`01 02`): step number, hour, minute, 5 levels. */
    fun downloadStep(mac: MacAddress, step: Int, channels: List<Int>): ByteArray {
        Validation.step(step)
        Validation.channels(channels, "step $step")
        val data = byteArrayOf(
            step.toByte(),
            (step / 2).toByte(),
            ((step % 2) * Limits.STEP_MINUTES).toByte(),
        ) + ByteArray(Limits.CHANNELS) { channels[it].toByte() }
        return build(Cmd.DOWNLOAD, Cmd.DOWNLOAD_STEP, data, mac)
    }

    /** End of a write session (`04 0A`), once after its last program (see [ProgramWriteSequence]). */
    fun stopAllProcess(mac: MacAddress): ByteArray = build(Cmd.STOP_ALL, Cmd.STOP_ALL_SUB, ByteArray(0), mac)

    /** End of a preview, back to the stored program; also the end of each program in a write session (`03 08`). */
    fun stopProcess(mac: MacAddress): ByteArray = build(Cmd.STOP, Cmd.STOP_SUB, ByteArray(0), mac)

    /**
     * Starts a preview (`06 01`). No reply. Same name rule as `01 01` (ASSUMPTION: the controller's
     * own limit for `06 01` has not been observed).
     */
    fun previewStart(mac: MacAddress, name: String): ByteArray {
        Validation.name(name)
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        return build(Cmd.PREVIEW, Cmd.PREVIEW_START, nameBytes, mac)
    }

    /** Live preview levels (`06 02`), five channels. No reply. */
    fun previewValues(mac: MacAddress, channels: List<Int>): ByteArray {
        Validation.channels(channels, "preview")
        return build(Cmd.PREVIEW, Cmd.PREVIEW_VALUES, ByteArray(Limits.CHANNELS) { channels[it].toByte() }, mac)
    }

    /** `18`, sub-command 1: SSID of a protected network, with security type and cipher. */
    fun wifiSsid(mac: MacAddress, ssid: String, security: WifiSecurity, cipher: WifiCipher): ByteArray {
        Validation.wifiText(ssid, "SSID")
        val frame = wifi(mac, WIFI_SSID_PROTECTED, ssid)
        frame[FrameLayout.WIFI_SECURITY] = security.code.toByte()
        frame[FrameLayout.WIFI_CIPHER] = cipher.code.toByte()
        return sealChecksum(frame)
    }

    /** `18`, sub-command 2: passphrase of a protected network. */
    fun wifiPassword(mac: MacAddress, password: String): ByteArray {
        Validation.wifiText(password, "Wi-Fi key")
        return wifi(mac, WIFI_PASSWORD, password)
    }

    /** `18`, sub-command 3: SSID of an open network. */
    fun wifiOpenSsid(mac: MacAddress, ssid: String): ByteArray {
        Validation.wifiText(ssid, "SSID")
        return wifi(mac, WIFI_SSID_OPEN, ssid)
    }

    /** Sum of bytes 0..48 modulo 256: the checksum of request frames (MAC excluded). */
    fun checksum(frame: ByteArray): Int {
        var sum = 0
        for (i in 0 until FrameLayout.CHECKSUM_END) sum += frame[i].toInt() and 0xFF
        return sum and 0xFF
    }

    // ASSUMPTION: the length byte of `18` is the length of the text (not text + sub-command).
    private fun wifi(mac: MacAddress, subCommand: Int, text: String): ByteArray {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        return build(Cmd.WIFI, 0, byteArrayOf(subCommand.toByte()) + textBytes, mac, length = textBytes.size)
    }

    /**
     * Builds a frame: header, [command], [subCommand], length byte, [data] from offset 6,
     * MAC in 49..60 (when [mac] is not null), checksum in 61, zero padding.
     */
    private fun build(
        command: Int,
        subCommand: Int,
        data: ByteArray,
        mac: MacAddress?,
        length: Int = data.size,
    ): ByteArray {
        require(FrameLayout.DATA + data.size <= FrameLayout.CHECKSUM_END) { "Data does not fit in the frame" }
        val frame = ByteArray(FrameLayout.SIZE)
        frame[0] = 0x55
        frame[1] = 0xAA.toByte()
        frame[FrameLayout.COMMAND] = command.toByte()
        frame[FrameLayout.SUBCOMMAND] = subCommand.toByte()
        frame[FrameLayout.LENGTH] = length.toByte()
        data.copyInto(frame, FrameLayout.DATA)
        mac?.toAscii()?.copyInto(frame, FrameLayout.MAC)
        return sealChecksum(frame)
    }

    private fun sealChecksum(frame: ByteArray): ByteArray {
        frame[FrameLayout.CHECKSUM] = checksum(frame).toByte()
        return frame
    }
}
