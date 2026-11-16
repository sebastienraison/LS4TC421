package net.rseb.ls4tc421.ui.preview

import androidx.lifecycle.ViewModel
import net.rseb.ls4tc421.AppContainer
import net.rseb.ls4tc421.PreviewRequest
import net.rseb.ls4tc421.data.toDevice
import net.rseb.ls4tc421.link.PreviewPlayer

/**
 * Holds the [PreviewPlayer] of the preview screen. The player runs in the app scope: when this view
 * model goes away, [onCleared] still ends an open preview with `03 08`.
 */
class PreviewViewModel(container: AppContainer, val request: PreviewRequest) : ViewModel() {
    val player = PreviewPlayer(
        clients = container.clients,
        device = { request.deviceId?.let { container.deviceDao.get(it) }?.toDevice() },
        name = request.name,
        steps = request.steps,
        guard = container.guard,
        scope = container.appScope,
    )

    override fun onCleared() {
        player.exit()
    }
}
