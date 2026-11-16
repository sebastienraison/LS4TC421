package net.rseb.ls4tc421

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder

/**
 * Release build: the debug-only tools do not exist. Same declarations as the debug source set,
 * all empty, so that `main` compiles the same way in both build types.
 */
object DebugHooks {
    @Suppress("UNUSED_PARAMETER")
    fun addDestinations(builder: NavGraphBuilder, nav: NavController) = Unit

    @Suppress("UNUSED_PARAMETER")
    @Composable
    fun DeviceExtras(deviceId: Long, nav: NavController) = Unit
}
