package io.github.zzzyyylllty.sertraline.config.common

import io.github.zzzyyylllty.sertraline.logger.severeL
import java.io.File
import io.github.zzzyyylllty.sertraline.util.serialize.parseToMap
import taboolib.common.platform.function.getDataFolder
import taboolib.module.configuration.Configuration

/**
 * TabooLib's [Configuration.reload] clears the in-memory configuration before parsing. If the file is corrupt, it clears the configuration and throws,
 * causing runtime reads to silently fall back to defaults. This extension makes the disk-to-memory update a small transaction:
 *
 * 1. Deep-copy the in-memory configuration before reloading.
 * 2. On success, use the new contents directly.
 * 3. On failure, restore the snapshot, retain the last-good state, and log a severe error.
 *
 * Rebuild the snapshot with [Configuration.loadFromString] without depending on implementation details.
 */
object ConfigReloadGuard {

    fun prepare(fileName: String): Configuration = parse(File(getDataFolder(), fileName))

    internal fun parse(file: File): Configuration {
        try {
            val text = file.bufferedReader().use { it.readText() }.removePrefix("\uFEFF")
            if (text.lineSequence().any { it.isNotBlank() && !it.trimStart().startsWith('#') }) {
                requireNotNull(parseToMap(text, "yaml")) { "Expected a configuration mapping" }
            }
            return Configuration.loadFromFile(file)
        } catch (e: Exception) {
            throw IllegalArgumentException("Could not read ${file.name}: ${e.message}", e)
        }
    }

    fun reloadSafely(config: Configuration, fileName: String) {
        tryReload(config, fileName)
    }

    internal fun tryReload(config: Configuration, fileName: String): Boolean {
        val snapshot = try {
            config.saveToString()
        } catch (_: Exception) {
            null
        }
        try {
            config.reload()
            return true
        } catch (e: Exception) {
            severeL("Config_Load_Error_Parse", fileName, e.message ?: "Unknown error")
            if (snapshot != null) {
                try {
                    config.loadFromString(snapshot)
                    severeL("Config_Reload_Recovered", fileName)
                } catch (e2: Exception) {
                    severeL("Config_Reload_Recover_Failed", fileName, e2.message ?: "Unknown error")
                }
            }
        }
        return false
    }
}
