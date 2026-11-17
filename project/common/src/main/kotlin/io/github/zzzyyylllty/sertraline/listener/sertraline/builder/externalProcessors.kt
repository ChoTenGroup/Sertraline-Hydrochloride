package io.github.zzzyyylllty.sertraline.listener.sertraline.builder

import io.github.zzzyyylllty.sertraline.Sertraline.itemManager
import io.github.zzzyyylllty.sertraline.debugMode.devLog

/**
 * Registry of item-section processors registered by external plugins (such as ChoTenTech).
 *
 * ### Why this registry is needed
 * Each time `Sertraline` reloads configuration, it calls `itemManager.unregisterAllProcessor()` and then registers only
 * the **built-in** processors ([registerNativeAdapter]). If an external plugin only calls `itemManager().registerProcessor(...)`,
 * its sections (`itemdef` / `model` / `mag` / `ammo` / `addon` / `armor`) silently stop working after `/sreload`.
 * Items then lose their names, descriptions, and models, and magazines lose their runtime fields.
 *
 * External registrations must therefore go through [SertralineAPI.registerItemProcessor] to be recorded here.
 * [registerNativeAdapter] calls [applyExternalProcessors] after each reload to reattach them.
 */
object ExternalItemProcessors {

    /** Section name -> processor (registration order is preserved). */
    private val processors = LinkedHashMap<String, ItemProcessor>()

    /** Records the processor and immediately attaches it to the current live processor table. */
    fun register(name: String, processor: ItemProcessor) {
        processors[name] = processor
        itemManager.forceRegisterProcessor(name, processor)
    }

    /** Reattaches all external processors after a reload; called by [registerNativeAdapter]. */
    fun applyExternalProcessors() {
        if (processors.isEmpty()) return
        for ((name, processor) in processors) {
            itemManager.forceRegisterProcessor(name, processor)
        }
        devLog("Re-registered ${processors.size} external item processors: ${processors.keys}")
    }

    /** All currently registered section names (for debugging). */
    fun names(): List<String> = processors.keys.toList()
}
