package io.github.zzzyyylllty.sertraline.attribute

import io.github.zzzyyylllty.sertraline.attribute.data.*
import java.util.Locale

/** Definition owner. */
enum class RegistrationSource { BUILTIN, CONFIG, API }

/** Atomic definition snapshot. */
object AttributeRegistry {
    data class Snapshot(
        val types: Map<String, AttributeType>,
        val operations: Map<String, AttributeExpression>,
    )

    private val builtins = java.util.Collections.unmodifiableMap(builtinTypes().associate { it.id to freeze(it) })
    private val apiTypes = linkedMapOf<String, AttributeType>()
    private val apiOperations = linkedMapOf<String, AttributeExpression>()
    private var configTypes: Map<String, AttributeType> = emptyMap()
    private var configOperations: Map<String, AttributeExpression> = emptyMap()
    @Volatile private var state = Snapshot(builtins, emptyMap())
    private val validId = Regex("^(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+$")

    fun isValidId(id: String): Boolean = validId.matches(id)
    fun normalizeId(raw: String): String {
        val id = raw.trim().lowercase(Locale.ROOT)
        return if (':' in id) id else id.replace('-', '_').replace(' ', '_')
    }
    fun snapshot(): Snapshot = state
    fun get(id: String): AttributeType? = state.types[normalizeId(id)]
    fun isRegistered(id: String): Boolean = get(id) != null
    fun all(): List<AttributeType> = state.types.values.sortedBy { it.id }
    fun ids(): Set<String> = state.types.keys.toSet()
    fun size(): Int = state.types.size
    fun builtinIds(): Set<String> = builtins.keys.toSet()
    fun isVanillaSuppressed(id: String): Boolean {
        val type = get(id) ?: return false
        return type.vanillaName != null && state.types.values.any {
            it.id != type.id && it.vanillaName == type.vanillaName && it.id in builtins
        }
    }

    @Synchronized
    fun register(type: AttributeType, source: RegistrationSource = RegistrationSource.API): Boolean {
        val normalized = freeze(type.copy(id = normalizeId(type.id), operations = type.operations.map(AttributeDefinitionParser::operation)))
        if (!isValidId(normalized.id) || normalized.id in builtins) return false
        val candidate = state.types + (normalized.id to normalized)
        if (runCatching { validate(candidate, state.operations) }.isFailure) return false
        if (source == RegistrationSource.CONFIG) configTypes = configTypes + (normalized.id to normalized)
        else apiTypes[normalized.id] = normalized
        rebuild()
        return true
    }

    @Synchronized
    fun unregister(id: String): Boolean {
        val key = normalizeId(id)
        if (key in builtins || key !in state.types) return false
        if (runCatching { validate(state.types - key, state.operations) }.isFailure) return false
        apiTypes.remove(key)
        configTypes = configTypes - key
        rebuild()
        return true
    }

    @Synchronized
    fun registerOperation(rawId: String, expression: AttributeExpression): Boolean {
        val id = AttributeDefinitionParser.operation(rawId)
        if (!isValidId(id) || id in DEFAULT_OPERATIONS) return false
        apiOperations[id] = expression
        rebuild()
        return true
    }

    @Synchronized
    fun unregisterOperation(rawId: String): Boolean {
        val id = AttributeDefinitionParser.operation(rawId)
        if (state.types.values.any { id in it.operations } || id in DEFAULT_OPERATIONS) return false
        val removed = apiOperations.remove(id) != null || id in configOperations
        configOperations = configOperations - id
        rebuild()
        return removed
    }

    @Synchronized
    fun getOrRegister(id: String): AttributeType? {
        val key = normalizeId(id)
        get(key)?.let { return it }
        if (!isValidId(key)) return null
        val type = AttributeType(key, key)
        configTypes = configTypes + (key to type)
        rebuild()
        return type
    }

    @Synchronized
    fun validateConfig(types: Map<String, AttributeType>, operations: Map<String, AttributeExpression>) {
        require(types.keys.none { it in builtins }) { "Builtin attribute override" }
        validate(builtins + types + apiTypes, operations + apiOperations)
    }

    @Synchronized
    fun configSnapshot(types: Map<String, AttributeType>, operations: Map<String, AttributeExpression>): Snapshot {
        validateConfig(types, operations)
        return Snapshot(builtins + types + apiTypes, operations + apiOperations)
    }

    @Synchronized
    fun publishConfig(types: Map<String, AttributeType>, operations: Map<String, AttributeExpression>) {
        validateConfig(types, operations)
        configTypes = types.mapValues { freeze(it.value) }
        configOperations = operations.toMap()
        rebuild()
        AttributeExpressions.clearWarnings()
    }

    @Synchronized
    fun resetToBuiltins() {
        configTypes = emptyMap()
        configOperations = emptyMap()
        rebuild()
    }

    private fun rebuild() {
        state = Snapshot(
            java.util.Collections.unmodifiableMap(builtins + configTypes + apiTypes),
            java.util.Collections.unmodifiableMap(configOperations + apiOperations)
        )
    }

    private fun freeze(type: AttributeType) = type.copy(
        operations = java.util.Collections.unmodifiableList(type.operations.toList()),
        entities = java.util.Collections.unmodifiableSet(type.entities.toSet()),
        sync = java.util.Collections.unmodifiableList(type.sync.toList())
    )

