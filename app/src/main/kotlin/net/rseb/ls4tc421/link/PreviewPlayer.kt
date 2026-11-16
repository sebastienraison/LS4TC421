package net.rseb.ls4tc421.link

import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ls4tc421.protocol.CommandResult
import ls4tc421.protocol.Device
import net.rseb.ls4tc421.model.AppName
import net.rseb.ls4tc421.data.ClientSource
import net.rseb.ls4tc421.model.CHANNELS
import net.rseb.ls4tc421.model.ProgramSpec
import net.rseb.ls4tc421.model.STEPS

/** Result of the last `03 08`. */
sealed interface PreviewEnd {
    data object None : PreviewEnd
    data object Sending : PreviewEnd
    data object Acknowledged : PreviewEnd
    data class NoReply(val attempts: Int) : PreviewEnd
    data class Unexpected(val received: Int) : PreviewEnd
    data object NetworkError : PreviewEnd
}

data class PreviewState(
    /** `06 01` sent and no `03 08` since: the ramp shows the preview. */
    val open: Boolean = false,
    val playing: Boolean = false,
    /** Minutes played since the start of the replay, 0..1440. */
    val position: Int = 0,
    /** Clock time shown, in minutes since midnight. */
    val minuteOfDay: Int = 0,
    /** Levels sent at [minuteOfDay]. */
    val levels: List<Int> = List(CHANNELS) { 0 },
    val end: PreviewEnd = PreviewEnd.None,
    /** A write is running: the preview cannot start. */
    val busy: Boolean = false,
    /** Sending failed (network error, unknown device): the preview stopped. */
    val sendError: Boolean = false,
    /** The name cannot be sent (D23): the preview did not start, nothing was sent. */
    val nameRefused: Boolean = false,
)

/**
 * Replays a day of [steps] on the ramp (D4): `06 01`, then one `06 02` every [frameInterval], the
 * whole day in [dayDuration] (2 minutes by default), starting 30 minutes before the lit period.
 * Levels between two steps are interpolated linearly (ASSUMPTION: the controller's own transition law
 * is not measured yet). Paused, the same frame keeps being sent. [end] sends `03 08`, which gives the
 * ramp back to its program. At the end of the day the replay stops on the last frame, still sent.
 * Runs in [scope], which must outlive the screen (leaving the screen still has to send `03 08`).
 */
class PreviewPlayer(
    private val clients: ClientSource,
    private val device: suspend () -> Device?,
    private val name: String,
    private val steps: List<List<Int>>,
    private val guard: LinkGuard,
    private val scope: CoroutineScope,
    private val frameInterval: Duration = 1.seconds,
    dayDuration: Duration = 2.minutes,
) {
    /** Minutes of the day played per frame (12 by default). */
    private val minutesPerFrame: Int = (DAY * frameInterval.inWholeMilliseconds / dayDuration.inWholeMilliseconds).toInt().coerceAtLeast(1)

    /** Where the replay starts: 30 min before the lit period (midnight if nothing or everything is lit). */
    val startMinute: Int = ProgramSpec.litPeriod(steps)?.let { Math.floorMod(it.first * 30 - 30, DAY) } ?: 0

    private val _state = MutableStateFlow(stateAt(0, playing = false))
    val state: StateFlow<PreviewState> = _state

    private val actions = Mutex()
    private var loop: Job? = null
    private var holdsGuard = false

    /**
     * Opens the preview (`06 01`) and plays from the start. Ignored while the frames are being sent.
     * A name that cannot be sent (D23) stops here, before any frame: [PreviewState.nameRefused].
     */
    fun start(): Job = scope.launch {
        actions.withLock {
            if (loop?.isActive == true) return@withLock
            if (!AppName.isValid(name)) {
                _state.update { it.copy(nameRefused = true) }
                return@withLock
            }
            if (!holdsGuard && !guard.tryAcquire(LinkGuard.Use.PREVIEW)) {
                _state.update { it.copy(busy = true) }
                return@withLock
            }
            holdsGuard = true
            _state.value = stateAt(0, playing = true).copy(open = true)
            loop = scope.launch { run() }
        }
    }

    fun play() = _state.update { s ->
        if (!s.open) s else if (s.position >= DAY) stateAt(0, true).copy(open = true, end = s.end) else s.copy(playing = true)
    }

    fun pause() = _state.update { it.copy(playing = false) }

    /** Moves to [minuteOfDay] (the next frame sends its levels). */
    fun seek(minuteOfDay: Int) = _state.update { s ->
        stateAt(Math.floorMod(minuteOfDay - startMinute, DAY), s.playing).copy(open = s.open, end = s.end)
    }

    /** « End preview »: stops the frames and sends `03 08`. Always allowed: it is also the way back. */
    fun end(): Job = scope.launch { actions.withLock { finish() } }

    /** Leaving the screen or the app: ends the preview if it is open; nothing otherwise. */
    fun exit(): Job = scope.launch { actions.withLock { if (_state.value.open) finish() } }

    private suspend fun run() {
        try {
            val target = device() ?: throw IOException("unknown device")
            val client = clients.get()
            client.startPreview(target, name)
            while (true) {
                client.sendPreviewValues(target, _state.value.levels)
                delay(frameInterval)
                _state.update { s ->
                    if (!s.playing) {
                        s
                    } else {
                        val next = (s.position + minutesPerFrame).coerceAtMost(DAY)
                        stateAt(next, playing = next < DAY).copy(open = s.open, end = s.end)
                    }
                }
            }
        } catch (_: IOException) {
            _state.update { it.copy(playing = false, sendError = true) }
        }
    }

    private suspend fun finish() {
        loop?.cancelAndJoin()
        loop = null
        _state.update { it.copy(playing = false, end = PreviewEnd.Sending) }
        val end = try {
            val target = device() ?: throw IOException("unknown device")
            when (val result = clients.get().endPreview(target)) {
                CommandResult.Success -> PreviewEnd.Acknowledged
                is CommandResult.TimedOut -> PreviewEnd.NoReply(result.attempts)
                is CommandResult.UnexpectedAck -> PreviewEnd.Unexpected(result.received)
            }
        } catch (_: IOException) {
            PreviewEnd.NetworkError
        }
        // No more preview frames from here: a write may go. Without an acknowledgement the ramp may still
        // show the preview, so the session stays open and leaving the screen tries `03 08` again.
        if (holdsGuard) guard.release(LinkGuard.Use.PREVIEW)
        holdsGuard = false
        _state.update { it.copy(open = end != PreviewEnd.Acknowledged, end = end) }
    }

    private fun stateAt(position: Int, playing: Boolean): PreviewState {
        val minute = Math.floorMod(startMinute + position, DAY)
        return PreviewState(playing = playing, position = position, minuteOfDay = minute, levels = levelsAt(steps, minute))
    }

    companion object {
        const val DAY = 24 * 60

        /** Levels at [minuteOfDay], linear between the step before and the step after (looping at midnight). */
        fun levelsAt(steps: List<List<Int>>, minuteOfDay: Int): List<Int> {
            val minute = Math.floorMod(minuteOfDay, DAY)
            val step = minute / 30
            val into = minute % 30
            val from = steps[step]
            val to = steps[(step + 1) % STEPS]
            return List(CHANNELS) { ch -> (2 * (from[ch] * (30 - into) + to[ch] * into) + 30) / 60 }
        }
    }
}
