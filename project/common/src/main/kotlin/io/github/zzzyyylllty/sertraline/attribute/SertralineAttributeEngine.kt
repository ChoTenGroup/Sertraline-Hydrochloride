package io.github.zzzyyylllty.sertraline.attribute

import io.github.zzzyyylllty.sertraline.attribute.data.*
import io.github.zzzyyylllty.sertraline.config.common.SertralineSettings
import io.github.zzzyyylllty.sertraline.item.itemSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Main-thread attribute runtime. */
object SertralineAttributeEngine {
    private data class Values(val totals: Map<String, Double>, val bases: Map<String, Double>)
    private data class CachedValues(val snapshot: AttributeRegistry.Snapshot, val values: Map<String, Double>)
    private data class CachedBase(val snapshot: AttributeRegistry.Snapshot, val due: Long, val value: Double)
    private val totals = ConcurrentHashMap<UUID, CachedValues>()
    private val external = ConcurrentHashMap<UUID, ConcurrentHashMap<String, AttributeModifier>>()
    private val tracked = hashMapOf<UUID, LivingEntity>()
    private val refreshing = hashSetOf<UUID>()
    private val evaluating = hashSetOf<UUID>()
    private val readingWeapons = hashSetOf<UUID>()
    private val baseCache = hashMapOf<UUID, MutableMap<String, CachedBase>>()
    private val failures = hashSetOf<UUID>()
    internal var tick = 0L

    val enabled: Boolean get() = SertralineSettings.rawBoolean("attribute.enable", true)
    internal val providerName: String get() = "sertraline-attribute"
    fun vanillaSyncModeName(): String = SertralineSettings.rawString("attribute.vanilla-sync", "auto").lowercase()
    fun vanillaSyncActive(): Boolean = when (vanillaSyncModeName()) {
        "false", "off", "no", "0", "none" -> false
        "true", "on", "yes", "1", "always" -> true
        else -> !ChotenAttributeProvider.available && !runCatching {
            Class.forName("io.lumine.mythic.lib.api.player.MMOPlayerData")
        }.isSuccess
    }

    fun refresh(entity: LivingEntity, itemList: Map<String, ItemBound>) {
        check(Bukkit.isPrimaryThread()) { "Use the server thread" }
        if (!refreshing.add(entity.uniqueId)) return
        try {
            tracked[entity.uniqueId] = entity
            if (!enabled) {
                AttributeVanillaSync.clear(entity)
                totals.remove(entity.uniqueId)
                return
            }
            val modifiers = AttributeItemModifiers.collect(entity, itemList) + external[entity.uniqueId]?.values.orEmpty()
            val snapshot = AttributeRegistry.snapshot()
            val values = calculate(entity, modifiers, snapshot)
            totals[entity.uniqueId] = CachedValues(snapshot, java.util.Collections.unmodifiableMap(values.totals))
            val sync = linkedMapOf<String, AttributeVanillaSync.Entry>()
            if (vanillaSyncActive()) {
                for ((id, value) in values.totals) {
                    val type = snapshot.types.getValue(id)
                    if (type.derived != null) continue
                    val base = values.bases[id] ?: type.defaultValue
                    val target = type.vanillaName
                    if (target != null && !AttributeRegistry.isVanillaSuppressed(id) && modifiers.any { it.attributeId == id }) {
                        val instance = VanillaAttributes.resolve(target)?.let { VanillaAttributes.instanceOf(entity, it) }
                        if (instance != null) sync["legacy/$id"] = AttributeVanillaSync.Entry(target, "add_value", value - instance.baseValue)
                    }
                    type.sync.forEachIndexed { index, entry ->
                        val amount = AttributeExpressions.number(entry.value, entity,
                            mapOf("value" to value, "base" to base, "attrs" to values.totals), 0.0)
                        sync["sync/$id/$index"] = AttributeVanillaSync.Entry(entry.target, entry.operation, amount)
                    }
                }
            }
            AttributeVanillaSync.apply(entity, sync)
        } finally {
            refreshing.remove(entity.uniqueId)
        }
    }

