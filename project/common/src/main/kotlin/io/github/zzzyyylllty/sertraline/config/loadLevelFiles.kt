package io.github.zzzyyylllty.sertraline.config

import io.github.zzzyyylllty.sertraline.Sertraline.levels
import io.github.zzzyyylllty.sertraline.config.common.SertralineSettings
import io.github.zzzyyylllty.sertraline.data.Level
import io.github.zzzyyylllty.sertraline.debugMode.devLog
import io.github.zzzyyylllty.sertraline.event.SertralineLevelFileLoadPostEvent
import io.github.zzzyyylllty.sertraline.event.SertralineLevelFileLoadPreEvent
import io.github.zzzyyylllty.sertraline.logger.infoL
import io.github.zzzyyylllty.sertraline.logger.infoS
import io.github.zzzyyylllty.sertraline.logger.severeL
import io.github.zzzyyylllty.sertraline.logger.warningL
import io.github.zzzyyylllty.sertraline.util.serialize.parseToMap
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.releaseResourceFile
import taboolib.common.platform.function.warning
import taboolib.module.configuration.Configuration
import taboolib.module.configuration.Type
import java.io.File

fun loadLevelFiles(context: LoadContext? = null) {
    infoL("Level_Load")
    SertralineLevelFileLoadPreEvent(context).call()
    try {
        if (!File(getDataFolder(), "levels").exists()) {
            warningL("Level_Load_Regen")
            releaseResourceFile("levels/default.yml")
        }
        val levelsFolder = File(getDataFolder(), "levels")
        val regex = SertralineSettings.fileLoadLevel
        val files = safeWalkDirectory(levelsFolder, maxDepth = 10) { file ->
            file.isFile && regex.matches(file.name)
        }
        for (file in files) {
            loadLevelFile(file, regex, context)
        }
        SertralineLevelFileLoadPostEvent(context).call()
    } catch (e: Exception) {
        SertralineLevelFileLoadPostEvent(context, e).call()
        throw e
    }
}

fun loadLevelFile(file: File, regex: Regex, context: LoadContext? = null) {
    devLog("Loading level file ${file.name}")

    if (file.isDirectory) {
        // 目录递归已由safeWalkDirectory处理，这里直接返回
        return
    }

    if (!file.name.matches(regex)) {
        devLog("${file.name} not match regex, skipping...")
        return
    }
    val raw = multiExtensionLoader(file) ?: return
    val map = TemplateManager.resolveInMap(raw, context)
    if (map.isEmpty()) {
        severeL("Config_Load_Error_Empty", file.name)
        return
    }
    for (it in map.entries) {
        val key = it.key
        val value = map[key]
        loadLevel(key, value as Map<String, Any?>? ?: linkedMapOf(), context)
    }
}

fun loadLevel(key: String, arg: Map<String, Any?>, context: LoadContext? = null) {
    val c = ConfigUtil
    val level = Level(
        id = key,
        name = c.getDeep(arg, "name") as? String ?: key,
        description = c.getDeep(arg, "description") as? String ?: "",
        color = c.getDeep(arg, "color") as? String ?: "<white>",
        weight = (c.getDeep(arg, "weight") as? Number)?.toInt() ?: 1,
        extra = c.getDeep(arg, "extra") as? Map<String, Any?> ?: mapOf()
    )
    if (context != null) context.levels[key] = level else levels[key] = level
    devLog("Loaded level: $key")
}