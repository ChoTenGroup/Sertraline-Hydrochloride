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

    // Candidate builds (the LoadContext mode used by reload) must parse all files because candidates start empty.
    // Skipping unchanged files based on mtime would omit their entries from the candidate and clear them from itemMap on publish.
    // Incremental optimization is only useful for in-place live updates without a context.
    val effectiveIncremental = incremental && context == null

    SertralineItemFileLoadPreEvent(context, effectiveIncremental).call()

    try {
        if (!File(getDataFolder(), "workspace").exists()) {
            warningL("Item_Load_Regen")
            releaseResourceFolder("workspace/default")
        }

        val workspaceDir = File(getDataFolder(), "workspace")

        if (!effectiveIncremental) {
            // Full mode does not use incremental timestamps, so clear stale records as well.
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

/**
 * Incremental publish merge: bring live entries from files not re-parsed in this pass back into the candidate.
 *
 * Incremental mode parses only new or modified files; other entries remain in the live table. publishToLive's
 * clear+putAll would discard them, so restore entries using the itemSourceFile index before publishing:
 * - If an entry's source file was parsed in this pass, skip it (the new parse is authoritative, including deleted entries).
 * - If its source file was not parsed, the entry is still valid and is merged into the candidate.
 * - If no source record exists (loaded before the previous publish, registered dynamically through the API, or from an older version), keep it conservatively.
 *
 * Called only in context (candidate build) mode; the compatibility path without a context updates the live table in place and needs no merge.
 *
 * **Currently unreachable; retained for now but no longer called by reload.** `itemsLoadedIncrementally` is set only
 * in the `effectiveIncremental` branch (where `incremental && context == null`), so `context` is always null there.
 * Reload always has a context, so candidates are always parsed from an empty table in full.
 * In other words, `incremental-loading` does **not** affect reload; it applies only to in-place loading without a context.
 * Reload no longer calls this function, avoiding the misleading impression that an incremental merge runs when it does not.
 */
internal fun mergeLiveItemsForIncrementalPublish(context: LoadContext) {
    val parsedFiles = context.parsedItemFiles
    val seenFiles = context.seenItemFiles
    val liveItems = Sertraline.itemMap
    for ((id, sItem) in liveItems) {
        if (context.items.containsKey(id)) continue
        val source = Sertraline.itemSourceFile[id]
        // This source file was parsed in this pass; use the new result, including any deleted entries, and do not restore old ones.
        if (source != null && source in parsedFiles) continue
        // The source file is no longer in the workspace, so its entries were deleted with it.
        if (source != null && source !in seenFiles) continue
        context.items[id] = sItem
        // Restore the corresponding itemCache entry as well so both maps stay in sync.
        if (id !in context.itemCache) {
            Sertraline.itemCache[id]?.let { context.itemCache[id] = it }
        }
    }
    // Remove source records for files that disappeared (parsed is a subset of seen; deleted files are absent from seenFiles).
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
        // Record every file seen on disk in this pass, whether parsed or not, for deletion detection.
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

    // Record files actually parsed in this pass for mergeLiveItemsForIncrementalPublish.
    context?.parsedItemFiles?.addAll(parsedThisRound)

    infoL("Item_Load_Incremental_Stats", loadedCount, skippedCount, files.size)
}

fun loadItemFile(file: File, regex: Regex, context: LoadContext? = null): Boolean {
    if (file.isDirectory) {
        // Directory recursion is handled by safeWalkDirectory; return false here.
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
            // Record the source file for this entry so incremental publishing can merge live entries from unchanged files into the candidate.
            // Candidate mode **must not** write directly to the live index: reload may fail midway after the live index has
            // already been changed, breaking the guarantee that a failed reload leaves live state untouched. Store it in the context and publish via publishToLive.
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
