package net.rseb.ls4tc421.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ls4tc421.protocol.Device
import kotlinx.coroutines.flow.map
import net.rseb.ls4tc421.data.AddResult
import net.rseb.ls4tc421.data.AddressChange
import net.rseb.ls4tc421.data.DeviceEntity
import net.rseb.ls4tc421.data.DeviceRepository
import net.rseb.ls4tc421.data.Ipv4
import net.rseb.ls4tc421.data.ScanResult

enum class Reach { Checking, Reachable, Unreachable }

/** State of the « sur ce réseau » section. */
enum class NetworkSearch { NotOnWifi, Searching, Done, Failed }

enum class AddError { InvalidAddress, NameRequired, NoReply, NetworkError }

/** State of the « add by address » dialog. */
sealed interface AddState {
    data object Closed : AddState
    data class Editing(val error: AddError? = null) : AddState
    data object Testing : AddState
    /** The controller answered but its MAC is already in the list. */
    data class AlreadyKnown(val name: String) : AddState
}

class DevicesViewModel(private val repository: DeviceRepository) : ViewModel() {

    val known: StateFlow<List<DeviceEntity>> =
        repository.devices.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _reach = MutableStateFlow<Map<Long, Reach>>(emptyMap())
    val reach: StateFlow<Map<Long, Reach>> = _reach

    private val _network = MutableStateFlow(NetworkSearch.NotOnWifi)
    val network: StateFlow<NetworkSearch> = _network

    private val _found = MutableStateFlow<List<Device>>(emptyList())

    /** True once at least one controller, known or not, answered the last broadcast search. */
    val anyAnswered: StateFlow<Boolean> =
        _found.map { it.isNotEmpty() }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _moved = MutableStateFlow<List<AddressChange>>(emptyList())

    /** Known controllers whose address the last search has just updated (D27): the screen says so. */
    val moved: StateFlow<List<AddressChange>> = _moved

    /** Controllers found on the network that are not in the list yet. */
    val nearby: StateFlow<List<Device>> =
        combine(known, _found) { knownList, found ->
            val knownMacs = knownList.map { it.mac }.toSet()
            found.filter { it.mac.text !in knownMacs }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _added = MutableStateFlow<Long?>(null)

    /**
     * A device just added (by the search or by address), whose page opens with « What does this
     * controller hold? » (D31); consumed by [addedHandled]. A known device updated by a search is not.
     */
    val added: StateFlow<Long?> = _added

    fun addedHandled() {
        _added.value = null
    }

    private val _add = MutableStateFlow<AddState>(AddState.Closed)
    val add: StateFlow<AddState> = _add

    private var refreshJob: Job? = null

    /**
     * Opening the screen: one discovery by address to each known controller, then, if the phone is
     * on Wi-Fi, the broadcast search. A new call replaces a running one.
     */
    fun refresh(onWifi: Boolean) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val snapshot = repository.devices.first()
            _reach.value = snapshot.associate { it.id to Reach.Checking }
            _network.value = if (onWifi) NetworkSearch.Searching else NetworkSearch.NotOnWifi
            _found.value = emptyList()
            _moved.value = emptyList()
            for (device in snapshot) {
                val reachable = repository.probe(device)
                _reach.value = _reach.value + (device.id to if (reachable) Reach.Reachable else Reach.Unreachable)
            }
            if (!onWifi) return@launch
            when (val result = repository.scan()) {
                is ScanResult.Found -> {
                    _found.value = result.devices
                    _moved.value = result.moved
                    val foundMacs = result.devices.map { it.mac.text }.toSet()
                    // A known controller seen by the broadcast is reachable, whatever its probe said.
                    snapshot.filter { it.mac in foundMacs }.forEach { _reach.value = _reach.value + (it.id to Reach.Reachable) }
                    _network.value = NetworkSearch.Done
                }
                ScanResult.Failed -> _network.value = NetworkSearch.Failed
            }
        }
    }

    fun openAdd() {
        _add.value = AddState.Editing()
    }

    fun closeAdd() {
        _add.value = AddState.Closed
    }

    /** « Test and add »: nothing is saved unless the controller answers. */
    fun addByAddress(address: String, name: String) {
        if (_add.value == AddState.Testing) return
        val cleanAddress = address.trim()
        val cleanName = name.trim()
        if (Ipv4.parse(cleanAddress) == null) {
            _add.value = AddState.Editing(AddError.InvalidAddress)
            return
        }
        if (cleanName.isEmpty()) {
            _add.value = AddState.Editing(AddError.NameRequired)
            return
        }
        _add.value = AddState.Testing
        viewModelScope.launch {
            _add.value = when (val result = repository.addByAddress(cleanAddress, cleanName)) {
                is AddResult.Added -> {
                    _reach.value = _reach.value + (result.device.id to Reach.Reachable)
                    _added.value = result.device.id
                    AddState.Closed
                }
                is AddResult.AlreadyKnown -> {
                    _reach.value = _reach.value + (result.device.id to Reach.Reachable)
                    AddState.AlreadyKnown(result.device.name)
                }
                AddResult.InvalidAddress -> AddState.Editing(AddError.InvalidAddress)
                AddResult.NoReply -> AddState.Editing(AddError.NoReply)
                AddResult.NetworkError -> AddState.Editing(AddError.NetworkError)
            }
        }
    }

    /** One tap on a controller found by the broadcast search. */
    fun addNearby(device: Device, name: String) {
        viewModelScope.launch {
            val result = repository.addFound(device, name)
            val device = (result as? AddResult.Added)?.device ?: (result as? AddResult.AlreadyKnown)?.device
            if (device != null) _reach.value = _reach.value + (device.id to Reach.Reachable)
            if (result is AddResult.Added) _added.value = result.device.id
        }
    }
}