    fun refresh(entity: LivingEntity) {
        check(Bukkit.isPrimaryThread()) { "Use the server thread" }
        refresh(entity, equipment(entity))
    }

    fun getValue(entity: LivingEntity, attributeId: String): Double {
        val id = AttributeRegistry.normalizeId(attributeId)
        return getAllWithDefaults(entity)[id] ?: 0.0
    }

    fun getAll(entity: LivingEntity): Map<String, Double> =
        totals[entity.uniqueId]?.takeIf { it.snapshot === AttributeRegistry.snapshot() }?.values ?: emptyMap()

    fun getAllWithDefaults(entity: LivingEntity): Map<String, Double> {
        val snapshot = AttributeRegistry.snapshot()
        totals[entity.uniqueId]?.takeIf { it.snapshot === snapshot }?.let { return it.values }
        check(Bukkit.isPrimaryThread()) { "Use the server thread before the first refresh" }
        if (entity.uniqueId in tracked && entity.uniqueId !in refreshing && entity.uniqueId !in evaluating) {
            refresh(entity)
            totals[entity.uniqueId]?.takeIf { it.snapshot === snapshot }?.let { return it.values }
        }
        return calculate(entity, external[entity.uniqueId]?.values?.toList().orEmpty(), snapshot).totals.toMap()
    }

    /** Weapon contribution starts at zero. */
    fun getWeaponValue(entity: LivingEntity, id: String): Double {
        check(Bukkit.isPrimaryThread()) { "Use the server thread" }
        val key = AttributeRegistry.normalizeId(id)
        val snapshot = AttributeRegistry.snapshot()
        if (entity.uniqueId in evaluating || !readingWeapons.add(entity.uniqueId)) return getAllWithDefaults(entity)[key] ?: 0.0
        try {
            val items = equipment(entity)
            val modifiers = AttributeItemModifiers.collect(entity, items) + external[entity.uniqueId]?.values.orEmpty()
            val weapon = AttributeItemModifiers.collect(entity, items.filterKeys { it == "mainhand" }, "weapon")
            return calculate(entity, modifiers, snapshot, weapon).totals[key] ?: 0.0
        } finally {
            readingWeapons.remove(entity.uniqueId)
            if (entity.uniqueId !in tracked) AttributeItemModifiers.clear(entity.uniqueId)
        }
    }

    fun setExternalModifier(entity: LivingEntity, key: String, modifier: AttributeModifier) {
        require(key.isNotBlank() && modifier.value.isFinite()) { "Invalid modifier" }
        val type = AttributeRegistry.get(modifier.attributeId) ?: error("Unknown attribute: ${modifier.attributeId}")
        require(type.derived == null) { "Derived attributes are read-only" }
        val operation = modifier.operation?.let(AttributeDefinitionParser::operation)
        require(operation == null || operation in DEFAULT_OPERATIONS || operation in AttributeRegistry.snapshot().operations) { "Unknown operation" }
        external.getOrPut(entity.uniqueId) { ConcurrentHashMap() }[key] = modifier.copy(attributeId = type.id, operation = operation)
    }

    fun removeExternalModifier(entity: LivingEntity, key: String): Boolean {
        val removed = external[entity.uniqueId]?.remove(key) != null
        if (removed) requestRefresh(entity)
        return removed
    }

    fun modifiers(entity: LivingEntity): Map<String, AttributeModifier> = external[entity.uniqueId]?.toMap() ?: emptyMap()

    fun requestRefresh(entity: LivingEntity) {
        if (entity is Player) AttributeManager.refreshAttributes(entity)
        else taboolib.common.platform.function.submit { if (entity.isValid) refresh(entity) }
    }

