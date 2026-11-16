package net.rseb.ls4tc421

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.luminance
import net.rseb.ls4tc421.data.SettingsStore
import net.rseb.ls4tc421.ui.AppRoot
import net.rseb.ls4tc421.ui.LocalContainer
import net.rseb.ls4tc421.ui.theme.ChannelPalette
import net.rseb.ls4tc421.ui.theme.LocalChannelColors
import net.rseb.ls4tc421.ui.theme.LsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as LsApp).container
        setContent {
            CompositionLocalProvider(LocalContainer provides container) {
                LsTheme { WithChannelColors(container.settings) { AppRoot() } }
            }
        }
    }
}

/**
 * Provides the channel colours chosen in Settings (D24) to every screen, in the hue of the current
 * theme. A change in Settings shows on the next screen drawn, without restarting the app.
 */
@Composable
private fun WithChannelColors(settings: SettingsStore, content: @Composable () -> Unit) {
    val palette by settings.channelColors.collectAsState(initial = ChannelPalette.defaults)
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val colors = remember(palette, darkTheme) { palette.map { it.forTheme(darkTheme) } }
    CompositionLocalProvider(LocalChannelColors provides colors, content = content)
}
