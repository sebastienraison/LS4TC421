package net.rseb.ls4tc421.ui.slots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.rseb.ls4tc421.data.SLOT_COUNT
import net.rseb.ls4tc421.link.ProgramUploader
import net.rseb.ls4tc421.link.SessionClose
import net.rseb.ls4tc421.link.UploadPhase
import net.rseb.ls4tc421.model.EmptySlot
import net.rseb.ls4tc421.model.EmptySlotNaming

/** Where an emptying of several slots stands (D30, D31). Slots are protocol slots 0..19. */
sealed interface EmptyingRun {
    /** Choosing the slots. */
    data object Idle : EmptyingRun

    /** A preview is running: nothing was written for the slot in hand; « Resume » tries it again. */
    data object Busy : EmptyingRun

    /** Writing [slot], the [number]-th of [total]; [frames] its upload; [stopping]: « Cancel » was touched. */
    data class Writing(
        val slot: Int,
        val number: Int,
        val total: Int,
        val frames: UploadPhase.Sending,
        val stopping: Boolean = false,
    ) : EmptyingRun

    /**
     * The write of [slot] ([number]-th of [total]) failed; the slots before it are emptied, the others untouched.
     * With [failure] at [ls4tc421.protocol.WriteStage.Finish], all the slots are emptied and only the closing
     * of the write session (`04 0A`, D32) failed.
     */
    data class Failed(val slot: Int, val number: Int, val total: Int, val failure: UploadPhase.Failed) : EmptyingRun

    /** Stopped by « Cancel » after a slot: [emptied] of [total] done; the write session is left open (D32). */
    data class Stopped(val emptied: Int, val total: Int) : EmptyingRun

    /** The [count] slots are emptied. */
    data class Done(val count: Int) : EmptyingRun
}

/**
 * « Empty… » of the device page (D30, D31): choose slots on the grid, then they get a program at 0 %
 * one after the other, through one [ProgramUploader] (its retries, failures and « Resume »).
 *
 * D32: all the slots are written in ONE write session: each slot ends with `03 08` (it is recorded as
 * emptied when that is acknowledged), then a single `04 0A` closes the session after the last slot. A
 * failure stops at the failed slot and sends nothing more: the slots already emptied are recorded so,
 * the others keep their state; « Resume » continues the session from the failed frame of that slot,
 * then the rest of the selection, then the closing. « Cancel » stops after the slot being written and
 * sends nothing more either; neither does closing the panel. A failed closing leaves the slots emptied;
 * « Resume » sends the closing again.
 */
