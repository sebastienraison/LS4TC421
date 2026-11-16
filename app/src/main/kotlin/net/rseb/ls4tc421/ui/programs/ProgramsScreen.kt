package net.rseb.ls4tc421.ui.programs

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import net.rseb.ls4tc421.R
import net.rseb.ls4tc421.data.BankEntry
import net.rseb.ls4tc421.model.ImportError
import net.rseb.ls4tc421.model.ImportResult
import net.rseb.ls4tc421.model.ProgramFile
import net.rseb.ls4tc421.model.ProgramSpec
import net.rseb.ls4tc421.ui.LocalContainer
import net.rseb.ls4tc421.ui.formatDate
import net.rseb.ls4tc421.ui.send.SendSheet

/** Dialogs of the bank, one at a time. */
private sealed interface BankDialog {
    data object New : BankDialog
    data class Duplicate(val entry: BankEntry) : BankDialog
    data class Rename(val entry: BankEntry) : BankDialog
    data class Delete(val entry: BankEntry) : BankDialog
    data class Export(val entry: BankEntry) : BankDialog
    data class ImportFailed(val error: ImportError) : BankDialog
}

/** Screen 5: the programs stored in the phone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgramsScreen(onOpenEditor: (Long) -> Unit) {
    val container = LocalContainer.current
    val vm: ProgramsViewModel = viewModel(factory = viewModelFactory { initializer { ProgramsViewModel(container.programs) } })
    val bank by vm.bank.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var dialog by remember { mutableStateOf<BankDialog?>(null) }
    var topMenu by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf<Long?>(null) }
    var exporting by remember { mutableStateOf<ProgramSpec?>(null) }

    val importedText = stringResource(R.string.programs_imported)
    val exportedText = stringResource(R.string.programs_exported)
    val exportFailedText = stringResource(R.string.programs_export_failed)

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            when (val result = ProgramFiles.read(context, uri)) {
                is ImportResult.Ok -> {
                    vm.import(result.spec)
                    snackbar.showSnackbar(importedText.format(result.spec.name))
                }
                is ImportResult.Refused -> dialog = BankDialog.ImportFailed(result.error)
            }
        }
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ProgramFiles.MIME)) { uri ->
        val spec = exporting
        exporting = null
        if (uri == null || spec == null) return@rememberLauncherForActivityResult
        scope.launch {
            snackbar.showSnackbar(if (ProgramFiles.save(context, uri, spec)) exportedText else exportFailedText)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.programs_title)) },
                actions = {
                    IconButton(onClick = { topMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_more))
                    }
                    DropdownMenu(expanded = topMenu, onDismissRequest = { topMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.programs_import)) },
                            leadingIcon = { Icon(Icons.Outlined.FileDownload, contentDescription = null) },
                            onClick = { topMenu = false; importer.launch(ProgramFiles.IMPORT_TYPES) },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { dialog = BankDialog.New },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.programs_new)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        val list = bank
        if (list != null && list.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(inner).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.programs_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(inner)) {
                items(list.orEmpty(), key = { it.id }) { entry ->
                    BankRow(
                        entry = entry,
                        onOpen = { onOpenEditor(entry.id) },
                        onDuplicate = { dialog = BankDialog.Duplicate(entry) },
                        onRename = { dialog = BankDialog.Rename(entry) },
                        onSend = { sending = entry.id },
                        onExport = { dialog = BankDialog.Export(entry) },
                        onDelete = { dialog = BankDialog.Delete(entry) },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        BankDialog.New -> NewProgramDialog(
            programs = bank.orEmpty(),
            onCreate = { name, copyOf ->
                dialog = null
                scope.launch { vm.create(name, copyOf)?.let(onOpenEditor) }
            },
            onDismiss = { dialog = null },
        )
        is BankDialog.Duplicate -> NameDialog(
            title = stringResource(R.string.programs_duplicate_title),
            initial = stringResource(R.string.programs_copy_name, d.entry.spec.name),
            confirm = stringResource(R.string.programs_duplicate),
            onConfirm = { name -> dialog = null; scope.launch { vm.duplicate(d.entry.id, name) } },
            onDismiss = { dialog = null },
        )
        is BankDialog.Rename -> NameDialog(
            title = stringResource(R.string.programs_rename_title),
            initial = d.entry.spec.name,
            confirm = stringResource(R.string.action_save),
            onConfirm = { name -> dialog = null; scope.launch { vm.rename(d.entry.id, name) } },
            onDismiss = { dialog = null },
        )
        is BankDialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.programs_delete_title)) },
            text = { Text(stringResource(R.string.programs_delete_text, d.entry.spec.name)) },
            confirmButton = {
                TextButton(onClick = { dialog = null; scope.launch { vm.delete(d.entry.id) } }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
        is BankDialog.Export -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.programs_export_title, d.entry.spec.name)) },
            text = {
                Column {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.programs_export_file)) },
                        leadingContent = { Icon(Icons.Outlined.FileDownload, contentDescription = null) },
                        modifier = Modifier.clickable {
                            dialog = null
                            exporting = d.entry.spec
                            saver.launch(ProgramFile.fileName(d.entry.spec))
                        },
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.programs_export_share)) },
                        leadingContent = { Icon(Icons.Outlined.IosShare, contentDescription = null) },
                        modifier = Modifier.clickable {
                            dialog = null
                            scope.launch {
                                val intent = ProgramFiles.shareIntent(context, d.entry.spec)
                                if (intent != null) context.startActivity(intent) else snackbar.showSnackbar(exportFailedText)
                            }
                        },
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
        is BankDialog.ImportFailed -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.programs_import_failed_title)) },
            text = { Text(importErrorText(d.error)) },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.action_ok)) } },
        )
    }

    sending?.let { id -> SendSheet(programId = id, presetDeviceId = null, onDismiss = { sending = null }) }
}

@Composable
private fun importErrorText(error: ImportError): String = when (error) {
    ImportError.TooLarge -> stringResource(R.string.import_error_too_large)
    is ImportError.NotJson -> stringResource(R.string.import_error_not_json)
    ImportError.NotAProgram -> stringResource(R.string.import_error_not_a_program)
    is ImportError.UnsupportedVersion -> stringResource(R.string.import_error_version, error.version)
    is ImportError.Invalid -> stringResource(R.string.import_error_invalid, error.where, error.detail)
    is ImportError.InvalidName -> nameProblemText(error.problem)
} + "\n\n" + stringResource(R.string.import_error_nothing_saved)

@Composable
private fun BankRow(
    entry: BankEntry,
    onOpen: () -> Unit,
    onDuplicate: () -> Unit,
    onRename: () -> Unit,
    onSend: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiniCurve(entry.levels, Modifier.size(width = 96.dp, height = 48.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.spec.name, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.programs_modified, formatDate(entry.updatedAt)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            entry.devices.forEach { device ->
                AssistChip(
                    onClick = onSend,
                    label = { Text(device) },
                    leadingIcon = { Icon(Icons.Outlined.Lightbulb, contentDescription = null, Modifier.size(AssistChipDefaults.IconSize)) },
                )
            }
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_more))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                MenuEntry(R.string.programs_edit, Icons.Outlined.Edit) { menu = false; onOpen() }
                MenuEntry(R.string.programs_duplicate, Icons.Outlined.ContentCopy) { menu = false; onDuplicate() }
                MenuEntry(R.string.action_rename, Icons.Outlined.DriveFileRenameOutline) { menu = false; onRename() }
                MenuEntry(R.string.programs_send, Icons.Outlined.Upload) { menu = false; onSend() }
                MenuEntry(R.string.programs_export, Icons.Outlined.IosShare) { menu = false; onExport() }
                MenuEntry(R.string.action_delete, Icons.Outlined.Delete, danger = true) { menu = false; onDelete() }
            }
        }
    }
}

@Composable
private fun MenuEntry(text: Int, icon: ImageVector, danger: Boolean = false, onClick: () -> Unit) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    DropdownMenuItem(
        text = { Text(stringResource(text), color = color) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = color) },
        onClick = onClick,
    )
}

/** « New »: an empty program, or a copy of an existing one. */
@Composable
private fun NewProgramDialog(programs: List<BankEntry>, onCreate: (String, Long?) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var copy by rememberSaveable { mutableStateOf(false) }
    var source by rememberSaveable { mutableStateOf(programs.firstOrNull()?.id) }
    val error = nameError(name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.programs_new_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.program_name_label)) },
                    isError = error != null && name.isNotEmpty(),
                    supportingText = { if (error != null && name.isNotEmpty()) Text(error) },
                )
                Choice(stringResource(R.string.programs_new_empty), selected = !copy) { copy = false }
                Choice(stringResource(R.string.programs_new_copy), selected = copy, enabled = programs.isNotEmpty()) { copy = true }
                if (copy) {
                    Selector(
                        label = stringResource(R.string.programs_new_copy_of),
                        options = programs,
                        selected = programs.firstOrNull { it.id == source },
                        text = { it.spec.name },
                        onSelect = { source = it.id },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = error == null && (!copy || source != null),
                onClick = { onCreate(name.trim(), if (copy) source else null) },
            ) { Text(stringResource(R.string.programs_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun Choice(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(
            text,
            Modifier.padding(start = 8.dp),
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
