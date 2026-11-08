package io.github.zzzyyylllty.sertraline.config.common

import taboolib.library.configuration.ConfigurationSection
import taboolib.module.configuration.Configuration

/**
 * 运行时配置访问入口（兼容保留的 object 门面）。
 *
 * 实际数据归属不可变的 [SettingsSnapshot]：[SertralineConfigManager.bind]/[SertralineConfigManager.reload]
 * 每轮构建一个新快照并整体替换 `current` 引用。所有属性均为只读转发 getter，
 * 并发读者要么看到完整的旧快照、要么看到完整的新快照，不会观察到逐字段写入的中间态。
 *
 * database.* 字段保留启动期一次性语义（DatabaseManager 连接为 lazy 单例，重建需重启）；
 * restart-only 值在运行期 reload 后发生变化时由 [SertralineConfigManager] 输出提示。
 */
object SertralineSettings {

    /** 各配置文件引用（由 SertralineConfigManager.bind 填充）。 */
    lateinit var mainSpec: SConfigSpec
        private set
    lateinit var experimentalSpec: SConfigSpec
        private set

    /** 兼容旧调用点：主配置 Configuration 引用。 */
    val config: Configuration get() = mainSpec.config

    /** bind 前的哨兵快照：全部为注册表默认值。 */
    private val INITIAL: SettingsSnapshot = SettingsSnapshot.ofDefault()

    @Volatile
    private var current: SettingsSnapshot = INITIAL

    private val snap: SettingsSnapshot get() = current

    val configVersion: Int get() = snap.configVersion
    val debug: Boolean get() = snap.debug
    val lang: String get() = snap.lang
    val asyncLogging: Boolean get() = snap.asyncLogging

    // metrics（启动期一次性读取，不随 reload 热更；FastStats context 只在 ENABLE 时创建一次）
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
     * 定时检查间隔（tick，0 = 禁用）。由 [io.github.zzzyyylllty.sertraline.function.update.ItemUpdateScheduler]
     * 在主线程按间隔扫描在线玩家；间隔过小会在玩家多时产生主线程压力，繁忙服务器建议 6000（5 分钟）级别。
     */
    val itemUpdateAsyncInterval: Int get() = snap.itemUpdateAsyncInterval

    /**
     * 更新时是否通知玩家。同一批检查（登录/容器/定时扫描）合并为一条消息，
     * 文案见 lang 的 `Item_Update_Notify`（{0} = 本次更新的物品数）。
     */
    val itemUpdateNotify: Boolean get() = snap.itemUpdateNotify

    // file-load（预编译）
    val fileLoadItem: Regex get() = snap.fileLoadItem
    val fileLoadLoreFormat: Regex get() = snap.fileLoadLoreFormat
    val fileLoadMapping: Regex get() = snap.fileLoadMapping
    val fileLoadCraftingStation: Regex get() = snap.fileLoadCraftingStation
    val fileLoadRecipe: Regex get() = snap.fileLoadRecipe
    val fileLoadTier: Regex get() = snap.fileLoadTier
    val fileLoadType: Regex get() = snap.fileLoadType
    val fileLoadLevel: Regex get() = snap.fileLoadLevel

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

    // database（启动期一次性读取，不随 reload 热更；DatabaseManager 连接为 lazy 单例，重建需重启）
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
     * 读取指定节点的原始值（注册表里没有强类型快照的动态节点走这里，
     * 例如 modules.<name>.feature / tags.<name> / action.throttle.<trigger>）。
     * 基于当前不可变快照的动态 map，不再逐次读可变 Configuration。
     */
    fun raw(path: String): Any? = snap.raw(path)

    fun rawBoolean(path: String, def: Boolean): Boolean = snap.rawBoolean(path, def)

    fun rawString(path: String, def: String): String = snap.rawString(path, def)

    fun rawLong(path: String, def: Long): Long = snap.rawLong(path, def)

    fun rawList(path: String): List<Any?>? = snap.rawList(path)

    /** MySQL 配置段（供 DatabaseManager 作 mapper source 使用；lazy/restart-only）。 */
    val databaseSection: ConfigurationSection?
        get() = mainSpec.config.getConfigurationSection("database")

    /** 当前快照（供需要一次性锁定整组设置的调用方使用，如 reload 比对 restart-only 变化）。 */
    fun currentSnapshot(): SettingsSnapshot = current

    // ==================== 加载 ====================

    /** 构建新快照并整体发布（bind/reload 共用；不做 database 启动值特殊处理——见 DatabaseManager 的 lazy 语义）。 */
    fun load(main: SConfigSpec, experimental: SConfigSpec) {
        mainSpec = main
        experimentalSpec = experimental
        current = SettingsSnapshot.of(main, experimental)
    }
}
