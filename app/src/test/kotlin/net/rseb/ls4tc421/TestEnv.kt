package net.rseb.ls4tc421

import java.net.InetAddress
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout
import ls4tc421.protocol.ClientConfig
import ls4tc421.protocol.MacAddress
import ls4tc421.protocol.Tc421Client
import ls4tc421.simulator.SimQuirks
import ls4tc421.simulator.Tc421Simulator
import net.rseb.ls4tc421.data.ClientSource
import net.rseb.ls4tc421.data.DeviceDao
import net.rseb.ls4tc421.data.DeviceEntity
import net.rseb.ls4tc421.data.DeviceRepository
import net.rseb.ls4tc421.data.ProgramDao
import net.rseb.ls4tc421.data.ProgramEntity
import net.rseb.ls4tc421.data.ProgramPlacement
import net.rseb.ls4tc421.data.ProgramRepository
import net.rseb.ls4tc421.data.SlotDao
import net.rseb.ls4tc421.data.SlotEntity
import net.rseb.ls4tc421.data.SlotKind
import net.rseb.ls4tc421.link.FrameTap
import net.rseb.ls4tc421.link.LinkGuard

/**
 * In-memory stand-in for the Room DAO (JVM tests, no Android). [onDelete] plays the foreign key
 * cascade of the slots table.
 */
class FakeDeviceDao(var onDelete: (Long) -> Unit = {}) : DeviceDao {
    private val rows = MutableStateFlow<List<DeviceEntity>>(emptyList())
    private var nextId = 1L

    val all: List<DeviceEntity> get() = rows.value

    override fun observeAll(): Flow<List<DeviceEntity>> = rows.map { list -> list.sortedBy { it.name.lowercase() } }
    override fun observe(id: Long): Flow<DeviceEntity?> = rows.map { list -> list.firstOrNull { it.id == id } }
    override suspend fun get(id: Long) = rows.value.firstOrNull { it.id == id }
    override suspend fun getByMac(mac: String) = rows.value.firstOrNull { it.mac == mac }
    override suspend fun insert(device: DeviceEntity): Long {
        val id = nextId++
        rows.value = rows.value + device.copy(id = id)
        return id
    }
    override suspend fun markSeen(id: Long, address: String, seenAt: Long) = change(id) { it.copy(address = address, lastSeenAt = seenAt) }
    override suspend fun markTimeSet(id: Long, at: Long) = change(id) { it.copy(timeSetAt = at) }
    override suspend fun rename(id: Long, name: String) = change(id) { it.copy(name = name) }
    override suspend fun setActiveSlot(id: Long, slot: Int?) = change(id) { it.copy(activeSlot = slot) }
    override suspend fun delete(id: Long) {
        rows.value = rows.value.filterNot { it.id == id }
        onDelete(id)
    }

    private fun change(id: Long, f: (DeviceEntity) -> DeviceEntity) {
        rows.value = rows.value.map { if (it.id == id) f(it) else it }
    }
}

/**
 * In-memory stand-in for the slots DAO: the primitives only, the meaning (written, emptied, declared
 * empty) comes from [SlotDao]. Like the real table, a row is written only for a device that exists.
 */
class FakeSlotDao(private val devices: FakeDeviceDao) : SlotDao() {
    private val rows = MutableStateFlow<List<SlotEntity>>(emptyList())

    val all: List<SlotEntity> get() = rows.value

    init {
        devices.onDelete = { id -> rows.value = rows.value.filterNot { it.deviceId == id } }
    }

    override fun observe(deviceId: Long): Flow<List<SlotEntity>> =
        rows.map { list -> list.filter { it.deviceId == deviceId }.sortedBy { it.slot } }

    override suspend fun get(deviceId: Long): List<SlotEntity> = rows.value.filter { it.deviceId == deviceId }.sortedBy { it.slot }

    override suspend fun put(deviceId: Long, slot: Int, state: SlotKind, programId: Long?, name: String?, at: Long?, levels: String?) {
        if (devices.all.none { it.id == deviceId }) return
        val row = SlotEntity(deviceId, slot, state, programId, name, at, levels)
        rows.value = rows.value.filterNot { it.deviceId == deviceId && it.slot == slot } + row
    }

    override suspend fun forget(deviceId: Long, slot: Int): Int {
        val before = rows.value.size
        rows.value = rows.value.filterNot { it.deviceId == deviceId && it.slot == slot }
        return before - rows.value.size
    }

