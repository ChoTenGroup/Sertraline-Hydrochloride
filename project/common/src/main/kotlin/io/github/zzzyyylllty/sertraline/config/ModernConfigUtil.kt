package io.github.zzzyyylllty.sertraline.config

import io.github.zzzyyylllty.sertraline.Sertraline
import io.github.zzzyyylllty.sertraline.Sertraline.console
import io.github.zzzyyylllty.sertraline.Sertraline.mappings
import io.github.zzzyyylllty.sertraline.event.FeatureLoadEvent
import io.github.zzzyyylllty.sertraline.logger.warningS
import io.github.zzzyyylllty.sertraline.util.minimessage.legacyToMiniMessage
import io.github.zzzyyylllty.sertraline.util.serialize.isListOfType
import taboolib.module.lang.asLangText
import java.util.concurrent.ConcurrentHashMap

object ConfigUtil {
    fun getString(input: Any?): String? {
        return input?.toString()
    }
    fun getInt(input: Any?): Int? {
        return input?.toString()?.toIntOrNull()
    }

    fun getLong(input: Any?): Long? {
        return input?.toString()?.toLong()
    }
    fun getDeep(input: Any?, location: String): Any? {
        if (input == null || location.isEmpty()) return null

        val keys = location.split(".")
        var current: Any? = input

        for (key in keys) {
            if (current !is Map<*, *>) return null  // ✓ changed to null
            current = current[key]
        }
        return current
    }
    fun existDeep(input: Any?, location: String): Boolean {
        if (input == null || location.isEmpty()) return false

        val keys = location.split(".")
        var current: Any? = input

        for (key in keys) {
            if (current !is Map<*, *>) return (current != null)
            current = current[key]
        }
        return (current != null)
    }

    fun getFeature(sItemId: String?, input: Map<*, *>?, feature: String, context: LoadContext? = null): Any? {
        val geted = getFeatureDefault(input, feature, context)
        val event = FeatureLoadEvent(sItemId, geted, feature, null, context)
        event.call()
        return event.result ?: geted // if no special treatment was triggered
    }


    fun getFeatures(
        sItemId: String?,
        input: Map<*, *>?,
        features: List<String>,
        final: Map<String, Any?>? = null,
        context: LoadContext? = null,
    ): Map<String, Any?> {
        val baseMap = final?.toMutableMap() ?: linkedMapOf()
        features.forEach { feature ->
            val unparsed = getFeature(sItemId, input, feature, context)
            unparsed?.let { baseMap[feature] = transformValue(it) }
        }
        return baseMap
    }

    private fun transformValue(value: Any): Any = when {
        value is String -> value.legacyToMiniMessage()
        value is List<*> && isListOfType<String>(value) -> value.legacyToMiniMessage()
        else -> value
    }


}
fun getFeatureDefault(input: Map<*, *>?, feature: String, context: LoadContext? = null): Any? {

    if (input == null) {
        return null
    }

    // context 模式：优先 candidate，未命中回读 live（增量加载时未变更映射仍在 live 表）
    val mapping: List<String>? = if (context != null) {
        context.mappings[feature] ?: Sertraline.mappings[feature]
    } else {
        mappings[feature]
    }
    if (mapping == null) {
        warningS(console.asLangText("Warning_No_Mapping", feature))
        return null
    }

    for (entry in mapping) {
        val (namespace, id) = entry.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val section = input[namespace] as? Map<*, *>
        if (section?.contains(id) == true) { // if the corresponding mapping is found
            return section[id]
        }
    }
    return null
}

// Lazy AND typed as ConcurrentMap (not Caffeine's Cache). This is a file-level (facade) property, so an eager
// initializer would resolve Caffeine the first time ANY member of this file is touched; and because `by lazy`
// allocates its lambda in the facade's <clinit>, a Caffeine-typed property would make the verifier resolve the
// lambda's return type there too. Caffeine only exists after LifeCycle.INIT, so both must be avoided.
private val regexCache: java.util.concurrent.ConcurrentMap<String, Regex> by lazy {
    com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
        .maximumSize(512)
        .build<String, Regex>()
        .asMap()
}

fun checkRegexMatch(input: String, regex: String): Boolean {
    val compiledRegex = regexCache.computeIfAbsent(regex) { it.toRegex() }
    return input.matches(compiledRegex!!)
}