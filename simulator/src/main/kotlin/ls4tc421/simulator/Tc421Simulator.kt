package ls4tc421.simulator

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.time.DateTimeException
import java.time.LocalDateTime
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import ls4tc421.protocol.AckCode
import ls4tc421.protocol.Cmd
import ls4tc421.protocol.FrameLayout
import ls4tc421.protocol.Frames
import ls4tc421.protocol.Limits
import ls4tc421.protocol.MacAddress
import ls4tc421.protocol.WifiCipher
import ls4tc421.protocol.WifiSecurity

/** A program as stored by the simulator (slot -> program). */
data class StoredProgram(val name: String, val steps: List<List<Int>>)

/** Wi-Fi settings received through `18`. [password] stays `null` until the key command arrives. */
data class SimWifi(
    val ssid: String,
    val open: Boolean,
    val security: WifiSecurity?,
    val cipher: WifiCipher?,
    val password: String?,
)

/**
 * Timing quirks of a real controller, reproduced by [Tc421Simulator] (observed 2026-11-16, see
 * docs/PROTOCOL.md). These thresholds are SETTINGS OF THE SIMULATOR, chosen so that tests catch a
 * return to the wrong write sequence and can run fast with shorter values. They are not measured
 * limits of the controller: the real ones are not known precisely.
 *
 * @property firstStepMinGap a `01 02` received less than this after the acknowledgement of a `01 01`
 *   is ignored (no acknowledgement). Real controller: dropped at 0 and 0.25 s, acknowledged from 0.5 s.
 * @property startIgnoredAfterClose a `01 01` received less than this after a `04 0A` is ignored.
 *   Real controller: acknowledged only at the third sending, about 4 s after the `04 0A`.
 * @property ignoredStartsBeforeSilence when this many `01 01` have been ignored that way (counted since
 *   the last [Tc421Simulator.reset]), the simulator stops answering anything, discovery included, until
 *   [Tc421Simulator.reset]. Real controller: silent from the fourth program written with a `04 0A`
 *   after each, until a power cycle; neither `04 0A` nor `03 08` brought it back.
 */
data class SimQuirks(
    val firstStepMinGap: Duration = 400.milliseconds,
    val startIgnoredAfterClose: Duration = 5.seconds,
    val ignoredStartsBeforeSilence: Int = 3,
) {
    init {
        require(ignoredStartsBeforeSilence >= 1) { "ignoredStartsBeforeSilence must be >= 1" }
    }
}

/**
 * A fake TC421 for tests: a UDP socket that answers like docs/PROTOCOL.md describes, quirks
 * included, and remembers what it received.
 *
 * Quirks reproduced from observation of a real controller:
 *  - byte 2 of an acknowledgement does not echo the command (observed: `03` for `02`,
 *    `11` for `03 08`; other commands: ASSUMPTION `03`);
 *  - the ack code overwrites the first MAC character at byte 4, the rest of the MAC stays at
 *    bytes 5..15, and the MAC is also at 49..60 (ASSUMPTION: same buffer for every ack);
 *  - the discovery reply carries the MAC at bytes 4..15 only;
 *  - `06 01` and `06 02` get no reply;
 *  - a `01 01` whose name is 10 bytes or more gets the ack `87` and opens no write;
 *  - the checksum byte of every reply is the constant [RESPONSE_CHECKSUM].
 *
 * Behaviour the specification does not state, decided here (ASSUMPTIONS):
 *  - requests with a bad header or checksum, or addressed to another MAC (except discovery),
 *    are ignored and counted in [rejectedFrames];
 *  - a `01 02` without an open `01 01` is ignored (no state change, no ack);
 *  - `03 08` is acknowledged; it ends the preview and commits the open write, if any: if fewer than
 *    48 steps were received the program is not stored and [incompleteCommits] is incremented;
 *  - `04 0A` is acknowledged and closes the session; a write still open (no `03 08`) is dropped,
 *    not stored, and counted in [droppedWrites]. The real controller did keep programs written with
 *    `01 01`, 48 steps, `04 0A` (2026-11-14 to 16): the simulator is stricter on purpose, so that a
 *    write without `03 08` fails the tests;
 *  - replies go to the source address and port of the request.
 *
 * Timing quirks ([SimQuirks], settings of the simulator): a first step too soon after `01 01` is
 * ignored; a `01 01` too soon after `04 0A` is ignored; after a few of those, the simulator goes
 * [silent] until [reset], the equivalent of a power cycle.
 */