    override fun observePlacements(): Flow<List<ProgramPlacement>> =
        combine(rows, devices.observeAll()) { slots, list ->
            list.flatMap { device ->
                slots.filter { it.deviceId == device.id && it.state == SlotKind.WRITTEN && it.programId != null }
                    .sortedBy { it.slot }
                    .map { ProgramPlacement(it.programId!!, device.id, device.name, it.slot, it.at) }
            }
        }
}

/** In-memory stand-in for the programs DAO. */
class FakeProgramDao : ProgramDao {
    private val rows = MutableStateFlow<List<ProgramEntity>>(emptyList())
    private var nextId = 1L

    val all: List<ProgramEntity> get() = rows.value

    override fun observeAll(): Flow<List<ProgramEntity>> = rows.map { list -> list.sortedBy { it.name.lowercase() } }
    override suspend fun get(id: Long) = rows.value.firstOrNull { it.id == id }
    override suspend fun insert(program: ProgramEntity): Long {
        val id = nextId++
        rows.value = rows.value + program.copy(id = id)
        return id
    }
    override suspend fun update(program: ProgramEntity) {
        rows.value = rows.value.map { if (it.id == program.id) program else it }
    }
    override suspend fun delete(id: Long) {
        rows.value = rows.value.filterNot { it.id == id }
    }
}

/**
 * The real protocol client against the simulator, everything on 127.0.0.1 with a free port.
 * The "broadcast" address of the client is the loopback too: no packet ever leaves the machine.
 *
 * Timing (D32), short for fast tests: the simulator drops a first step sent less than 40 ms after
 * `01 01`, and the client pauses [firstStepDelay] = 80 ms. By default the simulator does not ignore a
 * `01 01` that follows a `04 0A` ([QUICK]): tests that send programs one after the other stand for a
 * user who opens the send sheet again, seconds later. Tests of a write session pass [SESSION] instead.
 */
class TestEnv(
    ackTimeout: Duration = 200.milliseconds,
    attempts: Int = 2,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    quirks: SimQuirks = QUICK,
    firstStepDelay: Duration = 80.milliseconds,
) : AutoCloseable {
    companion object {
        /** No window after `04 0A`. */
        val QUICK = SimQuirks(firstStepMinGap = 40.milliseconds, startIgnoredAfterClose = Duration.ZERO)

        /** A `01 01` within a second of a `04 0A` is ignored; at the third, the simulator goes silent. */
        val SESSION = SimQuirks(firstStepMinGap = 40.milliseconds, startIgnoredAfterClose = 1_000.milliseconds)
    }

    val loopback: InetAddress = InetAddress.getLoopbackAddress()
    val mac: MacAddress = MacAddress.parse("AABBCCDDEEFF")
    /** Every frame of the client, like the app's tap. */
    val frames = FrameTap()
    val sim: Tc421Simulator = Tc421Simulator(mac, quirks = quirks).start()
    val client = Tc421Client(
        ClientConfig(
            remotePort = sim.port,
            localPort = 0,
            localAddress = loopback,
            ackTimeout = ackTimeout,
            attempts = attempts,
            stepInterval = Duration.ZERO,
            firstStepDelay = firstStepDelay,
            discoveryInterval = 50.milliseconds,
            broadcastAddress = loopback,
            ioDispatcher = ioDispatcher,
            frameListener = frames,
        ),
    )
    val dao = FakeDeviceDao()
    val slots = FakeSlotDao(dao)
    val programDao = FakeProgramDao()
    val programs = ProgramRepository(programDao, slots, now = { clock })
    val guard = LinkGuard()
    var clock = 1_000L
    val repository = DeviceRepository(dao, slots, ClientSource { client }, now = { clock })

    /** A stored controller pointing at the simulator (same MAC unless [mac] is given). */
    suspend fun store(name: String = "Test", mac: String = this.mac.text): DeviceEntity {
        val id = dao.insert(DeviceEntity(name = name, address = "127.0.0.1", mac = mac))
        return dao.get(id)!!
    }

    override fun close() {
        client.close()
        sim.close()
    }
}

suspend fun <T> StateFlow<T>.await(timeoutMs: Long = 5_000, predicate: (T) -> Boolean): T =
    withTimeout(timeoutMs) { first(predicate) }
