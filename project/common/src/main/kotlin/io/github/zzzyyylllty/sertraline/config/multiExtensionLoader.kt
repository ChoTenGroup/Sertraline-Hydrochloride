package io.github.zzzyyylllty.sertraline.config

import io.github.zzzyyylllty.sertraline.debugMode.devLog
import io.github.zzzyyylllty.sertraline.logger.severeL
import io.github.zzzyyylllty.sertraline.util.serialize.isSupportedFormat
import io.github.zzzyyylllty.sertraline.util.serialize.parseToMap
import io.github.zzzyyylllty.sertraline.util.textIsBlank
import java.io.File
import io.github.zzzyyylllty.sertraline.util.toLowerCase

fun multiExtensionLoader(file: File): Map<String, Any?>? = multiExtensionLoader(file, null)

internal fun multiExtensionLoader(file: File, context: LoadContext?, content: String? = null): Map<String, Any?>? {
    return try {
        val extension = file.extension.lowercase()
        val format = when (extension) {
            "yml" -> "yaml"
            "tml" -> "toml"
            "conf" -> "yaml"
            else -> extension
        }

        if (!isSupportedFormat(format)) {
            context?.invalidConfiguration = true
            severeL("Config_Load_Error_Extension", file.extension)
            return null
        }

        val text = (content ?: file.readText()).removePrefix("\uFEFF")
        // textIsBlank: Java 8-safe implementation (String.isBlank is a Java 11 member method, see StringUtil.kt)
        if (text.textIsBlank() && format != "json") return emptyMap()
        // Skip files with only comments — not real YAML content
        if (format != "json" && text.lineSequence().none { line ->
                val trimmed = line.trimStart { it == ' ' || it == '\t' }
                trimmed.isNotEmpty() && !trimmed.startsWith("#")
            }) {
            devLog("Skipping ${file.name}: no YAML content (comments only)")
            return emptyMap()
        }

        requireNotNull(parseToMap(text, format)) { "Expected a configuration mapping" }
    } catch (e: Exception) {
        context?.invalidConfiguration = true
        severeL("Config_Load_Error_Parse", file.path, e.message ?: "Unknown error")
        null
    }
}