package net.rseb.ls4tc421.ui.slots

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.rseb.ls4tc421.R
import net.rseb.ls4tc421.ui.theme.LocalExtraColors

/** What a tap on the grid does, and how the touched tiles are marked. */
enum class GridMode {
    /** The device page: a tap opens the slot's sheet. */
    BROWSE,

    /** Emptying (D30): several tiles checked, in the error colours. */
    SELECT_MANY,

    /** Send sheet, slot in service: one tile chosen. */
    PICK_ONE,
}

private const val COLUMNS = 4
private val TileShape = RoundedCornerShape(8.dp)

/**
 * The 20 slots as a 4 × 5 grid (D31): number and name; filled = written or emptied by the app, dashed
 * border = declared empty, grey « ? » = unknown, solid green and filled light bulb = declared in service
 * (a selection wins over it: see [tileEmphasis]).
 * [marked]: the tiles checked ([GridMode.SELECT_MANY]) or chosen ([GridMode.PICK_ONE]).
 */
@Composable
fun SlotGrid(
    tiles: List<SlotTile>,
    onTap: (Int) -> Unit,
    modifier: Modifier = Modifier,
    mode: GridMode = GridMode.BROWSE,
    marked: Set<Int> = emptySet(),
    enabled: Boolean = true,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        tiles.chunked(COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { tile ->
                    Tile(
                        tile = tile,
                        mode = mode,
                        marked = tile.slot in marked,
                        enabled = enabled,
                        onClick = { onTap(tile.slot) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Tile(tile: SlotTile, mode: GridMode, marked: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    val extra = LocalExtraColors.current
    val emphasis = tileEmphasis(tile.active, mode, marked)
    val filled = tile.look == TileLook.WRITTEN || tile.look == TileLook.EMPTIED
    val background = when (emphasis) {
        TileEmphasis.CHECKED -> c.errorContainer
        TileEmphasis.CHOSEN -> c.primaryContainer
        TileEmphasis.IN_SERVICE -> extra.inService
        TileEmphasis.STATE -> when {
            filled -> c.secondaryContainer
            tile.look == TileLook.UNKNOWN -> c.surfaceContainerHigh
            else -> Color.Transparent
        }
    }
    val content = when (emphasis) {
        TileEmphasis.CHECKED -> c.onErrorContainer
        TileEmphasis.CHOSEN -> c.onPrimaryContainer
        TileEmphasis.IN_SERVICE -> extra.onInService
        TileEmphasis.STATE -> when {
            filled -> c.onSecondaryContainer
            tile.look == TileLook.UNKNOWN -> c.outline
            else -> c.onSurfaceVariant
        }
    }
    val border: BorderStroke? = when (emphasis) {
        TileEmphasis.CHECKED -> BorderStroke(1.dp, c.error)
        TileEmphasis.CHOSEN -> BorderStroke(2.dp, c.primary)
        else -> null
    }
    val showsState = emphasis != TileEmphasis.IN_SERVICE
    val dashed = border == null && showsState && tile.look == TileLook.DECLARED_EMPTY
    Column(
        modifier
            .height(44.dp)
            .clip(TileShape)
            .background(background)
            .then(if (border != null) Modifier.border(border, TileShape) else Modifier)
            .then(if (dashed) Modifier.dashedBorder(c.outline, 8.dp, 1.dp) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (tile.slot + 1).toString(),
                style = MaterialTheme.typography.labelSmall,
                color = content,
                modifier = Modifier.weight(1f),
            )
            val mark = when (emphasis) {
                TileEmphasis.CHECKED -> Icons.Filled.CheckCircle to c.error
                TileEmphasis.CHOSEN -> Icons.Outlined.RadioButtonChecked to c.primary
                TileEmphasis.IN_SERVICE -> Icons.Filled.Lightbulb to extra.onInService
                TileEmphasis.STATE -> null
            }
            mark?.let { (icon, tint) ->
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
            }
        }
        // In service, the look of the state (fill, dashes, italics) gives way to the green; the text stays
        // (a name, « empty » or « ? », Q153).
        val secondary = tile.name == null
        Text(
            text = when {
                tile.name != null -> tile.name
                tile.showsEmpty -> stringResource(R.string.slot_empty_word)
                else -> "?"
            },
            style = MaterialTheme.typography.bodySmall,
            fontStyle = if (showsState && tile.look == TileLook.EMPTIED && !secondary) FontStyle.Italic else FontStyle.Normal,
            color = if (secondary && !marked) content.copy(alpha = 0.8f) else content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A dashed rounded outline (« declared empty »). */
private fun Modifier.dashedBorder(color: Color, corner: Dp, width: Dp): Modifier = drawBehind {
    val stroke = width.toPx()
    val radius = corner.toPx()
    drawRoundRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
        size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(radius),
        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
    )
}

/** Under the grid: what filled, dashed, grey and green mean. Wraps to a second line when the width is short. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SlotLegend(modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val extra = LocalExtraColors.current
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        LegendItem(stringResource(R.string.slots_legend_written)) {
            Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(c.secondaryContainer))
        }
        LegendItem(stringResource(R.string.slots_legend_declared)) {
            Box(Modifier.size(12.dp).dashedBorder(c.outline, 3.dp, 1.dp))
        }
        LegendItem(stringResource(R.string.slots_legend_unknown)) {
            Box(
                Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(c.surfaceContainerHigh)
                    .border(1.dp, c.outlineVariant, RoundedCornerShape(3.dp)),
            )
        }
        LegendItem(stringResource(R.string.slots_legend_in_service)) {
            Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(extra.inService))
        }
    }
}

@Composable
private fun LegendItem(text: String, swatch: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        swatch()
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}
