package io.github.zzzyyylllty.sertraline.attribute

import io.github.zzzyyylllty.sertraline.attribute.data.*
import org.junit.Assert.*
import org.junit.Test

class AttributeRegressionTest {
    @Test fun numericTernariesAreNotAttributeReferences() {
        val expression = AttributeExpression("current > 0 ? 1:0")
        assertTrue(AttributeExpressions.references(expression).isEmpty())
        assertEquals(expression.code, AttributeExpressions.rewrite(expression))
    }

    @Test fun quotedFunctionsAndEscapedQuotesStayLiteral() {
        val expression = AttributeExpression("'MIN(1, 2) demo:fake \\' demo:other' + demo:real")
        assertEquals(setOf("demo:real"), AttributeExpressions.references(expression))
        assertEquals("'MIN(1, 2) demo:fake \\' demo:other' + attrs.get(\"demo:real\")", AttributeExpressions.rewrite(expression))
    }

    @Test fun commentTextIsNotRewritten() {
        val expression = AttributeExpression("demo:real /* demo:fake MIN( */ + 1")
        assertEquals(setOf("demo:real"), AttributeExpressions.references(expression))
        assertEquals("attrs.get(\"demo:real\") /* demo:fake MIN( */ + 1", AttributeExpressions.rewrite(expression))
    }

    @Test fun scriptProvidersKeepTheirConfiguredInterval() {
        for (kind in listOf("js", "graaljs", "expression")) {
            val type = AttributeDefinitionParser.parse("test:dynamic", mapOf("base" to mapOf(
                "type" to kind, "expression" to "1", "update_interval" to 100
            )))
            assertEquals(kind, 100L, type.base.updateInterval)
        }
    }

    @Test fun registeredLegacyIdsRemainReachable() {
        assertTrue(AttributeRegistry.register(AttributeType("test-power", "Power")))
        try {
            assertNotNull(AttributeRegistry.get("test-power"))
            assertNotNull(AttributeRegistry.get("test_power"))
        } finally {
            AttributeRegistry.unregister("test-power")
        }
    }

    @Test fun shortOperationIdsCannotOverrideBuiltins() {
        assertFalse(AttributeRegistry.registerOperation("add_value", AttributeExpression("0")))
    }

    @Test fun shortCustomOperationsAreReachable() {
        assertTrue(AttributeRegistry.registerOperation("test_op", AttributeExpression("current + amount")))
        try {
            val type = AttributeDefinitionParser.parse("test:op", mapOf("operations" to listOf("test_op")))
            assertTrue(AttributeRegistry.register(type))
        } finally {
            AttributeRegistry.unregister("test:op")
            AttributeRegistry.unregisterOperation("test_op")
        }
    }

    @Test fun reloadValidationCannotUseRemovedDefinitions() {
        val old = AttributeType("test:removed", "Removed")
        AttributeRegistry.publishConfig(mapOf(old.id to old), emptyMap())
        try {
            val candidate = AttributeRegistry.configSnapshot(emptyMap(), emptyMap())
            val arguments = mapOf("attribute-modifiers" to listOf(mapOf(
                "type" to old.id, "id" to "test:mod", "slot" to "mainhand", "amount" to 1, "operation" to "add_value"
            )))
            assertThrows(IllegalArgumentException::class.java) {
                io.github.zzzyyylllty.sertraline.listener.sertraline.validateAttributeModifiers("test:item", arguments, candidate)
            }
            assertNotNull(AttributeRegistry.get(old.id))
        } finally { AttributeRegistry.resetToBuiltins() }
    }

    @Test fun persistedModifiersKeepTheirOwnershipMarker() {
        val modifier = AttributeVanillaSync.createModifier("sync/test:health/0", 10.0,
            org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER)
        val restored = org.bukkit.attribute.AttributeModifier.deserialize(modifier.serialize())
        assertTrue(AttributeVanillaSync.isOwned(restored))
        @Suppress("DEPRECATION")
        val foreign = org.bukkit.attribute.AttributeModifier(java.util.UUID.randomUUID(), "foreign", 5.0,
            org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER)
        assertFalse(AttributeVanillaSync.isOwned(foreign))
    }
}
