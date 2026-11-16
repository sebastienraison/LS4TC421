package net.rseb.ls4tc421.bench

import java.io.IOException
import kotlin.time.Duration
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
import ls4tc421.protocol.WriteResult
import ls4tc421.protocol.WriteStage
import net.rseb.ls4tc421.data.ClientSource

/** Result of the last « End preview ». */
sealed interface EndStatus {
    data object None : EndStatus
    data object Sending : EndStatus
    data class Acknowledged(val at: Long) : EndStatus
    data class NoReply(val attempts: Int) : EndStatus
    data class Unexpected(val received: Int, val expected: Int) : EndStatus
    data object NetworkError : EndStatus
}

data class PreviewState(
    /** The state being resent, or `null`. */
    val active: FixedState? = null,
    /** When [active] was selected (the stopwatch starts there). */
    val since: Long? = null,
    val end: EndStatus = EndStatus.None,
    /** The last attempt to send the preview failed (network error, unknown device). */
    val sendError: Boolean = false,
)

sealed interface FailureReason {
    data class TimedOut(val attempts: Int) : FailureReason
    data class UnexpectedAck(val received: Int, val expected: Int) : FailureReason
    data object NetworkError : FailureReason
}

sealed interface WritePhase {
    data object Idle : WritePhase

    /** [completed] of [total] frames acknowledged. */
    data class Writing(val completed: Int, val total: Int) : WritePhase

    /** Frame number [number] (1-based, of [total]) failed; « Resume » starts again at [stage]. */
    data class Failed(val stage: WriteStage, val number: Int, val total: Int, val reason: FailureReason) : WritePhase

    data class Done(val at: Long) : WritePhase
}

data class WriteState(val slot: Int, val phase: WritePhase = WritePhase.Idle)

/**
 * The logic of the test bench for one controller, without Android: fixed preview states with a
 * resend every [resendInterval], « End preview », and the write of the two test programs with resume.
 * Runs in [scope], which must outlive the screen (leaving the screen still has to send `03 08`).
 */
