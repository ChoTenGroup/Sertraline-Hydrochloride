package io.github.zzzyyylllty.sertraline.attribute

import io.github.zzzyyylllty.sertraline.attribute.data.*

/** Config decoder. */
object AttributeDefinitionParser {
    fun expression(raw: Any?): AttributeExpression {
        if (raw is Map<*, *>) {
            val engine = (raw["engine"] ?: raw["type"] ?: "graaljs").toString().lowercase()
            require(engine in setOf("graaljs", "gjs", "js", "javascript", "nashorn", "script")) { "Unknown engine: $engine" }
            val code = raw["expression"] ?: raw["script"] ?: raw["name"] ?: error("Missing expression")
            return AttributeExpression(code.toString(), engine, raw.containsKey("script") || engine == "script")
        }
        require(raw is String || raw is Number) { "Invalid expression" }
        return AttributeExpression(raw.toString())
    }

    fun parse(id: String, node: Map<*, *>): AttributeType {
        val derived = node["derived"]?.let(::expression)
        require(node["constraint"] == null || node["constraint"] is Map<*, *>) { "Invalid constraint: $id" }
        require(node["entities"] == null || node["entities"] is List<*>) { "Invalid entities: $id" }
        require(derived != null || node["sync"] == null || node["sync"] is List<*>) { "Invalid sync: $id" }
        val base = if (derived != null) AttributeBase() else parseBase(node["base"] ?: node["default"] ?: 0)
        val constraint = node["constraint"] as? Map<*, *>
        val operations = if (derived != null || !node.containsKey("operations")) DEFAULT_OPERATIONS else {
            val list = node["operations"] as? List<*> ?: error("Invalid operations: $id")
            list.map { operation(it.toString()) }.also { require(it.distinct().size == it.size) { "Duplicate operation: $id" } }
        }
        val sync = if (derived != null) emptyList() else (node["sync"] as? List<*>)?.map {
            val entry = it as? Map<*, *> ?: error("Invalid sync: $id")
            val target = entry["target"]?.toString() ?: error("Missing sync target: $id")
            val op = operation(entry["operation"]?.toString() ?: error("Missing sync operation: $id"))
            require(op in DEFAULT_OPERATIONS) { "Invalid sync operation: $op" }
            val raw = entry["value"] ?: "value"
            val value = when ((raw as? Map<*, *>)?.get("type")) {
                "delta" -> AttributeExpression("value - base")
                "ratio" -> AttributeExpression("value / base")
                else -> expression(raw)
            }
            AttributeSync(target, op, value)
        } ?: emptyList()
        return AttributeType(
            id = AttributeRegistry.normalizeId(id),
            name = node["name"]?.toString() ?: id,
            vanillaName = if (derived != null) null else (node["vanilla-name"] ?: node["vanillaName"])?.toString(),
            defaultValue = base.value,
            minValue = (constraint?.get("min") ?: node["min"])?.let(::finite),
            maxValue = (constraint?.get("max") ?: node["max"])?.let(::finite),
            isPercentage = node["percentage"] == true,
            description = node["description"]?.toString() ?: "",
            base = base, derived = derived, operations = operations,
            entities = (node["entities"] as? List<*>)?.map { it.toString().lowercase() }?.toSet() ?: emptySet(),
            sync = sync,
        )
    }

    fun operation(raw: String): String {
        val id = raw.trim().lowercase(java.util.Locale.ROOT)
        return if (':' in id) id else "minecraft:$id"
    }

    private fun parseBase(raw: Any): AttributeBase {
        if (raw is Number) return AttributeBase(value = finite(raw))
        if (raw is String) return AttributeBase(type = "expression", expression = expression(raw))
        val node = raw as? Map<*, *> ?: error("Invalid base")
        val type = node["type"]?.toString() ?: "constant"
        val fallback = finite(node["fallback"] ?: node["value"] ?: 0)
        val transform = node["transform"]?.let(::expression)
        val interval = (node["update_interval"] as? Number)?.toLong() ?: 20
        return when (type) {
            "constant" -> AttributeBase(value = finite(node["value"] ?: 0))
            "vanilla", "by_entity_type" -> AttributeBase(
                type, fallback, node["attribute"]?.toString() ?: error("Missing base attribute"), transform,
                updateInterval = interval,
            )
            "expression" -> AttributeBase(type = "expression", value = fallback, expression = expression(node["expression"]), updateInterval = interval)
            "js", "javascript", "graaljs", "gjs", "nashorn", "script" ->
                AttributeBase(type = "expression", value = fallback, expression = expression(node), updateInterval = interval)
            else -> error("Unknown base type: $type")
        }
    }

    private fun finite(raw: Any): Double = (raw as? Number)?.toDouble()?.also {
        require(it.isFinite()) { "Non-finite value" }
    } ?: error("Expected number: $raw")
}
