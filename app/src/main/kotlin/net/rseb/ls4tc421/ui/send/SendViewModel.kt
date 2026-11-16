package net.rseb.ls4tc421.ui.send

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.rseb.ls4tc421.AppContainer
import net.rseb.ls4tc421.data.DeviceEntity
import net.rseb.ls4tc421.data.DeviceRepository
import net.rseb.ls4tc421.data.ProgramPlacement
import net.rseb.ls4tc421.data.ProgramRepository
import net.rseb.ls4tc421.data.SlotState
import net.rseb.ls4tc421.link.ProgramUploader
import net.rseb.ls4tc421.link.UploadPhase
import net.rseb.ls4tc421.link.UploadRequest
import net.rseb.ls4tc421.model.ProgramSpec

/**
 * Default device of the send sheet: [presetId] if it is known, else the device where the program is
 * written (the most recent write if several), else the first device.
 */
fun defaultDevice(devices: List<DeviceEntity>, placements: List<ProgramPlacement>, presetId: Long?): DeviceEntity? =
    devices.firstOrNull { it.id == presetId }
        ?: placements.sortedByDescending { it.at ?: 0L }.firstNotNullOfOrNull { p -> devices.firstOrNull { it.id == p.deviceId } }
        ?: devices.firstOrNull()

/** Default slot on device [deviceId]: where the program is written there (the most recent write if several), else 0. */
fun defaultSlot(deviceId: Long, placements: List<ProgramPlacement>): Int =
    placements.filter { it.deviceId == deviceId }.maxByOrNull { it.at ?: 0L }?.slot ?: 0

/**
 * Screen 8: sending program [programId] to a device, in a slot (D31). [presetDeviceId]: opened from a
 * device page, the device is fixed; `null` (Programs tab, editor): it is chosen in the sheet.
 * [fixedSlot]: opened from a slot's sheet, the slot is fixed too.
 */
class SendViewModel(
    private val programId: Long,
    presetDeviceId: Long?,
    val fixedSlot: Int?,
    private val repository: DeviceRepository,
    private val programs: ProgramRepository,
    newUploader: (CoroutineScope) -> ProgramUploader,
) : ViewModel() {
    constructor(programId: Long, presetDeviceId: Long?, fixedSlot: Int?, container: AppContainer) : this(
        programId,
        presetDeviceId,
        fixedSlot,
        container.repository,
        container.programs,
        { scope -> ProgramUploader(container.clients, container.deviceDao, container.slotDao, container.frames, container.guard, scope) },
    )

    /** The device cannot be changed in this sheet (opened from a device page). */
    val deviceLocked: Boolean = presetDeviceId != null

    private val _program = MutableStateFlow<ProgramSpec?>(null)
    val program: StateFlow<ProgramSpec?> = _program

    val devices: StateFlow<List<DeviceEntity>?> = repository.devices.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Where this program is written (D31). */
    private val placements: StateFlow<List<ProgramPlacement>?> =
        programs.placements(programId).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _deviceId = MutableStateFlow(presetDeviceId)
    val deviceId: StateFlow<Long?> = _deviceId

    /** The 20 slots of the chosen device (D31), shown as the grid. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val slots: StateFlow<List<SlotState>?> = _deviceId
        .flatMapLatest { id -> if (id == null) flowOf(null) else repository.slots(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Protocol slot 0..19 (shown 1..20). */
    private val _slot = MutableStateFlow(fixedSlot?.takeIf { it in 0..19 } ?: 0)
    val slot: StateFlow<Int> = _slot

    private var defaultsApplied = false

    private val uploader = newUploader(viewModelScope)
    val phase: StateFlow<UploadPhase> = uploader.phase

    /** The program and the slot of the upload under way or the last one (its failure is about them). */
    val upload: UploadRequest? get() = uploader.request

    /** The slot declared in service after « Done, it is in service »; `null` until then. */
    private val _declared = MutableStateFlow<Int?>(null)
    val declared: StateFlow<Int?> = _declared

    init {
        // Follows the bank: the sheet's view model outlives the sheet, and the program may be renamed or
        // edited before the next opening (bug of 15/11: a renamed program was sent under its old name).
        viewModelScope.launch { programs.observe(programId).collect { _program.value = it } }
        viewModelScope.launch {
            combine(devices, placements) { list, written -> list to written }.collect { (list, written) ->
                if (list == null || written == null || defaultsApplied) return@collect
                defaultsApplied = true
                defaultDevice(list, written, _deviceId.value)?.let { applyDevice(it) }
            }
        }
    }

    /** Picks a device (not when it is locked); the slot goes to the one where this program is written on it, else slot 1. */
    fun selectDevice(device: DeviceEntity) {
        if (deviceLocked) return
        applyDevice(device)
    }

    private fun applyDevice(device: DeviceEntity) {
        if (phase.value is UploadPhase.Sending) return
        _deviceId.value = device.id
        if (fixedSlot == null) _slot.value = defaultSlot(device.id, placements.value.orEmpty())
        uploader.reset()
        _declared.value = null
    }

    fun selectSlot(slot: Int) {
        if (fixedSlot != null || phase.value is UploadPhase.Sending || slot !in 0..19) return
        _slot.value = slot
        uploader.reset()
        _declared.value = null
    }

    /** Sends the program as stored right now, never a copy kept from an earlier opening. */
    fun send() {
        val device = _deviceId.value ?: return
        val slot = _slot.value
        _declared.value = null
        viewModelScope.launch {
            val spec = programs.get(programId) ?: return@launch
            _program.value = spec
            // A name that breaks the rule for sending (D23) is refused there, before any frame.
            uploader.start(device, programId, spec, slot)
        }
    }

    /** « Done, it is in service » (D16): after an acknowledged upload, its slot is declared in service. */
    fun declareInService() {
        val done = phase.value as? UploadPhase.Done ?: return
        val device = _deviceId.value ?: return
        viewModelScope.launch {
            repository.setActiveSlot(device, done.slot)
            _declared.value = done.slot
        }
    }

    fun resume() = uploader.resume()

    fun cancel() = uploader.cancel()

    /** Closing the sheet: the next opening starts afresh. */
    fun reset() {
        uploader.reset()
        _declared.value = null
    }
}
