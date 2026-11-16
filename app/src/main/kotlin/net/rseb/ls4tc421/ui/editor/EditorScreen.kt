package net.rseb.ls4tc421.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import net.rseb.ls4tc421.PreviewRequest
import net.rseb.ls4tc421.R
import net.rseb.ls4tc421.data.DeviceEntity
import net.rseb.ls4tc421.model.CHANNELS
import net.rseb.ls4tc421.model.ChannelLink
import net.rseb.ls4tc421.model.ProgramSpec
import net.rseb.ls4tc421.model.QUARTERS
import net.rseb.ls4tc421.model.STEPS
import net.rseb.ls4tc421.model.quarterTime
import net.rseb.ls4tc421.model.stepTime
import net.rseb.ls4tc421.ui.LeaveGuard
import net.rseb.ls4tc421.ui.LocalContainer
import net.rseb.ls4tc421.ui.theme.ChannelPalette
import net.rseb.ls4tc421.ui.theme.ChannelSwatch
import net.rseb.ls4tc421.ui.theme.LocalChannelColors
import net.rseb.ls4tc421.ui.theme.isUnusedChannel
import net.rseb.ls4tc421.ui.programs.NameDialog
import net.rseb.ls4tc421.ui.programs.Selector
import net.rseb.ls4tc421.ui.send.SendSheet

/** Screens 6-7: the curve editor of program [id]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    id: Long,
    onBack: () -> Unit,
    onOpenPreview: () -> Unit,
    setLeaveGuard: (LeaveGuard?) -> Unit = {},
) {
    val container = LocalContainer.current
    val vm: EditorViewModel = viewModel(factory = viewModelFactory { initializer { EditorViewModel(id, container.programs) } })
    val state by vm.state.collectAsState()
    val devices by container.repository.devices.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var confirmLeave by remember { mutableStateOf(false) }
    // Where to go once the user has saved or discarded: null = back, else a bottom-bar destination.
    var pendingLeave by remember { mutableStateOf<(() -> Unit)?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var sendingId by remember { mutableStateOf<Long?>(null) }
    var choosingPreviewDevice by remember { mutableStateOf(false) }

    val savedText = stringResource(R.string.editor_saved)
    val noDeviceText = stringResource(R.string.editor_no_device)
    val nameNotSendableText = stringResource(R.string.program_name_not_sendable)

    LaunchedEffect(state.missing) { if (state.loaded && state.missing) onBack() }
    BackHandler(enabled = state.dirty) { confirmLeave = true }
    val leave = { if (state.dirty) confirmLeave = true else onBack() }
    val dirty = state.dirty
    DisposableEffect(dirty) {
        setLeaveGuard(if (dirty) { proceed -> pendingLeave = proceed; confirmLeave = true } else null)
        onDispose { setLeaveGuard(null) }
    }
    val finishLeave = {
        val next = pendingLeave
        pendingLeave = null
        if (next != null) next() else onBack()
    }

    val preview = { device: DeviceEntity ->
        container.previewRequest = PreviewRequest(state.spec.name, state.levels, device.id)
        onOpenPreview()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.spec.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(enabled = state.loaded) { renaming = true },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = leave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = vm::undo, enabled = state.canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.editor_undo))
                    }
                    IconButton(enabled = state.loaded, onClick = {
                        when {
                            // A name saved before D23 cannot be sent: no preview until it is renamed.
                            !state.spec.sendable -> scope.launch { snackbar.showSnackbar(nameNotSendableText) }
                            devices.isEmpty() -> scope.launch { snackbar.showSnackbar(noDeviceText) }
                            devices.size == 1 -> preview(devices.first())
                            else -> choosingPreviewDevice = true
                        }
                    }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.editor_preview))
                    }
                    IconButton(enabled = state.loaded, onClick = {
                        // An unsaved program is saved first: what is sent is what the bank holds.
                        scope.launch { sendingId = vm.save() }
                    }) {
                        Icon(Icons.Outlined.Upload, contentDescription = stringResource(R.string.programs_send))
                    }
                    IconButton(enabled = state.dirty, onClick = {
                        scope.launch {
                            vm.save()
                            snackbar.showSnackbar(savedText)
                        }
                    }) {
                        Icon(Icons.Outlined.Save, contentDescription = stringResource(R.string.action_save))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        if (!state.loaded) return@Scaffold
        EditorContent(state, vm, Modifier.padding(inner))
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false; pendingLeave = null },
            title = { Text(stringResource(R.string.editor_unsaved_title)) },
            text = { Text(stringResource(R.string.editor_unsaved_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    scope.launch { vm.save(); finishLeave() }
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmLeave = false; finishLeave() }) { Text(stringResource(R.string.editor_discard)) }
                    TextButton(onClick = { confirmLeave = false; pendingLeave = null }) { Text(stringResource(R.string.action_cancel)) }
                }
            },
        )
    }
    if (renaming) {
        NameDialog(
            title = stringResource(R.string.programs_rename_title),
            initial = state.spec.name,
            confirm = stringResource(R.string.action_ok),
            onConfirm = { renaming = false; vm.rename(it) },
            onDismiss = { renaming = false },
        )
    }
    if (choosingPreviewDevice) {
        AlertDialog(
            onDismissRequest = { choosingPreviewDevice = false },
            title = { Text(stringResource(R.string.editor_preview_on)) },
            text = {
                Column {
                    devices.forEach { device ->
                        Text(
                            device.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().clickable { choosingPreviewDevice = false; preview(device) }.padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosingPreviewDevice = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    sendingId?.let { programId -> SendSheet(programId = programId, presetDeviceId = null, onDismiss = { sendingId = null }) }
}

/** Tag of the symmetry axis slider, for tests. */
const val AXIS_SLIDER_TAG = "editor-axis-slider"

