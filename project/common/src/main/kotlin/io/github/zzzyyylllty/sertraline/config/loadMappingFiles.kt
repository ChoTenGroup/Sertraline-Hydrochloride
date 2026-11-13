package io.github.zzzyyylllty.sertraline.config

import io.github.zzzyyylllty.sertraline.Sertraline.mappings
import io.github.zzzyyylllty.sertraline.config.common.SertralineSettings
import io.github.zzzyyylllty.sertraline.debugMode.devLog
import io.github.zzzyyylllty.sertraline.event.SertralineMappingFileLoadPostEvent
import io.github.zzzyyylllty.sertraline.event.SertralineMappingFileLoadPreEvent
import io.github.zzzyyylllty.sertraline.logger.infoL
import io.github.zzzyyylllty.sertraline.logger.warningL
import org.yaml.snakeyaml.Yaml
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.releaseResourceFile
import taboolib.common.platform.function.warning
import java.io.File


fun loadMappingFiles(context: LoadContext? = null) {
    infoL("Mapping_Load")
    SertralineMappingFileLoadPreEvent(context).call()
    try {
        // `replace = true` is the intended contract, NOT an accident: the packaged file says so
        // in its own header ("请勿直接修改这个文件 / 因为每次重启都会覆盖该文件 /
        // 如想要添加映射请在此目录下建立更多文件添加"). Custom mappings belong in EXTRA files in
        // this directory — the loader merges every file matching `file-load.mapping`, so a
        // user-authored file is never touched by this release.
        //
        // Writing it only when missing (replace = false) looked safer but breaks every upgrade:
        // a server that installed an older jar keeps its stale copy forever and silently loses
        // mappings added in later versions — which surfaces as
        // "为配置项 sertraline:gem-slots 的 Mappings 未找到" and broken gem slots.
        releaseResourceFile("internal/mapping/mappings.yml", true)
        val mappingFolder = File(getDataFolder(), "internal/mapping")
        if (!mappingFolder.exists()) {
            warningL("Mapping_Load_Not_Found")
            SertralineMappingFileLoadPostEvent(context).call()
            return
        }
        val regex = SertralineSettings.fileLoadMapping
        val files = safeWalkDirectory(mappingFolder, maxDepth = 10) { file ->
            file.isFile && regex.matches(file.name)
        }
        for (file in files) {
            loadMappingFile(file, regex, context)
        }
        // Belt and braces: even if the on-disk file was left stale (restored backup, failed
        // release, hand-edited copy) the packaged defaults still take effect, so core features
        // can never end up without a mapping just because the file on disk is old.
        mergePackagedDefaultMappings(context)
        SertralineMappingFileLoadPostEvent(context).call()
    } catch (e: Exception) {
        SertralineMappingFileLoadPostEvent(context, e).call()
        throw e
    }
}
private const val PACKAGED_MAPPINGS_RESOURCE = "internal/mapping/mappings.yml"

/**
 * Merges the packaged `mappings.yml` into whatever the server currently has.
 *
 * Only ever ADDS keys/alternatives — nothing the server defined is removed, so a server that
 * added its own alternatives keeps them. This makes the built-in mappings unconditionally
 * available instead of depending on the on-disk copy being up to date.
 */
private fun mergePackagedDefaultMappings(context: LoadContext? = null) {
    val text = runCatching {
        val clazz = io.github.zzzyyylllty.sertraline.Sertraline::class.java
        (clazz.classLoader?.getResourceAsStream(PACKAGED_MAPPINGS_RESOURCE)
            ?: clazz.getResourceAsStream("/$PACKAGED_MAPPINGS_RESOURCE"))
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()
    if (text.isNullOrBlank()) {
        devLog("Packaged mappings resource not readable, skipping default merge.")
        return
    }
    // `load(text) as? Map` instead of `load<Map<..>>(text)`: the legacy12 compile surface ships an
    // older SnakeYAML whose `load(String)` is not generic, so an explicit type argument fails there.
    val parsed = runCatching { Yaml().load(text) as? Map<*, *> }.getOrNull() ?: return

    var addedKeys = 0
    var addedValues = 0
    for ((rawKey, rawValue) in parsed) {
        val key = rawKey?.toString() ?: continue
        val defaults = (rawValue as? List<*>)?.filterIsInstance<String>() ?: continue
        if (defaults.isEmpty()) continue

        if (context != null) {
            val existing = context.mappings[key]
            if (existing == null) {
                context.mappings[key] = defaults.toMutableList()
                addedKeys++
            } else {
                val missing = defaults.filterNot { def -> existing.any { it.equals(def, ignoreCase = true) } }
                if (missing.isNotEmpty()) {
                    existing.addAll(missing)
                    addedValues += missing.size
                }
            }
        } else {
            val existing = mappings[key]
            if (existing == null) {
                mappings[key] = defaults
                addedKeys++
            } else {
                val missing = defaults.filterNot { def -> existing.any { it.equals(def, ignoreCase = true) } }
                if (missing.isNotEmpty()) {
                    mappings[key] = existing + missing
                    addedValues += missing.size
                }
            }
        }
    }
    if (addedKeys > 0 || addedValues > 0) {
        infoL("Mapping_Default_Merged", addedKeys, addedValues)
    }
}

fun loadMappingFile(file: File, regex: Regex, context: LoadContext? = null) {
    devLog("Loading file ${file.name}")

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
    for ((key, value) in map) {
        val listValue = value as? List<*> ?: continue
        val mapped = listValue.filterIsInstance<String>()

        if (context != null) {
            // candidate 模式：写入局部 context（candidate 之间合并，不读 live ——
            // 增量语义由 reload 流程在发布时合并 live 旧值处理）
            val existing = context.mappings[key]
            if (existing != null) {
                existing.addAll(mapped)
                devLog("Mapping Merged: $key - $existing")
            } else {
                context.mappings[key] = mapped.toMutableList()
                devLog("Mapping Loaded: $key - $mapped")
            }
        } else {
            // 兼容入口：直接写 live 表（既有行为）
            val existing = mappings[key]
            if (existing != null) {
                val merged = existing.toMutableList()
                merged.addAll(mapped)
                mappings[key] = merged
                devLog("Mapping Merged: $key - $merged")
            } else {
                mappings[key] = mapped
                devLog("Mapping Loaded: $key - $mapped")
            }
        }
    }
}
