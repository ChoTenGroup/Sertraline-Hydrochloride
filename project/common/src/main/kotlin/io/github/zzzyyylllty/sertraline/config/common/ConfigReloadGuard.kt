package io.github.zzzyyylllty.sertraline.config.common

import io.github.zzzyyylllty.sertraline.logger.severeL
import taboolib.library.configuration.ConfigurationSection
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

    fun reloadSafely(config: Configuration, fileName: String) {
        val snapshot = try {
            config.saveToString()
        } catch (_: Exception) {
            null
        }
        try {
            config.reload()
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
    }
}
