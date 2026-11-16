package net.rseb.ls4tc421.ui.wifi

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import ls4tc421.protocol.WifiCipher
import net.rseb.ls4tc421.R
import net.rseb.ls4tc421.data.SendOutcome
import net.rseb.ls4tc421.ui.LocalContainer
import net.rseb.ls4tc421.ui.SectionCard
import net.rseb.ls4tc421.ui.theme.LocalExtraColors

/**
 * Screen 4, « Connection » (D31): first the controller's Wi-Fi network (written on the controller;
 * « Change network… » opens the form), then the address the app uses (D27), with its warning. The key
 * is typed, sent and forgotten.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiScreen(id: Long, onBack: () -> Unit) {
    val container = LocalContainer.current
    val vm: WifiViewModel = viewModel(
        factory = viewModelFactory { initializer { WifiViewModel(id, container.repository, container.settings.lastSsid) } },
    )
    val form by vm.form.collectAsState()
    val device by vm.device.collectAsState()
    val addressEdit by vm.address.collectAsState()
    val scope = rememberCoroutineScope()
    // Not rememberSaveable: the key must never end up in saved instance state.
    var showPassword by remember { mutableStateOf(false) }
    var changingNetwork by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        scope.launch { container.refreshNetwork() }
    }
    val snapshot = container.phone.snapshot

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.wifi_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { inner ->
        Column(
            Modifier.padding(inner).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            NetworkSection(open = changingNetwork, onOpen = { changingNetwork = true })
            if (changingNetwork) {
                HelpBanner()

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = form.ssid,
                        onValueChange = vm::onSsidChange,
                        label = { Text(stringResource(R.string.wifi_field_network)) },
                        singleLine = true,
                        isError = form.ssidTooLong,
                        supportingText = if (form.ssidTooLong) {
                            { Text(stringResource(R.string.wifi_error_too_long, form.ssidBytes)) }
                        } else null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!snapshot.locationGranted && form.ssid.isEmpty()) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.wifi_prefill_permission),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) {
                                Text(stringResource(R.string.action_allow))
                            }
                        }
                    } else if (form.ssid.isNotEmpty()) {
                        Text(
                            stringResource(R.string.wifi_prefill_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (!form.open) {
                    OutlinedTextField(
                        value = form.password,
                        onValueChange = vm::onPasswordChange,
                        label = { Text(stringResource(R.string.wifi_field_password)) },
                        singleLine = true,
                        isError = form.passwordTooLong,
                        supportingText = if (form.passwordTooLong) {
                            { Text(stringResource(R.string.wifi_error_too_long, form.passwordBytes)) }
                        } else null,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = stringResource(if (showPassword) R.string.wifi_hide_password else R.string.wifi_show_password),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.wifi_security_label), style = MaterialTheme.typography.titleSmall)
                    val securities = listOf(
                        SecurityChoice.Wpa to R.string.wifi_security_wpa,
                        SecurityChoice.Wpa2 to R.string.wifi_security_wpa2,
                        SecurityChoice.Open to R.string.wifi_security_open,
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        securities.forEachIndexed { index, (choice, label) ->
                            SegmentedButton(
                                selected = form.security == choice,
                                onClick = { vm.onSecurityChange(choice) },
                                shape = SegmentedButtonDefaults.itemShape(index, securities.size),
                            ) { Text(stringResource(label)) }
                        }
                    }
                    if (!form.open) {
                        val ciphers = listOf(WifiCipher.AES to R.string.wifi_cipher_aes, WifiCipher.TKIP to R.string.wifi_cipher_tkip)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            ciphers.forEachIndexed { index, (cipher, label) ->
                                SegmentedButton(
                                    selected = form.cipher == cipher,
                                    onClick = { vm.onCipherChange(cipher) },
                                    shape = SegmentedButtonDefaults.itemShape(index, ciphers.size),
                                ) { Text(stringResource(label)) }
                            }
                        }
                    }
                }

                if (!form.open) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            stringResource(R.string.wifi_clear_text_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Button(onClick = vm::send, enabled = form.canSend, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (form.send == WifiSend.Sending) R.string.wifi_sending else R.string.wifi_send))
                }

                (form.send as? WifiSend.Done)?.let { done ->
                    val ok = done.outcome == SendOutcome.Acknowledged
                    Text(
                        stringResource(
                            when (done.outcome) {
                                SendOutcome.Acknowledged -> R.string.wifi_result_ok
                                SendOutcome.NoReply -> R.string.wifi_result_no_reply
                                SendOutcome.UnexpectedReply -> R.string.wifi_result_unexpected
                                SendOutcome.NetworkError -> R.string.wifi_result_network_error
                                SendOutcome.Invalid -> R.string.wifi_result_invalid
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }

            device?.let { AddressSection(it.address, onChange = vm::openAddress) }
        }
    }
    val current = device
    if (addressEdit != AddressEdit.Closed && current != null) {
        ChangeAddressDialog(
            initial = current.address,
            state = addressEdit,
            onSubmit = vm::changeAddress,
            onDismiss = vm::closeAddress,
        )
    }
}

/** The shared card, with this screen's inner padding (text and a button). */
@Composable
private fun WifiCard(content: @Composable () -> Unit) {
    SectionCard {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            content()
        }
    }
}

@Composable
private fun CardLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** « Controller Wi-Fi network »: written on the controller; « Change network… » opens the form below. */
@Composable
private fun NetworkSection(open: Boolean, onOpen: () -> Unit) {
    WifiCard {
        CardLabel(stringResource(R.string.wifi_network_title))
        Text(stringResource(R.string.wifi_network_headline), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.wifi_network_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(enabled = !open, onClick = onOpen) { Text(stringResource(R.string.wifi_change_network)) }
        }
    }
}

/** The address the app uses to reach the controller, its warning, and « Change address » (D27). */
@Composable
private fun AddressSection(address: String, onChange: () -> Unit) {
    val extra = LocalExtraColors.current
    WifiCard {
        CardLabel(stringResource(R.string.wifi_address_title))
        Text(address, style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.wifi_address_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = extra.banner,
            contentColor = extra.onBanner,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, end = 8.dp),
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Filled.Warning, contentDescription = null)
                Text(stringResource(R.string.wifi_address_warning), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onChange) { Text(stringResource(R.string.detail_change_address)) }
        }
    }
}

@Composable
private fun HelpBanner() {
    val extra = LocalExtraColors.current
    Surface(shape = RoundedCornerShape(12.dp), color = extra.banner, contentColor = extra.onBanner, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.wifi_banner_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.wifi_banner_body), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.wifi_banner_warning), style = MaterialTheme.typography.titleSmall)
        }
    }
}
