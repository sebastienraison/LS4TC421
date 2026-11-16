package net.rseb.ls4tc421.link

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ls4tc421.protocol.Cmd
import ls4tc421.protocol.FrameDirection
import ls4tc421.protocol.FrameLayout
import ls4tc421.protocol.FrameListener
import ls4tc421.protocol.Program
import ls4tc421.protocol.WriteResult
import ls4tc421.protocol.WriteStage
import net.rseb.ls4tc421.data.ClientSource
import net.rseb.ls4tc421.data.DeviceDao
import net.rseb.ls4tc421.data.SlotDao
import net.rseb.ls4tc421.data.toDevice
import net.rseb.ls4tc421.model.ProgramSpec
import net.rseb.ls4tc421.model.StoredForm

/** Why an upload stopped. */
sealed interface UploadFailure {
    data class TimedOut(val attempts: Int) : UploadFailure
    data class UnexpectedAck(val received: Int, val expected: Int) : UploadFailure
    data object NetworkError : UploadFailure
    data object UnknownDevice : UploadFailure
    data object Cancelled : UploadFailure
}

/** The frame [stage] was not acknowledged; attempt [attempt] of [attempts] is under way. */
data class Retry(val stage: WriteStage, val attempt: Int, val attempts: Int)

sealed interface UploadPhase {
    data object Idle : UploadPhase

    /** A preview is running: no write until it ends. */
    data object Busy : UploadPhase

    /** The program's name cannot be sent (D23): nothing was sent, rename the program first. */
    data object NameRefused : UploadPhase

    /** [completed] of [total] frames acknowledged; [retry] while a frame is being resent. */
    data class Sending(val completed: Int, val total: Int, val retry: Retry? = null) : UploadPhase

    /** Frame [number] (1-based, of [total]) failed; « Resume » starts again at [stage]. */
    data class Failed(val stage: WriteStage, val number: Int, val total: Int, val reason: UploadFailure) : UploadPhase

    data class Done(val at: Long, val name: String, val slot: Int) : UploadPhase
}

/**
 * What is being uploaded: the program, where it comes from in the bank, the device and the slot.
 * [emptying]: « Empty a slot » (D30), a program at 0 % that is not in the bank; once its `03 08` is
 * acknowledged [slot] is recorded as emptied (D31), the other slots are left alone.
 * [endSession]: `true` (default) for a write session of this program alone, closed by `04 0A`;
 * `false` for a program of a longer session (D32), closed later by [ProgramUploader.closeSession].
 */
data class UploadRequest(
    val deviceId: Long,
    val programId: Long?,
    val program: Program,
    val slot: Int,
    val emptying: Boolean = false,
    val endSession: Boolean = true,
)

/** Outcome of [ProgramUploader.closeSession]. */
sealed interface SessionClose {
    /** `04 0A` was acknowledged. */
    data object Closed : SessionClose

    /** A preview holds the link: nothing was sent. */
    data object Busy : SessionClose

    /** `04 0A` was not acknowledged (frame 1 of 1); nothing more was sent. */
    data class Failed(val failure: UploadPhase.Failed) : SessionClose
}

/**
 * Sends a program to a controller, frame by frame with retries ([ls4tc421.protocol.Tc421Client.writeProgram]),
 * shows each resend, resumes a failed upload at its failed frame, and records what the slot now holds
 * (D31: written, or emptied) when the controller acknowledged the `03 08` that ends the program.
 * « Empty a slot » (D30) is an upload too, of a program at 0 % ([empty]). One upload at a time.
 *
 * Write sessions (D32): an upload is a session of one program (`01 01`, pause, 48 steps, `03 08`,
 * `04 0A`) unless [UploadRequest.endSession] is `false`; several such uploads then [closeSession] make
 * one session. On a failure or a cancellation nothing more is sent, as the original app does.
 */
