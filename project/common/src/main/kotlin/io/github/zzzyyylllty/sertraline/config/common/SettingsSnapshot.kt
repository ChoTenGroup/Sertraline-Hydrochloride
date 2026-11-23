package io.github.zzzyyylllty.sertraline.config.common

import io.github.zzzyyylllty.sertraline.logger.warningL
import taboolib.common5.Coerce
import taboolib.module.configuration.Configuration

/**
 * Immutable runtime snapshot of config.yml and experimental.yml.
 *
 * Built by [SertralineSettings.load] and published as a whole. Business code reads values through
 * the compatibility getters on [SertralineSettings] (which forward to the current snapshot), ensuring concurrent readers see
 * either the complete old snapshot or the complete new snapshot, never an intermediate state with only some fields updated.
 *
 * Dynamic nodes (paths without strongly typed registry fields, such as modules.<name>.feature, tags.<name>, and
 * action.throttle.<trigger>) are copied into a read-only [dynamic] map during construction. The [raw] accessors
 * use this map and no longer access the mutable Configuration.
 */
class SettingsSnapshot internal constructor(
    // ==================== Metadata ====================
    val configVersion: Int,

    // ==================== config.yml ====================
    val debug: Boolean,
    val lang: String,
    val asyncLogging: Boolean,

    // Metrics (read once at startup; the FastStats context is not recreated on reload).
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

    // File loading (precompiled regular expressions).
    val fileLoadItem: Regex,
    val fileLoadLoreFormat: Regex,
    val fileLoadMapping: Regex,
    val fileLoadCraftingStation: Regex,
    val fileLoadRecipe: Regex,
    val fileLoadTier: Regex,
    val fileLoadType: Regex,
    val fileLoadLevel: Regex,
    val fileLoadAttribute: Regex,

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

    // Database (read once at startup; restart-only).
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

    // ==================== Dynamic nodes ====================
    /** Read-only snapshot of dynamic nodes (path -> raw value; values are unwrapped during loading and safe to traverse). */
    val dynamic: Map<String, Any?>,
) {
    /** View of file-load regexes indexed by configuration section (item/recipe/...), used as the last-good fallback for invalid regexes. */
    val fileLoadPatterns: Map<String, Regex> = mapOf(
        "item" to fileLoadItem,
        "lore-format" to fileLoadLoreFormat,
        "mapping" to fileLoadMapping,
        "crafting-station" to fileLoadCraftingStation,
        "recipe" to fileLoadRecipe,
        "tier" to fileLoadTier,
        "type" to fileLoadType,
        "level" to fileLoadLevel,
        "attribute" to fileLoadAttribute,
    )

    /**
     * Reads the raw value of a dynamic node, matching the old `Configuration.get(path)` semantics:
     * TabooLib's get returns the unwrapped object, so this reads it directly from the snapshot map.
     */
    fun raw(path: String): Any? = dynamic[path]

    /** Returns [def] when missing; follows Coerce.toBoolean semantics ("1"/"true"/"yes" -> true). */
    fun rawBoolean(path: String, def: Boolean): Boolean =
        Coerce.toBoolean(dynamic[path] ?: def)

    fun rawString(path: String, def: String): String =
        (dynamic[path] as? String) ?: dynamic[path]?.toString() ?: def

    fun rawLong(path: String, def: Long): Long =
        Coerce.toLong(dynamic[path] ?: def)

    /** Returns a value only when it is a List (matching the old `get(path) as? List<Any?>` behavior). */
    fun rawList(path: String): List<Any?>? = dynamic[path] as? List<Any?>

    companion object {

        /**
         * Builds a snapshot from two specs that have already been bound, upgraded, and synchronized.
         *
         * Dynamic node snapshot = all flattened main configuration keys minus paths covered by strongly typed registry fields.
         * The implementation copies all keys directly. Strongly typed fields are still read from Configuration once during construction,
         * which has no concurrency issue. When a raw accessor finds a strongly typed path, it returns a copy of the latest value;
         * both values come from the same parse, so there is no inconsistency window.
         */
        fun of(main: SConfigSpec, experimental: SConfigSpec, previous: SettingsSnapshot? = null): SettingsSnapshot {
            val c = main.config
            val e = experimental.config

            fun compileFileLoadRegex(name: String): Regex {
                val pattern = c.getString("file-load.$name") ?: return DEFAULT_FILE_LOAD_REGEX
                return try {
                    pattern.toRegex()
                } catch (ex: Exception) {
                    // Fail closed: do not fall back to `.*` for an invalid regex, which would silently expand the load scope.
                    // On reload, keep the previous valid pattern; on first startup with no last-good value, use a strict no-match pattern.
                    val lastGood = previous?.fileLoadPatterns?.get(name)
                    if (lastGood != null) {
                        warningL("Config_Load_Error_Parse", "file-load.$name", "${ex.message ?: "Invalid regex"}; keeping previous pattern")
                        lastGood
                    } else {
                        warningL("Config_Load_Error_Parse", "file-load.$name", "${ex.message ?: "Invalid regex"}; using no-match until fixed")
                        Regex("(?!x)x")
                    }
                }
            }

            // Paths covered by strongly typed fields (used to filter redundant values from the dynamic map).
            val strongTypedPaths = setOf(
                "config-version", "debug", "lang", "async-logging",
                "metrics.enabled", "metrics.token", "metrics.error-tracking", "metrics.custom-metrics",
                "item-update.enabled", "item-update.auto-track", "item-update.auto-track-dir",
                "item-update.triggers.on-login", "item-update.triggers.on-hold", "item-update.triggers.on-interact",
                "item-update.triggers.async-interval", "item-update.notify",
                "file-load.item", "file-load.lore-format", "file-load.mapping", "file-load.crafting-station",
                "file-load.recipe", "file-load.tier", "file-load.type", "file-load.level", "file-load.attribute",
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
                // Filter out paths already covered by strongly typed fields to reduce memory use.
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
                fileLoadAttribute = compileFileLoadRegex("attribute"),

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
                    .ifEmpty { listOf("item") },
                useSplitReplaceListSerialize = c.getBoolean("performance.adventure.use-split-replace-list-serialize", false),

                fixXBuilderReapplyItemModel = c.getBoolean("fixes.xbuilder.reapply-item-model", true),

                fastMinimessage = e.getBoolean("fast-minimessage", false),
                fastMinimessageCacheSize = e.getInt("fast-minimessage-cache-size", 2048),

                dynamic = java.util.Collections.unmodifiableMap(dynamic),
            )
        }

        /**
         * Sentinel snapshot used before bind: all fields use registry/resource defaults and dynamic is an empty map.
         * Read only before SertralineConfigManager.bind (there should be no business read path yet).
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
            fileLoadAttribute = DEFAULT_FILE_LOAD_REGEX,

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
            incrementalLoadingMonitor = listOf("item"),
            useSplitReplaceListSerialize = false,

            fixXBuilderReapplyItemModel = true,

            fastMinimessage = false,
            fastMinimessageCacheSize = 2048,

            dynamic = emptyMap(),
        )

        /**
         * Default pattern when a file-load node is missing (existing behavior: no user configuration means matching all files).
         * Note: this is not the fallback for an invalid regex; invalid regexes fail closed (last-good or no-match).
         */
        val DEFAULT_FILE_LOAD_REGEX: Regex = Regex(".*")
    }
}
