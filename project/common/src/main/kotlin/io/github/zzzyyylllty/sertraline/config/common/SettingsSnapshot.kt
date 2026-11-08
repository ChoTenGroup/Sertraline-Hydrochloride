package io.github.zzzyyylllty.sertraline.config.common

import io.github.zzzyyylllty.sertraline.logger.warningL
import taboolib.common5.Coerce
import taboolib.module.configuration.Configuration

/**
 * config.yml / experimental.yml 的不可变运行时快照。
 *
 * 由 [SertralineSettings.load] 一次性构建并整体发布；业务代码读取一律经由
 * [SertralineSettings] 的兼容 getter（转发到当前快照），保证并发读者要么看到
 * 完整的旧快照、要么看到完整的新快照，不会观察到逐字段写入的中间态。
 *
 * 动态节点（注册表中没有强类型字段的路径，如 modules.<name>.feature / tags.<name> /
 * action.throttle.<trigger>）在构建时拷贝为只读 [dynamic] map，[raw] 系列访问器
 * 基于它工作，不再触碰可变 Configuration。
 */
class SettingsSnapshot internal constructor(
    // ==================== 元信息 ====================
    val configVersion: Int,

    // ==================== config.yml ====================
    val debug: Boolean,
    val lang: String,
    val asyncLogging: Boolean,

    // metrics（启动期一次性读取；FastStats context 不随 reload 重建）
    val metricsEnabled: Boolean,
    val metricsToken: String,
    val metricsErrorTracking: Boolean,
    val metricsCustomMetrics: Boolean,

    // item-update
    val itemUpdateEnabled: Boolean,
    val itemUpdateAutoTrack: Boolean,
    val itemUpdateAutoTrackDir: String,
    val itemUpdateOnLogin: Boolean,
    val itemUpdateOnHold: Boolean,
    val itemUpdateOnInteract: Boolean,
    val itemUpdateOnSwapHand: Boolean,
    val itemUpdateOnPickup: Boolean,
    val itemUpdateOnContainerOpen: Boolean,
    val itemUpdateOnArmorChange: Boolean,
    val itemUpdateOnReload: Boolean,
    val itemUpdateAsyncInterval: Int,
    val itemUpdateNotify: Boolean,

    // file-load（预编译正则）
    val fileLoadItem: Regex,
    val fileLoadLoreFormat: Regex,
    val fileLoadMapping: Regex,
    val fileLoadCraftingStation: Regex,
    val fileLoadRecipe: Regex,
    val fileLoadTier: Regex,
    val fileLoadType: Regex,
    val fileLoadLevel: Regex,

    // preload.script
    val preloadScriptGraalJs: Boolean,
    val preloadScriptJavascript: Boolean,
    val preloadScriptJexl: Boolean,

    // packet
    val packetRegister: Boolean,
    val packetLore: Boolean,
    val packetComponent: Boolean,

    // placeholders
    val placeholderPlus: String,
    val placeholderMinus: String,

    // rebuild
    val rebuildKeepData: List<String>,
    val rebuildKeepComponent: List<String>,

    // attribute / action
    val attributeDebounceTime: Long,
    val attributeArmorChangePolling: Boolean,
    val actionThrottleTime: Long,

    // database（启动期一次性读取，restart-only）
    val databaseEnable: Boolean,
    val databaseFilename: String,
    val databaseHost: String,
    val databasePort: Int,
    val databaseUser: String,
    val databasePassword: String,
    val databaseName: String,
    val databaseTable: String,

    // messages
    val msgConditionNotMet: String,
    val msgCraftingAlreadyActive: String,
    val msgMissingIngredients: String,
    val msgCraftingStarted: String,
    val msgCraftingComplete: String,

    // performance
    val incrementalLoading: Boolean,
    val incrementalLoadingMonitor: List<String>,
    val useSplitReplaceListSerialize: Boolean,

    // fixes
    val fixXBuilderReapplyItemModel: Boolean,

    // ==================== experimental.yml ====================
    val fastMinimessage: Boolean,
    val fastMinimessageCacheSize: Int,

    // ==================== 动态节点 ====================
    /** 动态节点的只读快照（路径 → 原始值，值本身在加载时已 unwrap，只读遍历安全）。 */
    val dynamic: Map<String, Any?>,
) {
    /** file-load 正则按配置节名（item/recipe/...）索引的视图，供非法 regex 时的 last-good 回退。 */
    val fileLoadPatterns: Map<String, Regex> = mapOf(
        "item" to fileLoadItem,
        "lore-format" to fileLoadLoreFormat,
        "mapping" to fileLoadMapping,
        "crafting-station" to fileLoadCraftingStation,
        "recipe" to fileLoadRecipe,
        "tier" to fileLoadTier,
        "type" to fileLoadType,
        "level" to fileLoadLevel,
    )

    /**
     * 读取动态节点原始值。与旧 `Configuration.get(path)` 语义一致：
     * TabooLib 的 get 返回 unwrap 后的原始对象，这里直接从快照 map 取。
     */
    fun raw(path: String): Any? = dynamic[path]

    /** 缺失返回 [def]；与 Coerce.toBoolean 语义一致（"1"/"true"/"yes" → true）。 */
    fun rawBoolean(path: String, def: Boolean): Boolean =
        Coerce.toBoolean(dynamic[path] ?: def)

    fun rawString(path: String, def: String): String =
        (dynamic[path] as? String) ?: dynamic[path]?.toString() ?: def

    fun rawLong(path: String, def: Long): Long =
        Coerce.toLong(dynamic[path] ?: def)

    /** 仅当值本身是 List 时返回（与旧 `get(path) as? List<Any?>` 一致）。 */
    fun rawList(path: String): List<Any?>? = dynamic[path] as? List<Any?>

    companion object {

        /**
         * 从两个已 bind/upgrade/sync 的 spec 构建快照。
         *
         * 动态节点快照 = 主配置全量扁平键 − 注册表强类型字段已覆盖的路径。
         * 实现上直接拷贝全量键；强类型字段读取仍走 Configuration（构建期一次性读，
         * 无并发问题），raw 访问器优先命中强类型路径时也只是一份最新值的拷贝，
         * 二者来自同一轮 parse，不存在不一致窗口。
         */
        fun of(main: SConfigSpec, experimental: SConfigSpec): SettingsSnapshot {
            val c = main.config
            val e = experimental.config

            fun compileFileLoadRegex(name: String): Regex {
                val pattern = c.getString("file-load.$name") ?: return DEFAULT_FILE_LOAD_REGEX
                return try {
                    pattern.toRegex()
                } catch (ex: Exception) {
                    // fail closed：非法 regex 不回退 `.*`（那会悄悄扩大加载范围）。
                    // reload 保留上一份有效 pattern；首次启动无 last-good 时用严格 no-match。
                    val lastGood = SertralineSettings.currentSnapshot().fileLoadPatterns[name]
                    if (lastGood != null) {
                        warningL("Config_Load_Error_Parse", "file-load.$name", "${ex.message ?: "Invalid regex"}; keeping previous pattern")
                        lastGood
                    } else {
                        warningL("Config_Load_Error_Parse", "file-load.$name", "${ex.message ?: "Invalid regex"}; using no-match until fixed")
                        Regex("(?!x)x")
                    }
                }
            }

            // 强类型字段已覆盖的路径集合（用于过滤动态map中的冗余数据）
            val strongTypedPaths = setOf(
                "config-version", "debug", "lang", "async-logging",
                "metrics.enabled", "metrics.token", "metrics.error-tracking", "metrics.custom-metrics",
                "item-update.enabled", "item-update.auto-track", "item-update.auto-track-dir",
                "item-update.triggers.on-login", "item-update.triggers.on-hold", "item-update.triggers.on-interact",
                "item-update.triggers.async-interval", "item-update.notify",
                "file-load.item", "file-load.lore-format", "file-load.mapping", "file-load.crafting-station",
                "file-load.recipe", "file-load.tier", "file-load.type", "file-load.level",
                "preload.script.graaljs", "preload.script.javascript", "preload.script.jexl",
                "packet.register", "packet.packet-lore", "packet.packet-component",
                "placeholders.plus", "placeholders.minus",
                "rebuild.keep-data", "rebuild.keep-component",
                "attribute.debounce-time", "attribute.armor-change-polling",
                "action.throttle-time", "action.throttle.onConsume",
                "database.enable", "database.filename", "database.host", "database.port",
                "database.user", "database.password", "database.database", "database.table",
                "messages.condition-not-met", "messages.crafting-already-active",
                "messages.missing-ingredients", "messages.crafting-started", "messages.crafting-complete",
                "messages.common.crafting-blocked", "messages.common.smithing-blocked", "messages.common.village-trade-blocked",
                "performance.incremental-loading.enable", "performance.incremental-loading.monitor",
                "performance.adventure.use-split-replace-list-serialize",
                "fixes.xbuilder.reapply-item-model"
            )

            val dynamic = HashMap<String, Any?>()
            for (key in c.getKeys(true)) {
                // 过滤掉强类型字段已覆盖的路径，减少内存浪费
                if (key !in strongTypedPaths) {
                    dynamic[key] = c[key]
                }
            }

            return SettingsSnapshot(
                configVersion = c.getInt("config-version", 1),
                debug = c.getBoolean("debug", false),
                lang = c.getString("lang", "en_US") ?: "en_US",
                asyncLogging = c.getBoolean("async-logging", true),

                metricsEnabled = c.getBoolean("metrics.enabled", true),
                metricsToken = c.getString("metrics.token", SertralineMainConfig.FASTSTATS_TOKEN) ?: "",
                metricsErrorTracking = c.getBoolean("metrics.error-tracking", false),
                metricsCustomMetrics = c.getBoolean("metrics.custom-metrics", true),

                itemUpdateEnabled = c.getBoolean("item-update.enabled", true),
                itemUpdateAutoTrack = c.getBoolean("item-update.auto-track", true),
                itemUpdateAutoTrackDir = c.getString("item-update.auto-track-dir", "revision") ?: "revision",
                itemUpdateOnLogin = c.getBoolean("item-update.triggers.on-login", true),
                itemUpdateOnHold = c.getBoolean("item-update.triggers.on-hold", true),
                itemUpdateOnInteract = c.getBoolean("item-update.triggers.on-interact", true),
                itemUpdateOnSwapHand = c.getBoolean("item-update.triggers.on-swap-hand", true),
                itemUpdateOnPickup = c.getBoolean("item-update.triggers.on-pickup", true),
                itemUpdateOnContainerOpen = c.getBoolean("item-update.triggers.on-container-open", true),
                itemUpdateOnArmorChange = c.getBoolean("item-update.triggers.on-armor-change", true),
                itemUpdateOnReload = c.getBoolean("item-update.triggers.on-reload", true),
                itemUpdateAsyncInterval = c.getInt("item-update.triggers.async-interval", 0),
                itemUpdateNotify = c.getBoolean("item-update.notify", true),

                fileLoadItem = compileFileLoadRegex("item"),
                fileLoadLoreFormat = compileFileLoadRegex("lore-format"),
                fileLoadMapping = compileFileLoadRegex("mapping"),
                fileLoadCraftingStation = compileFileLoadRegex("crafting-station"),
                fileLoadRecipe = compileFileLoadRegex("recipe"),
                fileLoadTier = compileFileLoadRegex("tier"),
                fileLoadType = compileFileLoadRegex("type"),
                fileLoadLevel = compileFileLoadRegex("level"),

                preloadScriptGraalJs = c.getBoolean("preload.script.graaljs", true),
                preloadScriptJavascript = c.getBoolean("preload.script.javascript", true),
                preloadScriptJexl = c.getBoolean("preload.script.jexl", true),

                packetRegister = c.getBoolean("packet.register", true),
                packetLore = c.getBoolean("packet.packet-lore", true),
                packetComponent = c.getBoolean("packet.packet-component", true),

                placeholderPlus = c.getString("placeholders.plus", "+") ?: "+",
                placeholderMinus = c.getString("placeholders.minus", "-") ?: "-",

                rebuildKeepData = c.getStringList("rebuild.keep-data").ifEmpty { listOf("sertraline_data", "sertraline_revision", "sertraline_gem") },
                rebuildKeepComponent = c.getStringList("rebuild.keep-component")
                    .ifEmpty { listOf("minecraft:damage", "minecraft:max_damage", "minecraft:repair_cost", "minecraft:enchantments") },

                attributeDebounceTime = c.getLong("attribute.debounce-time", 1000),
                attributeArmorChangePolling = c.getBoolean("attribute.armor-change-polling", false),
                actionThrottleTime = c.getLong("action.throttle-time", 500),

                databaseEnable = c.getBoolean("database.enable", false),
                databaseFilename = c.getString("database.filename", "data") ?: "data",
                databaseHost = c.getString("database.host", "localhost") ?: "localhost",
                databasePort = c.getInt("database.port", 3306),
                databaseUser = c.getString("database.user", "") ?: "",
                databasePassword = c.getString("database.password", "") ?: "",
                databaseName = c.getString("database.database", "sertraline") ?: "sertraline",
                databaseTable = c.getString("database.table", "sertraline_data") ?: "sertraline_data",

                msgConditionNotMet = c.getString("messages.condition-not-met", "<red>Condition not met: {condition_name}</red>") ?: "",
                msgCraftingAlreadyActive = c.getString("messages.crafting-already-active", "<yellow>You already have an active crafting process!</yellow>") ?: "",
                msgMissingIngredients = c.getString("messages.missing-ingredients", "<red>Missing ingredients: {display_name}</red>") ?: "",
                msgCraftingStarted = c.getString("messages.crafting-started", "<yellow>Crafting started... ({time} s)</yellow>") ?: "",
                msgCraftingComplete = c.getString("messages.crafting-complete", "<green>Crafting complete!</green>") ?: "",

                incrementalLoading = c.getBoolean("performance.incremental-loading.enable", true),
                incrementalLoadingMonitor = c.getStringList("performance.incremental-loading.monitor")
                    .ifEmpty { listOf("item", "mapping", "lore-format", "crafting-station") },
                useSplitReplaceListSerialize = c.getBoolean("performance.adventure.use-split-replace-list-serialize", false),

                fixXBuilderReapplyItemModel = c.getBoolean("fixes.xbuilder.reapply-item-model", true),

                fastMinimessage = e.getBoolean("fast-minimessage", false),
                fastMinimessageCacheSize = e.getInt("fast-minimessage-cache-size", 2048),

                dynamic = java.util.Collections.unmodifiableMap(dynamic),
            )
        }

        /**
         * bind 前的哨兵快照：全部字段为注册表/资源默认值，dynamic 为空 map。
         * 仅在 SertralineConfigManager.bind 之前被读取（此时不应有业务读路径）。
         */
        fun ofDefault(): SettingsSnapshot = SettingsSnapshot(
            configVersion = 1,

            debug = false,
            lang = "en_US",
            asyncLogging = true,

            metricsEnabled = true,
            metricsToken = SertralineMainConfig.FASTSTATS_TOKEN,
            metricsErrorTracking = false,
            metricsCustomMetrics = true,

            itemUpdateEnabled = true,
            itemUpdateAutoTrack = true,
            itemUpdateAutoTrackDir = "revision",
            itemUpdateOnLogin = true,
            itemUpdateOnHold = true,
            itemUpdateOnInteract = true,
            itemUpdateOnSwapHand = true,
            itemUpdateOnPickup = true,
            itemUpdateOnContainerOpen = true,
            itemUpdateOnArmorChange = true,
            itemUpdateOnReload = true,
            itemUpdateAsyncInterval = 0,
            itemUpdateNotify = true,

            fileLoadItem = DEFAULT_FILE_LOAD_REGEX,
            fileLoadLoreFormat = DEFAULT_FILE_LOAD_REGEX,
            fileLoadMapping = DEFAULT_FILE_LOAD_REGEX,
            fileLoadCraftingStation = DEFAULT_FILE_LOAD_REGEX,
            fileLoadRecipe = DEFAULT_FILE_LOAD_REGEX,
            fileLoadTier = DEFAULT_FILE_LOAD_REGEX,
            fileLoadType = DEFAULT_FILE_LOAD_REGEX,
            fileLoadLevel = DEFAULT_FILE_LOAD_REGEX,

            preloadScriptGraalJs = true,
            preloadScriptJavascript = true,
            preloadScriptJexl = true,

            packetRegister = true,
            packetLore = true,
            packetComponent = true,

            placeholderPlus = "+",
            placeholderMinus = "-",

            rebuildKeepData = listOf("sertraline_data", "sertraline_revision", "sertraline_gem"),
            rebuildKeepComponent = listOf("minecraft:damage", "minecraft:max_damage", "minecraft:repair_cost", "minecraft:enchantments"),

            attributeDebounceTime = 1000,
            attributeArmorChangePolling = false,
            actionThrottleTime = 500,

            databaseEnable = false,
            databaseFilename = "data",
            databaseHost = "localhost",
            databasePort = 3306,
            databaseUser = "",
            databasePassword = "",
            databaseName = "sertraline",
            databaseTable = "sertraline_data",

            msgConditionNotMet = "<red>Condition not met: {condition_name}</red>",
            msgCraftingAlreadyActive = "<yellow>You already have an active crafting process!</yellow>",
            msgMissingIngredients = "<red>Missing ingredients: {display_name}</red>",
            msgCraftingStarted = "<yellow>Crafting started... ({time} s)</yellow>",
            msgCraftingComplete = "<green>Crafting complete!</green>",

            incrementalLoading = true,
            incrementalLoadingMonitor = listOf("item", "mapping", "lore-format", "crafting-station"),
            useSplitReplaceListSerialize = false,

            fixXBuilderReapplyItemModel = true,

            fastMinimessage = false,
            fastMinimessageCacheSize = 2048,

            dynamic = emptyMap(),
        )

        /**
         * file-load 节点缺省时的默认 pattern（用户未配置 = 匹配全部文件，既有语义）。
         * 注意：这不是非法 regex 的回退值——非法 regex 走 fail-closed（last-good 或 no-match）。
         */
        val DEFAULT_FILE_LOAD_REGEX: Regex = Regex(".*")
    }
}
