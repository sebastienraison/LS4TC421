package net.rseb.ls4tc421.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import net.rseb.ls4tc421.model.CHANNELS
import net.rseb.ls4tc421.model.EmptySlotNaming
import net.rseb.ls4tc421.ui.theme.ChannelPalette
import net.rseb.ls4tc421.ui.theme.PaletteColor

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * Small preferences: the last Wi-Fi network name seen on the phone (never a key), whether the
 * permission prompt was already shown, the display colour of each channel (D24, one series for
 * the whole app) and the name written by « Empty a slot » (D30). Nothing secret is ever written here.
 */
class SettingsStore(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.dataStore)

    /**
     * The remembered network, unless it is a controller's access point: a name stored before the
     * rule knew « Smart-xxxxxx » is ignored here, not migrated.
     */
    val lastSsid: Flow<String?> = store.data.map { prefs -> prefs[LAST_SSID]?.takeIf(SsidRules::shouldRemember) }
    val permissionsAsked: Flow<Boolean> = store.data.map { it[PERMISSIONS_ASKED] ?: false }

    /** The colours of C1 to C5; a missing or unknown id gives the channel's default. */
    val channelColors: Flow<List<PaletteColor>> =
        store.data.map { prefs -> ChannelPalette.resolve(CHANNEL_COLORS.map { prefs[it] }) }.distinctUntilChanged()

    /** The name written by « Empty a slot » (D30); a missing or unknown value gives « numbered ». */
    val emptySlotNaming: Flow<EmptySlotNaming> =
        store.data.map { prefs -> EmptySlotNaming.fromKey(prefs[EMPTY_SLOT_NAMING]) }.distinctUntilChanged()

    suspend fun setEmptySlotNaming(naming: EmptySlotNaming) {
        store.edit { it[EMPTY_SLOT_NAMING] = naming.key }
    }

    suspend fun rememberSsid(ssid: String) {
        store.edit { it[LAST_SSID] = ssid }
    }

    suspend fun markPermissionsAsked() {
        store.edit { it[PERMISSIONS_ASKED] = true }
    }

    /** Gives [channel] (0 for C1) the palette colour [id]. Two channels may share a colour. */
    suspend fun setChannelColor(channel: Int, id: String) {
        require(channel in 0 until CHANNELS) { "channel $channel" }
        store.edit { it[CHANNEL_COLORS[channel]] = id }
    }

    /** Back to the five default colours. */
    suspend fun resetChannelColors() {
        store.edit { prefs -> CHANNEL_COLORS.forEach { prefs.remove(it) } }
    }

    private companion object {
        val LAST_SSID = stringPreferencesKey("last_ssid")
        val PERMISSIONS_ASKED = booleanPreferencesKey("permissions_asked")
        val CHANNEL_COLORS = List(CHANNELS) { stringPreferencesKey("channel_color_${it + 1}") }
        val EMPTY_SLOT_NAMING = stringPreferencesKey("empty_slot_naming")
    }
}
