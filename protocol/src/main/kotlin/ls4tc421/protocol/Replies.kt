package ls4tc421.protocol

/** A discovery reply: the controller announces its MAC (bytes 4..15). */
data class DiscoveryReply(val mac: MacAddress)

/** An acknowledgement: [code] at byte 4, [mac] at bytes 49..60. */
data class AckReply(val code: Int, val mac: MacAddress)

/**
 * Readers for reply frames (controller to app).
 *
 * Two rules come from observation of a real controller:
 *  - the checksum byte of a reply is NEVER verified: its formula is unknown;
 *  - byte 2 of an acknowledgement does not echo the command, so it is never used to pair a
 *    reply with a request.
 */
object FrameReader {
    private const val DISCOVERY_COMMAND = Cmd.SEARCH

    /** `true` if [bytes] is 64 bytes long and starts with `88 00`. */
    fun isReply(bytes: ByteArray): Boolean =
        bytes.size == FrameLayout.SIZE && bytes[0] == 0x88.toByte() && bytes[1] == 0.toByte()

    /** Reads a discovery reply, or `null` if [bytes] is not one. */
    fun discovery(bytes: ByteArray): DiscoveryReply? {
        if (!isReply(bytes) || (bytes[FrameLayout.COMMAND].toInt() and 0xFF) != DISCOVERY_COMMAND) return null
        val mac = MacAddress.fromFrame(bytes, FrameLayout.DISCOVERY_MAC) ?: return null
        return DiscoveryReply(mac)
    }

    /** Reads an acknowledgement, or `null` if [bytes] is not one (bad header, size, or MAC). */
    fun ack(bytes: ByteArray): AckReply? {
        if (!isReply(bytes)) return null
        val mac = MacAddress.fromFrame(bytes, FrameLayout.MAC) ?: return null
        return AckReply(bytes[FrameLayout.ACK_CODE].toInt() and 0xFF, mac)
    }
}
