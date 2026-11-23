package io.github.zzzyyylllty.sertraline.config.common

import taboolib.library.configuration.ConfigurationSection
import taboolib.module.configuration.Configuration

/**
 * Runtime configuration access point (the object facade is retained for compatibility).
 *
 * The immutable [SettingsSnapshot] owns the actual data. [SertralineConfigManager.bind] and [SertralineConfigManager.reload]
 * build a new snapshot each time and replace the `current` reference as a whole. All properties are read-only forwarding getters,
 * so concurrent readers see either the complete old snapshot or the complete new snapshot, never a partially updated state.
 *
 * database.* fields retain their startup-only semantics (DatabaseManager's connection is a lazy singleton and requires a restart to recreate).
 * [SertralineConfigManager] reports restart-only values that change after a runtime reload.
 */
object SertralineSettings {

    /** References to configuration files (populated by SertralineConfigManager.bind). */
    lateinit var mainSpec: SConfigSpec
        private set
    lateinit var experimentalSpec: SConfigSpec
        private set

    /** Main configuration reference, retained for compatibility with existing call sites. */
    val config: Configuration get() = mainSpec.config

    /** Sentinel snapshot before bind; all values use registry defaults. */
    private val INITIAL: SettingsSnapshot = SettingsSnapshot.ofDefault()

    @Volatile
    private var current: SettingsSnapshot = INITIAL

    private val candidate = ThreadLocal<SettingsSnapshot>()
    private val snap: SettingsSnapshot get() = candidate.get() ?: current

    internal fun <T> withCandidate(snapshot: SettingsSnapshot, action: () -> T): T {
        val previous = candidate.get()
        candidate.set(snapshot)
        return try {
            action()
        } finally {
            if (previous == null) candidate.remove() else candidate.set(previous)
        }
    }

    val configVersion: Int get() = snap.configVersion
    val debug: Boolean get() = snap.debug
    val lang: String get() = snap.lang
    val asyncLogging: Boolean get() = snap.asyncLogging

    // Metrics (read once at startup, not hot-reloaded; the FastStats context is created once during ENABLE).
    val metricsEnabled: Boolean get() = snap.metricsEnabled
    val metricsToken: String get() = snap.metricsToken
    val metricsErrorTracking: Boolean get() = snap.metricsErrorTracking
    val metricsCustomMetrics: Boolean get() = snap.metricsCustomMetrics

    // item-update
    val itemUpdateEnabled: Boolean get() = snap.itemUpdateEnabled
    val itemUpdateAutoTrack: Boolean get() = snap.itemUpdateAutoTrack
    val itemUpdateAutoTrackDir: String get() = snap.itemUpdateAutoTrackDir
    val itemUpdateOnLogin: Boolean get() = snap.itemUpdateOnLogin
    val itemUpdateOnHold: Boolean get() = snap.itemUpdateOnHold
    val itemUpdateOnInteract: Boolean get() = snap.itemUpdateOnInteract
    val itemUpdateOnSwapHand: Boolean get() = snap.itemUpdateOnSwapHand
    val itemUpdateOnPickup: Boolean get() = snap.itemUpdateOnPickup
    val itemUpdateOnContainerOpen: Boolean get() = snap.itemUpdateOnContainerOpen
    val itemUpdateOnArmorChange: Boolean get() = snap.itemUpdateOnArmorChange
    val itemUpdateOnReload: Boolean get() = snap.itemUpdateOnReload

    /**
     * Periodic check interval in ticks (0 = disabled). [io.github.zzzyyylllty.sertraline.function.update.ItemUpdateScheduler]
     * scans online players on the main thread at this interval. A short interval can burden the main thread when many players are online;
     * busy servers should use an interval around 6000 ticks (5 minutes).
     */
    val itemUpdateAsyncInterval: Int get() = snap.itemUpdateAsyncInterval

    /**
     * Whether to notify players when items are updated. Checks in the same batch (login, container, or periodic scan) are combined into one message.
     * See `Item_Update_Notify` in the language files ({0} = number of items updated in this pass).
     */
    val itemUpdateNotify: Boolean get() = snap.itemUpdateNotify

    // File loading (precompiled).
    val fileLoadItem: Regex get() = snap.fileLoadItem
    val fileLoadLoreFormat: Regex get() = snap.fileLoadLoreFormat
    val fileLoadMapping: Regex get() = snap.fileLoadMapping
    val fileLoadCraftingStation: Regex get() = snap.fileLoadCraftingStation
    val fileLoadRecipe: Regex get() = snap.fileLoadRecipe
    val fileLoadTier: Regex get() = snap.fileLoadTier
    val fileLoadType: Regex get() = snap.fileLoadType
    val fileLoadLevel: Regex get() = snap.fileLoadLevel
    val fileLoadAttribute: Regex get() = snap.fileLoadAttribute

