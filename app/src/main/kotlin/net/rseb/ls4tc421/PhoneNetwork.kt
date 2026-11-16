package net.rseb.ls4tc421

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import net.rseb.ls4tc421.data.SsidRules

/** What the phone's network looks like right now. */
data class NetworkSnapshot(
    val onWifi: Boolean,
    /** Name of the connected Wi-Fi network; `null` if unknown or not allowed to be read. */
    val ssid: String?,
    val locationGranted: Boolean,
    /** Only meaningful on Android 17+; true elsewhere. */
    val localNetworkGranted: Boolean,
)

/** Reads the phone's Wi-Fi state. The latest reading is observable from Compose. */
class PhoneNetwork(context: Context) {
    private val app = context.applicationContext

    var snapshot by mutableStateOf(read())
        private set

    fun refresh(): NetworkSnapshot = read().also { snapshot = it }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    private fun read(): NetworkSnapshot {
        val location = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val localNetwork = Build.VERSION.SDK_INT < LOCAL_NETWORK_SDK || granted(LOCAL_NETWORK_PERMISSION)
        return NetworkSnapshot(onWifi = onWifi(), ssid = if (location) ssid() else null, location, localNetwork)
    }

    private fun onWifi(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return false
        // Any network with the Wi-Fi transport: a VPN on top of Wi-Fi must still count.
        @Suppress("DEPRECATION")
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }

    @Suppress("DEPRECATION")
    private fun ssid(): String? {
        val wifi = app.getSystemService(WifiManager::class.java) ?: return null
        return try {
            SsidRules.clean(wifi.connectionInfo?.ssid)
        } catch (_: SecurityException) {
            null
        }
    }

    companion object {
        const val LOCAL_NETWORK_SDK = 37
        const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
