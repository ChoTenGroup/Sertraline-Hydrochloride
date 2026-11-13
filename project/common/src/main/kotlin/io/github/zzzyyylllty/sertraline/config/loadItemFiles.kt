package io.github.zzzyyylllty.sertraline.config

import io.github.zzzyyylllty.sertraline.Sertraline
import io.github.zzzyyylllty.sertraline.Sertraline.itemMap
import io.github.zzzyyylllty.sertraline.config.common.SertralineSettings
import io.github.zzzyyylllty.sertraline.debugMode.devLog
import io.github.zzzyyylllty.sertraline.event.ItemLoadEvent
import io.github.zzzyyylllty.sertraline.event.SertralineItemFileLoadPostEvent
import io.github.zzzyyylllty.sertraline.event.SertralineItemFileLoadPreEvent
import io.github.zzzyyylllty.sertraline.event.SertralineItemSingleFileLoadPostEvent
import io.github.zzzyyylllty.sertraline.event.SertralineItemSingleFileLoadPreEvent
import io.github.zzzyyylllty.sertraline.logger.infoL
import io.github.zzzyyylllty.sertraline.logger.infoS
import io.github.zzzyyylllty.sertraline.logger.severeL
import io.github.zzzyyylllty.sertraline.logger.warningL
import io.github.zzzyyylllty.sertraline.util.serialize.parseToMap
// import org.yaml.snakeyaml.Yaml
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.releaseResourceFile
import taboolib.common.platform.function.releaseResourceFolder
import taboolib.common.platform.function.warning
import taboolib.module.configuration.Configuration
import taboolib.module.configuration.Type
import java.io.File


fun loadItemFiles(
    context: LoadContext? = null,
    incremental: Boolean = SertralineSettings.incrementalLoading && "item" in SertralineSettings.incrementalLoadingMonitor,
) {
    infoL("Item_Load")

    // 候选构建（reload 的 LoadContext 模式）必须全量解析：候选从空开始，
    // mtime 增量跳过会让候选缺失未变更文件的条目，publish 后 itemMap 被清空。
    // 增量优化只对无 context 的 live 原位更新路径有意义。
    val effectiveIncremental = incremental && context == null

    SertralineItemFileLoadPreEvent(context, effectiveIncremental).call()

    try {
        if (!File(getDataFolder(), "workspace").exists()) {
            warningL("Item_Load_Regen")
            releaseResourceFolder("workspace/default")
        }

        val workspaceDir = File(getDataFolder(), "workspace")

        if (!effectiveIncremental) {
            // 全量模式不消费增量时间戳，顺手清理陈旧记录
            Sertraline.fileLastModified.clear()
        } else {
            context?.itemsLoadedIncrementally = true
        }

        if (effectiveIncremental) {
            loadItemFilesIncremental(workspaceDir, context)
        } else {
            loadItemFilesFull(workspaceDir, context)
        }

        val loadedCount = context?.items?.size ?: itemMap.size
        if (loadedCount == 0) {
            // 0 个物品几乎总是配置问题（workspace 空、file-load.item 正则写错、文件扩展名不匹配），
            // 以前只在 info 里显示一个 0，服务器「所有物品消失」却没有任何提示。
            warningL("Item_Load_Empty_Result")
        }
        SertralineItemFileLoadPostEvent(context, loadedCount).call()
        infoL("Item_Load_Complete", loadedCount)
    } catch (e: Exception) {
        SertralineItemFileLoadPostEvent(context, context?.items?.size ?: itemMap.size, e).call()
        throw e
    }
}

/**
 * 增量发布合并：把「本轮未重新解析的文件」对应的 live 条目带回候选。
 *
 * 增量模式只解析新增/修改的文件，其余条目仍留在 live 表；publishToLive 的
 * clear+putAll 会把这部分丢掉，因此发布前按 itemSourceFile 索引回填：
 * - live 条目来源文件本轮被解析过 → 跳过（文件内容以新解析结果为准，含被删除的条目）
 * - live 条目来源文件本轮未解析 → 条目仍有效，合并进候选
 * - 没有来源记录（上一轮发布前加载 / API 动态注册 / 旧版本数据）→ 保守保留
 *
 * 仅在 context（候选构建）模式下调用；无 context 的兼容路径 live 表原位更新，无需合并。
 *
 * **当前不可达，保留代码但不再被 reload 调用。** `itemsLoadedIncrementally` 只在
 * `effectiveIncremental`（= `incremental && context == null`）分支里被设置，而该分支里
 * `context` 恒为 null；reload 一定带 context，于是候选永远从空表全量解析。
 * 也就是说 `incremental-loading` 对 reload **不生效**，只在无 context 的原位加载路径生效。
 * reload 侧不再调用本函数，避免"看起来在做增量合并、实际从不执行"的误导。
 */
