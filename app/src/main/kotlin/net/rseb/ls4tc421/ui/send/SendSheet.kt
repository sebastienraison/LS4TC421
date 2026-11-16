package net.rseb.ls4tc421.ui.send

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ls4tc421.protocol.WriteStage
import net.rseb.ls4tc421.R
import net.rseb.ls4tc421.link.UploadFailure
import net.rseb.ls4tc421.link.UploadPhase
import net.rseb.ls4tc421.link.UploadRequest
import net.rseb.ls4tc421.model.stepTime
import net.rseb.ls4tc421.ui.LocalContainer
import net.rseb.ls4tc421.ui.programs.Selector
import net.rseb.ls4tc421.ui.slots.GridMode
import net.rseb.ls4tc421.ui.slots.SlotGrid
import net.rseb.ls4tc421.ui.slots.SlotLegend
import net.rseb.ls4tc421.ui.slots.slotTiles

/**
 * Screen 8: modal sheet that sends a saved program to a device (D31). [presetDeviceId] comes from a
 * device page: the device is fixed. [fixedSlot] comes from a slot's sheet: the slot is fixed and the
 * grid is not shown. From the bank or the editor the device is chosen here, then the slot on the grid.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendSheet(programId: Long, presetDeviceId: Long?, onDismiss: () -> Unit, fixedSlot: Int? = null) {
    val container = LocalContainer.current
    val vm: SendViewModel = viewModel(
        key = "send-$programId-$presetDeviceId-$fixedSlot",
        factory = viewModelFactory { initializer { SendViewModel(programId, presetDeviceId, fixedSlot, container) } },
    )
    val program by vm.program.collectAsState()
    val devices by vm.devices.collectAsState()
    val deviceId by vm.deviceId.collectAsState()
    val slot by vm.slot.collectAsState()
    val slots by vm.slots.collectAsState()
    val phase by vm.phase.collectAsState()
    val declared by vm.declared.collectAsState()
    val sending = phase is UploadPhase.Sending
    val close = { vm.reset(); onDismiss() }

    UploadSheet(sending = sending, onClose = close) {
        Text(
            stringResource(R.string.send_title, program?.name.orEmpty()),
            style = MaterialTheme.typography.headlineSmall,
        )
        val list = devices.orEmpty()
        if (devices != null && list.isEmpty()) {
            Text(stringResource(R.string.send_no_device), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = close) { Text(stringResource(R.string.action_close)) }
            }
            return@UploadSheet
        }
        val device = list.firstOrNull { it.id == deviceId }
        if (vm.deviceLocked) {
            Text(
                if (vm.fixedSlot != null) {
                    stringResource(R.string.send_to_slot, device?.name.orEmpty(), slot + 1)
                } else {
                    stringResource(R.string.send_to_device, device?.name.orEmpty())
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Selector(
                label = stringResource(R.string.send_device),
                options = list,
                selected = device,
                text = { it.name },
                onSelect = vm::selectDevice,
                enabled = !sending,
            )
            Text(
                if (vm.fixedSlot != null) stringResource(R.string.send_slot_fixed, slot + 1) else stringResource(R.string.send_choose_slot),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (vm.fixedSlot == null) {
            slots?.let { states ->
                SlotGrid(
                    tiles = slotTiles(states, device?.activeSlot),
                    onTap = vm::selectSlot,
                    mode = GridMode.PICK_ONE,
                    marked = setOf(slot),
                    enabled = !sending,
                )
                SlotLegend()
            }
        }
        Hint(stringResource(R.string.send_slot_hint))

        // A program saved before D23 may have a name the controller refuses: shown, never sent.
        val nameSendable = program?.sendable != false
        val sendLabel = stringResource(R.string.send_action_slot, slot + 1)
        if (!nameSendable && phase !is UploadPhase.NameRefused) NameNotSendable()
        when (val p = phase) {
            UploadPhase.Idle -> Actions {
                TextButton(onClick = close) { Text(stringResource(R.string.action_cancel)) }
                Button(enabled = program != null && device != null && nameSendable, onClick = vm::send) { Text(sendLabel) }
            }
            UploadPhase.Busy -> {
                Text(stringResource(R.string.send_busy), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Actions {
                    TextButton(onClick = close) { Text(stringResource(R.string.action_close)) }
                    Button(enabled = nameSendable, onClick = vm::send) { Text(sendLabel) }
                }
            }
            UploadPhase.NameRefused -> {
                NameNotSendable()
                Actions {
                    TextButton(onClick = close) { Text(stringResource(R.string.action_close)) }
                    Button(enabled = false, onClick = {}) { Text(sendLabel) }
                }
            }
            is UploadPhase.Sending -> UploadProgress(p, onCancel = vm::cancel)
            is UploadPhase.Failed -> SendFailed(p, vm.upload, onClose = close, onResume = vm::resume)
            is UploadPhase.Done -> {
                Text(
                    stringResource(R.string.send_done, p.slot + 1),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(stringResource(R.string.send_select_reminder, p.name), style = MaterialTheme.typography.bodyLarge)
                if (declared == p.slot) {
                    Text(stringResource(R.string.send_declared, p.slot + 1), style = MaterialTheme.typography.bodyMedium)
                    Actions { Button(onClick = close) { Text(stringResource(R.string.action_ok)) } }
                } else {
                    Actions {
                        TextButton(onClick = close) { Text(stringResource(R.string.action_ok)) }
                        Button(onClick = vm::declareInService) { Text(stringResource(R.string.send_declare_active)) }
                    }
                }
            }
        }
    }
}

/**
 * The bottom sheet of « Send » and of the slot sheets: no swipe-away while frames are being sent
 * (« Cancel » first), same padding and spacing; its content scrolls (the slot grid is tall).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UploadSheet(sending: Boolean, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val sendingNow by rememberUpdatedState(sending)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !sendingNow },
    )
    ModalBottomSheet(onDismissRequest = { if (!sending) onClose() }, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** Frames acknowledged, the resend in progress if any, and « Cancel ». */
