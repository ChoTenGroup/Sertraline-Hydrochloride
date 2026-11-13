package io.github.zzzyyylllty.sertraline.listener.sertraline

import io.github.zzzyyylllty.sertraline.attribute.AttributeRegistry
import io.github.zzzyyylllty.sertraline.attribute.data.formatAttributeValue
import io.github.zzzyyylllty.sertraline.attribute.data.parseAttributeValue
import io.github.zzzyyylllty.sertraline.config.asListEnhanced
import io.github.zzzyyylllty.sertraline.event.ItemLoadEvent
import taboolib.common.platform.event.EventPriority
import taboolib.common.platform.event.SubscribeEvent

/** Reserved keys of the `attribute:` section; they are not attribute ids. */
private val attributeReservedKeys = setOf("slot", "allowed", "source", "id")

/** Item attributes and lore. */
@SubscribeEvent(priority = EventPriority.NORMAL)
fun itemModuleAttribute(e: ItemLoadEvent) {
    val snapshot = e.context?.attributes ?: AttributeRegistry.snapshot()
    val modifiers = validateAttributeModifiers(e.itemKey, e.arguments, snapshot)
    if (modifiers != null) e.itemData["attribute-modifiers"] = modifiers
    @Suppress("UNCHECKED_CAST")
    val section = e.arguments["attribute"] as? Map<String, Any?> ?: return
    if (section.isEmpty()) return

    val loreLines = ArrayList<String>(section.size)

    for ((rawKey, rawValue) in section) {
        val key = AttributeRegistry.normalizeId(rawKey)
        when {
            key == "allowed" -> e.itemData["attribute:allowed"] = rawValue.asListEnhanced() ?: listOf<String>()
            key in attributeReservedKeys -> e.itemData["attribute:$key"] = rawValue?.toString() ?: ""
            rawValue == null -> continue
            else -> {
                e.itemData["attribute:$key"] = rawValue
                val parsed = parseAttributeValue(rawValue) ?: continue
                val type = snapshot.types[key]
                    ?: if (e.context == null) AttributeRegistry.getOrRegister(key) else null
                val name = type?.name ?: key
                val value = formatAttributeValue(type, parsed.first, parsed.second)
                loreLines.add("<!i><#777777>| <#cccccc>$name <white>$value")
            }
        }
    }

    if (loreLines.isNotEmpty()) e.itemData["attribute-lore:lines"] = loreLines
}

/** Validate before Bukkit dispatch. */
fun validateAttributeModifiers(itemKey: String, arguments: Map<String, Any?>, snapshot: AttributeRegistry.Snapshot): List<*>? {
    val settings = arguments["settings"] as? Map<*, *>
    val modifiers = arguments["attribute-modifiers"] ?: settings?.get("attribute_modifiers") ?: return null
    require(modifiers is List<*>) { "Invalid attribute-modifiers: $itemKey" }
    for (raw in modifiers) {
        val node = raw as? Map<*, *> ?: error("Invalid modifier: $itemKey")
        require(listOf("id", "type", "amount", "operation", "slot").all { node[it] != null }) { "Incomplete modifier: $itemKey" }
        require((node["scope"] ?: "entity") in setOf("entity", "weapon")) { "Invalid scope: $itemKey" }
        require(AttributeRegistry.isValidId(node["id"].toString())) { "Invalid modifier id: $itemKey" }
        val id = AttributeRegistry.normalizeId(node["type"].toString())
        val type = snapshot.types[id]
        require(type != null && type.derived == null) { "Unknown or derived attribute: $id" }
        val operation = io.github.zzzyyylllty.sertraline.attribute.AttributeDefinitionParser.operation(node["operation"].toString())
        require(operation in io.github.zzzyyylllty.sertraline.attribute.data.DEFAULT_OPERATIONS || operation in snapshot.operations) { "Unknown operation: $operation" }
        require(node["slot"].toString().lowercase() in setOf("mainhand", "main_hand", "offhand", "off_hand", "hand",
            "head", "helmet", "chest", "chestplate", "legs", "leggings", "feet", "boots", "armor", "any", "all")) { "Invalid slot: $itemKey" }
    }
    return modifiers
}
