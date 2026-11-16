package net.rseb.ls4tc421.ui.detail

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import kotlinx.coroutines.delay
import net.rseb.ls4tc421.PreviewRequest
import net.rseb.ls4tc421.R
import net.rseb.ls4tc421.data.DeviceEntity
import net.rseb.ls4tc421.data.SLOT_COUNT
import net.rseb.ls4tc421.data.SendOutcome
import net.rseb.ls4tc421.data.SlotState
import net.rseb.ls4tc421.link.ProgramUploader
import net.rseb.ls4tc421.ui.LeadCircle
import net.rseb.ls4tc421.ui.LocalContainer
import net.rseb.ls4tc421.ui.SectionCard
import net.rseb.ls4tc421.ui.formatDateTime
import net.rseb.ls4tc421.ui.formatLong
import net.rseb.ls4tc421.ui.programs.MiniCurve
import net.rseb.ls4tc421.ui.send.Actions
import net.rseb.ls4tc421.ui.send.SendSheet
import net.rseb.ls4tc421.ui.send.UploadSheet
import net.rseb.ls4tc421.ui.slots.EmptyingPanel
import net.rseb.ls4tc421.ui.slots.EmptyingRun
import net.rseb.ls4tc421.ui.slots.EmptySlotsViewModel
import net.rseb.ls4tc421.ui.slots.GridMode
import net.rseb.ls4tc421.ui.slots.InService
import net.rseb.ls4tc421.ui.slots.SlotGrid
import net.rseb.ls4tc421.ui.slots.SlotLegend
import net.rseb.ls4tc421.ui.slots.SlotTile
import net.rseb.ls4tc421.ui.slots.slotStateLine
import net.rseb.ls4tc421.ui.slots.slotTitle