@Composable
internal fun UploadProgress(p: UploadPhase.Sending, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.send_in_progress), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.send_count, p.completed, p.total), style = MaterialTheme.typography.titleSmall)
    }
    LinearProgressIndicator(progress = { p.completed / p.total.toFloat() }, modifier = Modifier.fillMaxWidth())
    // The retry line keeps its place for the whole upload, empty when there is no retry:
    // the first step is often retried once, and a line that came and went made the sheet jump.
    Row(
        Modifier.fillMaxWidth().height(24.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        p.retry?.let { retry ->
            Icon(Icons.Outlined.Replay, contentDescription = null, Modifier.size(18.dp))
            Text(
                stringResource(R.string.send_retry, stageName(retry.stage), retry.attempt - 1, retry.attempts - 1),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    Actions { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } }
}

/**
 * Where and why the upload stopped, « Close » and, unless the device is unusable, « Resume ».
 * [closeOnly]: what to say instead when only the `04 0A` that ends the exchange failed (D32, see
 * [onlyCloseFailed]): what was written is in place, and the restart advice does not apply.
 */
@Composable
internal fun UploadFailed(p: UploadPhase.Failed, onClose: () -> Unit, onResume: () -> Unit, closeOnly: String? = null) {
    Text(
        if (closeOnly != null && onlyCloseFailed(p)) closeOnly else failureText(p),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
    )
    Actions {
        TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
        if (p.reason != UploadFailure.UnknownDevice) {
            Button(onClick = onResume) { Text(stringResource(R.string.send_resume)) }
        }
    }
}

/** The failure of a send: [upload] is what was sent, named when only the closing `04 0A` failed (D32). */
@Composable
internal fun SendFailed(p: UploadPhase.Failed, upload: UploadRequest?, onClose: () -> Unit, onResume: () -> Unit) {
    UploadFailed(
        p,
        onClose = onClose,
        onResume = onResume,
        closeOnly = upload?.let { stringResource(R.string.send_failed_close_only, it.program.name, it.slot + 1) },
    )
}

@Composable
private fun NameNotSendable() {
    Text(
        stringResource(R.string.program_name_not_sendable),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
internal fun Actions(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) { content() }
}

@Composable
internal fun Hint(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Outlined.Info, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * « Step 30 (14:30) », « The opening frame », « The end-of-program frame » (`03 08`), « The
 * end-of-exchange frame » (`04 0A`, once per write session, D32).
 */
@Composable
fun stageName(stage: WriteStage): String = when (stage) {
    WriteStage.Start -> stringResource(R.string.send_stage_start)
    is WriteStage.Step -> stringResource(R.string.send_stage_step, stage.index + 1, stepTime(stage.index))
    WriteStage.EndProgram -> stringResource(R.string.send_stage_end_program)
    WriteStage.Finish -> stringResource(R.string.send_stage_end_exchange)
}

/** Where and why the upload failed; for a missing acknowledgement or a network error, what to do (D22). */
@Composable
private fun failureText(failed: UploadPhase.Failed): String {
    val where = stringResource(R.string.send_failed_where, stageName(failed.stage), failed.number, failed.total)
    val why = when (val r = failed.reason) {
        is UploadFailure.TimedOut -> stringResource(R.string.send_failed_timeout, r.attempts)
        is UploadFailure.UnexpectedAck -> stringResource(R.string.send_failed_unexpected, "%02X".format(r.received), "%02X".format(r.expected))
        UploadFailure.NetworkError -> stringResource(R.string.send_failed_network)
        UploadFailure.UnknownDevice -> stringResource(R.string.send_failed_unknown_device)
        UploadFailure.Cancelled -> stringResource(R.string.send_failed_cancelled)
    }
    return if (showsRestartHint(failed.reason)) "$where $why\n" + stringResource(R.string.send_failed_restart_hint) else "$where $why"
}

/**
 * D32: the failure is at the `04 0A` that ends the exchange, so the `03 08` of the last program was
 * acknowledged before it: everything is written, only the closing was not confirmed.
 */
internal fun onlyCloseFailed(failed: UploadPhase.Failed): Boolean = failed.stage == WriteStage.Finish

/** D22: the restart advice follows a missing acknowledgement or a network error, nothing else. */
internal fun showsRestartHint(reason: UploadFailure): Boolean =
    reason is UploadFailure.TimedOut || reason == UploadFailure.NetworkError
