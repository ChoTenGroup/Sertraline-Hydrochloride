package io.github.zzzyyylllty.sertraline.config

import io.github.zzzyyylllty.sertraline.Sertraline.craftingStations
import io.github.zzzyyylllty.sertraline.config.common.SertralineSettings
import io.github.zzzyyylllty.sertraline.data.*
import io.github.zzzyyylllty.sertraline.event.SertralineCraftingStationFileLoadPostEvent
import io.github.zzzyyylllty.sertraline.event.SertralineCraftingStationFileLoadPreEvent
import io.github.zzzyyylllty.sertraline.logger.infoL
import io.github.zzzyyylllty.sertraline.logger.severeL
import io.github.zzzyyylllty.sertraline.logger.warningL
import io.github.zzzyyylllty.sertraline.logger.warningS
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.releaseResourceFolder
import java.io.File

fun loadCraftingStationFiles(context: LoadContext? = null) {
    infoL("CraftingStation_Load")
    SertralineCraftingStationFileLoadPreEvent(context).call()
    try {
        val stationsFolder = File(getDataFolder(), "crafting-stations")
        if (!stationsFolder.exists()) {
            warningL("CraftingStation_Load_Regen")
            releaseResourceFolder("crafting-stations")
        }

        val regex = SertralineSettings.fileLoadCraftingStation
        val files = safeWalkDirectory(stationsFolder, maxDepth = 10) { file ->
            file.isFile && regex.matches(file.name)
        }
        for (file in files) {
            loadCraftingStationFile(file, regex, context)
        }

        SertralineCraftingStationFileLoadPostEvent(context).call()
        infoL("CraftingStation_Load_Complete", context?.craftingStations?.size ?: craftingStations.size)
    } catch (e: Exception) {
        SertralineCraftingStationFileLoadPostEvent(context, e).call()
        throw e
    }
}

fun loadCraftingStationFile(file: File, regex: Regex, context: LoadContext? = null): Boolean {
    if (file.isDirectory) {
        // 目录递归已由safeWalkDirectory处理，这里直接返回false
        return false
    }

    if (!file.name.matches(regex)) {
        return false
    }

    return try {
        val raw = multiExtensionLoader(file) ?: return false
        val map = TemplateManager.resolveInMap(raw, context)

        if (map.isEmpty()) {
            severeL("CraftingStation_Load_Error_Empty", file.name)
            return false
        }

        for (entry in map.entries) {
            val stationId = entry.key
            try {
                val station = parseCraftingStation(stationId, entry.value as? Map<String, Any?>? ?: linkedMapOf())
                if (context != null) context.craftingStations[stationId] = station else craftingStations[stationId] = station
            } catch (e: Exception) {
                severeL("CraftingStation_Load_Error_Station", file.name, stationId, e.message ?: "Unknown error")
            }
        }

        true
    } catch (e: Exception) {
        severeL("CraftingStation_Load_Error_Parse", file.name, e.message ?: "Unknown error")
        false
    }
}

// ==================== Helper extension functions ====================
private fun Map<String, Any?>.getOptional(key: String): Any? = this[key]

private fun Map<String, Any?>.getString(key: String): String? = this[key]?.toString()

private fun Map<String, Any?>.getInt(key: String): Int? = this[key]?.toString()?.toIntOrNull()

private fun Map<String, Any?>.getBoolean(key: String): Boolean? = this[key]?.toString()?.toBooleanStrictOrNull()

