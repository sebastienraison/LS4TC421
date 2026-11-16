package net.rseb.ls4tc421.ui.programs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ls4tc421.protocol.ProgramName
import net.rseb.ls4tc421.model.AppName
import net.rseb.ls4tc421.R
import net.rseb.ls4tc421.model.CHANNELS
import net.rseb.ls4tc421.model.STEPS
import net.rseb.ls4tc421.ui.theme.LocalChannelColors

/** Small curve of the 48 steps × 5 channels (bank rows, device card). */
@Composable
fun MiniCurve(levels: List<List<Int>>, modifier: Modifier = Modifier) {
    val background = MaterialTheme.colorScheme.surfaceContainerHigh
    val channelColors = LocalChannelColors.current
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(background)) {
        Canvas(Modifier.matchParentSize()) {
            val pad = 3.dp.toPx()
            val w = size.width - 2 * pad
            val h = size.height - 2 * pad
            for (channel in CHANNELS - 1 downTo 0) {
                if (levels.all { it[channel] == 0 }) continue
                val path = Path()
                for (step in 0..STEPS) {
                    val level = levels[step % STEPS][channel]
                    val p = Offset(pad + w * step / STEPS, pad + h * (1f - level / 100f))
                    if (step == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                drawPath(path, channelColors[channel], style = Stroke(width = 1.5.dp.toPx()))
            }
        }
    }
}

/**
 * Error text for a program name as typed (trimmed, as it will be saved), or null when it is fine.
 * The rule is the app's ([AppName], D23): printable ASCII, 1 to 7 characters.
 */
@Composable
fun nameError(name: String): String? = AppName.check(name.trim())?.let { nameProblemText(it) }

/** What is wrong with a name, for the user. */
@Composable
fun nameProblemText(problem: ProgramName.Problem): String = when (problem) {
    ProgramName.Problem.Empty -> stringResource(R.string.program_name_required)
    is ProgramName.Problem.TooLong -> stringResource(R.string.program_name_too_long, problem.length)
    is ProgramName.Problem.Refused -> stringResource(R.string.program_name_refused, problem.character)
}

/** A dialog that asks for a program name. */
@Composable
fun NameDialog(title: String, initial: String, confirm: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val error = nameError(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(stringResource(R.string.program_name_label)) },
                isError = error != null && text.isNotEmpty(),
                supportingText = { if (error != null && text.isNotEmpty()) Text(error) },
            )
        },
        confirmButton = { TextButton(enabled = error == null, onClick = { onConfirm(text.trim()) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** A read-only field that opens a list of choices. */
@Composable
fun <T> Selector(
    label: String,
    options: List<T>,
    selected: T?,
    text: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Box {
            OutlinedTextField(
                value = selected?.let { text(it) }.orEmpty(),
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                label = { Text(label) },
                trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
            )
            // Over the field, so that a tap anywhere opens the list.
            Box(Modifier.matchParentSize().clickable(enabled = enabled) { open = true })
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(text(option)) }, onClick = { open = false; onSelect(option) })
            }
        }
    }
}
