package net.rseb.ls4tc421.ui.wifi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ls4tc421.protocol.WifiCipher
import ls4tc421.protocol.WifiSecurity
import net.rseb.ls4tc421.data.AddressResult
import net.rseb.ls4tc421.data.DeviceEntity
import net.rseb.ls4tc421.data.DeviceRepository
import net.rseb.ls4tc421.data.Ipv4
import net.rseb.ls4tc421.data.SendOutcome
import net.rseb.ls4tc421.data.WIFI_MAX_BYTES
import net.rseb.ls4tc421.data.utf8Size

enum class SecurityChoice { Wpa, Wpa2, Open }

sealed interface WifiSend {
    data object Idle : WifiSend
    data object Sending : WifiSend
    data class Done(val outcome: SendOutcome) : WifiSend
}

/**
 * Form of the Wi-Fi screen. The [password] exists only here, in memory: it is not saved in the
 * database, in preferences, in saved instance state or in any log, and it is cleared once the controller acknowledged it.
 */
data class WifiForm(
    val ssid: String = "",
    val password: String = "",
    val security: SecurityChoice = SecurityChoice.Wpa2,
    val cipher: WifiCipher = WifiCipher.AES,
    val send: WifiSend = WifiSend.Idle,
) {
    val open: Boolean get() = security == SecurityChoice.Open
    val ssidBytes: Int get() = utf8Size(ssid)
    val passwordBytes: Int get() = utf8Size(password)
    val ssidTooLong: Boolean get() = ssidBytes > WIFI_MAX_BYTES
    val passwordTooLong: Boolean get() = !open && passwordBytes > WIFI_MAX_BYTES

    /** Nothing may be sent if this is false: the 32-byte limit is checked before any frame. */
    val canSend: Boolean
        get() = ssid.isNotEmpty() && !ssidTooLong &&
            (open || (password.isNotEmpty() && !passwordTooLong)) &&
            send != WifiSend.Sending
}

class WifiViewModel(
    private val id: Long,
    private val repository: DeviceRepository,
    /** Last Wi-Fi network name seen on the phone (not the controller's own access point). */
    defaultSsid: Flow<String?>,
) : ViewModel() {

    private val _form = MutableStateFlow(WifiForm())
    val form: StateFlow<WifiForm> = _form

    /** The controller, for the address shown at the top of the screen (null while loading or once deleted). */
    val device: StateFlow<DeviceEntity?> = repository.device(id)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _address = MutableStateFlow<AddressEdit>(AddressEdit.Closed)
    val address: StateFlow<AddressEdit> = _address

    fun openAddress() {
        _address.value = AddressEdit.Editing()
    }

    fun closeAddress() {
        if (_address.value != AddressEdit.Testing) _address.value = AddressEdit.Closed
    }

    /** « Test and save »: the address is saved only if this controller (same MAC) answers there (D27). */
    fun changeAddress(text: String) {
        if (_address.value == AddressEdit.Testing) return
        val clean = text.trim()
        if (Ipv4.parse(clean) == null) {
            _address.value = AddressEdit.Editing(AddressError.InvalidAddress)
            return
        }
        _address.value = AddressEdit.Testing
        viewModelScope.launch {
            _address.value = when (repository.changeAddress(id, clean)) {
                AddressResult.Saved, AddressResult.UnknownDevice -> AddressEdit.Closed
                AddressResult.InvalidAddress -> AddressEdit.Editing(AddressError.InvalidAddress)
                AddressResult.NoReply -> AddressEdit.Editing(AddressError.NoReply)
                AddressResult.OtherController -> AddressEdit.Editing(AddressError.OtherController)
                AddressResult.NetworkError -> AddressEdit.Editing(AddressError.NetworkError)
            }
        }
    }

    private var ssidEdited = false

    init {
        viewModelScope.launch {
            defaultSsid.collect { remembered ->
                // Pre-fill only while the user has not typed anything.
                if (!ssidEdited && remembered != null) _form.update { it.copy(ssid = remembered) }
            }
        }
    }

    fun onSsidChange(text: String) {
        ssidEdited = true
        _form.update { it.copy(ssid = text, send = WifiSend.Idle) }
    }

    fun onPasswordChange(text: String) = _form.update { it.copy(password = text, send = WifiSend.Idle) }

    fun onSecurityChange(choice: SecurityChoice) = _form.update { it.copy(security = choice, send = WifiSend.Idle) }

    fun onCipherChange(cipher: WifiCipher) = _form.update { it.copy(cipher = cipher, send = WifiSend.Idle) }

    fun send() {
        val form = _form.value
        if (!form.canSend) return
        _form.update { it.copy(send = WifiSend.Sending) }
        viewModelScope.launch {
            val outcome = repository.joinWifi(
                id = id,
                ssid = form.ssid,
                password = if (form.open) null else form.password,
                security = if (form.security == SecurityChoice.Wpa) WifiSecurity.WPA else WifiSecurity.WPA2,
                cipher = form.cipher,
            )
            _form.update {
                // The key is forgotten once acknowledged; after a failure it stays in memory only,
                // so that "send again" does not mean typing it again.
                it.copy(
                    password = if (outcome == SendOutcome.Acknowledged) "" else it.password,
                    send = WifiSend.Done(outcome),
                )
            }
        }
    }
}
