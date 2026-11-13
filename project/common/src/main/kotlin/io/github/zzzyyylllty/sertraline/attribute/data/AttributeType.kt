package io.github.zzzyyylllty.sertraline.attribute.data

/** Attribute definition. */
data class AttributeType(
    val id: String,
    val name: String,
    val vanillaName: String? = null,
    val defaultValue: Double = 0.0,
    val minValue: Double? = null,
    val maxValue: Double? = null,
    val isPercentage: Boolean = false,
    val description: String = "",
    val base: AttributeBase = AttributeBase(value = defaultValue),
    val derived: AttributeExpression? = null,
    val operations: List<String> = DEFAULT_OPERATIONS,
    val entities: Set<String> = emptySet(),
    val sync: List<AttributeSync> = emptyList(),
) {
    /** Clamps [value] into `[minValue, maxValue]`; null bounds are ignored. */
    fun clamp(value: Double): Double {
        var result = value
        if (!result.isFinite()) result = defaultValue
        if (minValue != null && result < minValue) result = minValue
        if (maxValue != null && result > maxValue) result = maxValue
        return result
    }
}

/** How a modifier folds into the final value. */
enum class ModifierType {
    ADD,
    PERCENT,
    MULTIPLY,
}

/** Equipment slot a modifier belongs to. */
enum class AttributeSlot {
    HEAD,
    CHEST,
    LEGS,
    FEET,
    MAINHAND,
    OFFHAND,
    INVENTORY,
    ANY,
    OTHER;

    companion object {
        fun parse(raw: String?): AttributeSlot? {
            if (raw.isNullOrBlank()) return null
            return when (raw.trim().lowercase()) {
                "head", "helmet" -> HEAD
                "chest", "chestplate" -> CHEST
                "legs", "leggings" -> LEGS
                "feet", "boots", "foot" -> FEET
                "mainhand", "main_hand", "hand" -> MAINHAND
                "offhand", "off_hand" -> OFFHAND
                "inventory", "inv" -> INVENTORY
                "any", "all" -> ANY
                else -> runCatching { valueOf(raw.trim().uppercase()) }.getOrNull() ?: OTHER
            }
        }
    }
}

/** Where a modifier came from; display/debug only. */
enum class ModifierSource {
    MAINHAND,
    OFFHAND,
    ARMOR,
    INVENTORY,
    EXTERNAL,
    OTHER;

    companion object {
        fun parse(raw: String?): ModifierSource? {
            if (raw.isNullOrBlank()) return null
            return runCatching { valueOf(raw.trim().uppercase()) }.getOrNull() ?: OTHER
        }
    }
}

/** A resolved modifier. Unlike ChoTenTech's version it needs no UUID: modifiers are derived from gear each refresh. */
data class AttributeModifier(
    val attributeId: String,
    val value: Double,
    val type: ModifierType = ModifierType.ADD,
    val source: ModifierSource = ModifierSource.OTHER,
    val slot: AttributeSlot = AttributeSlot.ANY,
    val operation: String? = null,
    val id: String? = null,
)

val DEFAULT_OPERATIONS: List<String> = java.util.Collections.unmodifiableList(listOf(
    "minecraft:add_value", "minecraft:add_multiplied_base", "minecraft:add_multiplied_total"
))

data class AttributeExpression(val code: String, val engine: String = "graaljs", val script: Boolean = false)

data class AttributeBase(
    val type: String = "constant",
    val value: Double = 0.0,
    val attribute: String? = null,
    val transform: AttributeExpression? = null,
    val expression: AttributeExpression? = null,
    val updateInterval: Long = 20,
)

data class AttributeSync(
    val target: String,
    val operation: String,
    val value: AttributeExpression = AttributeExpression("value"),
)
