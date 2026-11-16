package net.rseb.ls4tc421

import androidx.lifecycle.viewModelScope
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import ls4tc421.protocol.WifiCipher
import ls4tc421.protocol.WifiSecurity
import ls4tc421.simulator.SimWifi
import net.rseb.ls4tc421.data.SendOutcome
import net.rseb.ls4tc421.ui.detail.ClockAction
import net.rseb.ls4tc421.ui.detail.DetailState
import net.rseb.ls4tc421.ui.detail.DeviceDetailViewModel
import net.rseb.ls4tc421.ui.wifi.AddressEdit
import net.rseb.ls4tc421.ui.wifi.AddressError
import net.rseb.ls4tc421.ui.wifi.SecurityChoice
import net.rseb.ls4tc421.ui.wifi.WifiSend
import net.rseb.ls4tc421.ui.wifi.WifiViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceDetailViewModelTest {
    private lateinit var env: TestEnv
    private val phoneTime = LocalDateTime.of(2026, 11, 12, 21, 52, 10)

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        env = TestEnv()
    }

    @After fun tearDown() {
        env.close()
        Dispatchers.resetMain()
    }

    @Test fun setToPhoneTime_acknowledged_showsWhenItWasSet() = runBlocking {
        val device = env.store()
        val vm = DeviceDetailViewModel(device.id, env.repository) { phoneTime }
        env.clock = 3_000L
        vm.setToPhoneTime()
        assertEquals(ClockAction.Done(SendOutcome.Acknowledged), vm.clock.await { it is ClockAction.Done })
        assertEquals(phoneTime, env.sim.time)
        val present = vm.state.await { it is DetailState.Present && it.device.timeSetAt != null } as DetailState.Present
        assertEquals(3_000L, present.device.timeSetAt)
        vm.viewModelScope.cancel()
    }

    @Test fun setTimeManually_sendsTheChosenTime() = runBlocking {
        val device = env.store()
        val vm = DeviceDetailViewModel(device.id, env.repository) { phoneTime }
        val chosen = LocalDateTime.of(2027, 3, 4, 5, 6, 0)
        vm.setTime(chosen)
        vm.clock.await { it is ClockAction.Done }
        assertEquals(chosen, env.sim.time)
        vm.viewModelScope.cancel()
    }

    @Test fun setTime_timeout_isReportedAndNothingIsRecorded() = runBlocking {
        val device = env.store()
        env.sim.faults.dropEvery(1)
        val vm = DeviceDetailViewModel(device.id, env.repository) { phoneTime }
        vm.setToPhoneTime()
        assertEquals(ClockAction.Done(SendOutcome.NoReply), vm.clock.await { it is ClockAction.Done })
        val present = vm.state.value as DetailState.Present
        assertEquals(null, present.device.timeSetAt)
        vm.dismissClockResult()
        assertEquals(ClockAction.Idle, vm.clock.value)
        vm.viewModelScope.cancel()
    }

    @Test fun renameAndDelete() = runBlocking {
        val device = env.store("Old")
        val vm = DeviceDetailViewModel(device.id, env.repository)
        vm.rename("  New  ")
        vm.state.await { it is DetailState.Present && it.device.name == "New" }
        vm.rename("   ") // refused: a name is required
        assertEquals("New", env.dao.all.single().name)
        vm.delete()
        vm.state.await { it == DetailState.Gone }
        assertTrue(env.dao.all.isEmpty())
        vm.viewModelScope.cancel()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class WifiViewModelTest {
    private lateinit var env: TestEnv

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        env = TestEnv()
    }

    @After fun tearDown() {
        env.close()
        Dispatchers.resetMain()
    }

    private suspend fun newVm(default: String? = null): WifiViewModel =
        WifiViewModel(env.store().id, env.repository, flowOf(default))

    @Test fun changeAddress_sameController_isSavedAndClosesTheDialog() = runBlocking {
        val id = env.dao.insert(net.rseb.ls4tc421.data.DeviceEntity(name = "Tank", address = "192.0.2.7", mac = "AABBCCDDEEFF", activeSlot = 4))
        val vm = WifiViewModel(id, env.repository, flowOf(null))
        vm.openAddress()
        assertEquals(AddressEdit.Editing(), vm.address.value)
        vm.changeAddress("127.0.0.1")
        vm.address.await { it == AddressEdit.Closed }
        val device = vm.device.await { it?.address == "127.0.0.1" }!!
        assertEquals(4, device.activeSlot)
        vm.viewModelScope.cancel()
    }

    @Test fun changeAddress_refusals_keepTheDialogOpenAndSayWhy() = runBlocking {
        val other = env.store(mac = "112233445566") // the simulator answers with another MAC
        val vm = WifiViewModel(other.id, env.repository, flowOf(null))
        vm.openAddress()
        vm.changeAddress("not an ip")
        assertEquals(AddressEdit.Editing(AddressError.InvalidAddress), vm.address.value)
        assertEquals(0, env.sim.received.size)
        vm.changeAddress("127.0.0.1")
        assertEquals(AddressEdit.Editing(AddressError.OtherController), vm.address.await { it is AddressEdit.Editing && it.error != AddressError.InvalidAddress })
        env.sim.close()
        vm.changeAddress("127.0.0.1")
        assertEquals(AddressEdit.Editing(AddressError.NoReply), vm.address.await { it == AddressEdit.Editing(AddressError.NoReply) })
        vm.closeAddress()
        assertEquals(AddressEdit.Closed, vm.address.value)
        assertEquals("127.0.0.1", env.dao.get(other.id)!!.address) // unchanged: it was already this address
        vm.viewModelScope.cancel()
    }

    @Test fun networkIsPrefilledWithThePhonesNetwork_untilTheUserTypes() = runBlocking {
        val vm = newVm("HomeNet")
        assertEquals("HomeNet", vm.form.value.ssid)
        vm.onSsidChange("Other")
        assertEquals("Other", vm.form.value.ssid)
        vm.viewModelScope.cancel()
    }

    @Test fun noRememberedNetwork_leavesTheFieldEmptyAndTypingWorks() = runBlocking {
        val vm = newVm(null)
        assertEquals("", vm.form.value.ssid)
        assertFalse(vm.form.value.canSend)
        vm.onSsidChange("Typed")
        vm.onPasswordChange("pw")
        assertTrue(vm.form.value.canSend)
        vm.viewModelScope.cancel()
    }

    @Test fun defaults_areWpa2AndAes() = runBlocking {
        val vm = newVm()
        assertEquals(SecurityChoice.Wpa2, vm.form.value.security)
        assertEquals(WifiCipher.AES, vm.form.value.cipher)
        vm.viewModelScope.cancel()
    }

    @Test fun protectedNetwork_isSent_andTheKeyIsForgottenOnceAcknowledged() = runBlocking {
        val vm = newVm("HomeNet")
        vm.onPasswordChange("a-long-enough-key")
        vm.send()
        vm.form.await { it.send is WifiSend.Done }
        assertEquals(WifiSend.Done(SendOutcome.Acknowledged), vm.form.value.send)
        assertEquals(SimWifi("HomeNet", false, WifiSecurity.WPA2, WifiCipher.AES, "a-long-enough-key"), env.sim.wifi)
        assertEquals("", vm.form.value.password)
        vm.viewModelScope.cancel()
    }

    @Test fun wpaAndTkip_areHonoured() = runBlocking {
        val vm = newVm("HomeNet")
        vm.onSecurityChange(SecurityChoice.Wpa)
        vm.onCipherChange(WifiCipher.TKIP)
        vm.onPasswordChange("pw12345678")
        vm.send()
        vm.form.await { it.send is WifiSend.Done }
        assertEquals(SimWifi("HomeNet", false, WifiSecurity.WPA, WifiCipher.TKIP, "pw12345678"), env.sim.wifi)
        vm.viewModelScope.cancel()
    }

    @Test fun openNetwork_needsNoKey_andSendsOneFrame() = runBlocking {
        val vm = newVm("CafeNet")
        vm.onSecurityChange(SecurityChoice.Open)
        assertTrue(vm.form.value.canSend)
        vm.send()
        vm.form.await { it.send is WifiSend.Done }
        assertEquals(SimWifi("CafeNet", true, null, null, null), env.sim.wifi)
        assertEquals(1, env.sim.received.size)
        vm.viewModelScope.cancel()
    }

    @Test fun overThirtyTwoBytes_isRefusedBeforeSending_forNameAndKey() = runBlocking {
        val vm = newVm("HomeNet")
        vm.onPasswordChange("k".repeat(33))
        assertTrue(vm.form.value.passwordTooLong)
        assertFalse(vm.form.value.canSend)
        vm.send()
        assertEquals(WifiSend.Idle, vm.form.value.send)

        vm.onPasswordChange("k".repeat(32)) // exactly the limit: accepted
        assertTrue(vm.form.value.canSend)

        vm.onSsidChange("€".repeat(11)) // 11 characters but 33 bytes in UTF-8
        assertEquals(33, vm.form.value.ssidBytes)
        assertTrue(vm.form.value.ssidTooLong)
        assertFalse(vm.form.value.canSend)
        vm.send()
        assertEquals(0, env.sim.received.size) // nothing ever left
        vm.viewModelScope.cancel()
    }

    @Test fun overLimitKey_doesNotBlockAnOpenNetwork() = runBlocking {
        val vm = newVm("CafeNet")
        vm.onPasswordChange("k".repeat(40))
        vm.onSecurityChange(SecurityChoice.Open)
        assertTrue(vm.form.value.canSend)
        vm.viewModelScope.cancel()
    }

    @Test fun noAcknowledgement_isReported_andKeyStaysInMemoryForARetry() = runBlocking {
        env.sim.faults.dropEvery(1)
        val vm = newVm("HomeNet")
        vm.onPasswordChange("retry-key")
        vm.send()
        vm.form.await { it.send is WifiSend.Done }
        assertEquals(WifiSend.Done(SendOutcome.NoReply), vm.form.value.send)
        assertEquals("retry-key", vm.form.value.password)
        vm.viewModelScope.cancel()
    }
}
