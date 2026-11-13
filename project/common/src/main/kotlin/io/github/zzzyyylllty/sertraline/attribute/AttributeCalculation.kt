package io.github.zzzyyylllty.sertraline.attribute

import io.github.zzzyyylllty.sertraline.attribute.data.*

/** Ordered operation stages. */
object AttributeCalculation {
    fun calculate(
        type: AttributeType,
        base: Double,
        modifiers: List<AttributeModifier>,
        custom: (String, Double, Double, Double) -> Double,
    ): Double {
        var current = base
        for (operation in type.operations) {
            val stageBase = current
            for (modifier in modifiers) {
                val legacy = modifier.operation == null
                val op = modifier.operation ?: when (modifier.type) {
                    ModifierType.ADD -> DEFAULT_OPERATIONS[0]
                    ModifierType.PERCENT -> DEFAULT_OPERATIONS[if (type.isPercentage) 0 else 1]
                    ModifierType.MULTIPLY -> DEFAULT_OPERATIONS[2]
                }
                if (op != operation) continue
                val amount = when {
                    legacy && modifier.type == ModifierType.PERCENT && !type.isPercentage -> modifier.value / 100.0
                    legacy && modifier.type == ModifierType.MULTIPLY -> modifier.value - 1.0
                    else -> modifier.value
                }
                if (!amount.isFinite()) continue
                val next = when (operation) {
                    DEFAULT_OPERATIONS[0] -> current + amount
                    DEFAULT_OPERATIONS[1] -> current + stageBase * amount
                    DEFAULT_OPERATIONS[2] -> current * (1.0 + amount)
                    else -> custom(operation, stageBase, current, amount)
                }
                if (next.isFinite()) current = next
            }
        }
        return type.clamp(current)
    }
}
