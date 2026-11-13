package io.github.zzzyyylllty.sertraline.attribute

import io.github.zzzyyylllty.sertraline.attribute.data.*
import io.github.zzzyyylllty.sertraline.logger.warningS
import org.bukkit.entity.LivingEntity
import org.bukkit.inventory.ItemStack
import java.util.UUID

/** Item modifier decoder. */
object AttributeItemModifiers {
    private val warned = hashSetOf<String>()
    private val reserved = setOf("slot", "allowed", "source", "id")
    private data class ModifierKey(val attribute: String, val id: String)
    private data class CacheKey(val scope: String, val slot: String, val modifier: ModifierKey)
    private data class Cached(val item: ItemStack, val node: Map<*, *>, val snapshot: AttributeRegistry.Snapshot, val due: Long, val modifier: AttributeModifier?)
    private val cache = hashMapOf<UUID, MutableMap<CacheKey, Cached>>()
    private val intervals = hashMapOf<UUID, Long>()

    fun interval(uuid: UUID): Long = intervals[uuid] ?: Long.MAX_VALUE
    fun clear(uuid: UUID) { cache.remove(uuid); intervals.remove(uuid) }

    fun collect(entity: LivingEntity, items: Map<String, ItemBound>, scope: String = "entity"): List<AttributeModifier> {
        val out = linkedMapOf<ModifierKey, AttributeModifier>()
        val legacy = arrayListOf<AttributeModifier>()
        val snapshot = AttributeRegistry.snapshot()
        val cached = cache.getOrPut(entity.uniqueId) { hashMapOf() }
        val seen = hashSetOf<CacheKey>()
        var interval = Long.MAX_VALUE
        for ((slot, bound) in items) {
            if (scope == "entity") {
                val data = bound.sItem.data["attribute"] as? Map<*, *>
                if (data != null) legacy.addAll(collectLegacy(slot, bound, data))
            }
            val entries = bound.sItem.data["attribute-modifiers"] as? List<*> ?: continue
            for (raw in entries) {
                try {
                    val node = raw as? Map<*, *> ?: error("Invalid modifier")
                    if ((node["scope"] ?: "entity").toString() != scope) continue
                    if (!matchesSlot(slot, node["slot"]?.toString() ?: error("Missing slot"))) continue
                    val id = node["id"]?.toString() ?: error("Missing modifier id")
                    val type = AttributeRegistry.normalizeId(node["type"]?.toString() ?: error("Missing type"))
                    require(AttributeRegistry.get(type) != null) { "Unknown attribute: $type" }
                    val key = ModifierKey(type, id)
                    val cacheKey = CacheKey(scope, slot, key)
                    seen.add(cacheKey)
                    val period = ((node["update_interval"] as? Number)?.toLong() ?: 20).coerceAtLeast(1)
                    interval = minOf(interval, period)
                    val previous = cached[cacheKey]
                    if (previous != null && previous.snapshot === snapshot && previous.node == node && previous.item == bound.bItem && SertralineAttributeEngine.tick < previous.due) {
                        previous.modifier?.let { out[key] = it }
                        continue
                    }
                    val vars = mapOf("item" to bound.bItem, "sItem" to bound.sItem)
                    val conditions = node["conditions"] ?: node["condition"]
                    val checks = if (conditions is List<*>) conditions else listOfNotNull(conditions)
                    if (!checks.all {
                        val expression = if (it is Map<*, *> && it["type"] == "expression") it["expression"] else it
                        val result = AttributeExpressions.eval(AttributeDefinitionParser.expression(expression), entity, vars)
                        result == true || result?.toString() == "true"
                    }) {
                        cached[cacheKey] = Cached(bound.bItem.clone(), node, snapshot, SertralineAttributeEngine.tick + period, null)
                        continue
                    }
                    val amount = node["amount"] ?: error("Missing amount")
                    val value = if (amount is Number) amount.toDouble() else AttributeExpressions.number(
                        AttributeDefinitionParser.expression(amount), entity, vars, Double.NaN
                    )
                    require(value.isFinite()) { "Invalid amount: $id" }
                    val op = AttributeDefinitionParser.operation(node["operation"]?.toString() ?: error("Missing operation"))
                    val modifier = AttributeModifier(type, value, slot = AttributeSlot.parse(slot) ?: AttributeSlot.ANY, operation = op, id = id)
                    out[key] = modifier
                    cached[cacheKey] = Cached(bound.bItem.clone(), node, snapshot, SertralineAttributeEngine.tick + period, modifier)
                } catch (ex: Exception) {
                    val key = "${bound.sItem.key}:${ex.message}"
                    if (warned.size < 256 && warned.add(key)) warningS("Attribute item $key")
                }
            }
        }
        cached.keys.removeAll { it.scope == scope && it !in seen }
        if (scope == "entity") intervals[entity.uniqueId] = interval
        return legacy + out.values
    }

    fun matchesSlot(actual: String, requested: String): Boolean {
        val slot = normalizeSlot(actual)
        return when (requested.lowercase()) {
            "any", "all" -> true
            "hand" -> slot == "mainhand" || slot == "offhand"
            "armor" -> slot in setOf("head", "chest", "legs", "feet")
            else -> slot == normalizeSlot(requested)
        }
    }

    private fun normalizeSlot(raw: String): String = when (val value = raw.lowercase()) {
        "helmet" -> "head"
        "chestplate" -> "chest"
        "leggings" -> "legs"
        "boots" -> "feet"
        "main_hand" -> "mainhand"
        "off_hand" -> "offhand"
        else -> value
    }

    private fun collectLegacy(slot: String, bound: ItemBound, data: Map<*, *>): List<AttributeModifier> {
        val allowed = (data["allowed"] as? List<*>)?.map { it.toString() }.orEmpty().ifEmpty {
            listOf(bound.bItem.type.name.lowercase().substringAfterLast('_').takeIf {
                it in setOf("boots", "chestplate", "leggings", "helmet")
            } ?: "mainhand")
        }
        if (allowed.none { matchesSlot(slot, it) }) return emptyList()
        return data.mapNotNull { (key, raw) ->
            val id = AttributeRegistry.normalizeId(key.toString())
            if (id in reserved) return@mapNotNull null
            val parsed = parseAttributeValue(raw) ?: return@mapNotNull null
            if (AttributeRegistry.getOrRegister(id) == null) return@mapNotNull null
            AttributeModifier(id, parsed.first, parsed.second, ModifierSource.parse(data["source"]?.toString()) ?: ModifierSource.OTHER,
                AttributeSlot.parse(data["slot"]?.toString()?.takeIf { it.isNotBlank() } ?: slot) ?: AttributeSlot.ANY)
        }
    }
}
