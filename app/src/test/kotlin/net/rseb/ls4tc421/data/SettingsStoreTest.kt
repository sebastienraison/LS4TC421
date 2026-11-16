package net.rseb.ls4tc421.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.rseb.ls4tc421.model.EmptySlotNaming
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The channel colours of the settings (D24), on a real DataStore file, on the JVM (no Android). */
class SettingsStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dataStore by lazy {
        PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") }
    }
    private val store by lazy { SettingsStore(dataStore) }

    @After fun close() = scope.cancel()

    private fun ids() = runBlocking { store.channelColors.first().map { it.id } }

    @Test fun nothingStoredGivesTheFiveDefaults() {
        assertEquals(listOf("violet", "ochre", "pink", "brown", "slate"), ids())
    }

    @Test fun aChannelKeepsTheColourWrittenForIt() {
        runBlocking {
            store.setChannelColor(0, "red")
            store.setChannelColor(3, "white")
        }
        assertEquals(listOf("red", "ochre", "pink", "white", "slate"), ids())
    }

    @Test fun twoChannelsMayShareAColour() {
        runBlocking {
            store.setChannelColor(1, "blue")
            store.setChannelColor(2, "blue")
        }
        assertEquals(listOf("violet", "blue", "blue", "brown", "slate"), ids())
    }

    @Test fun anUnknownIdGivesTheChannelsDefault() {
        runBlocking {
            dataStore.edit { it[stringPreferencesKey("channel_color_2")] = "removed_colour" }
            store.setChannelColor(4, "green")
        }
        assertEquals(listOf("violet", "ochre", "pink", "brown", "green"), ids())
    }

    @Test fun resetGivesTheDefaultsBackAndLeavesTheOtherSettings() {
        runBlocking {
            store.rememberSsid("home")
            for (channel in 0 until 5) store.setChannelColor(channel, "orange")
            store.resetChannelColors()
        }
        assertEquals(listOf("violet", "ochre", "pink", "brown", "slate"), ids())
        assertEquals("home", runBlocking { store.lastSsid.first() })
    }

    @Test fun aRememberedControllerAccessPointIsIgnoredWhenRead() {
        // Stored by an earlier version that only knew « Smarttime… »: not migrated, not proposed.
        runBlocking { dataStore.edit { it[stringPreferencesKey("last_ssid")] = "Smart-0A1B2C" } }
        assertEquals(null, runBlocking { store.lastSsid.first() })
        runBlocking { store.rememberSsid("home") }
        assertEquals("home", runBlocking { store.lastSsid.first() })
    }

    @Test(expected = IllegalArgumentException::class)
    fun aChannelOutOfRangeIsRefused() {
        runBlocking { store.setChannelColor(5, "red") }
    }

    // « Empty a slot » (D30): the name written.

    private fun naming() = runBlocking { store.emptySlotNaming.first() }

    @Test fun emptySlotNamingIsNumberedByDefault() {
        assertEquals(EmptySlotNaming.NUMBERED, naming())
    }

    @Test fun emptySlotNamingIsReadBackAsWritten() {
        runBlocking { store.setEmptySlotNaming(EmptySlotNaming.AS_NEW) }
        assertEquals(EmptySlotNaming.AS_NEW, naming())
        runBlocking { store.setEmptySlotNaming(EmptySlotNaming.NUMBERED) }
        assertEquals(EmptySlotNaming.NUMBERED, naming())
    }

    @Test fun anUnknownEmptySlotNamingIsNumbered() {
        runBlocking { dataStore.edit { it[stringPreferencesKey("empty_slot_naming")] = "removed_value" } }
        assertEquals(EmptySlotNaming.NUMBERED, naming())
    }
}
