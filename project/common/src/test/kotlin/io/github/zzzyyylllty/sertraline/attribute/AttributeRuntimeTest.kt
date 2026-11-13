package io.github.zzzyyylllty.sertraline.attribute

import io.github.zzzyyylllty.sertraline.attribute.data.*
import io.github.zzzyyylllty.sertraline.data.ModernSItem
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.entity.LivingEntity
import org.bukkit.inventory.ItemStack
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.UUID

class AttributeRuntimeTest {
    private var oldServer: Server? = null
    private lateinit var entity: LivingEntity
    private var health = 20.0

    @Before fun setup() {
        val field = Bukkit::class.java.getDeclaredField("server").also { it.isAccessible = true }
        oldServer = field.get(null) as? Server
        val plugins = proxy(org.bukkit.plugin.PluginManager::class.java) { _, _ -> null }
        val server = proxy(Server::class.java) { method, _ -> when (method) {
            "isPrimaryThread" -> true
            "getOnlinePlayers" -> emptyList<Any>()
            "getPluginManager" -> plugins
            else -> null
        } }
        field.set(null, server)
        val uuid = UUID.randomUUID()
        entity = proxy(LivingEntity::class.java) { method, args -> when (method) {
            "getUniqueId" -> uuid
            "isValid" -> true
            "getHealth" -> health
            "setHealth" -> { health = args!![0] as Double; null }
            else -> null
        } }
    }

    @After fun cleanup() {
        if (::entity.isInitialized) SertralineAttributeEngine.clear(entity)
        AttributeRegistry.resetToBuiltins()
        Bukkit::class.java.getDeclaredField("server").also { it.isAccessible = true }.set(null, oldServer)
    }

    @Test fun modifierIdsDoNotCollideAcrossNamespaces() {
        val a = AttributeType("test:a", "A")
        val b = AttributeType("test", "B")
        AttributeRegistry.publishConfig(mapOf(a.id to a, b.id to b), emptyMap())
        val bound = item(listOf(node(a.id, "b", 2), node(b.id, "a:b", 3)))
        val result = AttributeItemModifiers.collect(entity, mapOf("mainhand" to bound))
        assertEquals(2, result.size)
        assertEquals(setOf(a.id, b.id), result.map { it.attributeId }.toSet())
    }

    @Test fun firstReadCanCallTheApiWithoutRecursing() {
        val type = AttributeType("test:recursive", "Recursive", base = AttributeBase(type = "expression",
            expression = AttributeExpression("AttributeAPI.getValue(entity, 'test:recursive') + 1", "js")))
        AttributeRegistry.publishConfig(mapOf(type.id to type), emptyMap())
        assertEquals(1.0, SertralineAttributeEngine.getValue(entity, type.id), 0.0)
    }

    @Test fun reloadInvalidatesDynamicModifierCache() {
        val type = AttributeType("test:dynamic", "Dynamic")
        AttributeRegistry.publishConfig(mapOf(type.id to type), emptyMap())
        val bound = item(listOf(node(type.id, "test:mod", mapOf("type" to "js", "expression" to "counter.get()"))))
        val counter = java.util.concurrent.atomic.AtomicInteger(2)
        io.github.zzzyyylllty.sertraline.data.defaultData["counter"] = counter
        try {
            assertEquals(2.0, AttributeItemModifiers.collect(entity, mapOf("mainhand" to bound)).single().value, 0.0)
            counter.set(7)
            AttributeRegistry.publishConfig(mapOf(type.id to type), emptyMap())
            assertEquals(7.0, AttributeItemModifiers.collect(entity, mapOf("mainhand" to bound)).single().value, 0.0)
        } finally {
            io.github.zzzyyylllty.sertraline.data.defaultData.remove("counter")
        }
    }

    @Test fun reloadInvalidatesPanelAndBaseCaches() {
        val old = AttributeType("test:base", "Base", base = AttributeBase(type = "expression",
            expression = AttributeExpression("2", "js"), updateInterval = 1000))
        AttributeRegistry.publishConfig(mapOf(old.id to old), emptyMap())
        SertralineAttributeEngine.refresh(entity, emptyMap())
        assertEquals(2.0, SertralineAttributeEngine.getValue(entity, old.id), 0.0)
        val next = old.copy(base = old.base.copy(expression = AttributeExpression("7", "js")))
        AttributeRegistry.publishConfig(mapOf(next.id to next), emptyMap())
        assertEquals(7.0, SertralineAttributeEngine.getValue(entity, next.id), 0.0)
        AttributeRegistry.publishConfig(emptyMap(), emptyMap())
        assertEquals(0.0, SertralineAttributeEngine.getValue(entity, old.id), 0.0)
    }

