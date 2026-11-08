package io.github.zzzyyylllty.sertraline.config

import io.github.zzzyyylllty.sertraline.Sertraline.types
import io.github.zzzyyylllty.sertraline.config.common.SertralineSettings
import io.github.zzzyyylllty.sertraline.Sertraline.console
import io.github.zzzyyylllty.sertraline.data.Type
import io.github.zzzyyylllty.sertraline.debugMode.devLog
import io.github.zzzyyylllty.sertraline.event.SertralineTypeFileLoadPostEvent
import io.github.zzzyyylllty.sertraline.event.SertralineTypeFileLoadPreEvent
import io.github.zzzyyylllty.sertraline.logger.infoL
import io.github.zzzyyylllty.sertraline.logger.infoS
import io.github.zzzyyylllty.sertraline.logger.severeL
import io.github.zzzyyylllty.sertraline.logger.warningL
import io.github.zzzyyylllty.sertraline.util.serialize.parseToMap
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.releaseResourceFile
import taboolib.common.platform.function.warning
import taboolib.module.configuration.Configuration
import taboolib.module.configuration.Type as ConfigType
import java.io.File

fun loadTypeFiles(context: LoadContext? = null) {
    infoL("Type_Load")
    SertralineTypeFileLoadPreEvent(context).call()
    try {
        if (!File(getDataFolder(), "types").exists()) {
            warningL("Type_Load_Regen")
            releaseResourceFile("types/default.yml")
        }
        val typesFolder = File(getDataFolder(), "types")
        val regex = SertralineSettings.fileLoadType
        val files = safeWalkDirectory(typesFolder, maxDepth = 10) { file ->
            file.isFile && regex.matches(file.name)
        }
        for (file in files) {
            loadTypeFile(file, regex, context)
        }
        SertralineTypeFileLoadPostEvent(context).call()
    } catch (e: Exception) {
        SertralineTypeFileLoadPostEvent(context, e).call()
        throw e
    }
}

fun loadTypeFile(file: File, regex: Regex, context: LoadContext? = null) {
    devLog("Loading type file ${file.name}")

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
        loadType(key, value as Map<String, Any?>? ?: linkedMapOf(), context)
    }
}

fun loadType(key: String, arg: Map<String, Any?>, context: LoadContext? = null) {
    val c = ConfigUtil
    val type = Type(
        id = key,
        name = c.getDeep(arg, "name") as? String ?: key,
        parent = c.getDeep(arg, "parent") as? String,
        description = c.getDeep(arg, "description") as? String ?: "",
        extra = c.getDeep(arg, "extra") as? Map<String, Any?> ?: mapOf(),
        meta = c.getDeep(arg, "meta") as? Map<String, Any?> ?: mapOf()
    )
    if (context != null) context.types[key] = type else types[key] = type
    devLog("Loaded type: $key")
}