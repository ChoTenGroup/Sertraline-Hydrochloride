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

    var main = SConfigSpec(
        "config.yml",
        SertralineMainConfig.nodes,
        version = SertralineMainConfig.VERSION,
        migrations = SertralineMainConfig.migrations,
    )
        private set
    var experimental = SConfigSpec(
        "experimental.yml",
        SertralineExperimentalRegistry.nodes,
        version = SertralineExperimentalRegistry.VERSION,
        migrations = SertralineExperimentalRegistry.migrations,
    )
        private set

    @Volatile
    private var bound = false

    private var startup: SettingsSnapshot? = null

    internal class Candidate(
        val main: SConfigSpec,
        val experimental: SConfigSpec,
        val settings: SettingsSnapshot,
    )

    /** Binds all configuration files (idempotent: skips the ENABLE call if PacketEventsUtil already bound them during LOAD). */
    fun bind() {
        if (bound) return
        synchronized(this) {
            if (bound) return
            main.bind()
            experimental.bind()
            SertralineSettings.load(main, experimental)
            startup = SertralineSettings.currentSnapshot()
            bound = true
        }
    }

    internal fun prepare(): Candidate {
        val newMain = main.prepare()
        val newExperimental = experimental.prepare()
        return Candidate(newMain, newExperimental, SettingsSnapshot.of(newMain, newExperimental, SertralineSettings.currentSnapshot()))
    }

    internal fun publish(candidate: Candidate) {
        main = candidate.main
        experimental = candidate.experimental
        SertralineSettings.publish(main, experimental, candidate.settings)
        val initial = startup ?: return
        val current = candidate.settings
        if (initial.databaseValues() != current.databaseValues()) warningL("Config_Restart_Required", "database")
        if (!initial.packetRegister && current.packetRegister) warningL("Config_Restart_Required", "packet.register")
        if (initial.metricsEnabled != current.metricsEnabled || initial.metricsToken != current.metricsToken ||
            initial.metricsCustomMetrics != current.metricsCustomMetrics || initial.metricsErrorTracking != current.metricsErrorTracking
        ) warningL("Config_Restart_Required", "metrics")
    }

    private fun SettingsSnapshot.databaseValues(): List<Any> =
        listOf(databaseEnable, databaseFilename, databaseHost, databasePort, databaseUser, databasePassword, databaseName, databaseTable)

    fun reload() = publish(prepare())

    internal fun reset() {
        bound = false
        startup = null
        SertralineSettings.reset()
    }

    /** Main configuration object, retained for compatibility with existing call sites. */
    @Deprecated("Use SertralineSettings (snapshot) or SertralineConfigManager.main instead")
    val config: Configuration get() = main.config
}