/** « Send a program » from this page: the program to choose, then the sheet; [slot] fixed from a slot's sheet. */
private data class SendTarget(val slot: Int?, val programId: Long? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailScreen(
    id: Long,
    onBack: () -> Unit,
    /** Opens the « Connection » screen (address and Wi-Fi network). */
    onOpenWifi: () -> Unit,
    /** Opens the preview screen (the request is set in the container first). */
    onOpenPreview: () -> Unit,
    /** Just added (D31): ask once « What does this controller hold? ». */
    askContents: Boolean = false,
    /** Debug-only additions below the Connection line (empty in release builds). */
    extras: @Composable () -> Unit = {},
) {
    val container = LocalContainer.current
    val vm: DeviceDetailViewModel =
        viewModel(factory = viewModelFactory { initializer { DeviceDetailViewModel(id, container.repository) } })
    val emptying: EmptySlotsViewModel = viewModel(
        key = "empty-$id",
        factory = viewModelFactory {
            initializer {
                EmptySlotsViewModel(id, container.settings.emptySlotNaming) { scope ->
                    ProgramUploader(container.clients, container.deviceDao, container.slotDao, container.frames, container.guard, scope)
                }
            }
        },
    )
    val state by vm.state.collectAsState()
    val clock by vm.clock.collectAsState()
    val slots by vm.slots.collectAsState()
    val selection by emptying.selection.collectAsState()
    val run by emptying.run.collectAsState()

    LaunchedEffect(state) { if (state is DetailState.Gone) onBack() }
    val device = (state as? DetailState.Present)?.device

    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var pickingDate by remember { mutableStateOf(false) }
    var pickedDate by remember { mutableStateOf<LocalDate?>(null) }
    var sendTarget by remember { mutableStateOf<SendTarget?>(null) }
    var touchedSlot by remember { mutableStateOf<Int?>(null) }
    var pickingActive by remember { mutableStateOf(false) }
    var askingContents by rememberSaveable { mutableStateOf(askContents) }

    val selecting = selection != null
    val writing = run is EmptyingRun.Writing
    // Selection mode: back leaves it, except while a slot is being written (« Cancel » first).
    BackHandler(enabled = selecting) { if (!writing) emptying.close() }

    val replay = { name: String, levels: List<List<Int>> ->
        container.previewRequest = PreviewRequest(name, levels, id)
        onOpenPreview()
    }

    Scaffold(
        topBar = {
            val chosen = selection
            if (chosen != null) {
                TopAppBar(
                    title = { Text(pluralStringResource(R.plurals.empty_selected, chosen.size, chosen.size)) },
                    navigationIcon = {
                        IconButton(enabled = !writing, onClick = emptying::close) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close))
                        }
                    },
                    actions = {
                        TextButton(enabled = run == EmptyingRun.Idle, onClick = emptying::selectAll) {
                            Text(stringResource(R.string.empty_select_all))
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(device?.name.orEmpty()) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        IconButton(onClick = { renaming = true }, enabled = device != null) {
                            Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.action_rename))
                        }
                        IconButton(onClick = { menuOpen = true }, enabled = device != null) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_delete)) },
                                onClick = { menuOpen = false; deleting = true },
                            )
                        }
                    },
                )
            }
        },
    ) { inner ->
        val view = slots
        if (device == null || view == null) return@Scaffold
        Column(
            Modifier.padding(inner).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val chosen = selection
            if (chosen != null) {
                Text(
                    stringResource(R.string.empty_touch_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SlotGrid(
                    tiles = view.tiles,
                    onTap = emptying::toggle,
                    mode = GridMode.SELECT_MANY,
                    marked = chosen,
                    enabled = run == EmptyingRun.Idle,
                )
                SlotLegend()
                val active = view.inService as? InService.Declared
                val template = stringResource(R.string.empty_slot_name)
                EmptyingPanel(
                    selection = chosen,
                    run = run,
                    activeSlot = active?.slot,
                    activeName = active?.name,
                    onEmpty = { emptying.empty(template) },
                    onCancel = emptying::cancel,
                    onResume = emptying::resume,
                    onClose = emptying::close,
                )
            } else {
                InServiceCard(
                    inService = view.inService,
                    onChange = { pickingActive = true },
                    onReplay = replay,
                )
                SlotsCard(
                    tiles = view.tiles,
                    onTap = { touchedSlot = it },
                    onEmpty = { emptying.open() },
                    onSend = { sendTarget = SendTarget(slot = null) },
                )
                ClockCard(
                    device = device,
                    clock = clock,
                    onSetToPhone = vm::setToPhoneTime,
                    onSetManually = { pickingDate = true },
                )
                ConnectionRow(device, onClick = onOpenWifi)
                extras()
            }
        }
    }

    if (renaming && device != null) {
        RenameDialog(
            initial = device.name,
            onConfirm = { vm.rename(it); renaming = false },
            onDismiss = { renaming = false },
        )
    }
    if (deleting && device != null) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.detail_delete_title)) },
            text = { Text(stringResource(R.string.detail_delete_text, device.name)) },
            confirmButton = {
                TextButton(onClick = { deleting = false; vm.delete() }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (pickingDate) {
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(
                    enabled = dateState.selectedDateMillis != null,
                    onClick = {
                        pickedDate = dateState.selectedDateMillis?.let {
                            Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                        }
                        pickingDate = false
                    },
                ) { Text(stringResource(R.string.action_next)) }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(state = dateState) }
    }
    sendTarget?.let { target ->
        val programId = target.programId
        if (programId == null) {
            if (device != null) {
                ProgramPicker(onPick = { sendTarget = target.copy(programId = it) }, onDismiss = { sendTarget = null })
            }
        } else {
            SendSheet(programId = programId, presetDeviceId = id, fixedSlot = target.slot, onDismiss = { sendTarget = null })
        }
    }
    val view = slots
    touchedSlot?.let { slot ->
        val tile = view?.tiles?.getOrNull(slot)
        val slotState = view?.states?.getOrNull(slot)
        if (tile != null && slotState != null) {
            SlotSheet(
                state = slotState,
                active = tile.active,
                onDismiss = { touchedSlot = null },
                onSendHere = { touchedSlot = null; sendTarget = SendTarget(slot = slot) },
                onReplay = { name, levels -> touchedSlot = null; replay(name, levels) },
                onDeclareActive = { touchedSlot = null; vm.setActiveSlot(slot) },
                onEmpty = { touchedSlot = null; emptying.open(setOf(slot)) },
                onForget = { touchedSlot = null; vm.forgetSlot(slot) },
            )
        }
    }
    if (pickingActive && view != null) {
        ActiveSlotSheet(
            tiles = view.tiles,
            onPick = { pickingActive = false; vm.setActiveSlot(it) },
            onDismiss = { pickingActive = false },
        )
    }
    if (askingContents && device != null) {
        ContentsDialog(
            onAnswer = { answer ->
                askingContents = false
                vm.answerContents(answer)
                if (answer == ContentsAnswer.EMPTY_NOW) emptying.open((0 until SLOT_COUNT).toSet())
            },
        )
    }
    pickedDate?.let { date ->
        TimeDialog(
            onConfirm = { time -> pickedDate = null; vm.setTime(LocalDateTime.of(date, time)) },
            onDismiss = { pickedDate = null },
        )
    }
}

@Composable
private fun CardLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * « In service, as you declared » (D16, D31): the slot the user declared, what the app knows of it,
 * « Change » and, if the app wrote its curve, « Replay on the light ». Without a declaration:
 * « Slot not indicated » and « Indicate ».
 */
