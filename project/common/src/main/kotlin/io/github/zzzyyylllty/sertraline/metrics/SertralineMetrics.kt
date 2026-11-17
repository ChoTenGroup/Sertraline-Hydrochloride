package io.github.zzzyyylllty.sertraline.metrics

import dev.faststats.ErrorTracker
import dev.faststats.Metrics
import dev.faststats.bukkit.BukkitContext
import dev.faststats.data.Metric
import io.github.zzzyyylllty.sertraline.Sertraline
import io.github.zzzyyylllty.sertraline.config.common.SertralineSettings
import io.github.zzzyyylllty.sertraline.debugMode.devLog
import io.github.zzzyyylllty.sertraline.logger.infoL
import io.github.zzzyyylllty.sertraline.logger.warningL
import io.github.zzzyyylllty.sertraline.util.VersionHelper
import org.bukkit.Bukkit
import taboolib.platform.BukkitPlugin

/**
 * FastStats integration (bStats-style anonymous usage metrics and error tracking).
 * FastStats integration (bStats-like anonymous usage metrics and error tracking).
 *
 * Lifecycle: call [start] once during plugin ENABLE and [shutdown] once during DISABLE; both are idempotent.
 * Lifecycle: [start] once at plugin ENABLE, [shutdown] once at DISABLE; both are idempotent.
 *
 * ## Platform constraints
 * Paper/Spigot use the default FastStats artifact (Java 17 bytecode); legacy12 (MC 1.12.2 / Java 8) uses the official
 * Java 8 fallback artifact `dev.faststats.metrics.j8` (same package and API, major version 52, with the same NoSuchMethodError guards
 * for Paper/Folia-only calls). Both are bundled and relocated by build.gradle.kts.
 * paper/spigot use the default FastStats artifact (Java 17 bytecode); legacy12 (MC 1.12.2 /
 * Java 8) uses the official Java 8 backport `dev.faststats.metrics.j8` (same packages, same API,
 * major 52, with the same NoSuchMethodError fallbacks for Paper/Folia-only calls). Both are
 * bundled and relocated by build.gradle.kts.
 *
 * ## Implementation constraints
 * No **member field type** in this object may be a FastStats type, or class initialization will fail on legacy12
 * (see the similar Caffeine note in Sertraline.kt). FastStats is referenced only inside function bodies.
 * Under JVM semantics, symbolic references in bytecode are resolved only when execution reaches the instruction, so they can safely be wrapped in try/catch.
 * No member field of this object may be typed with a FastStats type, otherwise class
 * initialization fails on legacy12 (see the equivalent Caffeine note in Sertraline.kt).
 * FastStats is only referenced inside function bodies; symbolic references resolve lazily
 * at execution, so they are safely covered by the try/catch below.
 */
object SertralineMetrics {

    /** FastStats context, intentionally stored as [Any] to avoid class initialization issues on legacy12. */
    @Volatile
    private var context: Any? = null

    /** Global error tracker, also stored as [Any]. */
    @Volatile
    private var errorTracker: Any? = null

    @Volatile
    private var active = false

    /** Whether metrics started successfully. */
    val isActive: Boolean get() = active

    /**
     * Starts metrics. Safely skips when the token is empty, metrics are disabled, or the platform is unsupported.
     * Any failure logs one warning and never affects plugin startup.
     * Starts metrics submission; silently no-ops without a token, when disabled, or on
     * unsupported platforms. Any failure only logs a warning and never breaks startup.
     */
    fun start() {
        if (active) return
        if (!SertralineSettings.metricsEnabled) {
            devLog("FastStats metrics are disabled in config.yml (metrics.enabled=false).")
            return
        }

        val token = SertralineSettings.metricsToken.trim()
        if (token.isEmpty()) {
            warningL("Metrics_No_Token")
            return
        }

        try {
            val factory = BukkitContext.Factory(BukkitPlugin.getInstance(), token)

            if (SertralineSettings.metricsErrorTracking) {
                val tracker = ErrorTracker.contextAware()
                errorTracker = tracker
                factory.errorTrackerService(tracker)
            }

            factory.metrics { metrics -> applyCustomMetrics(metrics).create() }

            val created = factory.create()
            created.ready()

            context = created
            active = true
            infoL("Metrics_Enabled")
        } catch (t: Throwable) {
            // Fallback for a missing SDK (not bundled for this platform) or an invalid token; metrics must never affect plugin startup.
            // Safety net: a missing SDK or an invalid token must never break plugin startup.
            if (t is NoClassDefFoundError || t is ClassNotFoundException) {
                devLog("FastStats SDK is not bundled on this platform; metrics and error tracking stay disabled.")
            } else {
                warningL("Metrics_Start_Failed", t.message ?: t.javaClass.simpleName)
            }
            context = null
            errorTracker = null
            active = false
        }
    }

