package io.github.zzzyyylllty.sertraline.attribute.api

import io.github.zzzyyylllty.sertraline.attribute.*
import io.github.zzzyyylllty.sertraline.attribute.data.*
import org.bukkit.entity.LivingEntity

/** Java, Kotlin and script API. */
class SertralineAttributeAPI private constructor() {
    companion object {
        @JvmStatic fun getValue(entity: LivingEntity, id: String): Double = SertralineAttributeEngine.getValue(entity, id)
        @JvmStatic fun getAll(entity: LivingEntity): Map<String, Double> = SertralineAttributeEngine.getAll(entity)
        @JvmStatic fun getAllWithDefaults(entity: LivingEntity): Map<String, Double> = SertralineAttributeEngine.getAllWithDefaults(entity)
        @JvmStatic fun getWeaponValue(entity: LivingEntity, id: String): Double = SertralineAttributeEngine.getWeaponValue(entity, id)
        @JvmStatic fun isRegistered(id: String): Boolean = AttributeRegistry.isRegistered(id)
        @JvmStatic fun getType(id: String): AttributeType? = AttributeRegistry.get(id)
        @JvmStatic fun getRegistered(): List<AttributeType> = AttributeRegistry.all()
        @JvmStatic fun register(type: AttributeType): Boolean = AttributeRegistry.register(type)
        @JvmStatic fun registerDefinition(id: String, definition: Map<String, Any?>): Boolean =
            register(AttributeDefinitionParser.parse(id, definition))
        @JvmStatic fun unregister(id: String): Boolean = AttributeRegistry.unregister(id)
        @JvmStatic fun registerOperation(id: String, expression: String): Boolean =
            AttributeRegistry.registerOperation(id, AttributeExpression(expression))
        @JvmStatic fun unregisterOperation(id: String): Boolean = AttributeRegistry.unregisterOperation(id)

        /** Legacy units. */
        @JvmStatic @JvmOverloads
        fun setModifier(entity: LivingEntity, key: String, id: String, value: Double, type: ModifierType = ModifierType.ADD) {
            SertralineAttributeEngine.setExternalModifier(entity, key, AttributeModifier(AttributeRegistry.normalizeId(id), value, type))
            requestRefresh(entity)
        }

        /** Decimal amounts. */
        @JvmStatic
        fun setOperationModifier(entity: LivingEntity, key: String, id: String, amount: Double, operation: String) {
            SertralineAttributeEngine.setExternalModifier(entity, key, AttributeModifier(
                AttributeRegistry.normalizeId(id), amount, operation = AttributeDefinitionParser.operation(operation), id = key
            ))
            requestRefresh(entity)
        }

        @JvmStatic fun removeModifier(entity: LivingEntity, key: String): Boolean = SertralineAttributeEngine.removeExternalModifier(entity, key)
        @JvmStatic fun getModifiers(entity: LivingEntity): Map<String, AttributeModifier> = SertralineAttributeEngine.modifiers(entity)
        @JvmStatic fun requestRefresh(entity: LivingEntity) = SertralineAttributeEngine.requestRefresh(entity)
        @JvmStatic fun refresh(entity: LivingEntity) = SertralineAttributeEngine.refresh(entity)
        @JvmStatic fun clear(entity: LivingEntity) = SertralineAttributeEngine.clear(entity)
    }
}
