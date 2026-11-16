package net.rseb.ls4tc421.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import net.rseb.ls4tc421.DebugHooks
import net.rseb.ls4tc421.R
import net.rseb.ls4tc421.ui.detail.DeviceDetailScreen
import net.rseb.ls4tc421.ui.devices.DevicesScreen
import net.rseb.ls4tc421.ui.editor.EditorScreen
import net.rseb.ls4tc421.ui.preview.PreviewScreen
import net.rseb.ls4tc421.ui.programs.ProgramsScreen
import net.rseb.ls4tc421.ui.settings.SettingsScreen
import net.rseb.ls4tc421.ui.wifi.WifiScreen

private object Routes {
    const val DEVICES = "devices"
    /** [ask]: just added, the page asks « What does this controller hold? » once (D31). */
    const val DEVICE = "device/{id}?ask={ask}"
    const val WIFI = "device/{id}/wifi"
    const val PROGRAMS = "programs"
    const val SETTINGS = "settings"
    const val EDITOR = "program/{id}"
    const val PREVIEW = "preview"

    fun editor(id: Long) = "program/$id"

    fun device(id: Long, ask: Boolean = false) = "device/$id?ask=$ask"
    fun wifi(id: Long) = "device/$id/wifi"
}

/** Called instead of leaving; it runs `proceed` once the user has saved or discarded. */
typealias LeaveGuard = (proceed: () -> Unit) -> Unit

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.DEVICES, R.string.nav_devices, Icons.Outlined.Lightbulb),
    Tab(Routes.PROGRAMS, R.string.nav_programs, Icons.AutoMirrored.Outlined.ShowChart),
    Tab(Routes.SETTINGS, R.string.nav_settings, Icons.Outlined.Settings),
)

@Composable
fun AppRoot() {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    // Set by a screen with unsaved changes (the editor): the bottom bar asks it before leaving.
    var leaveGuard by remember { mutableStateOf<LeaveGuard?>(null) }

    // Remember the phone's Wi-Fi name each time the app comes back to the foreground.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { container.refreshNetwork() } }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    // The device pages belong to the Devices tab.
                    val selected = when (tab.route) {
                        Routes.DEVICES -> route == Routes.DEVICES || route?.startsWith("device/") == true
                        // The editor belongs to the Programs tab.
                        Routes.PROGRAMS -> route == Routes.PROGRAMS || route == Routes.EDITOR
                        else -> route == tab.route
                    }
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            val go = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                            leaveGuard?.invoke(go) ?: go()
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.label)) },
                    )
                }
            }
        },
    ) { inner ->
        // Each screen has its own top bar and handles the top inset: only the bottom bar's height is applied here.
        NavHost(
            navController = nav,
            startDestination = Routes.DEVICES,
            modifier = Modifier.padding(bottom = inner.calculateBottomPadding()),
        ) {
            composable(Routes.DEVICES) {
                DevicesScreen(
                    onOpenDevice = { nav.navigate(Routes.device(it)) },
                    onAdded = { nav.navigate(Routes.device(it, ask = true)) },
                )
            }
            composable(
                Routes.DEVICE,
                arguments = listOf(
                    navArgument("id") { type = NavType.LongType },
                    navArgument("ask") { type = NavType.BoolType; defaultValue = false },
                ),
            ) { entry ->
                val id = entry.arguments!!.getLong("id")
                DeviceDetailScreen(
                    id = id,
                    askContents = entry.arguments!!.getBoolean("ask"),
                    onBack = { nav.popBackStack() },
                    onOpenWifi = { nav.navigate(Routes.wifi(id)) },
                    onOpenPreview = { nav.navigate(Routes.PREVIEW) },
                    extras = { DebugHooks.DeviceExtras(id, nav) },
                )
            }
            composable(Routes.WIFI, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                WifiScreen(id = entry.arguments!!.getLong("id"), onBack = { nav.popBackStack() })
            }
            composable(Routes.PROGRAMS) { ProgramsScreen(onOpenEditor = { nav.navigate(Routes.editor(it)) }) }
            composable(Routes.EDITOR, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                EditorScreen(
                    id = entry.arguments!!.getLong("id"),
                    onBack = { nav.popBackStack() },
                    onOpenPreview = { nav.navigate(Routes.PREVIEW) },
                    setLeaveGuard = { leaveGuard = it },
                )
            }
            composable(Routes.PREVIEW) { PreviewScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.SETTINGS) { SettingsScreen() }
            DebugHooks.addDestinations(this, nav)
        }
    }
}
