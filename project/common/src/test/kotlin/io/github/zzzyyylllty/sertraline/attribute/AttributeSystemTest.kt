package io.github.zzzyyylllty.sertraline.attribute

import io.github.zzzyyylllty.sertraline.attribute.data.*
import org.junit.Assert.*
import org.junit.Test

class AttributeSystemTest {
    private val type = AttributeType("test:power", "Power", defaultValue = 10.0)
    private fun mod(amount: Double, operation: String) = AttributeModifier(type.id, amount, operation = "minecraft:$operation")
    private fun calculate(type: AttributeType = this.type, mods: List<AttributeModifier>): Double =
        AttributeCalculation.calculate(type, type.defaultValue, mods) { _, _, _, _ -> error("Unexpected custom operation") }

    @Test fun stagesUseTheirStartingValue() {
        val mods = listOf(mod(5.0, "add_value"), mod(0.2, "add_multiplied_base"),
            mod(0.1, "add_multiplied_total"), mod(0.2, "add_multiplied_total"))
        assertEquals(23.76, calculate(mods = mods), 1e-9)
        assertEquals(21.0, calculate(mods = listOf(mods[0], mods[1], mods[1])), 1e-9)
    }

    @Test fun customStagesAndOmittedOperations() {
        val custom = type.copy(operations = listOf("test:cap"))
        val result = AttributeCalculation.calculate(custom, 10.0, listOf(
            mod(100.0, "add_value"), AttributeModifier(type.id, 30.0, operation = "test:cap")
        )) { _, base, current, amount -> minOf(current + amount, base * 2) }
        assertEquals(20.0, result, 0.0)
        assertEquals(10.0, calculate(type.copy(operations = emptyList()), listOf(mod(5.0, "add_value"))), 0.0)
    }

    @Test fun legacyUnitsStayCompatible() {
        assertEquals(36.0, calculate(mods = listOf(AttributeModifier(type.id, 5.0),
            AttributeModifier(type.id, 20.0, ModifierType.PERCENT), AttributeModifier(type.id, 2.0, ModifierType.MULTIPLY))), 0.0)
        assertEquals(25.0, calculate(type.copy(defaultValue = 0.0, isPercentage = true),
            listOf(AttributeModifier(type.id, 25.0, ModifierType.PERCENT))), 0.0)
        assertNull(parseAttributeValue(Double.NaN))
        assertNull(parseAttributeValue("Infinity"))
    }

    @Test fun constraintsApplyAfterAllStages() {
        assertEquals(-5.0, calculate(type.copy(minValue = -5.0), listOf(mod(-20.0, "add_value"))), 0.0)
        assertEquals(12.0, calculate(type.copy(maxValue = 12.0), listOf(mod(5.0, "add_value"))), 0.0)
    }

    @Test fun configSupportsProvidersAndSync() {
        val parsed = AttributeDefinitionParser.parse("test:ward", mapOf(
            "base" to mapOf("type" to "vanilla", "attribute" to "minecraft:armor", "fallback" to 2, "transform" to "value * 0.5"),
            "constraint" to mapOf("min" to -5),
            "sync" to listOf(mapOf("target" to "minecraft:armor", "operation" to "add_value", "value" to mapOf("type" to "delta")))
        ))
        assertEquals("vanilla", parsed.base.type)
        assertEquals(2.0, parsed.defaultValue, 0.0)
        assertEquals(-5.0, parsed.minValue!!, 0.0)
        assertNull(parsed.maxValue)
        assertEquals("value - base", parsed.sync.single().value.code)
    }

    @Test fun derivedIgnoresIndependentState() {
        val parsed = AttributeDefinitionParser.parse("test:derived", mapOf("derived" to "test:power * 2",
            "base" to "invalid", "operations" to listOf("missing"), "sync" to listOf("invalid")))
        assertEquals(0.0, parsed.defaultValue, 0.0)
        assertTrue(parsed.sync.isEmpty())
        assertEquals(setOf("test:power"), AttributeExpressions.references(parsed.derived!!))
    }

