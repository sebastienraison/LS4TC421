package net.rseb.ls4tc421

import android.app.Application
import java.net.SocketException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ls4tc421.protocol.ClientConfig
import ls4tc421.protocol.FrameListener
import ls4tc421.protocol.Tc421Client
import net.rseb.ls4tc421.data.AppDatabase
import net.rseb.ls4tc421.data.ClientSource
import net.rseb.ls4tc421.data.DeviceDao
import net.rseb.ls4tc421.data.DeviceRepository
import net.rseb.ls4tc421.data.ProgramRepository
import net.rseb.ls4tc421.data.SettingsStore
import net.rseb.ls4tc421.data.SlotDao
import net.rseb.ls4tc421.data.SsidRules
import net.rseb.ls4tc421.link.FrameLog
import net.rseb.ls4tc421.link.FrameTap
import net.rseb.ls4tc421.link.LinkGuard

/** Hand-wired dependencies: no injection library. */
class AppContainer(app: Application) {
    private val database = AppDatabase.create(app)
    val settings = SettingsStore(app)
    val phone = PhoneNetwork(app)
    /** The exchange log (D28), in memory only: Settings shares it, the debug bench shows it. */
    val frameLog = FrameLog()
    /** Every frame of the protocol client: the exchange log gets them all; the upload counts its resends. */
    val frames = FrameTap(frameLog)
    /** The one protocol client of the app. */
    val clients: ClientSource = LazyClientSource(frames)
    val deviceDao: DeviceDao = database.deviceDao()
    /** What the app knows of each slot of each device (D31). */
    val slotDao: SlotDao = database.slotDao()
    val repository = DeviceRepository(deviceDao, slotDao, clients)
    val programs = ProgramRepository(database.programDao(), slotDao)
    /** Writing and previewing never overlap. */
    val guard = LinkGuard()
    /** Outlives the screens: a preview must still be ended (`03 08`) after its screen is gone. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** What the preview screen plays (set just before opening it). */
    var previewRequest: PreviewRequest? = null

    /**
     * Reads the phone's network and remembers its Wi-Fi name as the default network proposed for the
     * controller, unless it is the controller's own access point ([SsidRules.isControllerAccessPoint]).
     */
    suspend fun refreshNetwork(): NetworkSnapshot {
        val snapshot = phone.refresh()
        if (SsidRules.shouldRemember(snapshot.ssid)) settings.rememberSsid(snapshot.ssid!!)
        return snapshot
    }
}

/**
 * One protocol client for the whole app, created on first use off the main thread. It binds a free
 * UDP port chosen by the system (the controller answers to the source port), so another app holding
 * port 5000 does not take its replies. If the bind fails the error is not cached: the next call
 * tries again.
 */
private class LazyClientSource(private val frames: FrameListener) : ClientSource {
    private val lock = Mutex()
    private var client: Tc421Client? = null

    @Throws(SocketException::class)
    override suspend fun get(): Tc421Client = lock.withLock {
        client ?: withContext(Dispatchers.IO) {
            Tc421Client(ClientConfig(frameListener = frames))
        }.also { client = it }
    }
}

class LsApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** A preview to play: [name] sent with `06 01`, the 48 [steps], and the device (null = to choose). */
data class PreviewRequest(val name: String, val steps: List<List<Int>>, val deviceId: Long?)