    fun clear(uuid: UUID) {
        check(Bukkit.isPrimaryThread()) { "Use the server thread" }
        tracked[uuid]?.let { AttributeVanillaSync.clear(it) }
        totals.remove(uuid)
        external.remove(uuid)
        baseCache.remove(uuid)
        tracked.remove(uuid)
        failures.remove(uuid)
        AttributeItemModifiers.clear(uuid)
    }

    fun clear(entity: LivingEntity) {
        check(Bukkit.isPrimaryThread()) { "Use the server thread" }
        AttributeVanillaSync.clear(entity)
        clear(entity.uniqueId)
    }

    fun restoreAllOnline() {
        tracked.values.toList().forEach { clear(it) }
    }

    fun pulse() {
        tick++
        val interval = SertralineSettings.rawLong("attribute.update-interval", 20).coerceAtLeast(1)
        val baseInterval = baseInterval()
        val players = Bukkit.getOnlinePlayers()
        for (player in players) {
            val due = minOf(interval, AttributeItemModifiers.interval(player.uniqueId), baseInterval)
            if (tick % due == 0L) refreshSafely(player)
        }
        for (entity in tracked.values.toList()) {
            if (entity is Player) continue
            if (!entity.isValid || entity.isDead) clear(entity)
            else if (tick % minOf(interval, AttributeItemModifiers.interval(entity.uniqueId), baseInterval) == 0L) refreshSafely(entity)
        }
    }

    private fun refreshSafely(entity: LivingEntity) {
        try {
            refresh(entity)
            failures.remove(entity.uniqueId)
        } catch (ex: Exception) {
            if (failures.add(entity.uniqueId)) io.github.zzzyyylllty.sertraline.logger.warningS("Attribute refresh: ${ex.message}")
        }
    }

    private fun baseInterval(): Long = AttributeRegistry.all().filter { it.base.type != "constant" }
        .map { it.base.updateInterval }.filter { it > 0 }.minOrNull() ?: Long.MAX_VALUE

    private fun calculate(entity: LivingEntity, modifiers: List<AttributeModifier>, snapshot: AttributeRegistry.Snapshot, weapon: List<AttributeModifier> = emptyList()): Values {
        if (!enabled || !evaluating.add(entity.uniqueId)) {
            val cached = totals[entity.uniqueId]?.takeIf { enabled && it.snapshot === snapshot }?.values
            return Values(cached ?: snapshot.types.values.filter { applies(it, entity) }
                .associate { it.id to it.clamp(it.defaultValue) }, emptyMap())
        }
        try {
            return calculateValues(entity, modifiers, snapshot, weapon)
        } finally {
            evaluating.remove(entity.uniqueId)
            if (entity.uniqueId !in tracked) baseCache.remove(entity.uniqueId)
        }
    }

    private fun calculateValues(entity: LivingEntity, modifiers: List<AttributeModifier>, snapshot: AttributeRegistry.Snapshot, weapon: List<AttributeModifier>): Values {
        val grouped = modifiers.groupBy { it.attributeId }
        val weaponGroups = weapon.groupBy { it.attributeId }
        val result = linkedMapOf<String, Double>()
        val bases = linkedMapOf<String, Double>()
        val active = hashSetOf<String>()
        fun resolve(id: String): Double {
            result[id]?.let { return it }
            val type = snapshot.types[id] ?: return 0.0
            if (!applies(type, entity)) return 0.0
            check(active.add(id)) { "Attribute cycle: $id" }
            val expression = type.derived
            val value = if (expression != null) {
                val deps = AttributeExpressions.references(expression).associateWith { resolve(it) }
                type.clamp(AttributeExpressions.number(expression, entity, mapOf("attrs" to (result + deps)), type.defaultValue))
            } else {
                val base = baseFor(entity, type, snapshot)
                bases[id] = base
                val normal = AttributeCalculation.calculate(type, base, grouped[id].orEmpty()) { op, stage, current, amount ->
                    evaluateOperation(snapshot, op, entity, stage, current, amount)
                }
                val extra = AttributeCalculation.calculate(type.copy(minValue = null, maxValue = null), 0.0, weaponGroups[id].orEmpty()) { op, stage, current, amount ->
                    evaluateOperation(snapshot, op, entity, stage, current, amount)
                }
                type.clamp(normal + extra)
            }
            active.remove(id)
            result[id] = value
            return value
        }
        snapshot.types.keys.forEach { resolve(it) }
        return Values(result, bases)
    }

