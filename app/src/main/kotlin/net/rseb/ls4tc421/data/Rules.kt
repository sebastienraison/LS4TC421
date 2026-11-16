package net.rseb.ls4tc421.data

import java.net.InetAddress

/** IPv4 literal parsing without any DNS lookup. */
object Ipv4 {
    private val PATTERN = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    /** The address for a dotted-quad literal such as `192.0.2.10`; `null` for anything else. */
    fun parse(text: String): InetAddress? {
        val match = PATTERN.matchEntire(text.trim()) ?: return null
        val octets = match.groupValues.drop(1).map { it.toInt() }
        if (octets.any { it > 255 }) return null
        return InetAddress.getByAddress(ByteArray(4) { octets[it].toByte() })
    }
}

/** Which phone Wi-Fi names are worth remembering as the default network for the controller. */
object SsidRules {
    /** Android reports this placeholder when it may not reveal the name. */
    private const val UNKNOWN = "<unknown ssid>"

    /** Prefix given by the manual of one controller (« Smarttime… »). */
    private const val CONTROLLER_AP_PREFIX = "Smarttime"

    /** Name observed on another controller: « Smart- » and the last 6 hex digits of its MAC. */
    private val CONTROLLER_AP_MAC_NAME = Regex("^Smart-[0-9A-F]{6}$", RegexOption.IGNORE_CASE)

    /** Strips the quotes Android puts around the name; `null` when there is no usable name. */
    fun clean(raw: String?): String? {
        val name = raw?.trim()?.removeSurrounding("\"") ?: return null
        return name.takeIf { it.isNotEmpty() && it != UNKNOWN }
    }

    /** True for the controller's own access point: « Smarttime… », or « Smart- » and 6 hex digits. */
    fun isControllerAccessPoint(ssid: String): Boolean =
        ssid.startsWith(CONTROLLER_AP_PREFIX, ignoreCase = true) || CONTROLLER_AP_MAC_NAME.matches(ssid)

    /** The controller's own access point is never the network to propose. */
    fun shouldRemember(ssid: String?): Boolean = ssid != null && !isControllerAccessPoint(ssid)
}

/** Length in bytes of [text] in UTF-8: the unit of the controller's 32-byte limit. */
fun utf8Size(text: String): Int = text.toByteArray(Charsets.UTF_8).size

/** The limit of the controller for a Wi-Fi name or key. */
const val WIFI_MAX_BYTES = 32
