package net.rseb.ls4tc421.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import ls4tc421.protocol.Device
import ls4tc421.protocol.MacAddress

/**
 * A controller known to the app. The MAC (12 upper-case hex characters, as in the protocol)
 * identifies it; the address may change with DHCP and is refreshed whenever the controller answers.
 * The Wi-Fi key is deliberately not a column: it is never stored.
 * Version 3 (D31): the last known upload left this table for one row per slot ([SlotEntity]).
 */
@Entity(tableName = "devices", indices = [Index(value = ["mac"], unique = true)])
data class DeviceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val address: String,
    val mac: String,
    /** Epoch millis of the last discovery reply; null if it never answered. */
    val lastSeenAt: Long? = null,
    /** Epoch millis of the last acknowledged clock command sent from this app; null if none. */
    val timeSetAt: Long? = null,
    /**
     * Slot 0..19 that the user declared in service (D16); `null` if not declared. Never deduced: the
     * controller cannot say which program runs. What each slot holds is in [SlotEntity].
     */
    val activeSlot: Int? = null,
)

/** The protocol address of a stored device; `null` if its address or MAC cannot be read. */
fun DeviceEntity.toDevice(): Device? {
    val inet = Ipv4.parse(address) ?: return null
    val parsed = MacAddress.parseOrNull(mac) ?: return null
    return Device(inet, parsed)
}