    @Test fun weaponReadsInsideBaseScriptsDoNotRecurse() {
        val type = AttributeType("test:weapon", "Weapon", base = AttributeBase(type = "expression",
            expression = AttributeExpression("AttributeAPI.getWeaponValue(entity, 'test:weapon') + 1", "js")))
        AttributeRegistry.publishConfig(mapOf(type.id to type), emptyMap())
        assertEquals(1.0, SertralineAttributeEngine.getWeaponValue(entity, type.id), 0.0)
    }

    @Test fun externalModifiersUseCanonicalIds() {
        val type = AttributeType("test_power", "Power")
        AttributeRegistry.publishConfig(mapOf(type.id to type), emptyMap())
        SertralineAttributeEngine.setExternalModifier(entity, "test:mod",
            AttributeModifier("test-power", 5.0, operation = "add_value"))
        assertEquals(5.0, SertralineAttributeEngine.getValue(entity, type.id), 0.0)
        assertEquals(type.id, SertralineAttributeEngine.modifiers(entity).getValue("test:mod").attributeId)
    }

    @Test fun nativeSyncPreservesForeignModifiersAndHealthRatio() {
        val native = NativeAttribute()
        @Suppress("DEPRECATION")
        val foreign = org.bukkit.attribute.AttributeModifier(UUID.randomUUID(), "foreign", 4.0,
            org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER)
        native.addModifier(foreign)
        health = 12.0
        val entry = AttributeVanillaSync.Entry("max_health", "add_value", 6.0)
        val lookup: (String) -> org.bukkit.attribute.AttributeInstance? = { native }
        AttributeVanillaSync.apply(entity, mapOf("sync/test:health/0" to entry), lookup) { listOf(native) }
        assertEquals(30.0, native.value, 0.0)
        assertEquals(15.0, health, 0.0)
        AttributeVanillaSync.apply(entity, emptyMap(), lookup) { listOf(native) }
        assertEquals(24.0, native.value, 0.0)
        assertEquals(12.0, health, 0.0)
        assertEquals(listOf(foreign), native.modifiers.toList())
        assertEquals(20.0, native.baseValue, 0.0)
    }

    @Test fun staleNativeModifiersAreRemovedWithoutConfigEntries() {
        val native = NativeAttribute()
        val persisted = AttributeVanillaSync.createModifier("sync/test:deleted/0", 10.0,
            org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER)
        native.addModifier(org.bukkit.attribute.AttributeModifier.deserialize(persisted.serialize()))
        health = 15.0
        AttributeVanillaSync.apply(entity, emptyMap(), { native }) { listOf(native) }
        assertTrue(native.modifiers.isEmpty())
        assertEquals(10.0, health, 0.0)
    }

    private class NativeAttribute : org.bukkit.attribute.AttributeInstance {
        private var base = 20.0
        private val entries = linkedSetOf<org.bukkit.attribute.AttributeModifier>()
        override fun getAttribute(): org.bukkit.attribute.Attribute = error("Unused")
        override fun getBaseValue() = base
        override fun setBaseValue(value: Double) { base = value }
        override fun getDefaultValue() = 20.0
        override fun getValue() = base + entries.sumOf { it.amount }
        override fun getModifiers(): Collection<org.bukkit.attribute.AttributeModifier> = entries.toList()
        override fun addModifier(modifier: org.bukkit.attribute.AttributeModifier) { entries.add(modifier) }
        override fun removeModifier(modifier: org.bukkit.attribute.AttributeModifier) { entries.remove(modifier) }
    }

    private fun node(type: String, id: String, amount: Any) = mapOf(
        "type" to type, "id" to id, "amount" to amount, "operation" to "minecraft:add_value",
        "slot" to "mainhand", "update_interval" to 1000
    )

    private fun item(nodes: List<Map<String, Any>>): ItemBound = ItemBound(
        ModernSItem("test:item", linkedMapOf("attribute-modifiers" to nodes)), BareStack()
    )

    private class BareStack : ItemStack(Material.STONE) {
        override fun clone(): ItemStack = BareStack()
        override fun equals(other: Any?): Boolean = other is BareStack
        override fun hashCode(): Int = 1
    }

    private fun <T> proxy(type: Class<T>, answer: (String, Array<out Any?>?) -> Any?): T = type.cast(Proxy.newProxyInstance(
        type.classLoader, arrayOf(type)
    ) { instance, method, args ->
        when (method.name) {
            "toString" -> "Test${type.simpleName}"
            "hashCode" -> System.identityHashCode(instance)
            "equals" -> instance === args?.firstOrNull()
            else -> answer(method.name, args) ?: when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Double::class.javaPrimitiveType -> 0.0
                else -> null
            }
        }
    })
}
