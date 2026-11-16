package net.rseb.ls4tc421.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.rseb.ls4tc421.R

/**
 * The swatch of a channel colour. An "unused" colour (transparent) is drawn as an outlined swatch
 * struck through, so that the channel stays visible. [lit] (only meaningful for an unused channel)
 * adds a warning dot: the channel is drawn as unused but has light, which goes to the controller.
 */
@Composable
fun ChannelSwatch(
    color: Color,
    shape: Shape,
    modifier: Modifier = Modifier,
    border: BorderStroke? = null,
    lit: Boolean = false,
    channel: Int? = null,
) {
    val outline = MaterialTheme.colorScheme.outline
    val unused = color.isUnusedChannel
    val warning = MaterialTheme.colorScheme.error
    val description = if (lit && unused && channel != null) stringResource(R.string.channel_unused_lit, channel + 1) else null
    Box(
        modifier.then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
    ) {
        Box(
            Modifier
                .matchParentSize()
                .clip(shape)
                .background(color)
                .border(border ?: BorderStroke(1.dp, outline), shape)
                .drawWithContent {
                    drawContent()
                    if (unused) {
                        drawLine(outline, Offset(0f, size.height), Offset(size.width, 0f), strokeWidth = 1.5.dp.toPx())
                    }
                },
        )
        if (lit && unused) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(warning),
            )
        }
    }
}