class Tc421Simulator(
    val mac: MacAddress,
    bindAddress: InetAddress = InetAddress.getLoopbackAddress(),
    port: Int = 0,
    val quirks: SimQuirks = SimQuirks(),
) : AutoCloseable {

    companion object {
        /** Checksum byte of every reply. Arbitrary: the real formula is unknown. (0x9F was seen on one real discovery reply.) */
        const val RESPONSE_CHECKSUM = 0x9F
        private const val ACK_BYTE2_TIME_SYNC = 0x03
        private const val ACK_BYTE2_STOP = 0x11
        private const val ACK_BYTE2_DEFAULT = 0x03
    }

    /** Fault injection; see [Faults]. */
    val faults = Faults()

    private val socket = DatagramSocket(null as java.net.SocketAddress?).apply {
        reuseAddress = true
        bind(InetSocketAddress(bindAddress, port))
    }
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "tc421-sim-delay").apply { isDaemon = true }
    }
    private val worker = Thread(::serve, "tc421-sim").apply { isDaemon = true }

    /** The port the simulator listens on (useful when started with port 0). */
    val port: Int get() = socket.localPort

    // ---- state (guarded by `this`) -----------------------------------------------------
    private val storedPrograms = sortedMapOf<Int, StoredProgram>()
    private var clock: LocalDateTime? = null
    private var wifiState: SimWifi? = null
    private var previewActiveFlag = false
    private var previewNameValue: String? = null
    private val previewHistory = ArrayList<List<Int>>()
    private val receivedFrames = ArrayList<ByteArray>()
    private var acksGenerated = 0
    private var acksDropped = 0
    private var incompleteCommitCount = 0
    private var rejectedCount = 0
    private var openSlot: Int? = null
    private var openName: String? = null
    private var openSteps: Array<List<Int>?> = arrayOfNulls(Limits.STEPS_PER_PROGRAM)
    private var droppedWriteCount = 0
    /** [System.nanoTime] of the last acknowledged `01 01`, while its first step is awaited. */
    private var startAckedAt: Long? = null
    /** [System.nanoTime] of the last `04 0A`. */
    private var sessionClosedAt: Long? = null
    private var ignoredStartCount = 0
    private var ignoredStepCount = 0
    private var silentFlag = false

    /** Programs received and committed, by slot. */
    val programs: Map<Int, StoredProgram> get() = synchronized(this) { storedPrograms.toMap() }

    /** Last time received through `02`, if any. */
    val time: LocalDateTime? get() = synchronized(this) { clock }

    val wifi: SimWifi? get() = synchronized(this) { wifiState }

    /** `true` between `06 01` and `03 08`. */
    val previewActive: Boolean get() = synchronized(this) { previewActiveFlag }
    val previewName: String? get() = synchronized(this) { previewNameValue }

    /** Every `06 02` level set received, in order. */
    val previewValues: List<List<Int>> get() = synchronized(this) { previewHistory.toList() }

    /** Every valid request received (copies of the 64 bytes), in order, retries included. */
    val received: List<ByteArray> get() = synchronized(this) { receivedFrames.map { it.copyOf() } }

    /** Number of acknowledgements the simulator decided to send (dropped ones included). */
    val ackCount: Int get() = synchronized(this) { acksGenerated }
    val droppedAckCount: Int get() = synchronized(this) { acksDropped }
    val incompleteCommits: Int get() = synchronized(this) { incompleteCommitCount }
    val rejectedFrames: Int get() = synchronized(this) { rejectedCount }

    /** Writes still open (no `03 08`) when a `04 0A` arrived: dropped, not stored. */
    val droppedWrites: Int get() = synchronized(this) { droppedWriteCount }

    /** `01 01` ignored because they came too soon after a `04 0A` (see [SimQuirks]). */
    val ignoredStarts: Int get() = synchronized(this) { ignoredStartCount }

    /** First steps ignored because they came too soon after the `01 01` acknowledgement (see [SimQuirks]). */
    val ignoredFirstSteps: Int get() = synchronized(this) { ignoredStepCount }

    /** `true` once the simulator stopped answering anything, until [reset]. */
    val silent: Boolean get() = synchronized(this) { silentFlag }

    /**
     * The equivalent of a power cycle: answers again, forgets the open write, the session and the
     * preview. Stored programs, the clock and the Wi-Fi settings are kept.
     */
    fun reset() = synchronized(this) {
        silentFlag = false
        ignoredStartCount = 0
        startAckedAt = null
        sessionClosedAt = null
        openSlot = null
        openName = null
        previewActiveFlag = false
    }

    /** Starts answering. Call once. */
    fun start(): Tc421Simulator {
        worker.start()
        return this
    }

    override fun close() {
        socket.close()
        scheduler.shutdownNow()
    }

    // ---- serving ----------------------------------------------------------------------

    private fun serve() {
        val buffer = ByteArray(2048)
        while (!socket.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: SocketException) {
                return
            }
            handle(packet.data.copyOf(packet.length), InetSocketAddress(packet.address, packet.port))
        }
    }

    private fun handle(frame: ByteArray, from: InetSocketAddress) {
        val reply: Reply? = synchronized(this) { process(frame) }
        when (reply) {
            null -> Unit
            is Reply.Discovery -> send(reply.bytes, from, 0)
            is Reply.Ack -> {
                val action = synchronized(this) {
                    acksGenerated += 1
                    faults.decide(acksGenerated).also { if (it.drop) acksDropped += 1 }
                }
                if (!action.drop) send(reply.bytes, from, action.delayMs)
            }
        }
    }

    private fun send(bytes: ByteArray, to: InetSocketAddress, delayMs: Long) {
        val task = Runnable {
            try {
                socket.send(DatagramPacket(bytes, bytes.size, to))
            } catch (_: Exception) {
                // closed while a delayed reply was pending
            }
        }
        if (delayMs <= 0) task.run() else try {
            scheduler.schedule(task, delayMs, TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // closed
        }
    }

    private sealed interface Reply {
        class Discovery(val bytes: ByteArray) : Reply
        class Ack(val bytes: ByteArray) : Reply
    }

    private fun u8(frame: ByteArray, i: Int) = frame[i].toInt() and 0xFF

    /** Applies a request to the state and returns the reply to send. Caller holds the lock. */
    private fun process(frame: ByteArray): Reply? {
        if (silentFlag) return null // not even recorded: as if switched off
        val valid = frame.size == FrameLayout.SIZE &&
            u8(frame, 0) == 0x55 && u8(frame, 1) == 0xAA &&
            u8(frame, FrameLayout.CHECKSUM) == Frames.checksum(frame)
        if (!valid) return reject()
        val cmd = u8(frame, FrameLayout.COMMAND)
        val sub = u8(frame, FrameLayout.SUBCOMMAND)
        if (cmd == Cmd.SEARCH) {
            receivedFrames += frame
            return Reply.Discovery(discoveryReply())
        }
        if (MacAddress.fromFrame(frame, FrameLayout.MAC) != mac) return reject()
        receivedFrames += frame
        return when (cmd) {
            Cmd.TIME_SYNC -> {
                val time = readTime(frame) ?: return null
                clock = time
                ack(AckCode.TIME_SYNC, ACK_BYTE2_TIME_SYNC)
            }
            Cmd.DOWNLOAD -> when (sub) {
                Cmd.DOWNLOAD_START -> startWrite(frame)
                Cmd.DOWNLOAD_STEP -> writeStep(frame)
                else -> null
            }
            Cmd.STOP_ALL -> if (sub == Cmd.STOP_ALL_SUB) closeSession() else null
            Cmd.STOP -> if (sub == Cmd.STOP_SUB) {
                previewActiveFlag = false
                commitWrite()
                ack(AckCode.STOP, ACK_BYTE2_STOP)
            } else {
                null
            }
            Cmd.PREVIEW -> {
                when (sub) {
                    Cmd.PREVIEW_START -> {
                        previewActiveFlag = true
                        previewNameValue = readText(frame, FrameLayout.DATA, u8(frame, FrameLayout.LENGTH))
                    }
                    Cmd.PREVIEW_VALUES -> previewHistory += (0 until Limits.CHANNELS).map { u8(frame, 6 + it) }
                }
                null // no reply, as observed
            }
            Cmd.WIFI -> wifi(frame)
            else -> null // unknown commands (e.g. 05 FE) are ignored
        }
    }

    private fun reject(): Reply? {
        rejectedCount += 1
        return null
    }

    private fun startWrite(frame: ByteArray): Reply? {
        val slot = u8(frame, FrameLayout.DATA)
        if (slot !in Limits.MIN_SLOT..Limits.MAX_SLOT) return null
        // As the controller: a name of 10 bytes or more is refused with `87`.
        // ASSUMPTION: the refusal leaves the state as it was (a write already open stays open).
        if (u8(frame, FrameLayout.LENGTH) > Limits.MAX_NAME_LENGTH) return ack(AckCode.REFUSED, ACK_BYTE2_DEFAULT)
        val closedAt = sessionClosedAt
        if (closedAt != null && System.nanoTime() - closedAt < quirks.startIgnoredAfterClose.inWholeNanoseconds) {
            ignoredStartCount += 1
            if (ignoredStartCount >= quirks.ignoredStartsBeforeSilence) silentFlag = true
            return null
        }
        startAckedAt = System.nanoTime()
        openSlot = slot
        openName = readText(frame, FrameLayout.DATA + 1, u8(frame, FrameLayout.LENGTH))
        openSteps = arrayOfNulls(Limits.STEPS_PER_PROGRAM)
        return ack(AckCode.DOWNLOAD_START, ACK_BYTE2_DEFAULT)
    }

    private fun writeStep(frame: ByteArray): Reply? {
        if (openSlot == null) return null
        val step = u8(frame, FrameLayout.DATA)
        if (step !in 0 until Limits.STEPS_PER_PROGRAM) return null
        val ackedAt = startAckedAt
        if (ackedAt != null) {
            if (System.nanoTime() - ackedAt < quirks.firstStepMinGap.inWholeNanoseconds) {
                ignoredStepCount += 1
                return null
            }
            startAckedAt = null
        }
        openSteps[step] = (0 until Limits.CHANNELS).map { u8(frame, 9 + it) }
        return ack(AckCode.DOWNLOAD_STEP, ACK_BYTE2_DEFAULT)
    }

    /** `03 08`: stores the open write, if any. */
    private fun commitWrite() {
        val slot = openSlot ?: return
        val steps = openSteps.toList()
        if (steps.all { it != null }) {
            storedPrograms[slot] = StoredProgram(openName.orEmpty(), steps.map { it!! })
        } else {
            incompleteCommitCount += 1
        }
        openSlot = null
        openName = null
        startAckedAt = null
    }

    /** `04 0A`: closes the session; a write still open is dropped. */
    private fun closeSession(): Reply {
        if (openSlot != null) droppedWriteCount += 1
        openSlot = null
        openName = null
        startAckedAt = null
        sessionClosedAt = System.nanoTime()
        return ack(AckCode.STOP_ALL, ACK_BYTE2_DEFAULT)
    }

    private fun wifi(frame: ByteArray): Reply? {
        val length = u8(frame, FrameLayout.LENGTH)
        val text = readText(frame, FrameLayout.WIFI_TEXT, length) ?: return null
        return when (u8(frame, FrameLayout.DATA)) {
            1 -> {
                wifiState = SimWifi(
                    ssid = text,
                    open = false,
                    security = WifiSecurity.entries.firstOrNull { it.code == u8(frame, FrameLayout.WIFI_SECURITY) },
                    cipher = WifiCipher.entries.firstOrNull { it.code == u8(frame, FrameLayout.WIFI_CIPHER) },
                    password = null,
                )
                ack(AckCode.WIFI_SSID, ACK_BYTE2_DEFAULT)
            }
            2 -> {
                wifiState = wifiState?.copy(password = text) ?: return null
                ack(AckCode.WIFI_PASSWORD, ACK_BYTE2_DEFAULT)
            }
            3 -> {
                wifiState = SimWifi(text, open = true, security = null, cipher = null, password = null)
                ack(AckCode.WIFI_OPEN_SSID, ACK_BYTE2_DEFAULT)
            }
            else -> null
        }
    }

    private fun readText(frame: ByteArray, offset: Int, length: Int): String? =
        if (length < 0 || offset + length > FrameLayout.CHECKSUM_END) {
            null
        } else {
            String(frame, offset, length, Charsets.UTF_8)
        }

    private fun readTime(frame: ByteArray): LocalDateTime? = try {
        LocalDateTime.of(
            (u8(frame, 6) shl 8) or u8(frame, 7), u8(frame, 8), u8(frame, 9),
            u8(frame, 10), u8(frame, 11), u8(frame, 12),
        )
    } catch (_: DateTimeException) {
        null
    }

    // ---- reply construction ------------------------------------------------------------

    private fun discoveryReply(): ByteArray {
        val reply = ByteArray(FrameLayout.SIZE)
        reply[0] = 0x88.toByte()
        reply[FrameLayout.COMMAND] = Cmd.SEARCH.toByte()
        mac.toAscii().copyInto(reply, FrameLayout.DISCOVERY_MAC)
        reply[FrameLayout.CHECKSUM] = RESPONSE_CHECKSUM.toByte()
        return reply
    }

    private fun ack(code: Int, byte2: Int): Reply {
        val reply = ByteArray(FrameLayout.SIZE)
        reply[0] = 0x88.toByte()
        reply[FrameLayout.COMMAND] = byte2.toByte()
        mac.toAscii().copyInto(reply, FrameLayout.DISCOVERY_MAC) // tail stays at 5..15
        reply[FrameLayout.ACK_CODE] = code.toByte() // overwrites the first MAC character
        mac.toAscii().copyInto(reply, FrameLayout.MAC)
        reply[FrameLayout.CHECKSUM] = RESPONSE_CHECKSUM.toByte()
        return Reply.Ack(reply)
    }
}

