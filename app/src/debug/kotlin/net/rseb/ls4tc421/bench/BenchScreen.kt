package net.rseb.ls4tc421.bench

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.PowerManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay
import ls4tc421.protocol.WriteStage
import net.rseb.ls4tc421.R
import net.rseb.ls4tc421.ui.LocalContainer
import net.rseb.ls4tc421.link.FrameLog
import net.rseb.ls4tc421.ui.SectionCard
import net.rseb.ls4tc421.ui.SectionTitle
import net.rseb.ls4tc421.ui.settings.shareExchangeLog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchScreen(id: Long, onBack: () -> Unit) {
    val container = LocalContainer.current
    val controller = remember(id) { Bench.controller(id, container) }
    val device by remember(id) { container.repository.device(id) }.collectAsState(initial = null)
    val preview by controller.preview.collectAsState()
    val writes by controller.writes.collectAsState()
    val log by container.frameLog.lines.collectAsState()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    // The ramp must never stay on a test state: leaving the screen or sending the app to the
    // background ends the preview. Not on a rotation, and not when the screen merely turns off.
    DisposableEffect(controller) {
        onDispose { if (activity?.isChangingConfigurations != true) controller.exit() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        val screenOn = context.getSystemService(PowerManager::class.java)?.isInteractive ?: true
        if (activity?.isChangingConfigurations != true && screenOn) controller.exit()
    }
    // Keep the screen on while a state is shown (J0c holds states for many minutes).
    val view = LocalView.current
    val previewing = preview.active != null
    DisposableEffect(previewing) {
        view.keepScreenOn = previewing
        onDispose { view.keepScreenOn = false }
    }

    var confirming by remember { mutableStateOf<BenchProgram?>(null) }
    val writing = writes.values.any { it.phase is WritePhase.Writing }
    val shareTitle = stringResource(R.string.bench_log_share)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bench_title, device?.name.orEmpty())) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { inner ->
        LazyColumn(
            Modifier.padding(inner).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.bench_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { StatesSection(preview, writing, controller) }
            item { SectionTitle(stringResource(R.string.bench_section_write)) }
            items(BenchProgram.entries) { which ->
                WriteCard(which, writes.getValue(which), writing, previewing, controller, onWrite = { confirming = which })
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SectionTitle(stringResource(R.string.bench_section_log))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(enabled = log.isNotEmpty(), onClick = {
                            shareExchangeLog(context, container.frameLog, shareTitle)
                        }) { Text(stringResource(R.string.bench_log_share)) }
                        TextButton(enabled = log.isNotEmpty(), onClick = { container.frameLog.clear() }) {
                            Text(stringResource(R.string.bench_log_clear))
                        }
                    }
                    Text(
                        if (log.isEmpty()) stringResource(R.string.bench_log_empty) else stringResource(R.string.bench_log_count, log.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(log.asReversed()) { line ->
                Text(
                    FrameLog.format(line),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (line.discarded) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }

    confirming?.let { which ->
        val slot = writes.getValue(which).slot
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(R.string.bench_confirm_title)) },
            text = { Text(stringResource(R.string.bench_confirm_text, which.program.name, which.label(), slot)) },
            confirmButton = {
                TextButton(onClick = { confirming = null; controller.write(which) }) { Text(stringResource(R.string.bench_write)) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private fun BenchProgram.label() = when (this) {
    BenchProgram.J0A -> "J0a"
    BenchProgram.J0B -> "J0b"
}

private fun hexCode(code: Int) = "%02X".format(code)

@Composable
private fun StateLabel.text(): String = when (this) {
    is StateLabel.All -> stringResource(R.string.bench_state_all, percent)
    is StateLabel.Only -> stringResource(R.string.bench_state_only, channel, percent)
    is StateLabel.Single -> stringResource(R.string.bench_state_single, channel, percent)
}

@Composable
private fun StatesSection(preview: PreviewState, writing: Boolean, controller: BenchController) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(preview.since) {
        while (preview.since != null) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.bench_section_states))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BenchPrograms.FIXED_STATES.forEach { state ->
                val label = state.label.text()
                if (preview.active == state) {
                    Button(onClick = { controller.select(state) }, enabled = !writing) { Text(label) }
                } else {
                    OutlinedButton(onClick = { controller.select(state) }, enabled = !writing) { Text(label) }
                }
            }
        }
        val active = preview.active
        val since = preview.since
        Text(
            if (active != null && since != null) {
                stringResource(
                    R.string.bench_preview_active,
                    active.label.text(),
                    active.levels.joinToString("/"),
                    stopwatch(now - since),
                )
            } else {
                stringResource(R.string.bench_preview_idle)
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(if (writing) R.string.bench_busy_writing else R.string.bench_preview_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { controller.endPreview() }, enabled = preview.end != EndStatus.Sending) {
                Text(stringResource(R.string.bench_end_preview))
            }
        }
        val endText = when (val end = preview.end) {
            EndStatus.None -> null
            EndStatus.Sending -> stringResource(R.string.bench_end_sending)
            is EndStatus.Acknowledged -> stringResource(R.string.bench_end_ok, FrameLog.time(end.at))
            is EndStatus.NoReply -> stringResource(R.string.bench_end_no_reply, end.attempts)
            is EndStatus.Unexpected -> stringResource(R.string.bench_end_unexpected, hexCode(end.received), hexCode(end.expected))
            EndStatus.NetworkError -> stringResource(R.string.bench_end_network)
        }
        endText?.let {
            val ok = preview.end is EndStatus.Acknowledged || preview.end == EndStatus.Sending
            Text(it, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
        if (preview.sendError) {
            Text(stringResource(R.string.bench_preview_send_error), color = MaterialTheme.colorScheme.error)
        }
    }
}

/** mm:ss (minutes keep counting past 59). */
private fun stopwatch(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
private fun WriteStage.text(): String = when (this) {
    WriteStage.Start -> stringResource(R.string.bench_stage_start)
    is WriteStage.Step -> stringResource(R.string.bench_stage_step, index, "%02d:%02d".format(index / 2, (index % 2) * 30))
    WriteStage.EndProgram -> stringResource(R.string.bench_stage_end_program)
    WriteStage.Finish -> stringResource(R.string.bench_stage_finish)
}

@Composable
private fun WriteCard(
    which: BenchProgram,
    state: WriteState,
    writing: Boolean,
    previewing: Boolean,
    controller: BenchController,
    onWrite: () -> Unit,
) {
    val busyHere = state.phase is WritePhase.Writing
    val canStart = !writing && !previewing
    SectionCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(
                    if (which == BenchProgram.J0A) R.string.bench_program_j0a else R.string.bench_program_j0b,
                    which.program.name,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(if (which == BenchProgram.J0A) R.string.bench_program_j0a_text else R.string.bench_program_j0b_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = !busyHere && state.slot > 0, onClick = { controller.setSlot(which, state.slot - 1) }) {
                    Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.bench_slot_down))
                }
                Text(stringResource(R.string.bench_slot, state.slot), style = MaterialTheme.typography.titleMedium)
                IconButton(enabled = !busyHere && state.slot < 19, onClick = { controller.setSlot(which, state.slot + 1) }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.bench_slot_up))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = canStart, onClick = onWrite) { Text(stringResource(R.string.bench_write)) }
                if (state.phase is WritePhase.Failed) {
                    OutlinedButton(enabled = canStart, onClick = { controller.resume(which) }) {
                        Text(stringResource(R.string.bench_resume))
                    }
                }
            }
            if (previewing && !writing) {
                Text(stringResource(R.string.bench_busy_preview), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when (val phase = state.phase) {
                WritePhase.Idle -> Unit
                is WritePhase.Writing -> Text(stringResource(R.string.bench_progress, phase.completed, phase.total))
                is WritePhase.Failed -> {
                    val reason = when (val r = phase.reason) {
                        is FailureReason.TimedOut -> stringResource(R.string.bench_failed_timeout, r.attempts)
                        is FailureReason.UnexpectedAck -> stringResource(R.string.bench_failed_unexpected, hexCode(r.received), hexCode(r.expected))
                        FailureReason.NetworkError -> stringResource(R.string.bench_failed_network)
                    }
                    Text(
                        stringResource(R.string.bench_failed, phase.number, phase.total, phase.stage.text(), reason),
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        stringResource(R.string.bench_resume_hint, state.slot),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is WritePhase.Done -> Text(
                    stringResource(R.string.bench_done, state.slot, FrameLog.time(phase.at)),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
