package net.rseb.ls4tc421.ui.wifi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.rseb.ls4tc421.R

/** Why « Test and save » did not save the new address. */
enum class AddressError { InvalidAddress, NoReply, OtherController, NetworkError }

/** State of the « Change address » dialog (D27). */
sealed interface AddressEdit {
    data object Closed : AddressEdit
    data class Editing(val error: AddressError? = null) : AddressEdit
    data object Testing : AddressEdit
}

/**
 * « Change address »: same field and same input check as « Add by address ». « Test and save » sends
 * a discovery; nothing is saved unless this controller (same MAC) answers.
 */
@Composable
internal fun ChangeAddressDialog(initial: String, state: AddressEdit, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var address by rememberSaveable { mutableStateOf(initial) }
    val testing = state == AddressEdit.Testing
    val error = (state as? AddressEdit.Editing)?.error
    AlertDialog(
        onDismissRequest = { if (!testing) onDismiss() },
        title = { Text(stringResource(R.string.detail_change_address)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.add_text), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text(stringResource(R.string.add_field_address)) },
                    singleLine = true,
                    enabled = !testing,
                    isError = error == AddressError.InvalidAddress,
                    supportingText = if (error == AddressError.InvalidAddress) {
                        { Text(stringResource(R.string.add_error_invalid_address)) }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                when {
                    testing -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.add_testing))
                    }
                    error == AddressError.NoReply -> Text(stringResource(R.string.add_error_no_reply), color = MaterialTheme.colorScheme.error)
                    error == AddressError.OtherController -> Text(stringResource(R.string.address_error_other_controller), color = MaterialTheme.colorScheme.error)
                    error == AddressError.NetworkError -> Text(stringResource(R.string.add_error_network), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !testing, onClick = { onSubmit(address) }) { Text(stringResource(R.string.address_confirm)) }
        },
        dismissButton = {
            TextButton(enabled = !testing, onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

