package net.rseb.ls4tc421.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import java.util.Locale
import kotlin.math.roundToInt
import net.rseb.ls4tc421.R
import net.rseb.ls4tc421.link.PreviewEnd
import net.rseb.ls4tc421.link.PreviewPlayer
import net.rseb.ls4tc421.model.CHANNELS
import net.rseb.ls4tc421.ui.LocalContainer
import net.rseb.ls4tc421.ui.theme.LocalExtraColors
import net.rseb.ls4tc421.ui.theme.ChannelSwatch
import net.rseb.ls4tc421.ui.theme.LocalChannelColors

/**
 * Preview on the ramp (D4, D17): replays the day in 2 minutes. Ends with `03 08` on « End preview »,
 * when leaving the screen and when the app goes to the background. The screen stays on meanwhile.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val request = container.previewRequest
    if (request == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val vm: PreviewViewModel = viewModel(factory = viewModelFactory { initializer { PreviewViewModel(container, request) } })
    val player = vm.player
    val state by player.state.collectAsState()
    val devices by container.repository.devices.collectAsState(initial = emptyList())
    val deviceName = devices.firstOrNull { it.id == request.deviceId }?.name.orEmpty()
    var endRequested by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { player.start() }
    // Back on « End preview » once the controller acknowledged `03 08`.
    LaunchedEffect(state.end, endRequested) {
        if (endRequested && state.end == PreviewEnd.Acknowledged) onBack()
    }
    // Background or leaving the screen ends the preview; a rotation (the activity is recreated) does not.
    val activity = LocalContext.current.findActivity()
    val rotating = { activity?.isChangingConfigurations == true }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (!rotating()) player.exit() }
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            if (!rotating()) player.exit()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.preview_title, request.name)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Warning()
            Text(stringResource(R.string.preview_on_device, deviceName), style = MaterialTheme.typography.bodyMedium)
            Text(
                "%02d:%02d".format(Locale.ROOT, state.minuteOfDay / 60, state.minuteOfDay % 60),
                style = MaterialTheme.typography.displayMedium,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Levels(state.levels)
            Slider(
                value = state.position.toFloat(),
                onValueChange = { player.seek(player.startMinute + it.roundToInt()) },
                valueRange = 0f..PreviewPlayer.DAY.toFloat(),
                enabled = state.open,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                FilledIconButton(
                    onClick = { if (state.playing) player.pause() else player.play() },
                    enabled = state.open && state.end != PreviewEnd.Sending,
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(
                        if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = stringResource(if (state.playing) R.string.preview_pause else R.string.preview_play),
                    )
                }
            }
            Status(state.busy, state.sendError, state.nameRefused, state.open, state.end)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (!state.open && !state.busy && !state.nameRefused) {
                    OutlinedButton(onClick = { endRequested = false; player.start() }) { Text(stringResource(R.string.preview_restart)) }
                }
                Button(
                    enabled = state.end != PreviewEnd.Sending,
                    onClick = { endRequested = true; player.end() },
                ) { Text(stringResource(R.string.preview_end)) }
            }
        }
    }
}

@Composable
private fun Warning() {
    val extra = LocalExtraColors.current
    Surface(color = extra.banner, contentColor = extra.onBanner, shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null)
            Text(stringResource(R.string.preview_dimmer_warning), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Levels(levels: List<Int>) {
    val channelColors = LocalChannelColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (channel in 0 until CHANNELS) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ChannelSwatch(
                    channelColors[channel], RoundedCornerShape(3.dp), Modifier.size(14.dp),
                    lit = levels[channel] > 0, channel = channel,
                )
                Text(stringResource(R.string.editor_channel, channel + 1), style = MaterialTheme.typography.labelMedium)
                Text(stringResource(R.string.preview_level, levels[channel]), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun Status(busy: Boolean, sendError: Boolean, nameRefused: Boolean, open: Boolean, end: PreviewEnd) {
    val text = when {
        nameRefused -> stringResource(R.string.program_name_not_sendable)
        busy -> stringResource(R.string.preview_busy)
        sendError -> stringResource(R.string.preview_send_error)
        end == PreviewEnd.Sending -> stringResource(R.string.preview_ending)
        end == PreviewEnd.Acknowledged && !open -> stringResource(R.string.preview_ended)
        end is PreviewEnd.NoReply -> stringResource(R.string.preview_end_no_reply)
        end is PreviewEnd.Unexpected -> stringResource(R.string.preview_end_unexpected)
        end == PreviewEnd.NetworkError -> stringResource(R.string.preview_end_network)
        else -> null
    } ?: return
    val error = nameRefused || busy || sendError || end is PreviewEnd.NoReply || end is PreviewEnd.Unexpected || end == PreviewEnd.NetworkError
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
