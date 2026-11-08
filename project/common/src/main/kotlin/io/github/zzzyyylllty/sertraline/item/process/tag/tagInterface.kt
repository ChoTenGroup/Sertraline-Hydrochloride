package io.github.zzzyyylllty.sertraline.item.process.tag

import io.github.zzzyyylllty.sertraline.config.common.SertralineSettings
import io.github.zzzyyylllty.sertraline.data.AbstractSItem
import io.github.zzzyyylllty.sertraline.debugMode.devLog
import io.github.zzzyyylllty.sertraline.util.replacePlaceholderSafety
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import taboolib.module.ui.type.Linked
import kotlin.collections.iterator

data class ProcessItemTagData(
    var item: AbstractSItem,
    val itemStack: ItemStack?,
    val player: Player?
)


fun interface TagProcessor {
    fun process(data: ProcessItemTagData, player: Player?, repl: Map<String, List<String>>, target: MutableMap<String, String?>)
}

class TagProcessorManager {
    // LinkedHashMap preserves insertion order
    private val processors = LinkedHashMap<String, TagProcessor>()
    val aliases = LinkedHashMap<String, String>()

    // Register a processor, using the name as the key for easy management
    fun registerProcessor(name: String, tagAliases: List<String> = listOf(), processor: TagProcessor) {
        if (SertralineSettings.rawBoolean("tags.$name", true)) {
            processors[name] = processor
            aliases[name] = name
            for (alias in tagAliases) {
                if (SertralineSettings.rawBoolean("tags.$alias", true)) aliases[alias] = name
            }
        }
    }

    // Remove a processor
    fun unregisterProcessor(name: String) {
        processors.remove(name)
    }

    fun unregisterAllProcessor() {
        processors.clear()
    }

    // Run all processors in order
    fun processItem(itemJson: String, item: AbstractSItem,itemStack: ItemStack?, player: Player?): String {
//        var itemData = ProcessItemTagData(itemJson, item, itemStack, player, extractPlaceholders(itemJson))
//        for (processor in processors) {
//            itemData = processor.value.process(itemData, player)
//        }

        val repl: Set<String> = extractPlaceholders(itemJson) ?: return itemJson

        // Extract all processor names for prefix matching
        val processorNames = aliases.keys.toList()

        // An empty set returns directly
        if (repl.isEmpty()) return itemJson

        // Perform the conversion
        val groupedRepl: Map<String, List<String>> = repl // operate directly on the Set
            .mapNotNull { fullKey -> // fullKey is each String element in the Set
                // Try to find a matching prefix
                val matchingProcessorName = processorNames.firstOrNull { pName ->
                    fullKey.startsWith("$pName:")
                }

                if (matchingProcessorName != null) {
                    // If a match is found, extract the inner key
                    val innerKey = fullKey.substringAfter("$matchingProcessorName:")
                    // Return a Pair containing the matching processor name and the inner key
                    matchingProcessorName to innerKey // use the infix function to build a Pair
                } else {
                    // If no processor name prefix matches, return null;
                    // mapNotNull will filter out these null values
                    null
                }
            }
            .groupBy { (processorName, _) -> processorName } // group by processor name; destructure the Pair here
            .mapValues { (_, listOfPairs) -> // for each group, listOfPairs is List<Pair<String, String>>
                // Convert List<Pair<processorName, innerKey>> to List<innerKey>
                listOfPairs.map { (_, innerKey) -> innerKey } // extract the innerKey from the Pair
            }

        val itemData = ProcessItemTagData(item, itemStack, player)
        val target = mutableMapOf<String, String?>()

        // linkedMapOf<processorId, LinkedHashMap<tagHeadId, List<content>>?>()
        // e.g. - sertraline={var={"name"="name"}}
        val useProcessors = linkedMapOf<String, LinkedHashMap<String, List<String>>?>()

        for ((key, value) in groupedRepl) {

            // "val:constant-id"
            // key = "val"
            // value = "constant-id"

            val processorName = aliases[key] ?: continue
            if (useProcessors[processorName] == null) {
                useProcessors[processorName] = linkedMapOf()
            }
            useProcessors[processorName]?.put(key, value)
        }

        devLog("groupedRepl: $groupedRepl")
        devLog("itemData: $itemData")
        devLog("useProcessors: $useProcessors")

        for ((name, processor) in useProcessors) {
            processor?.let {
                processors[name]?.process(itemData, player, it, target)
            }
        }

        var json = itemJson.replacePlaceholderSafety(player)

        devLog("targetRepl: $target")

        target.forEach { (key, value) ->
            json = if (value != "null" && value != null) {
                // If the replacement value is not empty
                json
                    .replace("\${$key}$", value)
                    .replace("\"\${$key}$\"", value)
            } else if (!key.endsWith("!!")) {
                // If the key is not marked as non-null, i.e. nullable, the value is replaced with null even when it is null.
                json
                    .replace("\${$key}$", "null")
                    .replace("\"\${$key}$\"", "null")
            } else {
                // If the key is marked as non-null, the value is replaced with an empty string even when it is null.
                json.replace("\${$key}$", "")
                    .replace("\"\${$key}$\"", "")
            }
        }

        return json
    }

    // Can view the currently registered processors
    fun listProcessors(): List<String> = processors.keys.toList()
}

fun extractPlaceholders(input: String): Set<String>? {

    // Return directly if there is no placeholder
    if (!input.contains("\${")) return null

    val regex = "\\$\\{(.*?)}\\$".toRegex() // A regex to match ${xxx}
    val result = mutableSetOf<String>() // Create a Map to store the results

    regex.findAll(input).forEach { matchResult ->
        val key = matchResult.groupValues[1]
        result.add(key)
    }

    return result
}

fun String.processRawTagKey(): String {
    return this.removeSuffix("!!")
}