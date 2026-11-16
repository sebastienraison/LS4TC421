package ls4tc421.protocol

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.time.LocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Client configuration. Defaults follow the original app (remote port 5000, three attempts,
 * discovery sent twice 300 ms apart, 50 ms between program steps), with two exceptions:
 *  - the local port is 0, i.e. a free port chosen by the system. The controller answers to the
 *    source port of the request, so the client does not need port 5000 (the original app's habit),
 *    and a port of its own is shared with no other program on the phone;
 *  - the wait for an acknowledgement is two seconds per attempt, because a real controller
 *    acknowledges the first step after `01 01` in 1.1 to 1.3 s (observed 2026-11-14). The last
 *    listening window of a discovery uses the same wait;
 *  - a pause of [firstStepDelay] (one second) between the acknowledgement of `01 01` and the first
 *    step: a real controller dropped a first step sent 0 or 0.25 s after that acknowledgement, and
 *    acknowledged it at the first attempt when sent 0.5 to 1.5 s after (2026-11-16, 14 of 14).
 *    One second is twice the shortest pause that worked.
 */
data class ClientConfig(
    val remotePort: Int = 5000,
    /** Local port to bind; 0 (default) = a free port chosen by the system. */
    val localPort: Int = 0,
    /** Local address to bind; `null` = all interfaces. */
    val localAddress: InetAddress? = null,
    val ackTimeout: Duration = 2.seconds,
    val attempts: Int = 3,
    /** Pause before each program step except the first, which waits [firstStepDelay] instead. */
    val stepInterval: Duration = 50.milliseconds,
    /**
     * Pause between the acknowledgement of a `01 01` sent in the same [Tc421Client.writeProgram] call
     * and the first `01 02`. Not applied when a write resumes at the first step without `01 01`.
     */
    val firstStepDelay: Duration = 1.seconds,
    val discoverySends: Int = 2,
    val discoveryInterval: Duration = 300.milliseconds,
    val broadcastAddress: InetAddress = InetAddress.getByAddress(byteArrayOf(-1, -1, -1, -1)),
    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Called for every datagram sent or received; `null` = none. See [FrameListener]. */
    val frameListener: FrameListener? = null,
) {
    init {
        require(attempts >= 1) { "attempts must be >= 1" }
        require(remotePort in 1..65535) { "remotePort out of range" }
        require(localPort in 0..65535) { "localPort out of range" }
        require(discoverySends >= 1) { "discoverySends must be >= 1" }
        require(!firstStepDelay.isNegative()) { "firstStepDelay must be >= 0" }
    }
}

/** Direction of a datagram seen by [FrameListener]. */
enum class FrameDirection { SENT, RECEIVED }

/**
 * One datagram sent or received by the client. [discarded] is `true` for a datagram that was
 * received but thrown away unread because it was already queued before a command was sent.
 */
class FrameEvent(
    val timeMillis: Long,
    val direction: FrameDirection,
    val remote: InetSocketAddress,
    val bytes: ByteArray,
    val discarded: Boolean = false,
)

/**
 * Observer of the traffic of a [Tc421Client], for diagnostics. Called on the client's I/O thread,
 * inside its lock: it must be quick and must not throw. [FrameEvent.bytes] is a copy the listener may
 * keep; it holds Wi-Fi keys in clear text when `18` frames are sent.
 */
fun interface FrameListener {
    fun onFrame(event: FrameEvent)
}

/**
 * UDP client for a TC421. One socket, one command at a time (calls are serialised).
 * Close it when done. Every command with an acknowledgement is tried `attempts` times.
 *
 * Constructing the client binds the local port; this throws [java.net.SocketException]
 * if a fixed `localPort` is taken. The port is not shared (no `SO_REUSEADDR`): when two sockets
 * share a UDP port, replies sent to that port may reach only one of them.
 */
class Tc421Client(val config: ClientConfig = ClientConfig()) : AutoCloseable {
    private val socket = DatagramSocket(null as java.net.SocketAddress?).apply {
        broadcast = true
        bind(InetSocketAddress(config.localAddress, config.localPort))
    }
    private val lock = Mutex()

    /** The local port actually bound (useful when `localPort` is 0). */
    val localPort: Int get() = socket.localPort

    override fun close() = socket.close()

    // ---- discovery -------------------------------------------------------------------

    /**
     * Discovery (`17`). With [address] `null`, the frame goes to the configured broadcast
     * address; with an address, it is a unicast (which works and crosses routers).
     * Unicast returns at the first reply; broadcast collects replies until the window ends.
     */
    suspend fun discover(address: InetAddress? = null): DiscoveryResult = lock.withLock {
        withContext(config.ioDispatcher) {
            val target = InetSocketAddress(address ?: config.broadcastAddress, config.remotePort)
            val frame = Frames.search()
            val found = LinkedHashSet<Device>()
            drain()
            for (send in 1..config.discoverySends) {
                send(frame, target)
                val window = if (send < config.discoverySends) config.discoveryInterval else config.ackTimeout
                val end = System.nanoTime() + window.inWholeNanoseconds
                while (true) {
                    val packet = receiveUntil(end) ?: break
                    val reply = FrameReader.discovery(packet.data.copyOf(packet.length)) ?: continue
                    found += Device(packet.address, reply.mac)
                    if (address != null) return@withContext DiscoveryResult.Found(found.toList())
                }
            }
            if (found.isEmpty()) DiscoveryResult.NoReply else DiscoveryResult.Found(found.toList())
        }
    }

    // ---- simple commands -------------------------------------------------------------

    /** Sets the controller clock (`02`). */
    suspend fun setTime(device: Device, time: LocalDateTime = LocalDateTime.now()): CommandResult =
        transact(device, Frames.timeSync(device.mac, time), AckCode.TIME_SYNC)

    /**
     * Joins a Wi-Fi network (`18`). [password] `null` means an open network (one command);
     * otherwise two commands, SSID then key. If the SSID is acknowledged but the key is not,
     * the result is the failure of the second command; resend both.
     * The key travels in clear text: the protocol has no encryption.
     */
    suspend fun joinWifi(
        device: Device,
        ssid: String,
        password: String?,
        security: WifiSecurity = WifiSecurity.WPA2,
        cipher: WifiCipher = WifiCipher.AES,
    ): CommandResult {
        if (password == null) {
            return transact(device, Frames.wifiOpenSsid(device.mac, ssid), AckCode.WIFI_OPEN_SSID)
        }
        val first = Frames.wifiSsid(device.mac, ssid, security, cipher)
        val second = Frames.wifiPassword(device.mac, password) // validated before anything is sent
        val ssidResult = transact(device, first, AckCode.WIFI_SSID)
        if (ssidResult != CommandResult.Success) return ssidResult
        return transact(device, second, AckCode.WIFI_PASSWORD)
    }

    // ---- preview ---------------------------------------------------------------------

    /** `06 01`: starts a preview. The controller does not answer; sent once. */
    suspend fun startPreview(device: Device, name: String) =
        sendOnce(device, Frames.previewStart(device.mac, name))

    /** `06 02`: live levels for the five channels. No answer; sent once. */
    suspend fun sendPreviewValues(device: Device, channels: List<Int>) =
        sendOnce(device, Frames.previewValues(device.mac, channels))

    /** `03 08`: ends the preview; the controller goes back to its stored program (acked `83`). */
    suspend fun endPreview(device: Device): CommandResult =
        transact(device, Frames.stopProcess(device.mac), AckCode.STOP)

    // ---- program write ---------------------------------------------------------------

    /**
     * Writes [program] into [slot]: `01 01`, a pause of [ClientConfig.firstStepDelay], 48 x `01 02`,
     * `03 08`, then `04 0A` unless [endSession] is `false` (see [ProgramWriteSequence]).
     *
     * One write session holds one or several programs and ends with a single `04 0A`. To write several
     * programs in one session, call this with `endSession = false` for each of them, then
     * [closeSession]; to write one program, call this once with the default `endSession = true`.
     * Never start another program with `01 01` after a `04 0A` in quick succession: a real controller
     * ignored it, then stopped answering (2026-11-16).
     *
     * Each frame is acknowledged and retried; progress is reported after every acknowledged frame. On
     * failure nothing more is sent, and the result names the stage; call again with the same
     * [endSession] and `resumeAt = result.stage` to continue the session there instead of resending
     * everything. Resuming at the first step does not resend `01 01`, and does not wait
     * [ClientConfig.firstStepDelay]. What a session left open without `04 0A` leaves in the
     * controller is unknown. (Resending a step whose acknowledgement came late was observed to work,
     * 2026-11-14.)
     */
    suspend fun writeProgram(
        device: Device,
        slot: Int,
        program: Program,
        resumeAt: WriteStage = WriteStage.Start,
        endSession: Boolean = true,
        onProgress: (WriteProgress) -> Unit = {},
    ): WriteResult {
        val plan = ProgramWriteSequence.plan(device.mac, slot, program, endSession)
        val first = plan.indexOfFirst { it.stage == resumeAt }
        require(first >= 0) { "Cannot resume at $resumeAt" }
        return walk(device, plan, first, onProgress)
    }

    /**
     * `04 0A` alone: closes a write session whose last program was written with `endSession = false`.
     * Progress and failure are reported as for [writeProgram], on a plan of one frame.
     */
    suspend fun closeSession(device: Device, onProgress: (WriteProgress) -> Unit = {}): WriteResult =
        walk(device, listOf(ProgramWriteSequence.closing(device.mac)), 0, onProgress)

    private suspend fun walk(
        device: Device,
        plan: List<PlannedCommand>,
        first: Int,
        onProgress: (WriteProgress) -> Unit,
    ): WriteResult {
        for (index in first until plan.size) {
            val command = plan[index]
            if (index > first && command.stage is WriteStage.Step) {
                val pause = if (plan[index - 1].stage == WriteStage.Start) config.firstStepDelay else config.stepInterval
                if (pause > Duration.ZERO) delay(pause)
            }
            when (val result = transact(device, command.frame, command.expectedAck)) {
                CommandResult.Success -> onProgress(WriteProgress(index + 1, plan.size, command.stage))
                is CommandResult.TimedOut -> return WriteResult.TimedOut(command.stage, result.attempts)
                is CommandResult.UnexpectedAck ->
                    return WriteResult.UnexpectedAck(command.stage, result.received, result.expected)
            }
        }
        return WriteResult.Success
    }

    // ---- transport -------------------------------------------------------------------

    private suspend fun sendOnce(device: Device, frame: ByteArray) = lock.withLock {
        withContext(config.ioDispatcher) {
            send(frame, InetSocketAddress(device.address, config.remotePort))
        }
    }

    /** Sends [frame] and waits for the acknowledgement [expected]; up to `attempts` tries. */
    private suspend fun transact(device: Device, frame: ByteArray, expected: Int): CommandResult =
        lock.withLock {
            withContext(config.ioDispatcher) {
                repeat(config.attempts) {
                    drain() // an old, late reply must not be taken for this command's
                    send(frame, InetSocketAddress(device.address, config.remotePort))
                    val end = System.nanoTime() + config.ackTimeout.inWholeNanoseconds
                    while (true) {
                        val packet = receiveUntil(end) ?: break
                        if (packet.address != device.address) continue
                        val ack = FrameReader.ack(packet.data.copyOf(packet.length)) ?: continue
                        if (ack.mac != device.mac) continue
                        return@withContext if (ack.code == expected) {
                            CommandResult.Success
                        } else {
                            CommandResult.UnexpectedAck(ack.code, expected)
                        }
                    }
                }
                CommandResult.TimedOut(config.attempts)
            }
        }

    private fun send(frame: ByteArray, target: InetSocketAddress) {
        socket.send(DatagramPacket(frame, frame.size, target))
        notify(FrameDirection.SENT, target, frame, frame.size, discarded = false)
    }

    private fun notify(direction: FrameDirection, remote: InetSocketAddress, data: ByteArray, length: Int, discarded: Boolean) {
        val listener = config.frameListener ?: return
        listener.onFrame(FrameEvent(System.currentTimeMillis(), direction, remote, data.copyOf(length), discarded))
    }

    private fun received(packet: DatagramPacket, discarded: Boolean) =
        notify(FrameDirection.RECEIVED, InetSocketAddress(packet.address, packet.port), packet.data, packet.length, discarded)

    /** Next datagram before the [endNanos] deadline, or `null`. Cancellable between slices. */
    private suspend fun receiveUntil(endNanos: Long): DatagramPacket? {
        val buffer = ByteArray(2048)
        val packet = DatagramPacket(buffer, buffer.size)
        while (true) {
            currentCoroutineContext().ensureActive()
            val remainingMs = (endNanos - System.nanoTime()) / 1_000_000
            if (remainingMs <= 0) return null
            socket.soTimeout = minOf(remainingMs, SLICE_MS).toInt().coerceAtLeast(1)
            try {
                socket.receive(packet)
                received(packet, discarded = false)
                return packet
            } catch (_: SocketTimeoutException) {
                // next slice
            }
        }
    }

    /** Discards datagrams already queued. */
    private fun drain() {
        val packet = DatagramPacket(ByteArray(2048), 2048)
        socket.soTimeout = 1
        try {
            while (true) {
                socket.receive(packet)
                received(packet, discarded = true)
            }
        } catch (_: SocketTimeoutException) {
            // queue empty
        }
    }

    private companion object {
        const val SLICE_MS = 50L
    }
}