private fun Map<String, Any?>.getMap(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>

private fun Map<String, Any?>.getList(key: String): List<Any?>? = this[key] as? List<*>

// Converts Map<String, Any?> to Map<String, String>, ignoring null values
private fun Map<String, Any?>.toStringMap(): Map<String, String> =
    this.filterValues { it != null }.mapValues { it.value!!.toString() }

// Leniently fetches the condition list: supports the conditions or condition field, as either a list or a single map
private fun Map<String, Any?>.getConditions(key: String): List<Map<String, Any?>> {
    // Try to get the key; if missing, try its singular form (drop the trailing 's')
    var raw = this[key]
    if (raw == null && key.endsWith('s')) {
        val singularKey = key.dropLast(1)
        raw = this[singularKey]
    }
    if (raw == null) return emptyList()
    return when (raw) {
        is List<*> -> raw.filterIsInstance<Map<String, Any?>>()
        is Map<*, *> -> listOf(raw.filterKeys { it is String }.mapKeys { it.key as String })
        else -> emptyList()
    }
}

// ==================== Parse functions ====================
private fun parseCraftingStation(id: String, map: Map<String, Any?>): CraftingStation {
    val option = map.getMap("option") ?: emptyMap()
    val displayMap = map.getMap("display") ?: throw IllegalArgumentException("CraftingStation $id missing display config")
    val display = parseDisplayConfig(displayMap)
    val recipesMap = map.getMap("recipes") ?: emptyMap()
    val recipes = recipesMap.mapValues { (recipeId, recipeMap) ->
        parseStationRecipe(recipeId, recipeMap as? Map<String, Any?> ?: emptyMap())
    }
    val agents = map.getMap("agents")?.toStringMap()
    return CraftingStation(option, display, recipes, agents)
}

private fun parseDisplayConfig(map: Map<String, Any?>): DisplayConfig {
    val title = map.getString("title") ?: ""
    val layout = map.getList("layout")?.filterIsInstance<String>() ?: emptyList()
    val elementsMap = map.getMap("elements") ?: emptyMap()
    val elements = elementsMap.mapValues { (elementId, elementMap) ->
        parseElementConfig(elementId, elementMap as? Map<String, Any?> ?: emptyMap())
    }
    val key = map.getString("key") ?: throw IllegalArgumentException("DisplayConfig missing key")
    return DisplayConfig(title, layout, elements, key)
}

private fun parseElementConfig(id: String, map: Map<String, Any?>): ElementConfig {
    val char = map.getString("char")
    val slot = map.getInt("slot")
    val material = map.getString("material") ?: throw IllegalArgumentException("ElementConfig $id missing material")
    val name = map.getString("name") ?: throw IllegalArgumentException("ElementConfig $id missing name")
    val lore = map.getString("lore")
    val roll = map.getBoolean("roll")
    val agents = map.getMap("agents")?.toStringMap()
    return ElementConfig(char, slot, material, name, lore, roll, agents)
}

private fun parseStationRecipe(id: String, map: Map<String, Any?>): StationRecipe {
    val displayName = map.getString("displayName") ?: id
    val plural = map.getString("plural")
    val displayTime = map.getString("displayTime")
    val time = map.getString("time") ?: "0"
    val options = map.getMap("options") ?: emptyMap()
    // Leniently parse conditions
    val conditionMaps = map.getConditions("conditions")
    val conditions = conditionMaps.map { parseConditionConfig(it) }.takeIf { it.isNotEmpty() }
    val messages = map.getMap("messages")?.toStringMap()
    val displayMap = map.getMap("display")
    val display = displayMap?.let { parseOmniItem(it) }
    val inputsList = map.getList("inputs") ?: emptyList()
    val inputs = inputsList.filterIsInstance<Map<String, Any?>>().map { parseStationRecipeInput(it) }
    val outputsList = map.getList("outputs") ?: emptyList()
    val outputs = outputsList.filterIsInstance<Map<String, Any?>>().map { parseOmniItem(it) }
    if (inputs.isEmpty()) warningS("Recipe '$id' has no inputs — nothing will be consumed")
    if (outputs.isEmpty()) warningS("Recipe '$id' has no outputs — nothing will be produced")
    val agents = map.getMap("agents")?.toStringMap()
    return StationRecipe(displayName, plural, displayTime, time, options, conditions, messages, display, inputs, outputs, agents)
}

private fun parseConditionConfig(map: Map<String, Any?>): ConditionConfig {
    val name = map.getString("name") ?: ""
    val type = map.getString("type") ?: ""
    val amount = map.getString("amount") ?: ""
    val required = map.getString("required") ?: ""
    val condition = map.getString("condition") ?: ""
    val agents = map.getMap("agents")?.toStringMap()
    return ConditionConfig(name, type, amount, required, condition, agents)
}

private fun parseStationRecipeInput(map: Map<String, Any?>): StationRecipeInput {
    val displayName = map.getString("displayName") ?: ""
    val input = parseOmniItem(map)
    val plural = map.getString("plural")
    return StationRecipeInput(displayName, input, plural)
}

private fun parseOmniItem(map: Map<String, Any?>): OmniItem {
    // OmniItem fields: source, item, parameters, components, amount
    // The item field in the config may be a full identifier, e.g. "plugin:item"
    val itemStr = map.getString("item") ?: throw IllegalArgumentException("OmniItem missing item")
    // Parse source and item
    val source: String
    val item: String
    if (itemStr.contains(":")) {
        val parts = itemStr.split(":", limit = 2)
        source = parts[0]
        item = parts[1]
    } else {
        // Default source is "mc" (minecraft)
        source = "mc"
        item = itemStr
    }
    val parameters = map.getMap("parameters")?.let { LinkedHashMap(it) }
    val components = map.getMap("components")?.let { LinkedHashMap(it) }
    val amount = map.getString("amount")
    return OmniItem(source, item, parameters, components, amount)
}