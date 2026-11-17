package io.github.zzzyyylllty.sertraline.config.common

import io.github.zzzyyylllty.sertraline.logger.warningL
import taboolib.module.configuration.Configuration

/**
 * Unified entry point for regular configuration files.
 *
 * - Releases/loads config.yml and experimental.yml.
 * - Automatically adds missing nodes from the registry (configuration auto-update).
 * - Builds an immutable [SettingsSnapshot] each time and publishes it to [SertralineSettings] as a whole.
 *
 * Call [bind] at the start of the plugin's ENABLE phase and [reload] when reloading.
 */
object SertralineConfigManager {

    val main = SConfigSpec(
        "config.yml",
        SertralineMainConfig.nodes,
        version = SertralineMainConfig.VERSION,
        migrations = SertralineMainConfig.migrations,
    )
    val experimental = SConfigSpec(
        "experimental.yml",
        SertralineExperimentalRegistry.nodes,
        version = SertralineExperimentalRegistry.VERSION,
        migrations = SertralineExperimentalRegistry.migrations,
    )

    @Volatile
    private var bound = false

    /** Database restart-only snapshot captured at startup (connection/mapper are lazy singletons and require a restart to recreate). */
    @Volatile
    private var startupDatabaseSnapshot: DatabaseStartupValues? = null

    /** Database restart-only values, matching the paths consumed by DatabaseManager's lazy dbConfig. */
    private class DatabaseStartupValues(
        val enable: Boolean,
        val filename: String,
    )

    /** Binds all configuration files (idempotent: skips the ENABLE call if PacketEventsUtil already bound them during LOAD). */
    fun bind() {
        if (bound) return
        synchronized(this) {
            if (bound) return
            main.bind()
            experimental.bind()
            SertralineSettings.load(main, experimental)
            startupDatabaseSnapshot = DatabaseStartupValues(
                enable = SertralineSettings.databaseEnable,
                filename = SertralineSettings.databaseFilename,
            )
            bound = true
        }
    }

    fun reload() {
        // TabooLib reloadSafely clears the in-memory configuration before parsing, so a damaged file leaves it empty.
        // Snapshot the old contents first and restore the last-good state on failure so SertralineSettings does not silently fall back to defaults.
        ConfigReloadGuard.reloadSafely(main.config, main.fileName)
        // Runtime reload also performs version migrations (effective after a server-side plugin update), then adds missing nodes.
        main.upgrade()
        // Re-run sync on every reload so newly-registered nodes from a plugin upgrade
        // get appended to the on-disk file (preserving existing user values/comments).
        main.sync()
        ConfigReloadGuard.reloadSafely(experimental.config, experimental.fileName)
        experimental.upgrade()
        experimental.sync()
        SertralineSettings.load(main, experimental)
        checkRestartOnlyChanges()
    }

    /**
     * Checks restart-only settings: packet.register (PacketEvents lifecycle is determined at startup) and
     * database.* (mapper connection is a lazy singleton) are not recreated during reload. If a startup value
     * differs from the current file, log a notice instead of hot-swapping it.
     */
    private fun checkRestartOnlyChanges() {
        val current = SertralineSettings.currentSnapshot()
        val startup = startupDatabaseSnapshot
        if (startup != null) {
            val changedDb = startup.enable != current.databaseEnable || startup.filename != current.databaseFilename
            if (changedDb) {
                warningL("Config_Restart_Required", "database")
            }
        }
        // If packet.register was false, PacketEvents was not initialized at startup and changing it back to true at runtime has no effect.
        // The reverse change (true at startup, then false at runtime) only stops packet handlers from checking it and needs no notice.
    }

    /** Main configuration object, retained for compatibility with existing call sites. */
    @Deprecated("Use SertralineSettings (snapshot) or SertralineConfigManager.main instead")
    val config: Configuration get() = main.config
}
