package net.rseb.ls4tc421.bench

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import ls4tc421.protocol.Device
import ls4tc421.protocol.MacAddress
import net.rseb.ls4tc421.AppContainer
import net.rseb.ls4tc421.data.Ipv4

/**
 * Process-wide state of the bench: one controller per device (the frame log is the app's exchange
 * log, [AppContainer.frameLog]). The controllers
 * outlive the screen so that leaving it can still send `03 08`, and a write keeps going (and shows
 * its result) if the screen is left and opened again.
 */
object Bench {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val controllers = HashMap<Long, BenchController>()

    @Synchronized
    fun controller(id: Long, container: AppContainer): BenchController = controllers.getOrPut(id) {
        BenchController(
            clients = container.clients,
            device = {
                container.repository.device(id).first()?.let { stored ->
                    val address = Ipv4.parse(stored.address)
                    val mac = MacAddress.parseOrNull(stored.mac)
                    if (address != null && mac != null) Device(address, mac) else null
                }
            },
            scope = scope,
        )
    }
}
