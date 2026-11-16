package net.rseb.ls4tc421.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import net.rseb.ls4tc421.model.CHANNELS
import net.rseb.ls4tc421.model.ProgramSpec
import net.rseb.ls4tc421.model.STEPS
import net.rseb.ls4tc421.ui.editor.EditorRules.WINDOW_STEPS
import net.rseb.ls4tc421.ui.theme.LocalChannelColors
import net.rseb.ls4tc421.ui.theme.isUnusedChannel

/** Tag of the main graph, for tests. */
const val GRAPH_TAG = "editor-graph"

/** Tag of the overview, for tests. */
const val OVERVIEW_TAG = "editor-overview"

/**
 * The main graph of the editor (D19): a 3 h window that never scrolls. One gesture: drag a movable
 * point of the active channel (grabbed within 28 dp). While dragging, its time and level are shown on
 * the axes. A press away from a point does nothing and is left to the page's scrolling. Mirror points
 * (symmetry) are hollow and cannot be grabbed; a linked channel shows no point.
 */
@Composable
fun CurveGraph(
    spec: ProgramSpec,
    levels: List<List<Int>>,
    active: Int,
    windowStart: Int,
    selected: Int?,
    dragging: Boolean,
    onGrab: (step: Int) -> Boolean,
    onDrag: (deltaSteps: Float, deltaLevel: Float) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val channelColors = LocalChannelColors.current
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = colors.onSurfaceVariant, fontSize = 13.sp)
    val readoutStyle = TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)

    // Gesture handlers outlive recompositions: they read the latest values through these.
    val currentSpec by rememberUpdatedState(spec)
    val currentActive by rememberUpdatedState(active)
    val currentWindow by rememberUpdatedState(windowStart)
    val currentOnGrab by rememberUpdatedState(onGrab)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnRelease by rememberUpdatedState(onRelease)

    Canvas(
        modifier
            .testTag(GRAPH_TAG)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceContainerLow)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val g = GraphGeometry(size.width.toFloat(), size.height.toFloat(), density, currentWindow)
                    val point = EditorRules.grab(
                        currentSpec, currentActive, currentWindow, down.position.x, down.position.y,
                        EditorRules.GRAB_RADIUS_DP * density, g::x, g::y,
                    ) ?: return@awaitEachGesture // not on a point: left to the page's scrolling
                    if (!currentOnGrab(point.step)) return@awaitEachGesture
                    down.consume()
                    try {
                        while (true) {
                            // Consumed before the page sees it: a drag started on a point never scrolls.
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (!change.pressed) break
                            val delta = change.position - down.position
                            currentOnDrag(delta.x / g.stepWidth, -delta.y / g.levelHeight)
                        }
                    } finally {
                        currentOnRelease()
                    }
                }
            },
    ) {
        val g = GraphGeometry(size.width, size.height, density, windowStart)
        drawGrid(g, colors.outlineVariant, measurer, labelStyle)
        spec.symmetryAxis?.let { axis ->
            val x = g.xMinute(axis * 15f)
            if (x >= g.left - 1 && x <= g.right + 1) {
                drawLine(
                    colors.outline, Offset(x, g.top), Offset(x, g.bottom), strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                )
            }
        }
        // An unused channel (transparent) is drawn in grey while selected: one sees what one edits.
        val color = channelColors[active].let { if (it.isUnusedChannel) colors.outline else it }
        val selectedPoint = selected?.let { s -> EditorRules.movablePointsInWindow(spec, active, windowStart).firstOrNull { it.step == s } }
        val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
        if (selectedPoint != null) {
            val c = Offset(g.x(selectedPoint.step), g.y(selectedPoint.level))
            if (dragging) {
                drawLine(color, Offset(g.left, c.y), c, strokeWidth = 1.dp.toPx(), pathEffect = dash)
                drawLine(color, c, Offset(c.x, g.bottom), strokeWidth = 1.dp.toPx(), pathEffect = dash)
            } else {
                drawLine(color, Offset(c.x, g.top), Offset(c.x, g.bottom), strokeWidth = 1.dp.toPx(), pathEffect = dash)
            }
        }
        clipRect(g.left, 0f, g.right, size.height) {
            for (channel in (0 until CHANNELS).filter { it != active } + active) {
                val isActive = channel == active
                if (!isActive && channelColors[channel].isUnusedChannel) continue
                val path = Path()
                val first = windowStart - 1
                val last = windowStart + WINDOW_STEPS + 1
                for (s in first..last) {
                    val p = Offset(g.x(s), g.y(levels[Math.floorMod(s, STEPS)][channel]))
                    if (s == first) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                drawPath(
                    path,
                    (if (isActive) color else channelColors[channel].copy(alpha = 0.35f)),
                    style = Stroke(width = if (isActive) 3.dp.toPx() else 1.5.dp.toPx()),
                )
            }
        }
        // Points at the window edges are drawn whole: the margins leave room for them.
        EditorRules.mirrorPointsInWindow(spec, active, windowStart).forEach { p ->
            val c = Offset(g.x(p.step), g.y(p.level))
            drawCircle(colors.surfaceContainerLow, 5.dp.toPx(), c)
            drawCircle(color, 5.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
        }
        EditorRules.movablePointsInWindow(spec, active, windowStart).forEach { p ->
            val c = Offset(g.x(p.step), g.y(p.level))
            if (p.step == selected) {
                drawCircle(color.copy(alpha = 0.18f), 16.dp.toPx(), c)
                drawCircle(colors.surface, 8.dp.toPx(), c)
                drawCircle(color, 8.dp.toPx(), c, style = Stroke(3.dp.toPx()))
            } else {
                drawCircle(color, 6.dp.toPx(), c)
            }
        }
        if (dragging && selectedPoint != null) {
            drawReadouts(g, selectedPoint.step, selectedPoint.level, color, measurer, readoutStyle)
        }
    }
}

/**
 * Pixel mapping of the main graph. Labels outside the plot: levels on the left, hours below.
 * [windowStart] is the first step shown; the plot spans [WINDOW_STEPS] steps.
 */
internal class GraphGeometry(width: Float, height: Float, density: Float, val windowStart: Int) {
    val left = 46f * density
    val right = width - 16f * density
    val top = 14f * density
    val bottom = height - 28f * density

    /** Pixels per step (30 min) and per level percent. */
    val stepWidth = (right - left) / WINDOW_STEPS
    val levelHeight = (bottom - top) / 100f

    fun x(step: Int): Float = left + stepWidth * (step - windowStart)
    fun xMinute(minute: Float): Float = left + stepWidth * (minute / 30f - windowStart)
    fun y(level: Int): Float = bottom - levelHeight * level
}

private fun DrawScope.drawGrid(g: GraphGeometry, line: Color, measurer: TextMeasurer, style: TextStyle) {
    for (level in listOf(0, 50, 100)) {
        val y = g.y(level)
        drawLine(line, Offset(g.left, y), Offset(g.right, y), strokeWidth = 1.dp.toPx())
        val text = measurer.measure("$level %", style)
        drawText(text, topLeft = Offset(g.left - 6.dp.toPx() - text.size.width, y - text.size.height / 2f))
    }
    // Full hours only: three or four labels in a 3 h window.
    for (step in g.windowStart..g.windowStart + WINDOW_STEPS) {
        if (step % 2 != 0) continue
        val text = measurer.measure("%02d:00".format(Locale.ROOT, step / 2), style)
        val x = (g.x(step) - text.size.width / 2f).coerceIn(0f, size.width - text.size.width)
        drawText(text, topLeft = Offset(x, g.bottom + 6.dp.toPx()))
    }
}

/** The coordinates of the dragged point, on the axes: level on the left, time below. */
private fun DrawScope.drawReadouts(
    g: GraphGeometry,
    step: Int,
    level: Int,
    color: Color,
    measurer: TextMeasurer,
    style: TextStyle,
) {
    val pad = 6.dp.toPx()
    val levelText = measurer.measure("$level %", style)
    val h = levelText.size.height + pad
    val levelLeft = 1.dp.toPx()
    val levelWidth = g.left - 4.dp.toPx() - levelLeft
    val cy = g.y(level)
    drawRoundRect(color, Offset(levelLeft, cy - h / 2), Size(levelWidth, h), CornerRadius(6.dp.toPx()))
    drawText(levelText, topLeft = Offset(levelLeft + (levelWidth - levelText.size.width) / 2, cy - levelText.size.height / 2f))

    val timeText = measurer.measure("%02d:%02d".format(Locale.ROOT, step / 2, (step % 2) * 30), style)
    val w = timeText.size.width + 2 * pad
    val cx = g.x(step)
    val x = (cx - w / 2).coerceIn(0f, size.width - w)
    val y = g.bottom + 2.dp.toPx()
    drawRoundRect(color, Offset(x, y), Size(w, h), CornerRadius(6.dp.toPx()))
    drawText(timeText, topLeft = Offset(x + pad, y + pad / 2))
}

/**
 * The overview (D19): the whole day, every curve, and a rectangle showing the window of the main
 * graph. The only way to scroll: drag the rectangle (on the half-hour), or touch an hour to centre the
 * window on it. No points are drawn. With symmetry on, the axis ([symmetryAxis], in quarter-hours) is
 * drawn as a dashed vertical line, like on the main graph (D20).
 */
@Composable
fun DayOverview(
    levels: List<List<Int>>,
    active: Int,
    windowStart: Int,
    symmetryAxis: Int?,
    description: String,
    onMoveWindow: (start: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val channelColors = LocalChannelColors.current
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = colors.onSurfaceVariant, fontSize = 10.sp)
    val currentWindow by rememberUpdatedState(windowStart)
    val currentOnMove by rememberUpdatedState(onMoveWindow)

    Canvas(
        modifier
            .testTag(OVERVIEW_TAG)
            .semantics { contentDescription = description }
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceContainerLow)
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    val g = OverviewGeometry(size.width.toFloat(), size.height.toFloat(), density)
                    currentOnMove(EditorRules.centeredWindowStart(g.minuteAt(pos.x)))
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        ?: return@awaitEachGesture // a tap, or a vertical move left to the page
                    val g = OverviewGeometry(size.width.toFloat(), size.height.toFloat(), density)
                    // Outside the rectangle, the window first jumps under the finger.
                    val x = down.position.x
                    val inside = x in g.x(currentWindow) - g.handleSlop..g.x(currentWindow + WINDOW_STEPS) + g.handleSlop
                    val startAtGrab = if (inside) currentWindow else EditorRules.centeredWindowStart(g.minuteAt(x))
                    val follow = { position: Offset ->
                        currentOnMove(EditorRules.draggedWindowStart(startAtGrab, (position.x - x) / g.minuteWidth))
                    }
                    follow(slop.position)
                    horizontalDrag(slop.id) { change ->
                        change.consume()
                        follow(change.position)
                    }
                }
            },
    ) {
        val g = OverviewGeometry(size.width, size.height, density)
        val wx0 = g.x(windowStart)
        val wx1 = g.x(windowStart + WINDOW_STEPS)
        val rectTop = 2.dp.toPx()
        val rectSize = Size(wx1 - wx0, g.bottom - rectTop)
        val corner = CornerRadius(5.dp.toPx())
        drawRoundRect(colors.secondaryContainer, Offset(wx0, rectTop), rectSize, corner)
        // The main graph's axis line, scaled down to the overview.
        symmetryAxis?.let { axis ->
            val x = g.xMinute(axis * 15f)
            drawLine(
                colors.outline, Offset(x, g.top), Offset(x, g.bottom), strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())),
            )
        }
        for (channel in (0 until CHANNELS).filter { it != active } + active) {
            val isActive = channel == active
            val hue = channelColors[channel]
            if (hue.isUnusedChannel && !isActive) continue
            val path = Path()
            for (s in 0..STEPS) {
                val p = Offset(g.x(s), g.y(levels[s % STEPS][channel]))
                if (s == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(
                path,
                if (hue.isUnusedChannel) colors.outline else hue.copy(alpha = if (isActive) 1f else 0.45f),
                style = Stroke(width = if (isActive) 2.dp.toPx() else 1.dp.toPx()),
            )
        }
        drawRoundRect(colors.primary, Offset(wx0, rectTop), rectSize, corner, style = Stroke(2.dp.toPx()))
        val handle = Size(6.dp.toPx(), 18.dp.toPx())
        for (x in listOf(wx0, wx1)) {
            drawRoundRect(
                colors.primary,
                Offset(x - handle.width / 2, rectTop + (rectSize.height - handle.height) / 2),
                handle,
                CornerRadius(3.dp.toPx()),
            )
        }
        for (hour in listOf(0, 6, 12, 18, 24)) {
            val text = measurer.measure("%02dh".format(Locale.ROOT, hour), labelStyle)
            val x = (g.x(hour * 2) - text.size.width / 2f).coerceIn(0f, size.width - text.size.width)
            drawText(text, topLeft = Offset(x, g.bottom + 1.dp.toPx()))
        }
    }
}

/** Pixel mapping of the overview: the whole day, hour labels below. */
internal class OverviewGeometry(width: Float, height: Float, density: Float) {
    val left = 8f * density
    val right = width - 8f * density
    val top = 6f * density
    val bottom = height - 16f * density
    val handleSlop = 16f * density
    val minuteWidth = (right - left) / (24 * 60)

    fun x(step: Int): Float = left + minuteWidth * step * 30
    fun xMinute(minute: Float): Float = left + minuteWidth * minute
    fun y(level: Int): Float = bottom - (bottom - top) * level / 100f
    fun minuteAt(x: Float): Float = ((x - left) / minuteWidth).coerceIn(0f, 24f * 60)
}
