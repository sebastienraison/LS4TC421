package net.rseb.ls4tc421

import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import ls4tc421.protocol.WifiCipher
import ls4tc421.protocol.WifiSecurity
import ls4tc421.simulator.SimWifi
import java.net.InetAddress
import ls4tc421.protocol.Device
import ls4tc421.protocol.MacAddress
import net.rseb.ls4tc421.data.AddResult
import net.rseb.ls4tc421.data.AddressChange
import net.rseb.ls4tc421.data.AddressResult
import net.rseb.ls4tc421.data.addressChanges
import net.rseb.ls4tc421.data.DeviceEntity
import net.rseb.ls4tc421.data.ScanResult
import net.rseb.ls4tc421.data.SendOutcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeviceRepositoryTest {
    private lateinit var env: TestEnv

    @Before fun setUp() { env = TestEnv() }
    @After fun tearDown() { env.close() }

    // ---- add by address -------------------------------------------------------------

    @Test fun addByAddress_reply_savesWithMacAsIdentifier() = runBlocking {
        val result = env.repository.addByAddress("127.0.0.1", "Aquarium")
        val added = (result as AddResult.Added).device
        assertEquals("AABBCCDDEEFF", added.mac)
        assertEquals("Aquarium", added.name)
        assertEquals("127.0.0.1", added.address)
        assertEquals(1_000L, added.lastSeenAt)
        assertEquals(listOf(added), env.dao.all)
    }

    @Test fun addByAddress_noReply_savesNothing() = runBlocking {
        env.sim.close() // nobody answers
        assertEquals(AddResult.NoReply, env.repository.addByAddress("127.0.0.1", "Aquarium"))
        assertTrue(env.dao.all.isEmpty())
    }

    @Test fun addByAddress_invalidAddress_sendsNothing() = runBlocking {
        for (bad in listOf("", "999.1.1.1", "192.0.2", "example.com", "192.0.2.1.5", "::1")) {
            assertEquals(bad, AddResult.InvalidAddress, env.repository.addByAddress(bad, "x"))
        }
        assertEquals(0, env.sim.received.size)
        assertTrue(env.dao.all.isEmpty())
    }

    @Test fun addByAddress_sameMacTwice_doesNotDuplicate() = runBlocking {
        env.repository.addByAddress("127.0.0.1", "First")
        env.clock = 2_000L
        val second = env.repository.addByAddress("127.0.0.1", "Second")
        assertTrue(second is AddResult.AlreadyKnown)
        assertEquals(1, env.dao.all.size)
        assertEquals("First", env.dao.all.single().name)
        assertEquals(2_000L, env.dao.all.single().lastSeenAt)
    }

    // ---- state of known controllers ---------------------------------------------------

    @Test fun probe_sameMac_isReachableAndRefreshesLastSeen() = runBlocking {
        val device = env.store()
        env.clock = 5_000L
        assertTrue(env.repository.probe(device))
        assertEquals(5_000L, env.dao.get(device.id)!!.lastSeenAt)
    }

    @Test fun probe_otherMacAtThatAddress_isNotReachable() = runBlocking {
        val device = env.store(mac = "112233445566")
        assertFalse(env.repository.probe(device))
        assertNull(env.dao.get(device.id)!!.lastSeenAt)
    }

    @Test fun probe_noReply_isNotReachable() = runBlocking {
        val device = env.store()
        env.sim.close()
        assertFalse(env.repository.probe(device))
    }

    @Test fun probe_badStoredAddress_isNotReachable() = runBlocking {
        val id = env.dao.insert(DeviceEntity(name = "x", address = "not-an-ip", mac = "AABBCCDDEEFF"))
        assertFalse(env.repository.probe(env.dao.get(id)!!))
        assertEquals(0, env.sim.received.size)
    }

    @Test fun scan_findsTheSimulator_andRefreshesAKnownOne() = runBlocking {
        val known = env.store()
        env.clock = 7_000L
        val result = env.repository.scan() as ScanResult.Found
        assertEquals(listOf("AABBCCDDEEFF"), result.devices.map { it.mac.text })
        assertEquals(7_000L, env.dao.get(known.id)!!.lastSeenAt)
    }

    // ---- the search says which known controller changed address (defect b, D27) -------

    @Test fun addressChanges_onlyKnownControllersWhoseAddressDiffers() {
        val moved = DeviceEntity(id = 1, name = "Moved", address = "192.0.2.7", mac = "AABBCCDDEEFF")
        val stayed = DeviceEntity(id = 2, name = "Stayed", address = "127.0.0.1", mac = "112233445566")
        val found = listOf(
            Device(InetAddress.getLoopbackAddress(), MacAddress.parse("AABBCCDDEEFF")),
            Device(InetAddress.getLoopbackAddress(), MacAddress.parse("112233445566")),
            Device(InetAddress.getLoopbackAddress(), MacAddress.parse("0A0B0C0D0E0F")), // not known
        )
        assertEquals(
            listOf(AddressChange(1, "Moved", "192.0.2.7", "127.0.0.1")),
            addressChanges(listOf(moved, stayed), found),
        )
        assertEquals(emptyList<AddressChange>(), addressChanges(listOf(moved, stayed), emptyList()))
    }

    @Test fun scan_knownControllerAtANewAddress_isReportedAndUpdated() = runBlocking {
        val id = env.dao.insert(DeviceEntity(name = "Tank", address = "192.0.2.7", mac = "AABBCCDDEEFF"))
        val result = env.repository.scan() as ScanResult.Found
        assertEquals(listOf(AddressChange(id, "Tank", "192.0.2.7", "127.0.0.1")), result.moved)
        assertEquals("127.0.0.1", env.dao.get(id)!!.address)
    }

    @Test fun scan_knownControllerAtTheSameAddress_isNotReported() = runBlocking {
        env.store()
        val result = env.repository.scan() as ScanResult.Found
        assertEquals(1, result.devices.size)
        assertTrue(result.moved.isEmpty())
    }

    @Test fun scan_noReply_findsNobody() = runBlocking {
        env.store()
        env.sim.close()
        val result = env.repository.scan() as ScanResult.Found
        assertTrue(result.devices.isEmpty())
        assertTrue(result.moved.isEmpty())
    }

    // ---- change address (D27) -----------------------------------------------------------

    private suspend fun storedWithHistory(mac: String = "AABBCCDDEEFF"): DeviceEntity {
        val id = env.dao.insert(
            DeviceEntity(
                name = "Tank", address = "192.0.2.7", mac = mac, lastSeenAt = 10L, timeSetAt = 20L, activeSlot = 4,
            ),
        )
        return env.dao.get(id)!!
    }

    @Test fun changeAddress_sameMac_isSavedAndTheHistoryIsKept() = runBlocking {
        val before = storedWithHistory()
        env.clock = 50L
        assertEquals(AddressResult.Saved, env.repository.changeAddress(before.id, " 127.0.0.1 "))
        val after = env.dao.get(before.id)!!
        assertEquals(before.copy(address = "127.0.0.1", lastSeenAt = 50L), after)
        assertEquals(1, env.sim.received.size) // one unicast discovery
    }

    @Test fun changeAddress_noReply_savesNothing() = runBlocking {
        val before = storedWithHistory()
        env.sim.close()
        assertEquals(AddressResult.NoReply, env.repository.changeAddress(before.id, "127.0.0.1"))
        assertEquals(before, env.dao.get(before.id))
    }

    @Test fun changeAddress_anotherControllerAnswers_savesNothing() = runBlocking {
        val before = storedWithHistory(mac = "112233445566") // the simulator answers as AABBCCDDEEFF
        assertEquals(AddressResult.OtherController, env.repository.changeAddress(before.id, "127.0.0.1"))
        assertEquals(before, env.dao.get(before.id))
    }

    @Test fun changeAddress_invalidAddress_sendsNothing() = runBlocking {
        val before = storedWithHistory()
        for (bad in listOf("", "999.1.1.1", "example.com", "::1")) {
            assertEquals(bad, AddressResult.InvalidAddress, env.repository.changeAddress(before.id, bad))
        }
        assertEquals(0, env.sim.received.size)
        assertEquals(before, env.dao.get(before.id))
    }

    @Test fun changeAddress_unknownDevice_sendsNothing() = runBlocking {
        assertEquals(AddressResult.UnknownDevice, env.repository.changeAddress(42, "127.0.0.1"))
        assertEquals(0, env.sim.received.size)
    }

    // ---- clock ------------------------------------------------------------------------

    @Test fun setTime_acknowledged_isRecorded() = runBlocking {
        val device = env.store()
        env.clock = 9_000L
        val time = LocalDateTime.of(2026, 11, 11, 14, 44, 26)
        assertEquals(SendOutcome.Acknowledged, env.repository.setTime(device.id, time))
        assertEquals(time, env.sim.time)
        assertEquals(9_000L, env.dao.get(device.id)!!.timeSetAt)
    }

    @Test fun setTime_acknowledgementLost_timesOutAndRecordsNothing() = runBlocking {
        val device = env.store()
        env.sim.faults.dropEvery(1) // every acknowledgement is lost
        assertEquals(SendOutcome.NoReply, env.repository.setTime(device.id, LocalDateTime.of(2026, 1, 2, 3, 4, 5)))
        assertNull(env.dao.get(device.id)!!.timeSetAt)
        assertEquals(2, env.sim.received.size) // two attempts
    }

    @Test fun setTime_unknownDevice_isInvalid() = runBlocking {
        assertEquals(SendOutcome.Invalid, env.repository.setTime(42, LocalDateTime.now()))
    }

    // ---- Wi-Fi ------------------------------------------------------------------------

    @Test fun joinWifi_protected_sendsSsidThenKey() = runBlocking {
        val device = env.store()
        val outcome = env.repository.joinWifi(device.id, "TestNet", "secret-pass", WifiSecurity.WPA2, WifiCipher.AES)
        assertEquals(SendOutcome.Acknowledged, outcome)
        assertEquals(SimWifi("TestNet", false, WifiSecurity.WPA2, WifiCipher.AES, "secret-pass"), env.sim.wifi)
    }

    @Test fun joinWifi_open_sendsOneFrame() = runBlocking {
        val device = env.store()
        assertEquals(SendOutcome.Acknowledged, env.repository.joinWifi(device.id, "OpenNet", null, WifiSecurity.WPA2, WifiCipher.AES))
        assertEquals(SimWifi("OpenNet", true, null, null, null), env.sim.wifi)
        assertEquals(1, env.sim.received.size)
    }

    @Test fun joinWifi_tooLong_isRefusedBeforeAnythingIsSent() = runBlocking {
        val device = env.store()
        val tooLong = "k".repeat(33)
        assertEquals(SendOutcome.Invalid, env.repository.joinWifi(device.id, "Net", tooLong, WifiSecurity.WPA2, WifiCipher.AES))
        assertEquals(SendOutcome.Invalid, env.repository.joinWifi(device.id, tooLong, "key", WifiSecurity.WPA2, WifiCipher.AES))
        assertEquals(0, env.sim.received.size)
    }

    @Test fun joinWifi_neverLeavesTheKeyInTheDatabase() = runBlocking {
        val device = env.store()
        env.repository.joinWifi(device.id, "TestNet", "very-secret-key", WifiSecurity.WPA2, WifiCipher.AES)
        assertFalse(env.dao.all.toString().contains("very-secret-key"))
        val columns = DeviceEntity::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(columns.none { "pass" in it || "key" in it || "secret" in it })
    }

    @Test fun joinWifi_noAcknowledgement_isReported() = runBlocking {
        val device = env.store()
        env.sim.faults.dropEvery(1)
        assertEquals(SendOutcome.NoReply, env.repository.joinWifi(device.id, "Net", "key", WifiSecurity.WPA, WifiCipher.TKIP))
    }
}
