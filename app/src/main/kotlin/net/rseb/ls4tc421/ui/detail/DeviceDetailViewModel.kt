package net.rseb.ls4tc421.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.LocalDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.rseb.ls4tc421.data.DeviceEntity
import net.rseb.ls4tc421.data.DeviceRepository
import net.rseb.ls4tc421.data.SendOutcome
import net.rseb.ls4tc421.data.SlotState
import net.rseb.ls4tc421.ui.slots.InService
import net.rseb.ls4tc421.ui.slots.SlotTile
import net.rseb.ls4tc421.ui.slots.inService
import net.rseb.ls4tc421.ui.slots.slotTiles

sealed interface DetailState {
    data object Loading : DetailState
    data class Present(val device: DeviceEntity) : DetailState
    /** Deleted (or never existed): the screen goes back. */
    data object Gone : DetailState
}

/** The two cards on the programs of a device (D31): the slot in service and the 20 tiles, from the 20 [states]. */
data class SlotsView(val inService: InService, val tiles: List<SlotTile>, val states: List<SlotState>)

/** « What does this controller hold? », asked once when a device is added (D31). */
enum class ContentsAnswer {
    /** « Programs to keep » (and « Later »): the 20 slots stay unknown, nothing is written. */
    KEEP,

    /** « Nothing: it is new or already emptied »: the 20 are declared empty, nothing is written. */
    NEW_OR_EMPTIED,

    /** « Doesn't matter: empty it now »: the page opens emptying with the 20 checked. */
    EMPTY_NOW,
}

sealed interface ClockAction {
    data object Idle : ClockAction
    data object Sending : ClockAction
    data class Done(val outcome: SendOutcome) : ClockAction
}

class DeviceDetailViewModel(
    private val id: Long,
    private val repository: DeviceRepository,
    private val phoneTime: () -> LocalDateTime = { LocalDateTime.now() },
) : ViewModel() {

    val state: StateFlow<DetailState> = repository.device(id)
        .map { if (it == null) DetailState.Gone else DetailState.Present(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, DetailState.Loading)

    /** « In service, as you declared » and the slot grid; `null` until loaded or once the device is gone. */
    val slots: StateFlow<SlotsView?> = combine(repository.device(id), repository.slots(id)) { device, states ->
        device?.let { SlotsView(inService(states, it.activeSlot), slotTiles(states, it.activeSlot), states) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Declares [slot] in service (« Declare in service », « Change », « Done, it is in service »), or withdraws it (`null`). */
    fun setActiveSlot(slot: Int?) {
        viewModelScope.launch { repository.setActiveSlot(id, slot) }
    }

    /** « I no longer know what it holds » (Q8): back to unknown. */
    fun forgetSlot(slot: Int) {
        viewModelScope.launch { repository.forgetSlot(id, slot) }
    }

    /**
     * The answer to « What does this controller hold? ». Only « new or already emptied » writes in the
     * app (20 declared empty); nothing is ever sent from here: [ContentsAnswer.EMPTY_NOW] is the page's
     * to open (emptying, 20 checked).
     */
    fun answerContents(answer: ContentsAnswer) {
        if (answer == ContentsAnswer.NEW_OR_EMPTIED) viewModelScope.launch { repository.declareAllEmpty(id) }
    }

    private val _clock = MutableStateFlow<ClockAction>(ClockAction.Idle)
    val clock: StateFlow<ClockAction> = _clock

    /** « Set to phone time ». */
    fun setToPhoneTime() = sendTime { phoneTime() }

    /** « Set manually ». */
    fun setTime(time: LocalDateTime) = sendTime { time }

    private fun sendTime(time: () -> LocalDateTime) {
        if (_clock.value == ClockAction.Sending) return
        _clock.value = ClockAction.Sending
        viewModelScope.launch {
            _clock.value = ClockAction.Done(repository.setTime(id, time()))
        }
    }

    fun dismissClockResult() {
        if (_clock.value is ClockAction.Done) _clock.value = ClockAction.Idle
    }

    fun rename(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch { repository.rename(id, clean) }
    }

    fun delete() {
        viewModelScope.launch { repository.delete(id) }
    }
}
