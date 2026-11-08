package io.github.zzzyyylllty.sertraline.listener.sertraline.builder

import io.github.zzzyyylllty.sertraline.config.common.SertralineSettings
import io.github.zzzyyylllty.sertraline.data.AbstractSItem
import io.github.zzzyyylllty.sertraline.data.ModernSItem
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack


fun interface ItemProcessor {
    fun process(item: AbstractSItem, itemStack: ItemStack, player: Player?): ItemStack
}

class ItemProcessorManager {
    // LinkedHashMap preserves insertion order
    private val processors = LinkedHashMap<String, ItemProcessor>()

    /** Processor names already reported as binary-incompatible (warn once, not per build). */
    private val reportedIncompatible: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    // Register a processor by name as the key, for easier management
    fun registerProcessor(name: String, processor: ItemProcessor) {
        if (SertralineSettings.rawBoolean("modules.$name.feature", true)) processors[name] = processor
    }

    /**
     * Registers a processor **ignoring** the `modules.<name>.feature` switch.
     *
     * Plugin-provided segments (for example ChoTenTech's `itemdef` / `gun` / `mag`) are not
     * Sertraline modules, so the per-module switch must not be able to silently disable them.
     */
    fun forceRegisterProcessor(name: String, processor: ItemProcessor) {
        processors[name] = processor
    }

    // Remove a processor
    fun unregisterProcessor(name: String) {
        processors.remove(name)
        reportedIncompatible.remove(name)
    }

    fun unregisterAllProcessor() {
        processors.clear()
        reportedIncompatible.clear()
    }

    /**
     * Apply all processors in order.
     *
     * [AbstractSItem.shouldAdapt] is the single gate for the whole Sertraline Adapter pipeline:
     * when it is `false` every processor is skipped. This only affects the build stage; the
     * load-time `itemModule*` chain is not controlled by it.
     */
    fun processItem(item: AbstractSItem, itemStack: ItemStack, player: Player?): ItemStack {
        if (!item.shouldAdapt) return itemStack
        var currentItem = itemStack
        for (processor in processors) {
            if (!item.config.contains(processor.key)) continue
            currentItem = try {
                processor.value.process(item, currentItem, player)
            } catch (e: AbstractMethodError) {
                // ItemProcessor.process() changed parameter type from ModernSItem to AbstractSItem
                // when multi-source items were introduced. A third-party processor compiled against
                // the old interface does not implement the new abstract method, so the JVM throws
                // AbstractMethodError *per build*, deep inside the item pipeline where the cause is
                // unrecognisable. Report it once, name the processor, and keep building.
                if (reportedIncompatible.add(processor.key)) {
                    io.github.zzzyyylllty.sertraline.logger.warningS(
                        "ItemProcessor '${processor.key}' was compiled against an older Sertraline API " +
                            "(process(ModernSItem, ...)). It cannot run until it is recompiled against " +
                            "the AbstractSItem signature; its segment is skipped."
                    )
                }
                currentItem
            }
        }
        return currentItem
    }

    // For inspecting the currently registered processors
    fun listProcessors(): List<String> = processors.keys.toList()
}