internal fun mergeLiveItemsForIncrementalPublish(context: LoadContext) {
    val parsedFiles = context.parsedItemFiles
    val seenFiles = context.seenItemFiles
    val liveItems = Sertraline.itemMap
    for ((id, sItem) in liveItems) {
        if (context.items.containsKey(id)) continue
        val source = Sertraline.itemSourceFile[id]
        // 来源文件本轮被解析过 → 以新解析结果为准（含条目被删除的情况），不回填
        if (source != null && source in parsedFiles) continue
        // 来源文件已从工作区消失 → 条目随文件删除
        if (source != null && source !in seenFiles) continue
        context.items[id] = sItem
        // 对应的 itemCache 条目同步回填，保持两表一致
        if (id !in context.itemCache) {
            Sertraline.itemCache[id]?.let { context.itemCache[id] = it }
        }
    }
    // 同步清理已消失文件的来源记录（parsed ⊆ seen，删除的文件不在 seenFiles 中）
    Sertraline.itemSourceFile.keys.retainAll(seenFiles)
}

private fun loadItemFilesFull(workspaceDir: File, context: LoadContext? = null) {
    if (!workspaceDir.exists()) {
        severeL("Item_Load_Not_Found")
        return
    }

    val regex = SertralineSettings.fileLoadItem
    val files = safeWalkDirectory(workspaceDir, maxDepth = 10) { file ->
        file.isFile && regex.matches(file.name)
    }
    for (file in files) {
        loadItemFile(file, regex, context)
    }
}

private fun loadItemFilesIncremental(workspaceDir: File, context: LoadContext? = null) {
    val regex = SertralineSettings.fileLoadItem
    val files = safeWalkDirectory(workspaceDir, maxDepth = 10) { file ->
        file.isFile && regex.matches(file.name)
    }

    var loadedCount = 0
    var skippedCount = 0
    val parsedThisRound = mutableSetOf<String>()

    for (file in files) {
        // 记录本轮在磁盘上见过的文件（无论是否解析），供删除检测
        context?.seenItemFiles?.add(file.absolutePath)
        val lastModified = Sertraline.fileLastModified[file.absolutePath]
        val currentModified = file.lastModified()

        // Only load new or modified files
        if (lastModified == null || lastModified < currentModified) {
            if (loadItemFile(file, regex, context)) {
                Sertraline.fileLastModified[file.absolutePath] = currentModified
                parsedThisRound.add(file.absolutePath)
                loadedCount++
            }
        } else {
            skippedCount++
        }
    }

    // 记录本轮实际解析过的文件，供 mergeLiveItemsForIncrementalPublish 判定
    context?.parsedItemFiles?.addAll(parsedThisRound)

    infoL("Item_Load_Incremental_Stats", loadedCount, skippedCount, files.size)
}

fun loadItemFile(file: File, regex: Regex, context: LoadContext? = null): Boolean {
    if (file.isDirectory) {
        // 目录递归已由safeWalkDirectory处理，这里直接返回false
        return false
    }

    if (!file.name.matches(regex)) {
        return false
    }

    SertralineItemSingleFileLoadPreEvent(file, context).call()

    return try {
        val raw = multiExtensionLoader(file) ?: return false.also {
            SertralineItemSingleFileLoadPostEvent(file, context, false).call()
        }
        val map = TemplateManager.resolveInMap(raw, context)

        if (map.isEmpty()) {
            severeL("Item_Load_Error_Empty", file.name)
            SertralineItemSingleFileLoadPostEvent(file, context, false).call()
            return false
        }

        val itemSink = context?.items ?: itemMap
        for (entry in map.entries) {
            val key = entry.key
            // skip __-prefixed items (reserved for private manager)
            if (key.startsWith("__")) {
                warningL("Manager_Item_Load_Skip_Private", key)
                continue
            }
            // 记录条目来源文件：增量发布时把「未变更文件」的 live 条目合并回候选。
            // 候选模式**不能**直接写 live 索引：reload 可能在中途失败，live 索引却已经指向
            // 新路径，于是「失败即保持不变」的语义被破坏。候选写进 context，由 publishToLive 发布。
            if (context != null) {
                context.itemSourceFiles[key] = file.absolutePath
            } else {
                Sertraline.itemSourceFile[key] = file.absolutePath
            }
            val value = map[key]
            val arguments = value as? Map<String, Any?> ?: linkedMapOf()
            io.github.zzzyyylllty.sertraline.listener.sertraline.validateAttributeModifiers(key, arguments,
                context?.attributes ?: io.github.zzzyyylllty.sertraline.attribute.AttributeRegistry.snapshot())
            ItemLoadEvent(key, arguments, linkedMapOf(), context).call()
        }

        SertralineItemSingleFileLoadPostEvent(file, context, true).call()
        true
    } catch (e: Exception) {
        severeL("Item_Load_Error_Parse", file.name, e.message ?: "Unknown error")
        SertralineItemSingleFileLoadPostEvent(file, context, false, e).call()
        if (context != null) throw e
        false
    }
}