class ProgramUploader(
    private val clients: ClientSource,
    private val devices: DeviceDao,
    private val slots: SlotDao,
    private val tap: FrameTap,
    private val guard: LinkGuard,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _phase = MutableStateFlow<UploadPhase>(UploadPhase.Idle)
    val phase: StateFlow<UploadPhase> = _phase

    /** The upload under way or the last one: what [UploadPhase.Failed] and « Resume » are about. */
    var request: UploadRequest? = null
        private set
    private var job: Job? = null

    /** When the program of [request] was recorded (its `03 08` acknowledged), if it was. */
    @Volatile private var recordedAt: Long? = null

    /**
     * Sends [spec] (program [programId] of the bank) to device [deviceId], slot [slot]. A name that
     * cannot be sent (D23) stops here, before any frame: [UploadPhase.NameRefused].
     */
    fun start(deviceId: Long, programId: Long?, spec: ProgramSpec, slot: Int): Job {
        val program = spec.toProgramOrNull()
        if (program == null) {
            synchronized(this) {
                if (_phase.value !is UploadPhase.Sending) _phase.value = UploadPhase.NameRefused
            }
            return done()
        }
        return start(UploadRequest(deviceId, programId, program, slot))
    }

    /**
     * « Empty a slot » (D30): writes [program] (0 % everywhere, see [net.rseb.ls4tc421.model.EmptySlot])
     * in [slot] of device [deviceId], with the same progress, retries, resume and failures as any upload.
     */
    fun empty(deviceId: Long, slot: Int, program: Program, endSession: Boolean = true): Job =
        start(UploadRequest(deviceId, programId = null, program = program, slot = slot, emptying = true, endSession = endSession))

    /** Sends [newRequest] from its first frame. Ignored while an upload runs. */
    fun start(newRequest: UploadRequest): Job {
        synchronized(this) {
            if (_phase.value is UploadPhase.Sending) return done()
            request = newRequest
            recordedAt = null
        }
        return launch(newRequest, WriteStage.Start)
    }

    /** « Resume »: the failed upload continues at its failed frame, same device, slot and program. */
    fun resume(): Job {
        val failed = _phase.value as? UploadPhase.Failed ?: return done()
        val current = request ?: return done()
        return launch(current, failed.stage)
    }

    /** « Cancel »: stops after the frame in progress; the upload can then be resumed. */
    fun cancel() {
        job?.cancel()
    }

    /** Back to idle (closing the sheet). Does not stop a running upload: [cancel] does. */
    fun reset() {
        if (_phase.value !is UploadPhase.Sending) _phase.value = UploadPhase.Idle
    }

    private fun launch(req: UploadRequest, from: WriteStage): Job {
        synchronized(this) {
            if (_phase.value is UploadPhase.Sending) return done()
            if (!guard.tryAcquire(LinkGuard.Use.WRITE)) {
                _phase.value = UploadPhase.Busy
                return done()
            }
            _phase.value = UploadPhase.Sending(indexOf(from), totalOf(req))
        }
        val total = totalOf(req)
        val attempts = AtomicInteger(0)
        val listener = FrameListener { event ->
            if (event.direction != FrameDirection.SENT || !isWriteFrame(event.bytes)) return@FrameListener
            val sent = attempts.incrementAndGet()
            if (sent >= 2) {
                _phase.update { phase ->
                    if (phase !is UploadPhase.Sending) phase
                    else phase.copy(retry = Retry(stageAt(phase.completed), sent, maxAttempts))
                }
            }
        }
        val launched = scope.launch {
            var completed = indexOf(from)
            var programAcked = false
            tap.add(listener)
            val result: UploadPhase = try {
                val device = devices.get(req.deviceId)?.toDevice()
                if (device == null) {
                    UploadPhase.Failed(from, completed + 1, total, UploadFailure.UnknownDevice)
                } else {
                    val client = clients.get()
                    maxAttempts = client.config.attempts
                    val outcome = client.writeProgram(
                        device, req.slot, req.program, resumeAt = from, endSession = req.endSession,
                    ) { progress ->
                        completed = progress.completed
                        if (progress.stage == WriteStage.EndProgram) programAcked = true
                        attempts.set(0)
                        _phase.value = UploadPhase.Sending(progress.completed, progress.total)
                    }
                    if (programAcked) record(req)
                    when (outcome) {
                        WriteResult.Success -> UploadPhase.Done(recordedAt ?: now(), req.program.name, req.slot)
                        is WriteResult.TimedOut -> failed(outcome.stage, total, UploadFailure.TimedOut(outcome.attempts))
                        is WriteResult.UnexpectedAck ->
                            failed(outcome.stage, total, UploadFailure.UnexpectedAck(outcome.received, outcome.expected))
                    }
                }
            } catch (_: IOException) {
                if (programAcked) record(req)
                failed(stageAt(completed), total, UploadFailure.NetworkError)
            } catch (e: CancellationException) {
                if (programAcked) record(req)
                _phase.value = failed(stageAt(completed), total, UploadFailure.Cancelled)
                throw e
            } finally {
                tap.remove(listener)
                guard.release(LinkGuard.Use.WRITE)
            }
            _phase.value = result
        }
        job = launched
        return launched
    }

    /**
     * « Close the session » (D32): `04 0A` alone, after uploads made with `endSession = false`. Its
     * failure is reported but changes no phase of [phase]; a failed close is retried by calling again.
     */
    suspend fun closeSession(deviceId: Long): SessionClose {
        synchronized(this) {
            if (_phase.value is UploadPhase.Sending) return SessionClose.Busy
            if (!guard.tryAcquire(LinkGuard.Use.WRITE)) return SessionClose.Busy
        }
        try {
            val device = devices.get(deviceId)?.toDevice()
                ?: return closeFailed(UploadFailure.UnknownDevice)
            return when (val outcome = clients.get().closeSession(device)) {
                WriteResult.Success -> SessionClose.Closed
                is WriteResult.TimedOut -> closeFailed(UploadFailure.TimedOut(outcome.attempts))
                is WriteResult.UnexpectedAck -> closeFailed(UploadFailure.UnexpectedAck(outcome.received, outcome.expected))
            }
        } catch (_: IOException) {
            return closeFailed(UploadFailure.NetworkError)
        } finally {
            guard.release(LinkGuard.Use.WRITE)
        }
    }

    private fun closeFailed(reason: UploadFailure) =
        SessionClose.Failed(UploadPhase.Failed(WriteStage.Finish, 1, 1, reason))

    /** Records what [req] wrote in its slot, once per request, whatever happens next. */
    private suspend fun record(req: UploadRequest) {
        if (recordedAt != null) return
        val at = now()
        withContext(NonCancellable) {
            if (req.emptying) {
                slots.recordEmptied(req.deviceId, req.slot, req.program.name, at)
            } else {
                slots.recordWritten(
                    deviceId = req.deviceId,
                    slot = req.slot,
                    programId = req.programId,
                    name = req.program.name,
                    at = at,
                    levels = StoredForm.levels(req.program.steps),
                )
            }
        }
        recordedAt = at
    }

    @Volatile private var maxAttempts = 3

    private fun failed(stage: WriteStage, total: Int, reason: UploadFailure) =
        UploadPhase.Failed(stage, indexOf(stage) + 1, total, reason)

    private fun done(): Job = Job().apply { complete() }

    companion object {
        /** One program in a longer session: `01 01`, 48 × `01 02`, `03 08`. */
        const val FRAMES_PER_PROGRAM = 50

        /** A session of one program: `01 01`, 48 × `01 02`, `03 08`, `04 0A`. */
        const val TOTAL_FRAMES = FRAMES_PER_PROGRAM + 1

        /** Frames of [request]'s upload. */
        fun totalOf(request: UploadRequest): Int = if (request.endSession) TOTAL_FRAMES else FRAMES_PER_PROGRAM

        /** 0-based position of [stage] in the write sequence. */
        fun indexOf(stage: WriteStage): Int = when (stage) {
            WriteStage.Start -> 0
            is WriteStage.Step -> stage.index + 1
            WriteStage.EndProgram -> FRAMES_PER_PROGRAM - 1
            WriteStage.Finish -> FRAMES_PER_PROGRAM
        }

        fun stageAt(index: Int): WriteStage = when (index) {
            0 -> WriteStage.Start
            in 1..48 -> WriteStage.Step(index - 1)
            FRAMES_PER_PROGRAM - 1 -> WriteStage.EndProgram
            else -> WriteStage.Finish
        }

        /** `01 xx`, `03 08` or `04 0A`: a frame of a program write. */
        internal fun isWriteFrame(bytes: ByteArray): Boolean {
            if (bytes.size != FrameLayout.SIZE) return false
            val command = bytes[FrameLayout.COMMAND].toInt() and 0xFF
            val sub = bytes[FrameLayout.SUBCOMMAND].toInt() and 0xFF
            return command == Cmd.DOWNLOAD ||
                (command == Cmd.STOP && sub == Cmd.STOP_SUB) ||
                (command == Cmd.STOP_ALL && sub == Cmd.STOP_ALL_SUB)
        }
    }
}