    @Test fun namespacesAndQuotedStrings() {
        assertTrue(AttributeRegistry.isValidId("demo:combat/power.v2"))
        assertFalse(AttributeRegistry.isValidId("bad:id:extra"))
        val expression = AttributeExpression("demo:might * 2 + ('demo:fake' === 'x' ? 1 : 0)")
        assertEquals(setOf("demo:might"), AttributeExpressions.references(expression))
        assertTrue(AttributeExpressions.rewrite(expression).contains("attrs.get(\"demo:might\")"))
        assertEquals("Math.min(current + amount, base * 2)", AttributeExpressions.rewrite(AttributeExpression("MIN(current + amount, base * 2)")))
    }

    @Test fun reloadIsAtomicAndKeepsApiEntries() {
        val api = AttributeType("test:api", "API", defaultValue = 3.0)
        assertTrue(AttributeRegistry.register(api))
        try {
            AttributeRegistry.publishConfig(mapOf(type.id to type), emptyMap())
            val before = AttributeRegistry.snapshot()
            val a = type.copy(id = "test:a", derived = AttributeExpression("test:b + 1"))
            val b = type.copy(id = "test:b", derived = AttributeExpression("test:a + 1"))
            assertThrows(IllegalArgumentException::class.java) { AttributeRegistry.publishConfig(mapOf(a.id to a, b.id to b), emptyMap()) }
            assertSame(before, AttributeRegistry.snapshot())
            AttributeRegistry.publishConfig(emptyMap(), emptyMap())
            assertNotNull(AttributeRegistry.get(api.id))
            assertNull(AttributeRegistry.get(type.id))
        } finally {
            AttributeRegistry.unregister(api.id)
            AttributeRegistry.resetToBuiltins()
        }
    }

    @Test fun rejectsUnknownReferencesAndInvalidBounds() {
        assertThrows(IllegalArgumentException::class.java) {
            AttributeRegistry.validateConfig(mapOf(type.id to type.copy(derived = AttributeExpression("test:missing * 2"))), emptyMap())
        }
        assertThrows(IllegalArgumentException::class.java) {
            AttributeRegistry.validateConfig(mapOf(type.id to type.copy(minValue = 10.0, maxValue = 5.0)), emptyMap())
        }
        assertThrows(IllegalArgumentException::class.java) {
            AttributeRegistry.validateConfig(mapOf(type.id to type.copy(operations = listOf("test:missing"))), emptyMap())
        }
    }

    @Test fun slotGroupsIncludeBothHandsAndArmorAliases() {
        assertTrue(AttributeItemModifiers.matchesSlot("offhand", "hand"))
        assertTrue(AttributeItemModifiers.matchesSlot("helmet", "armor"))
        assertTrue(AttributeItemModifiers.matchesSlot("boots", "feet"))
        assertFalse(AttributeItemModifiers.matchesSlot("mainhand", "armor"))
    }

    @Test fun graalBindingsRestoreNestedStateAndMath() {
        val js = io.github.zzzyyylllty.sertraline.util.GraalJsUtil
        js.withBindings(mapOf("value" to 10, "Math" to Math::class.java)) { outer ->
            assertEquals(5, outer.eval("js", "Math.min(value, 5)").asInt())
            js.withBindings(mapOf("value" to 99)) { inner -> assertEquals(99, inner.eval("js", "value").asInt()) }
            assertEquals(10, outer.eval("js", "value").asInt())
        }
        js.withBindings(mapOf("AttributeAPI" to io.github.zzzyyylllty.sertraline.attribute.api.SertralineAttributeAPI)) { context ->
            assertTrue(context.eval("js", "AttributeAPI.isRegistered('attack_damage')").asBoolean())
            assertEquals("undefined", context.eval("js", "typeof value").asString())
        }
        js.withBindings(mapOf("attrs" to mapOf("demo:might" to 10.0))) { context ->
            assertEquals(20.0, context.eval("js", AttributeExpressions.rewrite(AttributeExpression("demo:might * 2"))).asDouble(), 0.0)
        }
    }

    @Test fun nashornCanCallTheApi() {
        val engine = io.github.zzzyyylllty.sertraline.util.GraalJsUtil.nashornEngineHolder.get()
        val bindings = engine.createBindings()
        bindings["AttributeAPI"] = io.github.zzzyyylllty.sertraline.attribute.api.SertralineAttributeAPI
        assertEquals(true, engine.eval("AttributeAPI.isRegistered('attack_damage')", bindings))
    }
}
