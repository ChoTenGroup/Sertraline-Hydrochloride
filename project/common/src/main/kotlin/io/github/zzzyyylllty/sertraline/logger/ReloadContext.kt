package io.github.zzzyyylllty.sertraline.logger

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Isolated reporting context for a single reload.
 *
 * errors/warnings/stats belong only to this reload. [ReloadCollector] delegates writes to this context,
 * which [ReloadCoordinator] creates and binds at the start of a reload, then unbinds at the end.
 *
 * CopyOnWriteArrayList makes concurrent writes safe (loader submitAsync paths may call addError/addWarning concurrently).
 */
class ReloadContext(val id: Long) {

    val errors = CopyOnWriteArrayList<String>()
    val warnings = CopyOnWriteArrayList<String>()
    val stats = CopyOnWriteArrayList<String>()

    @Volatile
    var summaryPrinted: Boolean = false
        private set

    /** Marks this reload's summary as printed so printSummary remains idempotent (printed once per reload). */
    fun markSummaryPrinted() {
        summaryPrinted = true
    }
}

/**
 * Serializes reload operations.
 *
 * The gate allows at most one reload at a time; concurrent requests are rejected (returning null).
 * This prevents a second concurrent clear-and-rebuild cycle and keeps it from modifying the active reload's report data.
 */
object ReloadCoordinator {

    private val gate = AtomicBoolean(false)
    private val counter = AtomicLong(0)

    /** Whether a reload is currently running. */
    val isActive: Boolean get() = gate.get()

    /**
     * Attempts to start a reload.
     *
     * @return The new [ReloadContext], already bound to [ReloadCollector], or null if another reload is running.
     */
    @Synchronized
    fun tryBegin(): ReloadContext? {
        if (!gate.compareAndSet(false, true)) return null
        return ReloadContext(counter.incrementAndGet()).also { ReloadCollector.bind(it) }
    }

    @Synchronized
    internal fun <T> liveMutation(action: () -> T): T {
        check(!isActive) { "Item registry changes must wait until configuration reload completes" }
        return action()
    }

    /** Ends the current reload and releases the gate (called only by the reload flow that acquired it). */
    @Synchronized
    fun end() {
        ReloadCollector.unbind()
        gate.set(false)
    }
}
