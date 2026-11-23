package io.github.zzzyyylllty.sertraline.listener.sertraline.builder

import io.github.zzzyyylllty.sertraline.Sertraline.itemManager
import io.github.zzzyyylllty.sertraline.debugMode.devLog

/** Retains externally registered sections in registration order. */
object ExternalItemProcessors {

    /** Section name -> processor (registration order is preserved). */
    private val processors = LinkedHashMap<String, ItemProcessor>()

    /** Records the processor and immediately attaches it to the current live processor table. */
    @Synchronized
    fun register(name: String, processor: ItemProcessor) {
        processors[name] = processor
        itemManager.forceRegisterProcessor(name, processor)
    }

    /** Reattaches all external processors after a reload; called by [registerNativeAdapter]. */
    @Synchronized
    fun applyExternalProcessors() {
        if (processors.isEmpty()) return
        for ((name, processor) in processors) {
            itemManager.forceRegisterProcessor(name, processor)
        }
        devLog("Re-registered ${processors.size} external item processors: ${processors.keys}")
    }

    @Synchronized
    internal fun clear() = processors.clear()

    /** All currently registered section names (for debugging). */
    @Synchronized
    fun names(): List<String> = processors.keys.toList()
}
