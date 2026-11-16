package net.rseb.ls4tc421.ui.theme

import androidx.annotation.StringRes
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import net.rseb.ls4tc421.R
import net.rseb.ls4tc421.model.CHANNELS

/**
 * A colour the user can give to a channel (D24). [id] is what the settings store, never the ARGB
 * value: a hue can be retouched without breaking saved choices. [light] and [dark] are the hues drawn
 * on the light and dark themes; they differ only where one hue cannot be read on both backgrounds
 * (white and yellow on the light background, for instance).
 */
data class PaletteColor(val id: String, val light: Color, val dark: Color, @StringRes val label: Int) {
    fun forTheme(darkTheme: Boolean): Color = if (darkTheme) dark else light
}

/** The fixed palette of channel colours, and the default colour of each channel. */
object ChannelPalette {
    /** The id of the "unused" entry, drawn transparent. */
    const val UNUSED = "unused"

    private fun same(id: String, color: Long, label: Int) = PaletteColor(id, Color(color), Color(color), label)
    private fun pair(id: String, light: Long, dark: Long, label: Int) = PaletteColor(id, Color(light), Color(dark), label)

    /**
     * Every hue keeps a contrast of about 3:1 or more against the theme's background (#FBFAF5 light,
     * #12140F dark), so that a 1.5 dp curve stays visible. The five neutral defaults are the colours
     * the app always had, unchanged and identical in both themes.
     */
    val colors: List<PaletteColor> = listOf(
        same("violet", 0xFF8A63D2, R.string.colour_violet),
        same("ochre", 0xFFC08A1E, R.string.colour_ochre),
        same("pink", 0xFFD2609E, R.string.colour_pink),
        same("brown", 0xFF8B6248, R.string.colour_brown),
        same("slate", 0xFF6E8296, R.string.colour_slate),
        pair("red", 0xFFD32F2F, 0xFFFF6B6B, R.string.colour_red),
        pair("green", 0xFF2E7D32, 0xFF5CC96A, R.string.colour_green),
        pair("blue", 0xFF1E5FD9, 0xFF6AA2FF, R.string.colour_blue),
        // White cannot be drawn as white on the light background: a neutral grey stands for it there.
        pair("white", 0xFF858585, 0xFFF2F2F2, R.string.colour_white),
        // Same for warm white: a warm grey on the light background, a pale amber on the dark one.
        pair("warm_white", 0xFF958061, 0xFFFFDFA8, R.string.colour_warm_white),
        pair("cyan", 0xFF00838F, 0xFF4DD0E1, R.string.colour_cyan),
        // Bright yellow vanishes on the light background: a dark yellow there.
        pair("yellow", 0xFF9C8000, 0xFFFFE14D, R.string.colour_yellow),
        pair("orange", 0xFFD85A00, 0xFFFF9A40, R.string.colour_orange),
        // "Unused": the curve is not drawn. Guards: the swatch stays, struck through; the curve comes
        // back in grey when the channel is selected in the editor; light on it is flagged (D24, Q80).
        PaletteColor(UNUSED, Color.Transparent, Color.Transparent, R.string.colour_unused),
    )

    private val byId: Map<String, PaletteColor> = colors.associateBy { it.id }

    /** C1 to C5 by default: violet, ochre, pink, brown, slate. */
    val defaultIds: List<String> = listOf("violet", "ochre", "pink", "brown", "slate")

    val defaults: List<PaletteColor> = defaultIds.map { byId.getValue(it) }

    fun find(id: String?): PaletteColor? = id?.let { byId[it] }

    /**
     * The colours of the five channels from what is stored (one id per channel, null when absent).
     * A missing or unknown id (a colour removed from the palette) gives the channel's default.
     */
    fun resolve(stored: List<String?>): List<PaletteColor> =
        List(CHANNELS) { channel -> find(stored.getOrNull(channel)) ?: defaults[channel] }

    /**
     * For each channel, whether it is drawn as unused ([unused]) yet has light somewhere in [levels]
     * (48 steps × 5 channels): what goes to the controller must never be hidden.
     */
    fun unusedButLit(unused: List<Boolean>, levels: List<List<Int>>): List<Boolean> =
        List(CHANNELS) { channel -> unused[channel] && levels.any { it[channel] > 0 } }
}

/** True for the "unused" entry: nothing is drawn with it. */
val Color.isUnusedChannel: Boolean get() = alpha == 0f

/**
 * The hues of C1 to C5 for the current theme, provided at the root from the settings. Outside the
 * app (tests, previews) the defaults apply; they are the same in both themes.
 */
val LocalChannelColors = staticCompositionLocalOf<List<Color>> { ChannelPalette.defaults.map { it.light } }