    // preload.script
    val preloadScriptGraalJs: Boolean get() = snap.preloadScriptGraalJs
    val preloadScriptJavascript: Boolean get() = snap.preloadScriptJavascript
    val preloadScriptJexl: Boolean get() = snap.preloadScriptJexl

    // packet
    val packetRegister: Boolean get() = snap.packetRegister
    val packetLore: Boolean get() = snap.packetLore
    val packetComponent: Boolean get() = snap.packetComponent

    // placeholders
    val placeholderPlus: String get() = snap.placeholderPlus
    val placeholderMinus: String get() = snap.placeholderMinus

    // rebuild
    val rebuildKeepData: List<String> get() = snap.rebuildKeepData
    val rebuildKeepComponent: List<String> get() = snap.rebuildKeepComponent

    // attribute / action
    val attributeDebounceTime: Long get() = snap.attributeDebounceTime
    val attributeArmorChangePolling: Boolean get() = snap.attributeArmorChangePolling
    val actionThrottleTime: Long get() = snap.actionThrottleTime

    // Database (read once at startup, not hot-reloaded; DatabaseManager's connection is a lazy singleton and requires a restart to recreate).
    val databaseEnable: Boolean get() = snap.databaseEnable
    val databaseFilename: String get() = snap.databaseFilename
    val databaseHost: String get() = snap.databaseHost
    val databasePort: Int get() = snap.databasePort
    val databaseUser: String get() = snap.databaseUser
    val databasePassword: String get() = snap.databasePassword
    val databaseName: String get() = snap.databaseName
    val databaseTable: String get() = snap.databaseTable

    // messages
    val msgConditionNotMet: String get() = snap.msgConditionNotMet
    val msgCraftingAlreadyActive: String get() = snap.msgCraftingAlreadyActive
    val msgMissingIngredients: String get() = snap.msgMissingIngredients
    val msgCraftingStarted: String get() = snap.msgCraftingStarted
    val msgCraftingComplete: String get() = snap.msgCraftingComplete

    // performance
    val incrementalLoading: Boolean get() = snap.incrementalLoading
    val incrementalLoadingMonitor: List<String> get() = snap.incrementalLoadingMonitor
    val useSplitReplaceListSerialize: Boolean get() = snap.useSplitReplaceListSerialize

    // fixes
    val fixXBuilderReapplyItemModel: Boolean get() = snap.fixXBuilderReapplyItemModel

    // ==================== experimental.yml ====================
    val fastMinimessage: Boolean get() = snap.fastMinimessage
    val fastMinimessageCacheSize: Int get() = snap.fastMinimessageCacheSize

    /**
     * Reads the raw value of a node. Dynamic nodes without strongly typed snapshot fields use this path,
     * such as modules.<name>.feature, tags.<name>, and action.throttle.<trigger>.
     * Reads from the current immutable snapshot's dynamic map instead of the mutable Configuration.
     */
    fun raw(path: String): Any? = snap.raw(path)

    fun rawBoolean(path: String, def: Boolean): Boolean = snap.rawBoolean(path, def)

    fun rawString(path: String, def: String): String = snap.rawString(path, def)

    fun rawLong(path: String, def: Long): Long = snap.rawLong(path, def)

    fun rawList(path: String): List<Any?>? = snap.rawList(path)

    /** MySQL configuration section (used by DatabaseManager as the mapper source; lazy and restart-only). */
    val databaseSection: ConfigurationSection?
        get() = mainSpec.config.getConfigurationSection("database")

    /** Current snapshot (for callers that need to capture a consistent set of settings, such as comparing restart-only values during reload). */
    fun currentSnapshot(): SettingsSnapshot = snap

    // ==================== Loading ====================

    /** Builds and publishes a new snapshot as a whole (shared by bind/reload; database startup values are not specially handled, per DatabaseManager's lazy semantics). */
    fun load(main: SConfigSpec, experimental: SConfigSpec) {
        publish(main, experimental, SettingsSnapshot.of(main, experimental, current.takeUnless { it === INITIAL }))
    }

    internal fun publish(main: SConfigSpec, experimental: SConfigSpec, snapshot: SettingsSnapshot) {
        mainSpec = main
        experimentalSpec = experimental
        current = snapshot
    }

    internal fun reset() {
        current = INITIAL
    }
}
