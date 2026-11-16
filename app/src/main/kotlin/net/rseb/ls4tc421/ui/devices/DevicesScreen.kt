package net.rseb.ls4tc421.ui.devices

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import ls4tc421.protocol.Device
import net.rseb.ls4tc421.PhoneNetwork
import net.rseb.ls4tc421.R
import net.rseb.ls4tc421.data.DeviceEntity
import net.rseb.ls4tc421.ui.LeadCircle
import net.rseb.ls4tc421.ui.LocalContainer
import net.rseb.ls4tc421.ui.SectionTitle
import net.rseb.ls4tc421.ui.formatDate
import net.rseb.ls4tc421.ui.formatDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    onOpenDevice: (Long) -> Unit,
    /** A device was just added: its page opens and asks what it holds (D31). */
    onAdded: (Long) -> Unit,
) {
    val container = LocalContainer.current
    val vm: DevicesViewModel = viewModel(factory = viewModelFactory { initializer { DevicesViewModel(container.repository) } })
    val scope = rememberCoroutineScope()

    val known by vm.known.collectAsState()
    val reach by vm.reach.collectAsState()
    val network by vm.network.collectAsState()
    val nearby by vm.nearby.collectAsState()
    val moved by vm.moved.collectAsState()
    val anyAnswered by vm.anyAnswered.collectAsState()
    val add by vm.add.collectAsState()
    val added by vm.added.collectAsState()
    LaunchedEffect(added) {
        added?.let { vm.addedHandled(); onAdded(it) }
    }
    val snapshot = container.phone.snapshot

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        scope.launch {
            val fresh = container.refreshNetwork()
            vm.refresh(fresh.onWifi)
        }
    }
    val wanted = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= PhoneNetwork.LOCAL_NETWORK_SDK) add(PhoneNetwork.LOCAL_NETWORK_PERMISSION)
    }.toTypedArray()

    // Ask once, the first time the screen is shown.
    val asked by container.settings.permissionsAsked.collectAsState(initial = true)
    LaunchedEffect(asked) {
        if (!asked) {
            container.settings.markPermissionsAsked()
            launcher.launch(wanted)
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.refresh(container.phone.refresh().onWifi)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.devices_title)) },
                actions = {
                    IconButton(onClick = { vm.refresh(container.phone.refresh().onWifi) }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_search_again))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = vm::openAdd,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.fab_add_by_address)) },
            )
        },
    ) { inner ->
        LazyColumn(Modifier.padding(inner), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)) {
            item { SectionTitle(stringResource(R.string.devices_section_known), Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            if (known.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.devices_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(known, key = { it.id }) { device ->
                KnownDeviceRow(device, reach[device.id] ?: Reach.Checking, onClick = { onOpenDevice(device.id) })
            }

            item { SectionTitle(stringResource(R.string.devices_section_network), Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            item {
                val wifiLine = snapshot.ssid?.let { stringResource(R.string.network_on_wifi, it) }
                    ?: if (snapshot.onWifi) stringResource(R.string.network_on_wifi_unnamed) else null
                when (network) {
                    NetworkSearch.Searching -> ListItem(
                        leadingContent = { LeadCircle { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) } },
                        headlineContent = { Text(stringResource(R.string.network_searching)) },
                        supportingContent = wifiLine?.let { { Text(it) } },
                    )
                    NetworkSearch.NotOnWifi -> Text(
                        stringResource(R.string.network_not_wifi),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    NetworkSearch.Failed -> Text(
                        stringResource(R.string.network_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    // « None found » only when nobody answered: a known controller that answered
                    // shows as reachable above, and a change of address is said below.
                    NetworkSearch.Done -> if (!anyAnswered) {
                        Text(
                            stringResource(R.string.network_none_found),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            if (network == NetworkSearch.Done) {
                items(moved, key = { "moved-${it.id}" }) { change ->
                    Text(
                        stringResource(R.string.network_address_updated, change.name),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            if (network == NetworkSearch.Done) {
                items(nearby, key = { it.mac.text }) { device ->
                    val name = stringResource(R.string.devices_default_name, device.mac.text.takeLast(4))
                    NearbyDeviceRow(device, name, onAdd = { vm.addNearby(device, name) })
                }
            }
            if (!snapshot.localNetworkGranted) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            stringResource(R.string.permission_local_network),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { launcher.launch(wanted) }) { Text(stringResource(R.string.action_allow)) }
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.network_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
    }

    if (add != AddState.Closed) {
        AddByAddressDialog(state = add, onSubmit = vm::addByAddress, onDismiss = vm::closeAdd)
    }
}

@Composable
private fun KnownDeviceRow(device: DeviceEntity, reach: Reach, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            LeadCircle(highlighted = reach == Reach.Reachable, dimmed = reach == Reach.Unreachable) {
                if (reach == Reach.Checking) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Lightbulb, contentDescription = null)
                }
            }
        },
        headlineContent = { Text(device.name) },
        supportingContent = {
            Column {
                Text(
                    when (reach) {
                        Reach.Checking -> stringResource(R.string.device_status_checking)
                        Reach.Reachable -> stringResource(R.string.device_status_reachable)
                        Reach.Unreachable -> device.lastSeenAt
                            ?.let { stringResource(R.string.device_status_unreachable_since, formatDate(it)) }
                            ?: stringResource(R.string.device_status_unreachable_never)
                    },
                )
                device.timeSetAt?.let { Text(stringResource(R.string.device_clock_set_on, formatDateTime(it))) }
            }
        },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
    )
}

@Composable
private fun NearbyDeviceRow(device: Device, name: String, onAdd: () -> Unit) {
    ListItem(
        leadingContent = { LeadCircle(highlighted = true) { Icon(Icons.Outlined.Lightbulb, contentDescription = null) } },
        headlineContent = { Text(name) },
        supportingContent = { Text(device.address.hostAddress.orEmpty()) },
        trailingContent = { TextButton(onClick = onAdd) { Text(stringResource(R.string.network_add)) } },
    )
}

/** Screen 2: add by address. « Test and add » sends a discovery; nothing is saved without a reply. */
@Composable
private fun AddByAddressDialog(state: AddState, onSubmit: (String, String) -> Unit, onDismiss: () -> Unit) {
    var address by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    val testing = state == AddState.Testing
    val knownName = (state as? AddState.AlreadyKnown)?.name
    val error = (state as? AddState.Editing)?.error

    AlertDialog(
        onDismissRequest = { if (!testing) onDismiss() },
        title = { Text(stringResource(R.string.add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.add_text), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text(stringResource(R.string.add_field_address)) },
                    singleLine = true,
                    enabled = !testing && knownName == null,
                    isError = error == AddError.InvalidAddress,
                    supportingText = if (error == AddError.InvalidAddress) {
                        { Text(stringResource(R.string.add_error_invalid_address)) }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.add_field_name)) },
                    singleLine = true,
                    enabled = !testing && knownName == null,
                    isError = error == AddError.NameRequired,
                    supportingText = if (error == AddError.NameRequired) {
                        { Text(stringResource(R.string.add_error_name_required)) }
                    } else null,
                )
                when {
                    testing -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.add_testing))
                    }
                    error == AddError.NoReply -> Text(stringResource(R.string.add_error_no_reply), color = MaterialTheme.colorScheme.error)
                    error == AddError.NetworkError -> Text(stringResource(R.string.add_error_network), color = MaterialTheme.colorScheme.error)
                    knownName != null -> Text(stringResource(R.string.add_already_known, knownName))
                }
            }
        },
        confirmButton = {
            if (knownName != null) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
            } else {
                TextButton(enabled = !testing, onClick = { onSubmit(address, name) }) { Text(stringResource(R.string.add_confirm)) }
            }
        },
        dismissButton = {
            if (knownName == null) {
                TextButton(enabled = !testing, onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}
