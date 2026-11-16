package ls4tc421.protocol

import java.io.File

/** One entry of spec/vectors.json. */
internal class Vector(private val raw: Map<String, Any?>) {
    val name: String get() = raw["name"] as String
    val direction: String get() = raw["direction"] as String
    val status: String get() = raw["status"] as String
    val call: String? get() = raw["call"] as String?
    val hex: String get() = raw["hex"] as String
    val bytes: ByteArray get() = hexToBytes(hex)

    @Suppress("UNCHECKED_CAST")
    val params: Map<String, Any?> get() = raw["params"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    val expected: Map<String, Any?> get() = raw["expected"] as Map<String, Any?>

    fun int(key: String): Int = (params[key] as Long).toInt()
    fun string(key: String): String = params[key] as String

    @Suppress("UNCHECKED_CAST")
    fun ints(key: String): List<Int> = (params[key] as List<Long>).map { it.toInt() }
}

internal object Vectors {
    val mac: MacAddress
    val all: List<Vector>

    init {
        val path = requireNotNull(System.getProperty("ls4tc421.vectors")) { "ls4tc421.vectors not set" }
        @Suppress("UNCHECKED_CAST")
        val doc = MiniJson.parse(File(path).readText()) as Map<String, Any?>
        mac = MacAddress.parse(doc["mac"] as String)
        @Suppress("UNCHECKED_CAST")
        all = (doc["vectors"] as List<Map<String, Any?>>).map { Vector(it) }
    }
}

internal fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "Odd hex length" }
    return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