class BenchController(
    private val clients: ClientSource,
    private val device: suspend () -> Device?,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val resendInterval: Duration = 1.seconds,
) {
    private val _preview = MutableStateFlow(PreviewState())
    val preview: StateFlow<PreviewState> = _preview

    private val _writes = MutableStateFlow(BenchProgram.entries.associateWith { WriteState(it.defaultSlot) })
    val writes: StateFlow<Map<BenchProgram, WriteState>> = _writes

    /** Serialises the preview actions (start, resend, end). */
    private val actions = Mutex()
    private var previewJob: Job? = null

    /** `06 01` was sent and no `03 08` since. */
    @Volatile private var previewOpen = false

    val writing: Boolean get() = _writes.value.values.any { it.phase is WritePhase.Writing }

    // ---- part A: preview ---------------------------------------------------------------

    /** Shows [state] on the ramp and resends it every second until « End preview ». Ignored while writing. */
    fun select(state: FixedState): Job {
        synchronized(this) {
            if (writing) return done()
            _preview.update { PreviewState(active = state, since = now(), end = EndStatus.None) }
        }
        return scope.launch {
            actions.withLock {
                previewJob?.cancelAndJoin()
                if (_preview.value.active == state) previewJob = scope.launch { resend(state) }
            }
        }
    }

    private suspend fun resend(state: FixedState) {
        try {
            val target = device() ?: throw IOException("unknown device")
            val client = clients.get()
            if (!previewOpen) {
                client.startPreview(target, BenchPrograms.PREVIEW_NAME)
                previewOpen = true
            }
            while (true) {
                client.sendPreviewValues(target, state.levels)
                delay(resendInterval)
            }
        } catch (_: IOException) {
            _preview.update { it.copy(active = null, since = null, sendError = true) }
        }
    }

    /** « End preview »: stops the resend and sends `03 08`. Always allowed (it is also the way back). */
    fun endPreview(): Job = scope.launch { actions.withLock { end() } }

    /**
     * Leaving the screen or the app: ends the preview if one is running, so that the ramp never stays
     * on a test state. Does nothing otherwise.
     */
    fun exit(): Job = scope.launch {
        actions.withLock { if (_preview.value.active != null || previewOpen) end() }
    }

    private suspend fun end() {
        previewJob?.cancelAndJoin()
        previewJob = null
        _preview.update { it.copy(active = null, since = null, end = EndStatus.Sending, sendError = false) }
        val status = try {
            val target = device() ?: throw IOException("unknown device")
            when (val result = clients.get().endPreview(target)) {
                CommandResult.Success -> EndStatus.Acknowledged(now())
                is CommandResult.TimedOut -> EndStatus.NoReply(result.attempts)
                is CommandResult.UnexpectedAck -> EndStatus.Unexpected(result.received, result.expected)
            }
        } catch (_: IOException) {
            EndStatus.NetworkError
        }
        if (status !is EndStatus.NetworkError) previewOpen = false
        _preview.update { it.copy(end = status) }
    }

    // ---- part B: program write ---------------------------------------------------------

    fun setSlot(which: BenchProgram, slot: Int) {
        if (slot !in 0..19) return
        _writes.update { all ->
            val current = all.getValue(which)
            if (current.phase is WritePhase.Writing) all else all + (which to WriteState(slot))
        }
    }

    /** « Write »: the whole program, from `01 01`. Ignored while a preview runs or another write is going. */
    fun write(which: BenchProgram): Job = launchWrite(which, WriteStage.Start)

    /** « Resume »: continues a failed write at its failed frame, in the same slot. */
    fun resume(which: BenchProgram): Job {
        val failed = _writes.value.getValue(which).phase as? WritePhase.Failed ?: return done()
        return launchWrite(which, failed.stage)
    }

    private fun launchWrite(which: BenchProgram, from: WriteStage): Job {
        val total = TOTAL_FRAMES
        val slot: Int
        synchronized(this) {
            if (writing || _preview.value.active != null) return done()
            slot = _writes.value.getValue(which).slot
            set(which, slot, WritePhase.Writing(indexOf(from), total))
        }
        return scope.launch {
            var completed = indexOf(from)
            val phase = try {
                val target = device() ?: throw IOException("unknown device")
                val result = clients.get().writeProgram(target, slot, which.program, resumeAt = from) { progress ->
                    completed = progress.completed
                    set(which, slot, WritePhase.Writing(progress.completed, progress.total))
                }
                when (result) {
                    WriteResult.Success -> WritePhase.Done(now())
                    is WriteResult.TimedOut ->
                        WritePhase.Failed(result.stage, indexOf(result.stage) + 1, total, FailureReason.TimedOut(result.attempts))
                    is WriteResult.UnexpectedAck -> WritePhase.Failed(
                        result.stage, indexOf(result.stage) + 1, total,
                        FailureReason.UnexpectedAck(result.received, result.expected),
                    )
                }
            } catch (_: IOException) {
                WritePhase.Failed(stageAt(completed), completed + 1, total, FailureReason.NetworkError)
            }
            set(which, slot, phase)
        }
    }

    private fun done(): Job = Job().apply { complete() }

    private fun set(which: BenchProgram, slot: Int, phase: WritePhase) =
        _writes.update { it + (which to WriteState(slot, phase)) }

    companion object {
        /** A session of one program (D32): `01 01`, 48 × `01 02`, `03 08`, `04 0A`. */
        const val TOTAL_FRAMES = 51

        /** 0-based position of [stage] in the write sequence. */
        fun indexOf(stage: WriteStage): Int = when (stage) {
            WriteStage.Start -> 0
            is WriteStage.Step -> stage.index + 1
            WriteStage.EndProgram -> TOTAL_FRAMES - 2
            WriteStage.Finish -> TOTAL_FRAMES - 1
        }

        fun stageAt(index: Int): WriteStage = when (index) {
            0 -> WriteStage.Start
            in 1..48 -> WriteStage.Step(index - 1)
            TOTAL_FRAMES - 2 -> WriteStage.EndProgram
            else -> WriteStage.Finish
        }
    }
}
