package io.github.zzzyyylllty.sertraline.config

import io.github.zzzyyylllty.sertraline.attribute.*
import io.github.zzzyyylllty.sertraline.attribute.data.*
import io.github.zzzyyylllty.sertraline.config.common.SertralineSettings
import io.github.zzzyyylllty.sertraline.logger.infoL
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.releaseResourceFile
import java.io.File

private const val DEFAULT_ATTRIBUTE_FILE_PATTERN = "^(?![#!]).*\\.(?i)(yaml|yml|toml|tml|json|conf)$"

/** Load, validate, then publish. */
fun loadAttributeFiles(context: LoadContext? = null) {
    val regex = Regex(SertralineSettings.rawString("file-load.attribute", DEFAULT_ATTRIBUTE_FILE_PATTERN))
    val folder = File(getDataFolder(), "attributes")
    if (!folder.exists()) releaseResourceFile("attributes/default.yml")
    val types = linkedMapOf<String, AttributeType>()
    val operations = linkedMapOf<String, AttributeExpression>()
    for (file in safeWalkDirectory(folder, maxDepth = 10) { it.isFile && regex.matches(it.name) }.sortedBy { it.path }) {
        try {
            if (file.readLines().none { it.isNotBlank() && !it.trimStart().startsWith('#') }) continue
            val raw = multiExtensionLoader(file) ?: error("Unreadable file")
            val map = TemplateManager.resolveInMap(raw, context)
            require(map["attribute_operations"] == null || map["attribute_operations"] is Map<*, *>) { "Invalid attribute_operations" }
            val opNodes = map["attribute_operations"] as? Map<*, *> ?: emptyMap<Any, Any>()
            for ((key, value) in opNodes) {
                val id = AttributeDefinitionParser.operation(key.toString())
                require(AttributeRegistry.isValidId(id) && id !in DEFAULT_OPERATIONS) { "Invalid operation: $id" }
                val node = value as? Map<*, *> ?: error("Invalid operation: $id")
                require(operations.put(id, AttributeDefinitionParser.expression(node["expression"])) == null) { "Duplicate operation: $id" }
            }
            val definitions = if (map.containsKey("attributes")) {
                map["attributes"] as? Map<*, *> ?: error("Invalid attributes")
            } else map.filterKeys { it != "attribute_operations" }
            for ((key, value) in definitions) {
                val type = AttributeDefinitionParser.parse(key.toString(), value as? Map<*, *> ?: error("Invalid attribute: $key"))
                require(types.put(type.id, type) == null) { "Duplicate attribute: ${type.id}" }
            }
        } catch (ex: Exception) {
            throw IllegalArgumentException("${file.name}: ${ex.message}", ex)
        }
    }
    val snapshot = AttributeRegistry.configSnapshot(types, operations)
    if (context == null) AttributeRegistry.publishConfig(types, operations)
    else {
        context.attributeTypes = types
        context.attributeOperations = operations
        context.attributes = snapshot
    }
    infoL("Attribute_Load", AttributeRegistry.builtinIds().size + types.size, types.size)
}

fun loadAttribute(key: String, arg: Map<String, Any?>): Boolean =
    AttributeRegistry.register(AttributeDefinitionParser.parse(key, arg), RegistrationSource.CONFIG)