class EmptySlotsViewModel(
    private val deviceId: Long,
    private val naming: Flow<EmptySlotNaming>,
    newUploader: (CoroutineScope) -> ProgramUploader,
) : ViewModel() {
    private val uploader = newUploader(viewModelScope)

    /** The checked slots; `null` when the page is not in selection mode. */
    private val _selection = MutableStateFlow<Set<Int>?>(null)
    val selection: StateFlow<Set<Int>?> = _selection

    private val _run = MutableStateFlow<EmptyingRun>(EmptyingRun.Idle)
    val run: StateFlow<EmptyingRun> = _run

    /** Slots still to write, the one in hand first. */
    private var queue: List<Int> = emptyList()
    private var total = 0
    /** The last slot of the selection: the one shown while the session is being closed. */
    private var lastSlot = 0
    /** All the slots are written, the `04 0A` that closes the session is still to send. */
    private var closePending = false
    private var stopRequested = false
    private var template = ""
    private var chosenNaming = EmptySlotNaming.NUMBERED

    private val writing: Boolean get() = _run.value is EmptyingRun.Writing

    /** Enters selection mode with [checked] (« Empty… »: none; « Empty this slot »: one; new device: all). */
    fun open(checked: Set<Int> = emptySet()) {
        if (writing) return
        _selection.value = checked.filter { it in 0 until SLOT_COUNT }.toSet()
        _run.value = EmptyingRun.Idle
        uploader.reset()
    }

    /** Leaves selection mode. Refused while a slot is being written (« Cancel » first). */
    fun close() {
        if (writing) return
        _selection.value = null
        _run.value = EmptyingRun.Idle
        queue = emptyList()
        closePending = false
        uploader.reset()
    }

    private val choosing: Boolean get() = _run.value == EmptyingRun.Idle

    fun toggle(slot: Int) {
        if (!choosing || slot !in 0 until SLOT_COUNT) return
        _selection.update { current -> current?.let { if (slot in it) it - slot else it + slot } }
    }

    fun selectAll() {
        if (!choosing) return
        _selection.update { current -> current?.let { (0 until SLOT_COUNT).toSet() } }
    }

    /**
     * Empties the checked slots, in slot order. [numberedTemplate] is the translated « Free %1$02d »;
     * the naming is read from the settings now.
     */
    fun empty(numberedTemplate: String) {
        if (!choosing) return
        val slots = _selection.value.orEmpty().sorted()
        if (slots.isEmpty()) return
        queue = slots
        total = slots.size
        lastSlot = slots.last()
        closePending = false
        stopRequested = false
        template = numberedTemplate
        _run.value = EmptyingRun.Writing(slots.first(), 1, total, UploadPhase.Sending(0, ProgramUploader.FRAMES_PER_PROGRAM))
        viewModelScope.launch {
            chosenNaming = naming.first()
            runQueue(resumeFailed = false)
        }
    }

    /**
     * « Resume »: after a failure, from the failed frame of the failed slot; after « Cancel » or a busy
     * link, from the next slot; when only the closing of the session is left, the closing.
     */
    fun resume() {
        val current = _run.value
        if (queue.isEmpty() && !closePending) return
        val failed = current is EmptyingRun.Failed
        if (!failed && current !is EmptyingRun.Stopped && current != EmptyingRun.Busy) return
        stopRequested = false
        _run.value = if (queue.isEmpty()) {
            closingState()
        } else {
            EmptyingRun.Writing(queue.first(), number(), total, UploadPhase.Sending(0, ProgramUploader.FRAMES_PER_PROGRAM))
        }
        viewModelScope.launch { runQueue(resumeFailed = failed && queue.isNotEmpty()) }
    }

    /** « Cancel »: the slot being written is finished, then it stops. */
    fun cancel() {
        if (!writing) return
        stopRequested = true
        _run.update { if (it is EmptyingRun.Writing) it.copy(stopping = true) else it }
    }

    private fun number() = total - queue.size + 1

    /** While the `04 0A` is sent: the last slot, all its frames done. */
    private fun closingState() = EmptyingRun.Writing(
        lastSlot, total, total,
        UploadPhase.Sending(ProgramUploader.FRAMES_PER_PROGRAM, ProgramUploader.FRAMES_PER_PROGRAM),
        stopRequested,
    )

    private suspend fun runQueue(resumeFailed: Boolean) {
        var resumeFirst = resumeFailed
        while (queue.isNotEmpty()) {
            val slot = queue.first()
            val number = number()
            _run.value = EmptyingRun.Writing(slot, number, total, UploadPhase.Sending(0, ProgramUploader.FRAMES_PER_PROGRAM), stopRequested)
            val watcher = viewModelScope.launch {
                uploader.phase.collect { phase ->
                    if (phase is UploadPhase.Sending) _run.update { if (it is EmptyingRun.Writing) it.copy(frames = phase) else it }
                }
            }
            if (resumeFirst) {
                resumeFirst = false
                uploader.resume().join()
            } else {
                uploader.empty(deviceId, slot, EmptySlot.program(slot, chosenNaming, template), endSession = false).join()
            }
            watcher.cancel()
            when (val phase = uploader.phase.value) {
                is UploadPhase.Done -> {
                    queue = queue.drop(1)
                    if (queue.isEmpty()) closePending = true
                }
                is UploadPhase.Failed -> {
                    _run.value = EmptyingRun.Failed(slot, number, total, phase)
                    return
                }
                else -> {
                    // Busy (a preview holds the link) or reset meanwhile: nothing was written for this slot.
                    _run.value = EmptyingRun.Busy
                    return
                }
            }
            if (stopRequested) {
                // Nothing more is sent, not even the closing (D32, as the original app on an abort).
                _run.value = EmptyingRun.Stopped(total - queue.size, total)
                return
            }
        }
        _run.value = closingState()
        _run.value = when (val closing = uploader.closeSession(deviceId)) {
            SessionClose.Closed -> {
                closePending = false
                EmptyingRun.Done(total)
            }
            SessionClose.Busy -> EmptyingRun.Busy
            is SessionClose.Failed -> EmptyingRun.Failed(lastSlot, total, total, closing.failure)
        }
    }
}