    /**
     * Stops metrics (submits pending data and unregisters scheduled tasks). Safely returns if not started.
     * Stops submission and cancels the SDK's scheduled tasks; safe to call when not started.
     */
    fun shutdown() {
        val ctx = context ?: return
        context = null
        active = false
        try {
            (ctx as? dev.faststats.bukkit.BukkitContext)?.shutdown()
        } catch (t: Throwable) {
            devLog("Failed to shut down FastStats cleanly: ${t.message}")
        }
    }

    /**
     * Explicitly reports an exception (no-op when error tracking is disabled).
     * Reports an error explicitly through FastStats error tracking; no-op when it is disabled.
     */
    fun reportError(throwable: Throwable) {
        val tracker = errorTracker as? ErrorTracker ?: return
        try {
            tracker.trackError(throwable)
        } catch (_: Throwable) {
            // Error tracking itself must never interrupt business logic.
            // Error tracking must never break the caller's flow.
        }
    }
}

/**
 * Registers Sertraline custom metrics.
 * Each source ID must match the name of a data source configured in the FastStats project (lowercase letters, digits, and underscores),
 * or the server will ignore the value.
 *
 * Registers Sertraline's custom metrics. Every source id must match a data source
 * configured in the FastStats project (lowercase letters, digits and underscores),
 * otherwise the value is ignored by the server.
 *
 * The SDK calls the supplier during submission on an asynchronous thread, so it should perform only lightweight, side-effect-free reads.
 * Suppliers run on the SDK's submission thread, so they only perform cheap, side-effect
 * free reads.
 */
private fun applyCustomMetrics(factory: Metrics.Factory): Metrics.Factory {
    if (!SertralineSettings.metricsCustomMetrics) return factory

    return factory
        // ==================== Content scale ====================
        .addMetric(Metric.number("loaded_items") { Sertraline.itemMap.size })
        .addMetric(Metric.number("loaded_mappings") { Sertraline.mappings.size })
        .addMetric(Metric.number("loaded_lore_formats") { Sertraline.loreFormats.size })
        .addMetric(Metric.number("loaded_tiers") { Sertraline.tiers.size })
        .addMetric(Metric.number("loaded_types") { Sertraline.types.size })
        .addMetric(Metric.number("loaded_levels") { Sertraline.levels.size })
        .addMetric(Metric.number("crafting_stations") { Sertraline.craftingStations.size })
        .addMetric(Metric.number("config_version") { SertralineSettings.configVersion })

        // ==================== Deployment shape ====================
        .addMetric(Metric.string("edition") { if (VersionHelper.isSertralinePremium) "premium" else "free" })
        .addMetric(Metric.string("database_type") { if (SertralineSettings.databaseEnable) "mysql" else "sqlite" })
        .addMetric(Metric.stringArray("installed_hooks") { installedHooks() })
        .addMetric(Metric.stringArray("script_engines") { enabledScriptEngines() })
}

/**
 * Optional dependency plugins integrated with Sertraline. Keep this list in sync with dependencies { name(...) }
 * in plugin/build.gradle.kts and plugin.yml's softdepend; update it when adding a dependency.
 * Optional plugins Sertraline integrates with, mirroring the softdepend list declared in
 * plugin/build.gradle.kts (plugin.yml); add new integrations here as well.
 */
private val TRACKED_HOOKS = listOf(
    "DylsemHokma", "MythicLib", "TrMenu", "Zaphkiel", "MMOItems", "SX-Item",
    "MythicMobs", "NeigeItems", "Chemdah", "CraftEngine", "ItemsAdder",
    "Oxaren", "MagicCosmetics", "packetevents", "PlaceholderAPI",
)

/**
 * Optional dependency plugins that are actually installed.
 * Optional plugins that are actually installed on this server.
 */
private fun installedHooks(): Array<String> {
    return try {
        val pluginManager = Bukkit.getPluginManager()
        TRACKED_HOOKS.filter { pluginManager.getPlugin(it) != null }.toTypedArray()
    } catch (t: Throwable) {
        devLog("Failed to collect installed hooks: ${t.message}")
        emptyArray()
    }
}

/**
 * Enabled script engines (corresponding to preload.script.* in config.yml).
 * Enabled script engines, mirroring the preload.script.* switches in config.yml.
 */
private fun enabledScriptEngines(): Array<String> {
    return try {
        buildList {
            if (SertralineSettings.preloadScriptGraalJs) add("graaljs")
            if (SertralineSettings.preloadScriptJavascript) add("javascript")
            if (SertralineSettings.preloadScriptJexl) add("jexl")
        }.toTypedArray()
    } catch (t: Throwable) {
        emptyArray()
    }
}
