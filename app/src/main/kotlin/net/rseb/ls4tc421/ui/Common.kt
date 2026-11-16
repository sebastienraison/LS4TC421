package net.rseb.ls4tc421.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import net.rseb.ls4tc421.AppContainer

val LocalContainer = compositionLocalOf<AppContainer> { error("AppContainer not provided") }

/** Short date and time in the phone's locale, e.g. « 11/11/2026, 14:44 ». */
fun formatDateTime(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMillis))

/** Date only, short. */
fun formatDate(epochMillis: Long): String =
    DateFormat.getDateInstance(DateFormat.SHORT).format(Date(epochMillis))

/** Weekday, date and time, e.g. « Wed, Nov 11, 2026, 21:52 ». */
fun formatLong(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMillis))

/**
 * Section heading in the primary colour (Bivouac's `SettingsSection` style). Alone, it has no inset:
 * [Section] sets it 12 dp in from the card edge, 6 dp above the card.
 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    )
}

/** The cards' shape: 20 dp corners, as in Bivouac. */
val SectionCardShape = RoundedCornerShape(20.dp)

/**
 * The one card of the app (Bivouac's `SettingsSection`): elevated, 20 dp corners, no outline, full
 * width. Material 3 fills it with `surfaceContainerLow` and gives it a level-1 shadow. Inner padding
 * is the caller's: 12 dp for a list of rows, 16 dp for text or buttons. With [onClick], the whole
 * card is clickable and its ripple follows the rounded shape.
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (onClick != null) {
        ElevatedCard(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = SectionCardShape, content = content)
    } else {
        ElevatedCard(modifier = modifier.fillMaxWidth(), shape = SectionCardShape, content = content)
    }
}

/** A titled section: [SectionTitle] 12 dp in and 6 dp above a [SectionCard]. */
@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier) {
        SectionTitle(title, Modifier.padding(start = 12.dp, bottom = 6.dp))
        SectionCard(content = content)
    }
}

/** Round icon holder used in list rows and cards. */
@Composable
fun LeadCircle(
    highlighted: Boolean = false,
    dimmed: Boolean = false,
    content: @Composable () -> Unit,
) {
    val container = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val onContainer = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = CircleShape,
        color = container.copy(alpha = if (dimmed) 0.55f else 1f),
        contentColor = onContainer,
        modifier = Modifier.size(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}
