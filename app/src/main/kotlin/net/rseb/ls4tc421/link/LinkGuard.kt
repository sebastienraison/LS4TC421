package net.rseb.ls4tc421.link

import java.util.concurrent.atomic.AtomicReference
import ls4tc421.protocol.FrameEvent
import ls4tc421.protocol.FrameListener
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Writing a program and previewing never overlap: a preview frame in the middle of a write, or a
 * write while the ramp shows a preview, would leave the controller in an unknown state. One use at a
 * time for the whole app (there is one protocol client).
 */
class LinkGuard {
    enum class Use { WRITE, PREVIEW }

    private val current = AtomicReference<Use?>(null)

    val inUse: Use? get() = current.get()

    fun tryAcquire(use: Use): Boolean = current.compareAndSet(null, use)

    fun release(use: Use) {
        current.compareAndSet(use, null)
    }
}

/**
 * The frame listener given to the protocol client: forwards every frame to [delegate] (the exchange
 * log, [FrameLog]) and to the listeners added at run time (the upload counts its resends).
 */
class FrameTap(private val delegate: FrameListener? = null) : FrameListener {
    private val listeners = CopyOnWriteArrayList<FrameListener>()

    fun add(listener: FrameListener) = listeners.add(listener)

    fun remove(listener: FrameListener) = listeners.remove(listener)

    override fun onFrame(event: FrameEvent) {
        delegate?.onFrame(event)
        for (listener in listeners) listener.onFrame(event)
    }
}