/**
 * The editor below the top bar (D20): the channel chips and the overview are pinned; the rest scrolls
 * under them, so the overview stays in sight while the axis or a link percentage is being set.
 */
@Composable
internal fun EditorContent(state: EditorState, vm: EditorViewModel, modifier: Modifier) {
    val spec = state.spec
    val active = state.active
    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
            ChannelChips(spec, active, vm::selectChannel)
            val windowEnd = state.windowStart + EditorRules.WINDOW_STEPS
            val windowDescription = stringResource(
                R.string.editor_overview_description,
                stepTime(state.windowStart),
                if (windowEnd >= STEPS) "24:00" else stepTime(windowEnd),
            )
            val axisDescription = spec.symmetryAxis?.let { stringResource(R.string.editor_overview_axis, quarterTime(it)) }
            DayOverview(
                levels = state.levels,
                active = active,
                windowStart = state.windowStart,
                symmetryAxis = spec.symmetryAxis,
                description = if (axisDescription == null) windowDescription else "$windowDescription $axisDescription",
                onMoveWindow = vm::moveWindow,
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(64.dp),
            )
        }
        EditorScrollingPart(state, vm, Modifier.weight(1f))
    }
}

/** Everything under the overview, in a column that scrolls: graph, hint, buttons, symmetry, links. */
@Composable
private fun EditorScrollingPart(state: EditorState, vm: EditorViewModel, modifier: Modifier) {
    val spec = state.spec
    val active = state.active
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
        CurveGraph(
            spec = spec,
            levels = state.levels,
            active = active,
            windowStart = state.windowStart,
            selected = state.selected,
            dragging = state.dragging,
            onGrab = vm::grab,
            onDrag = vm::dragBy,
            onRelease = vm::release,
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(252.dp),
        )
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Outlined.TouchApp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(
                stringResource(
                    when (state.hint) {
                        EditorRules.Hint.LINKED -> R.string.editor_hint_linked
                        EditorRules.Hint.HOLLOW -> R.string.editor_hint_hollow
                        EditorRules.Hint.DRAG -> R.string.editor_hint
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val add = state.addTarget
            val addDescription = add?.let { stringResource(R.string.editor_add_point_at, stepTime(it.step)) }
            OutlinedButton(
                onClick = vm::addPoint,
                enabled = add != null,
                modifier = Modifier.heightIn(min = 40.dp).semantics { if (addDescription != null) contentDescription = addDescription },
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.editor_add_point), modifier = Modifier.padding(start = 6.dp))
            }
            val deleteDescription = state.selected
                ?.takeIf { state.canDelete }
                ?.let { stringResource(R.string.editor_delete_point_at, stepTime(it)) }
            TextButton(
                onClick = vm::deleteSelected,
                enabled = state.canDelete,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.heightIn(min = 40.dp).semantics { if (deleteDescription != null) contentDescription = deleteDescription },
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.action_delete), modifier = Modifier.padding(start = 6.dp))
            }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
        SymmetryRow(spec, vm)
        HorizontalDivider()
        LinkRow(spec, active, vm)
    }
}

