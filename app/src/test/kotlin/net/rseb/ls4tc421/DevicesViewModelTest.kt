package net.rseb.ls4tc421

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import androidx.lifecycle.viewModelScope
import net.rseb.ls4tc421.ui.devices.AddError
import net.rseb.ls4tc421.ui.devices.AddState
import net.rseb.ls4tc421.ui.devices.DevicesViewModel
import net.rseb.ls4tc421.ui.devices.NetworkSearch
import net.rseb.ls4tc421.ui.devices.Reach
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DevicesViewModelTest {
    private lateinit var env: TestEnv
    private lateinit var vm: DevicesViewModel

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        env = TestEnv()
        vm = DevicesViewModel(env.repository)
    }

    @After fun tearDown() {
        vm.viewModelScope.cancel()
        env.close()
        Dispatchers.resetMain()
    }

    @Test fun opening_probesEachKnownControllerByAddress() = runBlocking {
        val answering = env.store("Answering")
        val impostor = env.store("Other MAC", mac = "112233445566") // same address, another controller
        vm.refresh(onWifi = false)
        vm.reach.await { it.size == 2 && Reach.Checking !in it.values }
        assertEquals(Reach.Reachable, vm.reach.value[answering.id])
        assertEquals(Reach.Unreachable, vm.reach.value[impostor.id])
        assertEquals(NetworkSearch.NotOnWifi, vm.network.value)
        assertEquals(2, env.sim.received.size) // one unicast discovery per known controller, no broadcast
    }

    @Test fun unreachableController_keepsItsLastSeenForDisplay() = runBlocking {
        val device = env.store()
        env.clock = 4_000L
        vm.refresh(false)
        vm.reach.await { it[device.id] == Reach.Reachable }
        env.sim.close()
        env.clock = 8_000L
        vm.refresh(false)
        vm.reach.await { it[device.id] == Reach.Unreachable }
        assertEquals(4_000L, env.dao.get(device.id)!!.lastSeenAt) // « ne répond pas · vu le … »
    }

    @Test fun onWifi_foundControllerNotYetKnown_canBeAddedWithOneTap() = runBlocking {
        vm.refresh(onWifi = true)
        vm.network.await { it == NetworkSearch.Done }
        val found = vm.nearby.await { it.isNotEmpty() }.single()
        assertEquals("AABBCCDDEEFF", found.mac.text)
        vm.addNearby(found, "Tank")
        vm.known.await { it.size == 1 }
        assertEquals("Tank", env.dao.all.single().name)
        vm.nearby.await { it.isEmpty() }
        Unit // JUnit needs a void test method
    }

    @Test fun added_byTheSearch_opensItsPageOnce_D31() = runBlocking {
        vm.refresh(onWifi = true)
        vm.network.await { it == NetworkSearch.Done }
        val found = vm.nearby.await { it.isNotEmpty() }.single()
        vm.addNearby(found, "Tank")
        val id = vm.added.await { it != null }
        assertEquals(env.dao.all.single().id, id)
        vm.addedHandled()
        assertEquals(null, vm.added.value)
    }

    @Test fun added_byAddress_opensItsPage_butAKnownOneDoesNot_D31() = runBlocking {
        vm.openAdd()
        vm.addByAddress("127.0.0.1", "Tank")
        vm.add.await { it == AddState.Closed }
        assertEquals(env.dao.all.single().id, vm.added.value)
        vm.addedHandled()

        vm.openAdd()
        vm.addByAddress("127.0.0.1", "Again")
        vm.add.await { it is AddState.AlreadyKnown }
        assertEquals(null, vm.added.value) // already known: the question is not asked again
    }

    @Test fun onWifi_knownControllerIsNotOfferedAgain() = runBlocking {
        val known = env.store()
        vm.refresh(onWifi = true)
        vm.network.await { it == NetworkSearch.Done }
        assertTrue(vm.nearby.value.isEmpty())
        assertEquals(Reach.Reachable, vm.reach.value[known.id])
    }

    @Test fun onWifi_knownControllerAtANewAddress_isSaidUpdated() = runBlocking {
        // Stored at another loopback address, where nothing answers: the probe fails, the search finds it.
        val id = env.dao.insert(net.rseb.ls4tc421.data.DeviceEntity(name = "Tank", address = "127.0.0.2", mac = "AABBCCDDEEFF"))
        vm.refresh(onWifi = true)
        vm.network.await { it == NetworkSearch.Done }
        assertEquals(listOf("Tank"), vm.moved.value.map { it.name })
        assertTrue(vm.anyAnswered.value)
        assertTrue(vm.nearby.value.isEmpty())
        assertEquals(Reach.Reachable, vm.reach.value[id])
        assertEquals("127.0.0.1", env.dao.get(id)!!.address)
    }

    @Test fun onWifi_knownControllerAtTheSameAddress_isOnlyReachable() = runBlocking {
        env.store()
        vm.refresh(onWifi = true)
        vm.network.await { it == NetworkSearch.Done }
        assertTrue(vm.moved.value.isEmpty())
        assertTrue(vm.anyAnswered.value) // so no « no new controller found »
    }

    @Test fun onWifi_nobodyAnswers_isNoNewControllerFound() = runBlocking {
        env.sim.close()
        vm.refresh(onWifi = true)
        vm.network.await { it == NetworkSearch.Done }
        assertFalse(vm.anyAnswered.value)
        assertTrue(vm.moved.value.isEmpty())
    }

    @Test fun addByAddress_success_closesTheDialogAndSaves() = runBlocking {
        vm.openAdd()
        vm.addByAddress(" 127.0.0.1 ", " Aquarium ")
        vm.add.await { it == AddState.Closed }
        vm.known.await { it.size == 1 }
        assertEquals("Aquarium", env.dao.all.single().name)
        assertEquals("AABBCCDDEEFF", env.dao.all.single().mac)
    }

    @Test fun addByAddress_noReply_keepsTheDialogOpenAndSavesNothing() = runBlocking {
        env.sim.close()
        vm.openAdd()
        vm.addByAddress("127.0.0.1", "Aquarium")
        assertEquals(AddState.Editing(AddError.NoReply), vm.add.await { it is AddState.Editing && it.error != null })
        assertTrue(env.dao.all.isEmpty())
    }

    @Test fun addByAddress_invalidInput_isRefusedLocally() = runBlocking {
        vm.openAdd()
        vm.addByAddress("not an ip", "Aquarium")
        assertEquals(AddState.Editing(AddError.InvalidAddress), vm.add.value)
        vm.addByAddress("127.0.0.1", "   ")
        assertEquals(AddState.Editing(AddError.NameRequired), vm.add.value)
        assertEquals(0, env.sim.received.size)
    }

    @Test fun addByAddress_alreadyKnownMac_isSaidNotDuplicated() = runBlocking {
        env.store("Existing")
        vm.addByAddress("127.0.0.1", "Another name")
        assertEquals(AddState.AlreadyKnown("Existing"), vm.add.await { it is AddState.AlreadyKnown })
        assertEquals(1, env.dao.all.size)
    }
}
