package io.github.zzzyyylllty.sertraline.config

import io.github.zzzyyylllty.sertraline.Sertraline
import io.github.zzzyyylllty.sertraline.Sertraline.itemMap
import io.github.zzzyyylllty.sertraline.config.common.SertralineSettings
import io.github.zzzyyylllty.sertraline.event.ItemLoadEvent
import io.github.zzzyyylllty.sertraline.event.SertralineItemFileLoadPostEvent
import io.github.zzzyyylllty.sertraline.event.SertralineItemFileLoadPreEvent
import io.github.zzzyyylllty.sertraline.event.SertralineItemSingleFileLoadPostEvent
import io.github.zzzyyylllty.sertraline.event.SertralineItemSingleFileLoadPreEvent
import io.github.zzzyyylllty.sertraline.logger.infoL
import io.github.zzzyyylllty.sertraline.logger.severeL
import io.github.zzzyyylllty.sertraline.logger.warningL
// import org.yaml.snakeyaml.Yaml
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.releaseResourceFile
import taboolib.common.platform.function.releaseResourceFolder
import java.io.File


fun loadItemFiles(
    context: LoadContext? = null,
    incremental: Boolean = SertralineSettings.incrementalLoading && "item" in SertralineSettings.incrementalLoadingMonitor,
) {
    infoL("Item_Load")

    SertralineItemFileLoadPreEvent(context, incremental).call()
    try {
        val workspace = File(getDataFolder(), "workspace")
        if (!workspace.exists()) {
            warningL("Item_Load_Regen")
            releaseResourceFolder("workspace/default")
        }
        val regex = SertralineSettings.fileLoadItem
        val files = safeWalkDirectory(workspace, maxDepth = 10) { regex.matches(it.name) }
        val previousBySource = if (context == null) emptyMap() else Sertraline.itemSourceFile.entries.groupBy(
            keySelector = { it.value }, valueTransform = { it.key }
        )
        var reused = 0
        for (file in files) {
            val path = file.absolutePath
            val previous = Sertraline.parsedWorkspaceFiles[path].takeIf { incremental }
            val parsed = parseWorkspaceFile(file, previous)
            if (parsed != null && parsed === previous) reused++
            if (parsed != null && loadParsedItemFile(file, context, parsed.data)) {
                if (incremental) context?.parsedWorkspaceFiles?.put(path, parsed)
            } else if (context != null) {
                for (id in previousBySource[path].orEmpty()) {
                    val item = itemMap[id] ?: continue
                    if (context.items.putIfAbsent(id, item) == null) {
                        context.itemSourceFiles[id] = path
                        Sertraline.itemCache[id]?.let { context.itemCache[id] = it }
                    }
                }
            }
        }
        if (incremental) infoL("Item_Load_Incremental_Stats", files.size - reused, reused, files.size)
        val loadedCount = context?.items?.size ?: itemMap.size
        if (loadedCount == 0) {
            // Zero items almost always indicate a configuration issue (an empty workspace, an invalid file-load.item regex,
            // or a mismatched file extension). Previously, the only indication was a count of zero, with no warning
            // that all items had disappeared from the server.
            warningL("Item_Load_Empty_Result")
        }
        SertralineItemFileLoadPostEvent(context, loadedCount).call()
        infoL("Item_Load_Complete", loadedCount)
    } catch (e: Exception) {
        SertralineItemFileLoadPostEvent(context, context?.items?.size ?: itemMap.size, e).call()
        throw e
    }
}

internal data class ParsedWorkspaceFile(val content: String, val data: Map<String, Any?>)

private fun parseWorkspaceFile(file: File, previous: ParsedWorkspaceFile?): ParsedWorkspaceFile? {
    val content = try {
        file.readText()
    } catch (e: Exception) {
        severeL("Item_Load_Error_Parse", file.path, e.message ?: e.javaClass.simpleName)
        return null
    }
    if (previous?.content == content) return previous
    val data = multiExtensionLoader(file, null, content) ?: return null
    @Suppress("UNCHECKED_CAST")
    return ParsedWorkspaceFile(content, TemplateManager.freeze(data) as Map<String, Any?>)
}

fun loadItemFile(file: File, regex: Regex, context: LoadContext? = null): Boolean {
    if (!file.isFile || !regex.matches(file.name)) return false
    val parsed = parseWorkspaceFile(file, null)
    return loadParsedItemFile(file, context, parsed?.data)
}

private fun loadParsedItemFile(file: File, context: LoadContext?, raw: Map<String, Any?>?): Boolean {
    SertralineItemSingleFileLoadPreEvent(file, context).call()
    if (raw == null) {
        SertralineItemSingleFileLoadPostEvent(file, context, false).call()
        return false
    }

    val itemSink = context?.items ?: itemMap
    val sourceSink = context?.itemSourceFiles ?: Sertraline.itemSourceFile
    val cacheSink = context?.itemCache ?: Sertraline.itemCache
    val before = LinkedHashMap<String, Triple<io.github.zzzyyylllty.sertraline.data.AbstractSItem?, String?, Map<String, Any?>?>>()
    return try {
        val map = TemplateManager.resolveInMap(raw, context)

        for (entry in map.entries) {
            val key = entry.key
            // skip __-prefixed items (reserved for private manager)
            if (key.startsWith("__")) {
                warningL("Manager_Item_Load_Skip_Private", key)
                continue
            }
            before[key] = Triple(itemSink[key], sourceSink[key], cacheSink[key])
            val arguments = entry.value as? Map<String, Any?>
                ?: error("Item '$key' must be a configuration section")
            sourceSink[key] = file.absolutePath
            io.github.zzzyyylllty.sertraline.listener.sertraline.validateAttributeModifiers(key, arguments,
                context?.attributes ?: io.github.zzzyyylllty.sertraline.attribute.AttributeRegistry.snapshot())
            ItemLoadEvent(key, arguments, linkedMapOf(), context).call()
        }

        SertralineItemSingleFileLoadPostEvent(file, context, true).call()
        true
    } catch (e: Exception) {
        for ((key, old) in before) {
            if (old.first == null) itemSink.remove(key) else itemSink[key] = old.first!!
            if (old.second == null) sourceSink.remove(key) else sourceSink[key] = old.second!!
            if (old.third == null) cacheSink.remove(key) else cacheSink[key] = old.third!!
        }
        severeL("Item_Load_Error_Parse", file.path, e.message ?: e.javaClass.simpleName)
        SertralineItemSingleFileLoadPostEvent(file, context, false, e).call()
        false
    }
}
