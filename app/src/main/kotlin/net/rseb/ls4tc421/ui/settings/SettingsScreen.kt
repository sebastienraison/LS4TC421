package net.rseb.ls4tc421.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import net.rseb.ls4tc421.BuildConfig
import net.rseb.ls4tc421.R
import net.rseb.ls4tc421.link.FrameLog
import net.rseb.ls4tc421.model.CHANNELS
import net.rseb.ls4tc421.model.EmptySlot
import net.rseb.ls4tc421.model.EmptySlotNaming
import net.rseb.ls4tc421.ui.LocalContainer
import net.rseb.ls4tc421.ui.Section
import net.rseb.ls4tc421.ui.theme.ChannelPalette
import net.rseb.ls4tc421.ui.theme.ChannelSwatch
import net.rseb.ls4tc421.ui.theme.isUnusedChannel

/**
 * Screen 10: channel colours (D24), the name of an emptied slot (D30), the exchange log (D28), then
 * the disclaimer and the credits (D10), version and copyright last.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) }) { inner ->
        Column(
            Modifier
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            ChannelColorsSection()
            EmptySlotSection()
            ExchangeLogSection()
            DisclaimerSection()
            CreditsSection(onOpenUrl = { url -> openInBrowser(context, url) })
            // Version, then copyright, centred under the cards (as in Bivouac). The copyright year is
            // the build year; the line is left out if BUILD_DATE has an unexpected form.
            Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 18.dp)) {
                CenteredNote(stringResource(R.string.credits_version, BuildConfig.VERSION_NAME, BuildConfig.BUILD_DATE))
                buildYearOrNull(BuildConfig.BUILD_DATE)?.let { year ->
                    CenteredNote(stringResource(R.string.credits_copyright, year))
                }
            }
        }
    }
}

@Composable
private fun CenteredNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** « Disclaimer » (D10): non-affiliation, interoperability, no warranty. Above the credits. */
@Composable
private fun DisclaimerSection() {
    Section(stringResource(R.string.credits_disclaimer_title), Modifier.padding(top = 8.dp)) {
        Text(
            stringResource(R.string.credits_notices_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

/**
 * « Credits », after Bivouac's `CreditsSection`: label / value rows (libraries as plain text, licence),
 * a divider, then two full-width links that open the browser. The privacy address is a
 * string resource, so it follows the language; GitHub's does not change with it.
 */
@Composable
private fun CreditsSection(onOpenUrl: (String) -> Unit) {
    Section(stringResource(R.string.credits_section), Modifier.padding(top = 16.dp)) {
        Column(Modifier.padding(top = 6.dp)) {
            CreditRow(stringResource(R.string.credits_libraries), stringResource(R.string.credits_libraries_value), primary = false)
            CreditRow(stringResource(R.string.credits_license), stringResource(R.string.credits_license_value), primary = true)
        }
        HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
        CreditLinkRow(stringResource(R.string.credits_github_link), stringResource(R.string.credits_github_url), onOpenUrl)
        CreditLinkRow(stringResource(R.string.credits_privacy_link), stringResource(R.string.credits_privacy_url), onOpenUrl)
        Spacer(Modifier.height(4.dp))
    }
}

/** Label on the left, value on the right; a long value wraps, right-aligned (Bivouac's `CreditRow`, without links). */
@Composable
private fun CreditRow(label: String, value: String, primary: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A full-width link row: label in the primary colour, « open in browser » icon on the right (Bivouac's `CreditLinkRow`). */
@Composable
private fun CreditLinkRow(label: String, url: String, onOpenUrl: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenUrl(url) }
            .padding(horizontal = 12.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        Icon(
            Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Opens [url] in the browser; without one, nothing happens. */
private fun openInBrowser(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (_: ActivityNotFoundException) {
        // No browser on the phone: nothing to open.
    }
}

/**
 * The copyright year: the year of [buildDate] (BuildConfig.BUILD_DATE, « yyyy-MM-dd »), as in Bivouac.
 * Any other form gives null, and the copyright line is not shown: better no year than a wrong one.
 */
internal fun buildYearOrNull(buildDate: String): String? = BUILD_DATE_FORMAT.matchEntire(buildDate)?.groupValues?.get(1)

private val BUILD_DATE_FORMAT = Regex("""(\d{4})-\d{2}-\d{2}""")

/** The name written by « Empty a slot » (D30): numbered (the default) or as new. Two exclusive choices. */
@Composable
private fun EmptySlotSection() {
    val container = LocalContainer.current
    val settings = container.settings
    val naming by settings.emptySlotNaming.collectAsState(initial = EmptySlotNaming.NUMBERED)
    // The example uses slot 3, as the app shows it (protocol slot 2).
    val example = EmptySlot.name(2, EmptySlotNaming.NUMBERED, stringResource(R.string.empty_slot_name))

    Section(stringResource(R.string.settings_empty_slot), Modifier.padding(top = 8.dp)) {
        Column(Modifier.selectableGroup().padding(vertical = 4.dp)) {
            for (option in EmptySlotNaming.entries) {
                val label = when (option) {
                    EmptySlotNaming.NUMBERED -> stringResource(R.string.settings_empty_slot_numbered, example)
                    EmptySlotNaming.AS_NEW -> stringResource(R.string.settings_empty_slot_as_new)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = naming == option, role = Role.RadioButton) {
                            container.appScope.launch { settings.setEmptySlotNaming(option) }
                        }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                ) {
                    RadioButton(selected = naming == option, onClick = null)
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

/**
 * The exchange log (D28): what it is, how many lines it holds, « Share » through Android's share
 * sheet (the user chooses where it goes; the app sends nothing itself) and « Clear ».
 */
@Composable
private fun ExchangeLogSection() {
    val container = LocalContainer.current
    val log = container.frameLog
    val lines by log.lines.collectAsState()
    val context = LocalContext.current
    val title = stringResource(R.string.log_section)

    Section(title, Modifier.padding(top = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.log_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(R.string.log_count, lines.size), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                TextButton(enabled = lines.isNotEmpty(), onClick = log::clear) { Text(stringResource(R.string.log_clear)) }
                OutlinedButton(enabled = lines.isNotEmpty(), onClick = { shareExchangeLog(context, log, title) }) {
                    Icon(Icons.Outlined.Share, contentDescription = null)
                    Text(stringResource(R.string.log_share), Modifier.padding(start = 8.dp))
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

/**
 * Opens Android's share sheet with the exchange log: a header (app, version, Android version), then
 * the lines, compressed and capped by [FrameLog.shareText] so that the Intent stays well under the
 * Binder limit. If the share sheet cannot be opened all the same, a short message says so and the app
 * goes on. Also used by the debug bench.
 */
fun shareExchangeLog(context: Context, log: FrameLog, chooserTitle: String) {
    val header = FrameLog.exportHeader(
        appName = context.getString(R.string.app_name),
        versionName = BuildConfig.VERSION_NAME,
        androidRelease = Build.VERSION.RELEASE,
        sdk = Build.VERSION.SDK_INT,
    )
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, log.shareText(header))
    try {
        context.startActivity(Intent.createChooser(send, chooserTitle))
    } catch (e: RuntimeException) {
        // A transaction too large comes back as a RuntimeException from startActivity; so does a
        // missing share sheet (ActivityNotFoundException).
        Log.w(TAG, "share sheet not opened", e)
        Toast.makeText(context, R.string.log_share_failed, Toast.LENGTH_SHORT).show()
    }
}

private const val TAG = "LS4TC421"

/** C1 to C5 with their colour; a row opens the palette; a button restores the defaults. */
@Composable
private fun ChannelColorsSection() {
    val container = LocalContainer.current
    val settings = container.settings
    val chosen by settings.channelColors.collectAsState(initial = ChannelPalette.defaults)
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }

    Section(stringResource(R.string.settings_channel_colours), Modifier.padding(top = 8.dp)) {
        Column(Modifier.padding(vertical = 4.dp)) {
            for (channel in 0 until CHANNELS) {
                val color = chosen[channel]
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { editing = channel }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                ) {
                    Text(stringResource(R.string.editor_channel, channel + 1), style = MaterialTheme.typography.titleMedium)
                    Swatch(color.forTheme(darkTheme), Modifier.size(24.dp))
                    Text(stringResource(color.label), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    TextButton(
        onClick = { container.appScope.launch { settings.resetChannelColors() } },
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    ) { Text(stringResource(R.string.settings_default_colours)) }

    editing?.let { channel ->
        PaletteDialog(
            channel = channel,
            current = chosen[channel].id,
            darkTheme = darkTheme,
            onPick = { id ->
                editing = null
                container.appScope.launch { settings.setChannelColor(channel, id) }
            },
            onDismiss = { editing = null },
        )
    }
}

/** The fixed palette in a grid; the current colour is marked; a choice closes the dialog. */
@Composable
private fun PaletteDialog(channel: Int, current: String, darkTheme: Boolean, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_colour_of, channel + 1)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ChannelPalette.colors.chunked(PALETTE_COLUMNS).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { color ->
                            val selected = color.id == current
                            val name = stringResource(color.label)
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .selectable(selected = selected, role = Role.RadioButton) { onPick(color.id) }
                                    .semantics(mergeDescendants = true) { contentDescription = name }
                                    .padding(vertical = 4.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Swatch(color.forTheme(darkTheme), Modifier.size(40.dp), selected = selected)
                                    if (selected) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = when {
                                                color.forTheme(darkTheme).isUnusedChannel -> MaterialTheme.colorScheme.onSurface
                                                color.forTheme(darkTheme).luminance() > 0.4f -> Color.Black
                                                else -> Color.White
                                            },
                                        )
                                    }
                                }
                                Text(
                                    name,
                                    style = MaterialTheme.typography.labelSmall,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(top = 4.dp).clearAndSetSemantics {},
                                )
                            }
                        }
                        // Keeps the last row's cells the width of the others.
                        repeat(PALETTE_COLUMNS - row.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** A colour dot with a thin outline, so that a pale hue stays visible; "unused" is struck through. */
@Composable
private fun Swatch(color: Color, modifier: Modifier = Modifier, selected: Boolean = false) {
    val border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null
    ChannelSwatch(color, CircleShape, modifier, border = border)
}

private const val PALETTE_COLUMNS = 3
