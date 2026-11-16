package net.rseb.ls4tc421.ui.slots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.rseb.ls4tc421.R
import net.rseb.ls4tc421.link.ProgramUploader
import net.rseb.ls4tc421.ui.send.Actions
import net.rseb.ls4tc421.ui.send.UploadFailed
import net.rseb.ls4tc421.ui.send.stageName
import net.rseb.ls4tc421.ui.theme.LocalExtraColors

/**
 * The part of the device page under the grid while emptying (D30, D31): what will happen, then the
 * progress slot by slot, the failure (D22, « Resume ») or the result. [activeSlot] and [activeName]:
 * the slot declared in service, for « The light will go off ».
 */
@Composable
fun EmptyingPanel(
    selection: Set<Int>,
    run: EmptyingRun,
    activeSlot: Int?,
    activeName: String?,
    onEmpty: () -> Unit,
    onCancel: () -> Unit,
    onResume: () -> Unit,
    onClose: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (run) {
            EmptyingRun.Idle -> {
                if (selection.isNotEmpty()) WhatHappens(selection, activeSlot, activeName)
                Actions {
                    TextButton(onClick = onClose) { Text(stringResource(R.string.action_cancel)) }
                    Button(
                        enabled = selection.isNotEmpty(),
                        onClick = onEmpty,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Icon(Icons.Outlined.CleaningServices, contentDescription = null, Modifier.size(18.dp))
                        Text(
                            pluralStringResource(R.plurals.empty_action, selection.size, selection.size),
                            Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
            is EmptyingRun.Writing -> {
                // One write session (D32): 50 frames per slot, then the single `04 0A`.
                val frames = ProgramUploader.FRAMES_PER_PROGRAM
                Text(
                    stringResource(R.string.empty_progress, run.slot + 1, run.number, run.total),
                    style = MaterialTheme.typography.titleSmall,
                )
                LinearProgressIndicator(
                    progress = { ((run.number - 1) * frames + run.frames.completed) / (run.total * frames + 1).toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
                // Same place kept for the retry line as in the send sheet: the panel does not jump.
                Row(Modifier.fillMaxWidth().height(24.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    run.frames.retry?.let { retry ->
                        Icon(Icons.Outlined.Replay, contentDescription = null, Modifier.size(18.dp))
                        Text(
                            stringResource(R.string.send_retry, stageName(retry.stage), retry.attempt - 1, retry.attempts - 1),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (run.stopping) Text(stringResource(R.string.empty_stopping), style = MaterialTheme.typography.bodyMedium)
                Actions { TextButton(enabled = !run.stopping, onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } }
            }
            is EmptyingRun.Failed -> {
                Text(stringResource(R.string.empty_progress, run.slot + 1, run.number, run.total), style = MaterialTheme.typography.titleSmall)
                UploadFailed(
                    run.failure,
                    onClose = onClose,
                    onResume = onResume,
                    closeOnly = if (run.total == 1) {
                        stringResource(R.string.empty_failed_close_only_one)
                    } else {
                        stringResource(R.string.empty_failed_close_only, run.total)
                    },
                )
            }
            is EmptyingRun.Stopped -> {
                Text(stringResource(R.string.empty_stopped, run.emptied, run.total), style = MaterialTheme.typography.bodyLarge)
                Actions {
                    TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
                    Button(onClick = onResume) { Text(stringResource(R.string.send_resume)) }
                }
            }
            EmptyingRun.Busy -> {
                Text(stringResource(R.string.send_busy), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Actions {
                    TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
                    Button(onClick = onResume) { Text(stringResource(R.string.send_resume)) }
                }
            }
            is EmptyingRun.Done -> {
                Text(
                    pluralStringResource(R.plurals.empty_done, run.count, run.count),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Actions { Button(onClick = onClose) { Text(stringResource(R.string.action_ok)) } }
            }
        }
    }
}

/** The box above the buttons: what is written, the light warning (D30) and the duration. */
@Composable
private fun WhatHappens(selection: Set<Int>, activeSlot: Int?, activeName: String?) {
    val warning = lightWarning(selection, activeSlot)
    val lost = stringResource(if (selection.size == 1) R.string.empty_lost_one else R.string.empty_lost_many)
    val who = emptyingWho(selection)
    val duration = emptyingDurationText(selection.size)
    val text = when (warning) {
        LightWarning.ACTIVE_SELECTED -> {
            val light = activeName?.let { stringResource(R.string.empty_light_named, it) }
                ?: stringResource(R.string.empty_light_slot, (activeSlot ?: 0) + 1)
            "$light $who $duration"
        }
        LightWarning.NONE -> "$who $lost $duration"
        LightWarning.GENERIC -> {
            val generic = stringResource(if (selection.size == 1) R.string.empty_generic_one else R.string.empty_generic_many)
            "$who $lost $generic $duration"
        }
    }
    val extra = LocalExtraColors.current
    val bad = warning == LightWarning.ACTIVE_SELECTED
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (bad) MaterialTheme.colorScheme.errorContainer else extra.banner,
        contentColor = if (bad) MaterialTheme.colorScheme.onErrorContainer else extra.onBanner,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (warning == LightWarning.NONE) Icons.Outlined.Info else Icons.Filled.Warning, contentDescription = null)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
