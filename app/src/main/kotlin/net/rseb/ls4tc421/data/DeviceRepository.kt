package net.rseb.ls4tc421.data

import java.io.IOException
import java.time.LocalDateTime
import kotlinx.coroutines.flow.Flow
import ls4tc421.protocol.CommandResult
import ls4tc421.protocol.Device
import ls4tc421.protocol.DiscoveryResult
import ls4tc421.protocol.Tc421Client
import ls4tc421.protocol.WifiCipher
import ls4tc421.protocol.WifiSecurity

/** Supplies the (lazily created) protocol client. Creating it can fail, e.g. if no UDP socket can be opened. */
fun interface ClientSource {
    @Throws(IOException::class)
    suspend fun get(): Tc421Client
}

sealed interface AddResult {
    data class Added(val device: DeviceEntity) : AddResult
    data class AlreadyKnown(val device: DeviceEntity) : AddResult
    data object InvalidAddress : AddResult
    data object NoReply : AddResult
    data object NetworkError : AddResult
}

sealed interface ScanResult {
    /**
     * Every controller that answered the broadcast (known or not), and the known ones whose stored
     * address the search has just replaced ([moved]).
     */
    data class Found(val devices: List<Device>, val moved: List<AddressChange> = emptyList()) : ScanResult
    data object Failed : ScanResult
}

/** A known controller [id] ([name]) answered from [to] while its stored address was [from]. */
data class AddressChange(val id: Long, val name: String, val from: String, val to: String)

/**
 * The known controllers whose stored address differs from the address they answered from (D27:
 * the search updates it, and the screen says so). Pure: nothing is written.
 */
fun addressChanges(known: List<DeviceEntity>, found: List<Device>): List<AddressChange> =
    found.mapNotNull { device ->
        val entity = known.firstOrNull { it.mac == device.mac.text } ?: return@mapNotNull null
        val answered = device.address.hostAddress ?: return@mapNotNull null
        if (answered == entity.address) null else AddressChange(entity.id, entity.name, entity.address, answered)
    }

/** Result of « Change address » on the device page (D27). */
enum class AddressResult { Saved, InvalidAddress, NoReply, OtherController, NetworkError, UnknownDevice }

/** What came back from a command that expects an acknowledgement. */
enum class SendOutcome { Acknowledged, NoReply, UnexpectedReply, NetworkError, Invalid }

/**
 * The single place that talks to the controller and to the database.
 * Network problems are returned, never thrown; cancellation is left alone.
 */