    private fun validate(types: Map<String, AttributeType>, operations: Map<String, AttributeExpression>) {
        val visited = hashSetOf<String>()
        val active = hashSetOf<String>()
        fun visit(id: String) {
            require(id in types) { "Unknown attribute: $id" }
            if (id in visited) return
            require(active.add(id)) { "Attribute cycle: $id" }
            val type = types.getValue(id)
            require(isValidId(id) && type.defaultValue.isFinite() && type.base.value.isFinite()) { "Invalid attribute: $id" }
            require(type.minValue?.isFinite() != false && type.maxValue?.isFinite() != false) { "Invalid bounds: $id" }
            require(type.minValue == null || type.maxValue == null || type.minValue <= type.maxValue) { "Invalid bounds: $id" }
            require(type.operations.all { it in DEFAULT_OPERATIONS || it in operations }) { "Unknown operation: $id" }
            type.derived?.let { expression -> AttributeExpressions.references(expression).forEach { visit(it) } }
            active.remove(id)
            visited.add(id)
        }
        types.keys.forEach { visit(it) }
    }

    private fun builtinTypes(): List<AttributeType> =
        listOf(
            AttributeType("max_health", "Max Health", "max_health", 20.0, minValue = 0.0),
            AttributeType("movement_speed", "Movement Speed", "movement_speed", 0.1, minValue = 0.0),
            AttributeType("attack_damage", "Attack Damage", "attack_damage", 1.0, minValue = 0.0),
            AttributeType("attack_speed", "Attack Speed", "attack_speed", 4.0, minValue = 0.0),
            AttributeType("attack_knockback", "Attack Knockback", "attack_knockback", 0.0),
            AttributeType("knockback_resistance", "Knockback Resistance", "knockback_resistance", 0.0),
            AttributeType("armor", "Armor", "armor", 0.0, minValue = 0.0),
            AttributeType("armor_toughness", "Armor Toughness", "armor_toughness", 0.0, minValue = 0.0),
            AttributeType("max_absorption", "Max Absorption", "max_absorption", 0.0, minValue = 0.0),
            AttributeType("luck", "Luck", "luck", 0.0),
            AttributeType("fall_damage_multiplier", "Fall Damage Multiplier", "fall_damage_multiplier", 1.0),
            AttributeType("burning_time", "Burning Time", "burning_time", 1.0),
            AttributeType("explosion_knockback_resistance", "Explosion Knockback Resistance", "explosion_knockback_resistance", 0.0),
            AttributeType("gravity", "Gravity", "gravity", 0.08),
            AttributeType("jump_strength", "Jump Strength", "jump_strength", 0.42),
            AttributeType("safe_fall_distance", "Safe Fall Distance", "safe_fall_distance", 3.0),
            AttributeType("scale", "Scale", "scale", 1.0),
            AttributeType("step_height", "Step Height", "step_height", 0.6),
            AttributeType("block_break_speed", "Block Break Speed", "block_break_speed", 1.0),
            AttributeType("block_interaction_range", "Block Interaction Range", "block_interaction_range", 4.5),
            AttributeType("entity_interaction_range", "Entity Interaction Range", "entity_interaction_range", 3.0),
            AttributeType("submerged_mining_speed", "Submerged Mining Speed", "submerged_mining_speed", 0.2),
            AttributeType("mining_efficiency", "Mining Efficiency", "mining_efficiency", 0.0),
            AttributeType("movement_efficiency", "Movement Efficiency", "movement_efficiency", 0.0),
            AttributeType("oxygen_bonus", "Oxygen Bonus", "oxygen_bonus", 0.0),
            AttributeType("water_movement_efficiency", "Water Movement Efficiency", "water_movement_efficiency", 0.0),
            AttributeType("sneaking_speed", "Sneaking Speed", "sneaking_speed", 0.3),
            AttributeType("sweeping_damage_ratio", "Sweeping Damage Ratio", "sweeping_damage_ratio", 0.0),

            // Custom attributes: no vanilla mapping, they only exist for this plugin's API and lore.
            AttributeType("crit_chance", "Crit Chance", null, 0.0, 0.0, 100.0, isPercentage = true),
            AttributeType("crit_damage", "Crit Damage", null, 0.0, 0.0, null, isPercentage = true),
            AttributeType("lifesteal", "Lifesteal", null, 0.0, 0.0, 100.0, isPercentage = true),
            AttributeType("dodge_chance", "Dodge Chance", null, 0.0, 0.0, 100.0, isPercentage = true),
            AttributeType("block_chance", "Block Chance", null, 0.0, 0.0, 100.0, isPercentage = true),
            AttributeType("defense", "Defense", null, 0.0),
            AttributeType("physical_damage", "Physical Damage", null, 0.0),
            AttributeType("magic_damage", "Magic Damage", null, 0.0),
            AttributeType("ranged_damage", "Ranged Damage", null, 0.0),
            AttributeType("damage_reduction", "Damage Reduction", null, 0.0, 0.0, 100.0, isPercentage = true),
            AttributeType("physical_damage_reduction", "Physical Damage Reduction", null, 0.0, 0.0, 100.0, isPercentage = true),
            AttributeType("magic_damage_reduction", "Magic Damage Reduction", null, 0.0, 0.0, 100.0, isPercentage = true),
            AttributeType("max_mana", "Max Mana", null, 100.0, minValue = 0.0),
            AttributeType("mana_regeneration", "Mana Regeneration", null, 0.0, minValue = 0.0),
            AttributeType("max_stamina", "Max Stamina", null, 100.0, minValue = 0.0),
            AttributeType("stamina_regeneration", "Stamina Regeneration", null, 0.0, minValue = 0.0),
            AttributeType("harvest_power", "Harvest Power", null, 1.0, minValue = 0.0),
            AttributeType("experience_bonus", "Experience Bonus", null, 0.0, 0.0, null, isPercentage = true),
        )
}