@Composable
private fun ChannelChips(spec: ProgramSpec, active: Int, onSelect: (Int) -> Unit) {
    val channelColors = LocalChannelColors.current
    val lit = remember(spec, channelColors) {
        ChannelPalette.unusedButLit(channelColors.map { it.isUnusedChannel }, spec.levels())
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (channel in 0 until CHANNELS) {
            val link = spec.links[channel]
            FilterChip(
                selected = channel == active,
                onClick = { onSelect(channel) },
                label = {
                    Text(
                        if (link == null) {
                            stringResource(R.string.editor_channel, channel + 1)
                        } else {
                            stringResource(R.string.editor_channel_linked, channel + 1, link.source + 1)
                        },
                    )
                },
                leadingIcon = {
                    ChannelSwatch(channelColors[channel], RoundedCornerShape(3.dp), Modifier.size(12.dp), lit = lit[channel], channel = channel)
                },
            )
        }
    }
}

@Composable
private fun SymmetryRow(spec: ProgramSpec, vm: EditorViewModel) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.editor_symmetry), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.editor_symmetry_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = spec.symmetric, onCheckedChange = vm::setSymmetry)
        }
        val axis = spec.symmetryAxis ?: return@Column
        var dragging by remember { mutableStateOf(false) }
        Text(
            stringResource(R.string.editor_axis, quarterTime(axis)),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Slider(
            value = axis.toFloat(),
            onValueChange = { value ->
                if (!dragging) {
                    dragging = true
                    vm.begin()
                }
                vm.update(vm.state.value.spec.withAxis(value.roundToInt()))
            },
            onValueChangeFinished = { dragging = false; vm.end() },
            valueRange = 0f..(QUARTERS - 1).toFloat(),
            steps = QUARTERS - 2,
            modifier = Modifier.testTag(AXIS_SLIDER_TAG),
        )
    }
}

@Composable
private fun LinkRow(spec: ProgramSpec, active: Int, vm: EditorViewModel) {
    val link = spec.links[active]
    val followers = (0 until CHANNELS).filter { spec.links[it]?.source == active }
    val sources = (0 until CHANNELS).filter { it != active && !spec.isLinked(it) }
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.editor_link, active + 1), style = MaterialTheme.typography.bodyLarge)
                Text(
                    when {
                        followers.isNotEmpty() -> stringResource(
                            R.string.editor_link_is_source,
                            followers.joinToString(", ") { "C${it + 1}" },
                        )
                        else -> stringResource(R.string.editor_link_hint)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = link != null,
                enabled = followers.isEmpty() && (link != null || sources.isNotEmpty()),
                onCheckedChange = { on -> vm.setLink(active, if (on) ChannelLink(sources.first(), 100) else null) },
            )
        }
        if (link == null) return@Column
        Selector(
            label = stringResource(R.string.editor_link_source),
            options = sources,
            selected = link.source,
            text = { stringResource(R.string.editor_channel, it + 1) },
            onSelect = { vm.setLink(active, link.copy(source = it)) },
            modifier = Modifier.padding(top = 8.dp),
        )
        var dragging by remember { mutableStateOf(false) }
        Text(
            stringResource(R.string.editor_link_percent, link.percent),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Slider(
            value = link.percent.toFloat(),
            onValueChange = { value ->
                if (!dragging) {
                    dragging = true
                    vm.begin()
                }
                vm.update(vm.state.value.spec.withLink(active, link.copy(percent = value.roundToInt())))
            },
            onValueChangeFinished = { dragging = false; vm.end() },
            valueRange = 0f..100f,
        )
    }
}