class DeviceRepository(
    private val dao: DeviceDao,
    private val slotDao: SlotDao,
    private val clients: ClientSource,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val devices: Flow<List<DeviceEntity>> = dao.observeAll()

    fun device(id: Long): Flow<DeviceEntity?> = dao.observe(id)

    /** The 20 slots of device [id] (D31), in order, [SlotState.Unknown] where nothing is known. */
    fun slots(id: Long): Flow<List<SlotState>> = slotDao.observeStates(id)

    /** « New or already emptied » (D31): the 20 slots are declared empty; nothing is sent. */
    suspend fun declareAllEmpty(id: Long) = slotDao.declareAllEmpty(id, now())

    /** « I no longer know what it holds » (D31, Q8): [slot] goes back to unknown. */
    suspend fun forgetSlot(id: Long, slot: Int) {
        slotDao.forget(id, slot)
    }

    /** Declares [slot] in service (D16), or withdraws the declaration with `null`. A slot outside 0..19 is ignored. */
    suspend fun setActiveSlot(id: Long, slot: Int?) {
        if (slot != null && slot !in 0 until SLOT_COUNT) return
        dao.setActiveSlot(id, slot)
    }

    /**
     * Discovery sent to the stored address of [device]. True if a controller with the same MAC
     * answered (the address and last-seen time are then refreshed). A different MAC at that address
     * is another device: not reachable.
     */
    suspend fun probe(device: DeviceEntity): Boolean {
        val address = Ipv4.parse(device.address) ?: return false
        val result = try {
            clients.get().discover(address)
        } catch (_: IOException) {
            return false
        }
        val found = (result as? DiscoveryResult.Found)?.devices?.firstOrNull { it.mac.text == device.mac }
            ?: return false
        dao.markSeen(device.id, found.address.hostAddress ?: device.address, now())
        return true
    }

    /**
     * Broadcast search. Known controllers that answer get their address and last-seen refreshed; the
     * result says which of them changed address.
     */
    suspend fun scan(): ScanResult {
        val result = try {
            clients.get().discover(null)
        } catch (_: IOException) {
            return ScanResult.Failed
        }
        val devices = (result as? DiscoveryResult.Found)?.devices.orEmpty()
        val known = devices.mapNotNull { dao.getByMac(it.mac.text) }
        val moved = addressChanges(known, devices)
        for (found in devices) {
            val entity = known.firstOrNull { it.mac == found.mac.text } ?: continue
            dao.markSeen(entity.id, found.address.hostAddress ?: entity.address, now())
        }
        return ScanResult.Found(devices, moved)
    }

    /**
     * « Change address » (D27): a discovery sent to [address]; the address is saved only if the
     * controller that answers has the MAC of device [id]. Name, slots and clock time are kept.
     */
    suspend fun changeAddress(id: Long, address: String): AddressResult {
        val inet = Ipv4.parse(address) ?: return AddressResult.InvalidAddress
        val stored = dao.get(id) ?: return AddressResult.UnknownDevice
        val result = try {
            clients.get().discover(inet)
        } catch (_: IOException) {
            return AddressResult.NetworkError
        }
        val replies = (result as? DiscoveryResult.Found)?.devices.orEmpty()
        if (replies.isEmpty()) return AddressResult.NoReply
        val same = replies.firstOrNull { it.mac.text == stored.mac } ?: return AddressResult.OtherController
        dao.markSeen(id, same.address.hostAddress ?: return AddressResult.InvalidAddress, now())
        return AddressResult.Saved
    }

    /** « Test and add »: discovery by address; the MAC in the reply becomes the identifier. */
    suspend fun addByAddress(address: String, name: String): AddResult {
        val inet = Ipv4.parse(address) ?: return AddResult.InvalidAddress
        val result = try {
            clients.get().discover(inet)
        } catch (_: IOException) {
            return AddResult.NetworkError
        }
        val found = (result as? DiscoveryResult.Found)?.devices?.firstOrNull() ?: return AddResult.NoReply
        return register(found, name)
    }

    /** Adds a controller found by the broadcast search. */
    suspend fun addFound(found: Device, name: String): AddResult = register(found, name)

    private suspend fun register(found: Device, name: String): AddResult {
        val hostAddress = found.address.hostAddress ?: return AddResult.InvalidAddress
        val existing = dao.getByMac(found.mac.text)
        if (existing != null) {
            dao.markSeen(existing.id, hostAddress, now())
            return AddResult.AlreadyKnown(dao.get(existing.id) ?: existing)
        }
        val entity = DeviceEntity(name = name, address = hostAddress, mac = found.mac.text, lastSeenAt = now())
        return AddResult.Added(entity.copy(id = dao.insert(entity)))
    }

    /** Sets the controller clock; on an acknowledgement, records when it was done. */
    suspend fun setTime(id: Long, time: LocalDateTime): SendOutcome {
        val stored = dao.get(id) ?: return SendOutcome.Invalid
        val device = toDevice(stored) ?: return SendOutcome.Invalid
        val outcome = send { clients.get().setTime(device, time) }
        if (outcome == SendOutcome.Acknowledged) dao.markTimeSet(id, now())
        return outcome
    }

    /**
     * Sends the Wi-Fi configuration. [password] `null` = open network. The key lives only in the
     * arguments of this call: it is not stored and not logged anywhere.
     */
    suspend fun joinWifi(
        id: Long,
        ssid: String,
        password: String?,
        security: WifiSecurity,
        cipher: WifiCipher,
    ): SendOutcome {
        val stored = dao.get(id) ?: return SendOutcome.Invalid
        val device = toDevice(stored) ?: return SendOutcome.Invalid
        return try {
            send { clients.get().joinWifi(device, ssid, password, security, cipher) }
        } catch (_: IllegalArgumentException) {
            SendOutcome.Invalid // refused by the protocol layer before anything was sent
        }
    }

    suspend fun rename(id: Long, name: String) = dao.rename(id, name)

    /** The device and what the app knew of its slots (cascade). */
    suspend fun delete(id: Long) = dao.delete(id)

    private fun toDevice(stored: DeviceEntity): Device? = stored.toDevice()

    private suspend fun send(command: suspend () -> CommandResult): SendOutcome =
        try {
            when (command()) {
                CommandResult.Success -> SendOutcome.Acknowledged
                is CommandResult.TimedOut -> SendOutcome.NoReply
                is CommandResult.UnexpectedAck -> SendOutcome.UnexpectedReply
            }
        } catch (_: IOException) {
            SendOutcome.NetworkError
        }
}
