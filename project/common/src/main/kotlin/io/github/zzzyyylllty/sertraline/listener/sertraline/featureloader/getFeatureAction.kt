package io.github.zzzyyylllty.sertraline.listener.sertraline.featureloader

import io.github.zzzyyylllty.sertraline.Sertraline.gjsScriptCache
import io.github.zzzyyylllty.sertraline.Sertraline.jexlScriptCache
import io.github.zzzyyylllty.sertraline.Sertraline.jsScriptCache
import io.github.zzzyyylllty.sertraline.config.common.SertralineSettings
import io.github.zzzyyylllty.sertraline.config.asListEnhanced
import io.github.zzzyyylllty.sertraline.config.asListedStringEnhanced
import io.github.zzzyyylllty.sertraline.event.FeatureLoadEvent
import io.github.zzzyyylllty.sertraline.logger.severeS
import io.github.zzzyyylllty.sertraline.util.ComplexTypeHelper
import io.github.zzzyyylllty.sertraline.util.GraalJsUtil
import io.github.zzzyyylllty.sertraline.util.VersionHelper
import io.github.zzzyyylllty.sertraline.util.JexlUtil.prodJexlCompiler
import io.github.zzzyyylllty.sertraline.util.serialize.generateHash
import io.github.zzzyyylllty.sertraline.util.toBooleanTolerance
import taboolib.common.platform.event.SubscribeEvent
import taboolib.common5.compileJS


@Suppress("UNCHECKED_CAST", "unused")
@SubscribeEvent
fun getFeatureAction(e: FeatureLoadEvent) {
    if (e.feature != "sertraline:actions") return
    val feature = e.feature
    val content = e.content as? Map<*,*> ?: return

    val actions = LinkedHashMap<String, List<LinkedHashMap<*,*>>>()
    val triggers = mutableListOf<String>()
    content.forEach {
        triggers.add(it.key.toString())
        val rawList = it.value as List<LinkedHashMap<*,*>>
        val list = mutableListOf<LinkedHashMap<*,*>>()
        rawList.forEach { it ->

            list.add(linkedMapOf(
                "condition" to (it["condition"] ?: it["require"] ?: it["requirement"] ?: it["requirements"]).asListEnhanced(),
                "async" to (it["async"])?.toBooleanTolerance(),
                "kether" to (it["kether"] ?: it["ke"]).asListEnhanced(),
                "javascript" to (it["javascript"] ?: it["js"]).asListedStringEnhanced(),
                "jexl" to (it["jexl"] ?: it["je"]).asListedStringEnhanced(),
                "fluxon" to (it["fluxon"] ?: it["fl"]).asListedStringEnhanced(),
                "graaljs" to (it["graaljs"] ?: it["gjs"]).asListedStringEnhanced(),
                "mythic" to (it["mythic"] ?: it["mm"]).asListEnhanced(),
                // "kotlinscript" to (it["kotlinscript"] ?: it["kts"] ?: it["kt"]).asListedStringEnhanced()
            ))
        }

        for (map in list) {

            // Pre-compile script
            if (SertralineSettings.preloadScriptJexl) (map["jexl"] as? String)?.let { script ->
                try {
                    val compiled = prodJexlCompiler.compileToScript(script)
                    jexlScriptCache[script.generateHash()] = compiled
                } catch (ex: Exception) {
                    severeS("An error was occurred in trying to pre-compile jexl script.")
                    severeS("Item: ${e.sItemKey}")
                    severeS("script: $script")
                    severeS("exception: $ex")
                }
            }
            if (SertralineSettings.preloadScriptJavascript) (map["javascript"] as? String)?.let { script ->
                try {
                    val compiled = script.compileJS()
                    compiled?.let { jsScriptCache[script.generateHash()] = it }
                } catch (ex: Exception) {
                    severeS("An error was occurred in trying to pre-compile js script.")
                    severeS("Item: ${e.sItemKey}")
                    severeS("script: $script")
                    severeS("exception: $ex")
                }
            }
            // legacy12 has no bundled GraalJS, so skip pre-compilation (at runtime GraalJsUtil guards and falls back to Nashorn)
            if (!VersionHelper.isLegacy() && SertralineSettings.preloadScriptGraalJs) (map["graaljs"] as? String)?.let { script ->
                try {
                    val compiled = GraalJsUtil.compile(script)
                    compiled?.let { gjsScriptCache.put(script.generateHash(), it) }
                } catch (ex: Exception) {
                    severeS("An error was occurred in trying to pre-compile Graal-js script.")
                    severeS("Item: ${e.sItemKey}")
                    severeS("script: $script")
                    severeS("exception: $ex")
                }
            }
            // Fluxon pre-compilation does not pass in the corresponding variables, which may fail compilation, so pre-compilation is disabled.
            // if (config.getBoolean("preload.script.fluxon",true)) (map["fluxon"] as? String)?.let { script ->
            //     FluxonShell.preload(script)
            // }
        }

        actions[it.key as String] = list
    }

    // context 模式写候�?itemCache（发布时合并回全局），�?context 直接写全局�?
    val cacheSink: MutableMap<String, Map<String, Any?>> =
        e.context?.itemCache ?: io.github.zzzyyylllty.sertraline.Sertraline.itemCache
    val cache = e.sItemKey?.let { cacheSink[it] }?.toMutableMap() ?: mutableMapOf()

    cache["preloadActions"] = ComplexTypeHelper(actions).getAsActions()
    cache["actions"] = triggers

    e.sItemKey?.let { cacheSink[it] = cache }

    e.result = actions
}