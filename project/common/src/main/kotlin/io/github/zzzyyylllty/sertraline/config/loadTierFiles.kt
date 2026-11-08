package io.github.zzzyyylllty.sertraline.config

import io.github.zzzyyylllty.sertraline.Sertraline.tiers
import io.github.zzzyyylllty.sertraline.config.common.SertralineSettings
import io.github.zzzyyylllty.sertraline.Sertraline.console
import io.github.zzzyyylllty.sertraline.data.Tier
import io.github.zzzyyylllty.sertraline.debugMode.devLog
import io.github.zzzyyylllty.sertraline.event.SertralineTierFileLoadPostEvent
import io.github.zzzyyylllty.sertraline.event.SertralineTierFileLoadPreEvent
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

fun loadTierFiles(context: LoadContext? = null) {
    infoL("Tier_Load")
    SertralineTierFileLoadPreEvent(context).call()
    try {
        if (!File(getDataFolder(), "tiers").exists()) {
            warningL("Tier_Load_Regen")
            releaseResourceFile("tiers/default.yml")
        }
        val tiersFolder = File(getDataFolder(), "tiers")
        val regex = SertralineSettings.fileLoadTier
        val files = safeWalkDirectory(tiersFolder, maxDepth = 10) { file ->
            file.isFile && regex.matches(file.name)
        }
        for (file in files) {
            loadTierFile(file, regex, context)
        }
        SertralineTierFileLoadPostEvent(context).call()
    } catch (e: Exception) {
        SertralineTierFileLoadPostEvent(context, e).call()
        throw e
    }
}

fun loadTierFile(file: File, regex: Regex, context: LoadContext? = null) {
    devLog("Loading tier file ${file.name}")

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
        loadTier(key, value as Map<String, Any?>? ?: linkedMapOf(), context)
    }
}

fun loadTier(key: String, arg: Map<String, Any?>, context: LoadContext? = null) {
    val c = ConfigUtil
    val tier = Tier(
        id = key,
        name = c.getDeep(arg, "name") as? String ?: key,
        description = c.getDeep(arg, "description") as? String ?: "",
        color = c.getDeep(arg, "color") as? String ?: "<white>",
        weight = (c.getDeep(arg, "weight") as? Number)?.toInt() ?: 1,
        extra = c.getDeep(arg, "extra") as? Map<String, Any?> ?: mapOf(),
        meta = c.getDeep(arg, "meta") as? Map<String, Any?> ?: mapOf()
    )
    // context 模式写 candidate；无 context（兼容入口）写 live 表
    if (context != null) context.tiers[key] = tier else tiers[key] = tier
    devLog("Loaded tier: $key")
}