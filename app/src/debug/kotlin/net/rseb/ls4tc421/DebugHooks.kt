package net.rseb.ls4tc421

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import net.rseb.ls4tc421.bench.BenchScreen

/**
 * Debug build: the test bench (J0 tests on the real controller). It shows the app's exchange log
 * ([net.rseb.ls4tc421.link.FrameLog]), which exists in both build types. The release source set
 * declares the same object with empty members, so nothing of the bench reaches a release build.
 */
object DebugHooks {
    private const val ROUTE = "device/{id}/bench"

    fun addDestinations(builder: NavGraphBuilder, nav: NavController) {
        builder.composable(ROUTE, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            BenchScreen(id = entry.arguments!!.getLong("id"), onBack = { nav.popBackStack() })
        }
    }

    @Composable
    fun DeviceExtras(deviceId: Long, nav: NavController) {
        OutlinedButton(
            onClick = { nav.navigate("device/$deviceId/bench") },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.Science, contentDescription = null)
            Text(stringResource(R.string.bench_open), Modifier.padding(start = 8.dp))
        }
    }
}