/**
 * Fault injection on acknowledgements. Acks are numbered from 1 in the order the simulator
 * produces them (a retried command produces a new ack). A dropped ack still has its effect on
 * the simulator's state: only the reply is lost, as on a lossy network.
 * Discovery replies are not acknowledgements and are never affected.
 */
class Faults {
    internal class Action(val drop: Boolean, val delayMs: Long)

    private val dropNumbers = HashSet<Int>()
    private var dropEveryK = 0
    private val delayNumbers = HashMap<Int, Long>()
    private var delayAllMs = 0L

    /** Loses the n-th acknowledgement (1-based), for each given n. */
    @Synchronized
    fun dropNth(vararg numbers: Int) {
        dropNumbers += numbers.toList()
    }

    /** Loses one acknowledgement out of [k]: the k-th, 2k-th, 3k-th... */
    @Synchronized
    fun dropEvery(k: Int) {
        require(k >= 1) { "k must be >= 1" }
        dropEveryK = k
    }

    /** Delays the n-th acknowledgement by [millis]. */
    @Synchronized
    fun delayNth(n: Int, millis: Long) {
        delayNumbers[n] = millis
    }

    /** Delays every acknowledgement by [millis]. */
    @Synchronized
    fun delayAll(millis: Long) {
        delayAllMs = millis
    }

    @Synchronized
    fun clear() {
        dropNumbers.clear()
        dropEveryK = 0
        delayNumbers.clear()
        delayAllMs = 0
    }

    @Synchronized
    internal fun decide(ackNumber: Int): Action {
        val drop = ackNumber in dropNumbers || (dropEveryK > 0 && ackNumber % dropEveryK == 0)
        val delay = delayNumbers[ackNumber] ?: delayAllMs
        return Action(drop, delay)
    }
}
