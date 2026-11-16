package ls4tc421.protocol

/**
 * A controller MAC address as it travels on the wire: 12 ASCII characters, upper-case
 * hexadecimal, no separator (e.g. `AABBCCDDEEFF`).
 */
class MacAddress private constructor(val text: String) {

    /** The 12 ASCII bytes written into frames. */
    fun toAscii(): ByteArray = text.toByteArray(Charsets.US_ASCII)

    override fun equals(other: Any?): Boolean = other is MacAddress && other.text == text
    override fun hashCode(): Int = text.hashCode()
    override fun toString(): String = text

    companion object {
        const val LENGTH = 12

        /** Parses `AABBCCDDEEFF`, `aabbccddeeff`, `AA:BB:CC:DD:EE:FF` or `AA-BB-...`; `null` if invalid. */
        fun parseOrNull(raw: String): MacAddress? {
            val compact = raw.replace(":", "").replace("-", "").uppercase()
            return if (compact.length == LENGTH && compact.all { it in '0'..'9' || it in 'A'..'F' }) {
                MacAddress(compact)
            } else {
                null
            }
        }

        fun parse(raw: String): MacAddress =
            requireNotNull(parseOrNull(raw)) { "Invalid MAC address: expected 12 hexadecimal digits" }

        /** Reads 12 ASCII bytes at [offset]; `null` if they are not an upper- or lower-case hex string. */
        fun fromFrame(bytes: ByteArray, offset: Int): MacAddress? {
            if (offset < 0 || offset + LENGTH > bytes.size) return null
            val raw = String(bytes, offset, LENGTH, Charsets.US_ASCII)
            return parseOrNull(raw)
        }
    }
}