    private fun evaluateOperation(snapshot: AttributeRegistry.Snapshot, op: String, entity: LivingEntity, base: Double, current: Double, amount: Double): Double {
        val expression = snapshot.operations[op] ?: return current
        return AttributeExpressions.number(expression, entity, mapOf("base" to base, "current" to current, "amount" to amount), current)
    }

    private fun baseFor(entity: LivingEntity, type: AttributeType, snapshot: AttributeRegistry.Snapshot): Double {
        if (type.vanillaName != null && !AttributeRegistry.isVanillaSuppressed(type.id)) {
            if (SertralineSettings.rawBoolean("attribute.rebase-vanilla", false)) return 0.0
            return VanillaAttributes.resolve(type.vanillaName)?.let { VanillaAttributes.instanceOf(entity, it)?.baseValue } ?: type.defaultValue
        }
        val base = type.base
        val cached = baseCache.getOrPut(entity.uniqueId) { hashMapOf() }
        cached[type.id]?.let { if (it.snapshot === snapshot && tick < it.due) return it.value }
        var value = when (base.type) {
            "vanilla", "by_entity_type" -> {
                val attribute = base.attribute?.let { VanillaAttributes.resolve(it) }
                attribute?.let {
                    if (base.type == "vanilla") VanillaAttributes.instanceOf(entity, it)?.baseValue
                    else VanillaAttributes.typeDefault(entity, it)
                } ?: base.value
            }
            "expression" -> base.expression?.let { AttributeExpressions.number(it, entity, emptyMap(), base.value) } ?: base.value
            else -> base.value
        }
        base.transform?.let { value = AttributeExpressions.number(it, entity, mapOf("value" to value), base.value) }
        if (base.type != "constant") cached[type.id] = CachedBase(snapshot,
            if (base.updateInterval > 0) tick + base.updateInterval else Long.MAX_VALUE, value)
        return value
    }

    private fun applies(type: AttributeType, entity: LivingEntity): Boolean {
        if (type.entities.isEmpty()) return true
        val id = "minecraft:${entity.type.name.lowercase()}"
        return type.entities.any { it == id || it == entity.type.name.lowercase() || matchesTag(it, entity) }
    }

    private fun matchesTag(id: String, entity: LivingEntity): Boolean {
        if (!id.startsWith('#')) return false
        return runCatching {
            val keyClass = Class.forName("org.bukkit.NamespacedKey")
            val raw = id.drop(1)
            val key = keyClass.getConstructor(String::class.java, String::class.java).newInstance(raw.substringBefore(':', "minecraft"), raw.substringAfter(':'))
            val method = Bukkit::class.java.methods.first { it.name == "getTag" && it.parameterCount == 3 }
            val tag = method.invoke(null, "entity_types", key, org.bukkit.entity.EntityType::class.java) ?: return@runCatching false
            val tagged = Class.forName("org.bukkit.Tag").methods.first { it.name == "isTagged" }
            tagged.invoke(tag, entity.type) == true
        }.getOrDefault(false)
    }

    private fun equipment(entity: LivingEntity): Map<String, ItemBound> {
        val eq = entity.equipment ?: return emptyMap()
        val items = linkedMapOf("mainhand" to eq.itemInMainHand, "offhand" to eq.itemInOffHand,
            "helmet" to eq.helmet, "chestplate" to eq.chestplate, "leggings" to eq.leggings, "boots" to eq.boots)
        return items.mapNotNull { (slot, item) ->
            item?.let { itemSerializer(it, entity as? Player)?.let { data -> slot to ItemBound(data, item) } }
        }.toMap()
    }
}
