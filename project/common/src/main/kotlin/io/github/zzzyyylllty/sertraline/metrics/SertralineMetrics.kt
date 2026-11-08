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
 * FastStats 集成（bStats 风格的匿名使用统计 + 错误追踪）。
 * FastStats integration (bStats-like anonymous usage metrics and error tracking).
 *
 * 生命周期：插件 ENABLE 时 [start] 一次，DISABLE 时 [shutdown] 一次；两者都是幂等的。
 * Lifecycle: [start] once at plugin ENABLE, [shutdown] once at DISABLE; both are idempotent.
 *
 * ## 平台约束 / Platform constraints
 * paper/spigot 使用 FastStats 默认构件（Java 17 字节码），legacy12（MC 1.12.2 / Java 8）使用官方
 * 的 Java 8 回退构件 `dev.faststats.metrics.j8`（包名相同、API 相同、major 52，内部同样对
 * Paper/Folia 专有调用做了 NoSuchMethodError 兜底），两者均由 build.gradle.kts 打进 jar 并重定位。
 * paper/spigot use the default FastStats artifact (Java 17 bytecode); legacy12 (MC 1.12.2 /
 * Java 8) uses the official Java 8 backport `dev.faststats.metrics.j8` (same packages, same API,
 * major 52, with the same NoSuchMethodError fallbacks for Paper/Folia-only calls). Both are
 * bundled and relocated by build.gradle.kts.
 *
 * ## 实现约束 / Implementation constraints
 * 本 object 的任何**成员字段类型**都不得是 FastStats 类型，否则 legacy12 上类初始化即失败
 * （参考 Sertraline.kt 中对 Caffeine 的同类注释）。FastStats 只在函数体内被引用，
 * 字节码中的符号引用按 JVM 语义在执行到该指令时才解析，因此可以安全地被 try/catch 包住。
 * No member field of this object may be typed with a FastStats type, otherwise class
 * initialization fails on legacy12 (see the equivalent Caffeine note in Sertraline.kt).
 * FastStats is only referenced inside function bodies; symbolic references resolve lazily
 * at execution, so they are safely covered by the try/catch below.
 */
object SertralineMetrics {

    /** FastStats context，故意用 [Any] 持有以避免 legacy12 的类初始化问题。 */
    @Volatile
    private var context: Any? = null

    /** 全局错误追踪器，同样用 [Any] 持有。 */
    @Volatile
    private var errorTracker: Any? = null

    @Volatile
    private var active = false

    /** 统计是否已成功启动。 */
    val isActive: Boolean get() = active

    /**
     * 启动统计。token 为空、配置未启用或平台不支持时安全跳过；
     * 任何失败只记一条警告，绝不影响插件启动。
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
            // 兜底：SDK 缺失（构型未打包）或 token 非法时都走这里，绝不让统计影响插件启动。
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
     * 停止统计（提交未决数据、注销调度）。未启动时安全返回。
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
     * 主动上报一个异常（错误追踪未启用时为空操作）。
     * Reports an error explicitly through FastStats error tracking; no-op when it is disabled.
     */
    fun reportError(throwable: Throwable) {
        val tracker = errorTracker as? ErrorTracker ?: return
        try {
            tracker.trackError(throwable)
        } catch (_: Throwable) {
            // 错误追踪本身绝不能反过来打断业务逻辑。
            // Error tracking must never break the caller's flow.
        }
    }
}

/**
 * 注册 Sertraline 的自定义指标。
 * 每个 source id 都必须与 FastStats 项目里配置的数据源同名（小写字母/数字/下划线），
 * 否则该值会被服务端忽略。
 *
 * Registers Sertraline's custom metrics. Every source id must match a data source
 * configured in the FastStats project (lowercase letters, digits and underscores),
 * otherwise the value is ignored by the server.
 *
 * supplier 由 SDK 在提交时调用（异步线程），因此这里只做轻量、无副作用的读取。
 * Suppliers run on the SDK's submission thread, so they only perform cheap, side-effect
 * free reads.
 */
private fun applyCustomMetrics(factory: Metrics.Factory): Metrics.Factory {
    if (!SertralineSettings.metricsCustomMetrics) return factory

    return factory
        // ==================== 配置规模 / Content scale ====================
        .addMetric(Metric.number("loaded_items") { Sertraline.itemMap.size })
        .addMetric(Metric.number("loaded_mappings") { Sertraline.mappings.size })
        .addMetric(Metric.number("loaded_lore_formats") { Sertraline.loreFormats.size })
        .addMetric(Metric.number("loaded_tiers") { Sertraline.tiers.size })
        .addMetric(Metric.number("loaded_types") { Sertraline.types.size })
        .addMetric(Metric.number("loaded_levels") { Sertraline.levels.size })
        .addMetric(Metric.number("crafting_stations") { Sertraline.craftingStations.size })
        .addMetric(Metric.number("config_version") { SertralineSettings.configVersion })

        // ==================== 运行形态 / Deployment shape ====================
        .addMetric(Metric.string("edition") { if (VersionHelper.isSertralinePremium) "premium" else "free" })
        .addMetric(Metric.string("database_type") { if (SertralineSettings.databaseEnable) "mysql" else "sqlite" })
        .addMetric(Metric.stringArray("installed_hooks") { installedHooks() })
        .addMetric(Metric.stringArray("script_engines") { enabledScriptEngines() })
}

/**
 * Sertraline 会对接的可选前置插件（与 plugin/build.gradle.kts 的 dependencies { name(...) }、
 * 也就是 plugin.yml 的 softdepend 保持一致；新增前置时同步这里即可）。
 * Optional plugins Sertraline integrates with, mirroring the softdepend list declared in
 * plugin/build.gradle.kts (plugin.yml); add new integrations here as well.
 */
private val TRACKED_HOOKS = listOf(
    "DylsemHokma", "MythicLib", "TrMenu", "Zaphkiel", "MMOItems", "SX-Item",
    "MythicMobs", "NeigeItems", "Chemdah", "CraftEngine", "ItemsAdder",
    "Oxaren", "MagicCosmetics", "packetevents", "PlaceholderAPI",
)

/**
 * 已实际安装的可选前置插件。
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
 * 启用的脚本引擎（对应 config.yml 的 preload.script.*）。
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