@Composable
private fun InServiceCard(inService: InService, onChange: () -> Unit, onReplay: (String, List<List<Int>>) -> Unit) {
    SectionCard {
        Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (inService) {
                InService.NotDeclared -> {
                    CardLabel(stringResource(R.string.detail_in_service_title))
                    Text(stringResource(R.string.detail_in_service_none), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.detail_in_service_none_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onChange) { Text(stringResource(R.string.detail_in_service_indicate)) }
                    }
                }
                is InService.Declared -> {
                    val levels = inService.replay
                    Row(Modifier.padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            CardLabel(stringResource(R.string.detail_in_service_label))
                            val name = inService.name
                            val number = inService.slot + 1
                            Text(
                                name ?: stringResource(R.string.slot_number, number),
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                when (val s = inService.state) {
                                    is SlotState.Written -> stringResource(R.string.detail_in_service_written, number, formatDateTime(s.at))
                                    is SlotState.Emptied ->
                                        if (name != null) stringResource(R.string.detail_in_service_emptied, number, formatDateTime(s.at)) else slotStateLine(s)
                                    else -> slotStateLine(s)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (levels != null) MiniCurve(levels, Modifier.width(72.dp).height(44.dp))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                        TextButton(onClick = onChange) { Text(stringResource(R.string.detail_in_service_change)) }
                        if (levels != null) {
                            TextButton(onClick = { onReplay(inService.name.orEmpty(), levels) }) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, Modifier.size(18.dp))
                                Text(stringResource(R.string.slot_replay), Modifier.padding(start = 4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** « Controller slots »: the grid, its legend, « Empty… » and « Send a program » (D31). */
@Composable
private fun SlotsCard(tiles: List<SlotTile>, onTap: (Int) -> Unit, onEmpty: () -> Unit, onSend: () -> Unit) {
    SectionCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardLabel(stringResource(R.string.slots_label))
            SlotGrid(tiles = tiles, onTap = onTap)
            SlotLegend()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onEmpty) {
                    Icon(Icons.Outlined.CleaningServices, contentDescription = null, Modifier.size(18.dp))
                    Text(stringResource(R.string.slots_empty_action), Modifier.padding(start = 6.dp))
                }
                Button(onClick = onSend) {
                    Icon(Icons.Outlined.Upload, contentDescription = null, Modifier.size(18.dp))
                    Text(stringResource(R.string.detail_program_send), Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

/** Touching a slot (D31): everything about it in one place. On an unknown slot: send, declare, empty. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SlotSheet(
    state: SlotState,
    active: Boolean,
    onDismiss: () -> Unit,
    onSendHere: () -> Unit,
    onReplay: (String, List<List<Int>>) -> Unit,
    onDeclareActive: () -> Unit,
    onEmpty: () -> Unit,
    onForget: () -> Unit,
) {
    UploadSheet(sending = false, onClose = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(slotTitle(state), style = MaterialTheme.typography.titleLarge)
            Text(slotStateLine(state), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column {
            SheetAction(Icons.Outlined.Upload, stringResource(R.string.slot_send_here), onSendHere)
            val written = state as? SlotState.Written
            val levels = written?.levels
            if (written != null && levels != null) {
                SheetAction(Icons.Default.PlayArrow, stringResource(R.string.slot_replay)) { onReplay(written.name, levels) }
            }
            // Already the slot in service: nothing to declare.
            if (!active) SheetAction(Icons.Outlined.Lightbulb, stringResource(R.string.slot_declare_active), onDeclareActive)
            SheetAction(Icons.Outlined.CleaningServices, stringResource(R.string.slot_empty_this), onEmpty)
            if (state !is SlotState.Unknown) {
                SheetAction(Icons.AutoMirrored.Outlined.HelpOutline, stringResource(R.string.slot_forget), onForget)
            }
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * « Change » / « Indicate » (D16): the slot in service is chosen on the same grid; « I don't know »
 * withdraws the declaration. Not drawn on the mock-ups: the pilot's assumption.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActiveSlotSheet(tiles: List<SlotTile>, onPick: (Int?) -> Unit, onDismiss: () -> Unit) {
    UploadSheet(sending = false, onClose = onDismiss) {
        Text(stringResource(R.string.active_pick_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.active_pick_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SlotGrid(
            tiles = tiles,
            onTap = { onPick(it) },
            mode = GridMode.PICK_ONE,
            marked = tiles.filter { it.active }.map { it.slot }.toSet(),
        )
        SlotLegend()
        Actions {
            TextButton(onClick = { onPick(null) }) { Text(stringResource(R.string.active_pick_unknown)) }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    }
}

/** « What does this controller hold? » (D31), asked once after « Add ». « Later » = the first choice. */
@Composable
private fun ContentsDialog(onAnswer: (ContentsAnswer) -> Unit) {
    var choice by rememberSaveable { mutableStateOf(ContentsAnswer.KEEP) }
    val options = listOf(
        Triple(ContentsAnswer.KEEP, R.string.contents_keep, R.string.contents_keep_hint),
        Triple(ContentsAnswer.NEW_OR_EMPTIED, R.string.contents_new, R.string.contents_new_hint),
        Triple(ContentsAnswer.EMPTY_NOW, R.string.contents_empty, R.string.contents_empty_hint),
    )
    AlertDialog(
        onDismissRequest = { onAnswer(ContentsAnswer.KEEP) },
        title = { Text(stringResource(R.string.contents_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.contents_text), style = MaterialTheme.typography.bodyMedium)
                options.forEach { (answer, title, hint) ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = choice == answer, role = Role.RadioButton, onClick = { choice = answer })
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RadioButton(selected = choice == answer, onClick = null, modifier = Modifier.padding(top = 2.dp))
                        Column {
                            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(choice) }) { Text(stringResource(R.string.contents_continue)) } },
        dismissButton = { TextButton(onClick = { onAnswer(ContentsAnswer.KEEP) }) { Text(stringResource(R.string.contents_later)) } },
    )
}

/** « Connection »: address and Wi-Fi network, the address in clear; opens the Connection screen. */
@Composable
private fun ConnectionRow(device: DeviceEntity, onClick: () -> Unit) {
    SectionCard(onClick = onClick) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            LeadCircle { Icon(Icons.Outlined.Wifi, contentDescription = null) }
            Column(Modifier.weight(1f)) {
                CardLabel(stringResource(R.string.detail_connection_label))
                Text(stringResource(R.string.detail_connection_value), style = MaterialTheme.typography.bodyLarge)
                Text(device.address, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

/** « Send a program »: choose one in the bank. */
@Composable
private fun ProgramPicker(onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val bank by container.programs.bank.collectAsState(initial = null)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.detail_pick_program)) },
        text = {
            val list = bank
            if (list != null && list.isEmpty()) {
                Text(stringResource(R.string.detail_pick_program_empty))
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    list.orEmpty().forEach { entry ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(entry.id) }.padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MiniCurve(entry.levels, Modifier.width(64.dp).height(32.dp))
                            Text(entry.spec.name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ClockCard(device: DeviceEntity, clock: ClockAction, onSetToPhone: () -> Unit, onSetManually: () -> Unit) {
    var phoneNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            phoneNow = System.currentTimeMillis()
        }
    }
    val sending = clock == ClockAction.Sending
    SectionCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                LeadCircle { Icon(Icons.Outlined.Schedule, contentDescription = null) }
                Column {
                    Text(stringResource(R.string.detail_clock_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        device.timeSetAt?.let { stringResource(R.string.detail_clock_set_on, formatDateTime(it)) }
                            ?: stringResource(R.string.detail_clock_never),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.detail_phone_time, formatLong(phoneNow)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                TextButton(enabled = !sending, onClick = onSetManually) { Text(stringResource(R.string.detail_set_manually)) }
                Button(enabled = !sending, onClick = onSetToPhone) {
                    Icon(Icons.Default.Sync, contentDescription = null)
                    Text(stringResource(R.string.detail_set_phone_time), Modifier.padding(start = 8.dp))
                }
            }
            when (clock) {
                ClockAction.Idle -> Unit
                ClockAction.Sending -> Text(stringResource(R.string.detail_clock_sending), style = MaterialTheme.typography.bodyMedium)
                is ClockAction.Done -> {
                    val ok = clock.outcome == SendOutcome.Acknowledged
                    Text(
                        stringResource(
                            when (clock.outcome) {
                                SendOutcome.Acknowledged -> R.string.detail_clock_ok
                                SendOutcome.NoReply -> R.string.detail_clock_no_reply
                                SendOutcome.UnexpectedReply -> R.string.detail_clock_unexpected
                                SendOutcome.NetworkError, SendOutcome.Invalid -> R.string.detail_clock_network_error
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.detail_rename_title)) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(onConfirm: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val now = remember { LocalTime.now() }
    val state = rememberTimePickerState(
        initialHour = now.hour,
        initialMinute = now.minute,
        is24Hour = DateFormat.is24HourFormat(LocalContext.current),
    )
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = Modifier.width(IntrinsicSize.Min).height(IntrinsicSize.Min),
        ) {
            Column(Modifier.padding(24.dp)) {
                Text(
                    stringResource(R.string.detail_time_title),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(bottom = 20.dp),
                )
                TimePicker(state = state)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                    TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) {
                        Text(stringResource(R.string.action_set))
                    }
                }
            }
        }
    }
}
