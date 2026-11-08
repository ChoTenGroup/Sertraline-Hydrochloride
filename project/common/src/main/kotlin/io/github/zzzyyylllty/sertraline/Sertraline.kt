package io.github.zzzyyylllty.sertraline

import io.github.zzzyyylllty.sertraline.api.SertralineAPI
import io.github.zzzyyylllty.sertraline.api.SertralineAPIImpl
import io.github.zzzyyylllty.sertraline.config.ConfigUtil
import io.github.zzzyyylllty.sertraline.config.CustomEventsConfig
import io.github.zzzyyylllty.sertraline.config.LoadContext
import io.github.zzzyyylllty.sertraline.config.TemplateManager
import io.github.zzzyyylllty.sertraline.config.common.SertralineConfigManager
import io.github.zzzyyylllty.sertraline.config.common.SertralineSettings
import io.github.zzzyyylllty.sertraline.config.loadCraftingStationFiles
import io.github.zzzyyylllty.sertraline.gui.CraftingStationManager
import io.github.zzzyyylllty.sertraline.config.loadItemFiles
import io.github.zzzyyylllty.sertraline.config.loadLoreFormatFiles
import io.github.zzzyyylllty.sertraline.config.loadMappingFiles
import io.github.zzzyyylllty.sertraline.config.loadTierFiles
import io.github.zzzyyylllty.sertraline.config.loadTypeFiles
import io.github.zzzyyylllty.sertraline.config.loadLevelFiles
import io.github.zzzyyylllty.sertraline.manager.ManagerConfig
import io.github.zzzyyylllty.sertraline.metrics.SertralineMetrics
import io.github.zzzyyylllty.sertraline.function.update.initRevisionAutoTracker
import io.github.zzzyyylllty.sertraline.function.update.ItemUpdateScheduler
import io.github.zzzyyylllty.sertraline.data.CraftingStation
import io.github.zzzyyylllty.sertraline.data.AbstractLoreFormat
import io.github.zzzyyylllty.sertraline.data.AbstractSItem
import io.github.zzzyyylllty.sertraline.data.Tier
import io.github.zzzyyylllty.sertraline.data.Type
import io.github.zzzyyylllty.sertraline.data.Level
import io.github.zzzyyylllty.sertraline.compat.CompatLog
import io.github.zzzyyylllty.sertraline.compat.PlatformCompat
import io.github.zzzyyylllty.sertraline.listener.PaperEventBridge
import io.github.zzzyyylllty.sertraline.listener.attribute.resetDebounceRefreshStat
import io.github.zzzyyylllty.sertraline.listener.custom.CustomEventListener
import io.github.zzzyyylllty.sertraline.listener.global.GlobalListenerManager
import io.github.zzzyyylllty.sertraline.listener.update.handleArmorSlotChanged
import io.github.zzzyyylllty.sertraline.debugMode.devLog
import io.github.zzzyyylllty.sertraline.debugMode.devLogSync
import io.github.zzzyyylllty.sertraline.attribute.AttributeManager
import io.github.zzzyyylllty.sertraline.attribute.AttributeProvider
import io.github.zzzyyylllty.sertraline.attribute.ChotenAttributeProvider
import io.github.zzzyyylllty.sertraline.event.SertralineAllFileLoadPostEvent
import io.github.zzzyyylllty.sertraline.event.SertralineAllFileLoadPreEvent
import io.github.zzzyyylllty.sertraline.event.SertralineReloadEvent
import io.github.zzzyyylllty.sertraline.listener.sertraline.builder.ItemProcessorManager
import io.github.zzzyyylllty.sertraline.listener.sertraline.builder.registerNativeAdapter
import io.github.zzzyyylllty.sertraline.item.process.tag.TagProcessorManager
import io.github.zzzyyylllty.sertraline.item.process.tag.registerNativeTagAdapter
import io.github.zzzyyylllty.sertraline.logger.ReloadCollector
import io.github.zzzyyylllty.sertraline.logger.ReloadContext
import io.github.zzzyyylllty.sertraline.logger.ReloadCoordinator
import io.github.zzzyyylllty.sertraline.logger.infoL
import io.github.zzzyyylllty.sertraline.logger.infoLSync
import io.github.zzzyyylllty.sertraline.logger.infoSSync
import io.github.zzzyyylllty.sertraline.logger.warningSSync
import io.github.zzzyyylllty.sertraline.logger.severeL
import io.github.zzzyyylllty.sertraline.logger.severeS
import io.github.zzzyyylllty.sertraline.logger.severeSSync
import io.github.zzzyyylllty.sertraline.logger.sendStringAsComponent
import io.github.zzzyyylllty.sertraline.logger.warningL
import io.github.zzzyyylllty.sertraline.database.DatabaseManager
import io.github.zzzyyylllty.sertraline.util.SertralineLocalDependencyHelper
import io.github.zzzyyylllty.sertraline.util.dependencies
import io.github.zzzyyylllty.sertraline.util.ItemTagManager
import io.github.zzzyyylllty.sertraline.util.ScriptHelper
import io.github.zzzyyylllty.sertraline.util.VersionHelper
import io.github.zzzyyylllty.sertraline.util.GraalJsUtil
import io.github.zzzyyylllty.sertraline.util.minimessage.FastMiniMessage
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.graalvm.polyglot.Source
import java.util.concurrent.Callable
import com.github.benmanes.caffeine.cache.Caffeine
import java.util.concurrent.TimeUnit
import org.tabooproject.fluxon.runtime.FluxonRuntime
import taboolib.common.LifeCycle
import taboolib.common.PrimitiveSettings
import taboolib.common.env.RuntimeDependencies
import taboolib.common.env.RuntimeDependency
import taboolib.common.env.RuntimeEnv
import taboolib.common.env.RuntimeEnvDependency
import taboolib.common.platform.Awake
import taboolib.common.platform.Plugin
import taboolib.common.platform.event.SubscribeEvent
import taboolib.platform.BukkitPlugin
import taboolib.common.platform.function.console
import taboolib.common.platform.function.submit
import taboolib.expansion.JexlCompiledScript
import taboolib.module.configuration.Configuration
import taboolib.module.kether.KetherShell
import taboolib.module.lang.Language
import taboolib.module.lang.asLangText
import taboolib.module.lang.event.PlayerSelectLocaleEvent
import taboolib.module.lang.event.SystemSelectLocaleEvent
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import javax.script.CompiledScript

//@RuntimeDependencies(
//    RuntimeDependency(
//        value = "!com.google.code.gson:gson:2.10.1",
//        relocate = ["!com.google.gson", "!io.github.zzzyyylllty.sertraline.dep.gson"]
//    ),
//    RuntimeDependency(
//        value = "!org.graalvm.polyglot:js:25.0.1",
//        // repository = "https://repo1.maven.org/maven2",
//        relocate = ["!graalvm.polyglot.js", "!io.github.zzzyyylllty.sertraline.dep.js"]
//    ),
//    RuntimeDependency(
//        value = "!org.graalvm.polyglot:polyglot:25.0.1",
//        // repository = "https://repo1.maven.org/maven2",
//        relocate = ["!org.graalvm.polyglot", "!io.github.zzzyyylllty.sertraline.dep.polyglot"]
//    ),
//    RuntimeDependency(
//        value = "!org.kotlincrypto.hash:sha2:0.7.0",
//        relocate = ["!org.kotlincrypto.hash", "!io.github.zzzyyylllty.sertraline.dep.hash"]
//    ),
//    RuntimeDependency(
//        value = "!com.github.ben-manes.caffeine:caffeine:3.2.3",
//        relocate = ["!com.github.benmanes.caffeine", "!io.github.zzzyyylllty.sertraline.dep.caffeine"]
//    ),
//    RuntimeDependency(
//        value = "!org.tabooproject.fluxon:core:1.2.18",
//        repository = "https://repo.tabooproject.org/repository/releases",
//        relocate = ["!org.tabooproject.fluxon", "!io.github.zzzyyylllty.sertraline.dep.fluxon"],
//        transitive = false  // do not download transitive dependencies
//    ),
//    RuntimeDependency(
//        value = "!org.tabooproject.fluxon:core-console:1.2.18",
//        repository = "https://repo.tabooproject.org/repository/releases",
//        relocate = ["!org.tabooproject.fluxon", "!io.github.zzzyyylllty.sertraline.dep.fluxon"],
//        transitive = false  // do not download transitive dependencies
//    ),
//    RuntimeDependency(
//        value = "!org.tabooproject.fluxon:core-jsr223:1.2.18",
//        repository = "https://repo.tabooproject.org/repository/releases",
//        relocate = ["!org.tabooproject.fluxon", "!io.github.zzzyyylllty.sertraline.dep.fluxon"],
//        transitive = false  // do not download transitive dependencies
//    ),
//    RuntimeDependency(
//        value = "!com.mojang:datafixerupper:8.0.16",
//        // repository = "https://libraries.minecraft.net",
//        relocate = ["!com.mojang.datafixerupper", "!io.github.zzzyyylllty.sertraline.dep.datafixerupper"]
//    ),
//    RuntimeDependency(
//        value = "!io.github.projectunified:uni-item-all:2.3.1",
//        relocate = ["!io.github.projectunified.uniitem", "!io.github.zzzyyylllty.sertraline.dep.uniitem"],
//        transitive = false  // do not download transitive dependencies
//    ),
//    RuntimeDependency(
//        value = "!net.kyori:adventure-text-serializer-legacy:4.19.0",
//        relocate = ["!net.kyori", "!io.github.zzzyyylllty.sertraline.dep.kyori"],
//        transitive = false  // do not download transitive dependencies
//    ),
//    RuntimeDependency(
//        value = "!net.kyori:adventure-api:4.19.0",
//        relocate = ["!net.kyori", "!io.github.zzzyyylllty.sertraline.dep.kyori"],
//        transitive = false  // do not download transitive dependencies
//    ),
//    RuntimeDependency(
//        value = "!net.kyori:adventure-text-minimessage:4.19.0",
//        relocate = ["!net.kyori", "!io.github.zzzyyylllty.sertraline.dep.kyori"],
//        transitive = false  // do not download transitive dependencies
//    ),
//    RuntimeDependency(
//        value = "!net.kyori:adventure-nbt:4.19.0",
//        relocate = ["!net.kyori", "!io.github.zzzyyylllty.sertraline.dep.kyori"],
//        transitive = false  // do not download transitive dependencies
//    ),
//    RuntimeDependency(
//        value = "!com.fasterxml.jackson.module:jackson-module-kotlin:2.16.1",
//        relocate = ["!com.fasterxml.jackson", "!io.github.zzzyyylllty.sertraline.dep.jackson"]
//    ),
//    RuntimeDependency(
//        value = "!com.fasterxml.jackson.core:jackson-databind:2.16.1",
//        relocate = ["!com.fasterxml.jackson", "!io.github.zzzyyylllty.sertraline.dep.jackson"]
//    ),
//    RuntimeDependency(
//        value = "!com.fasterxml.jackson.core:jackson-annotations:2.16.1",
//        relocate = ["!com.fasterxml.jackson", "!io.github.zzzyyylllty.sertraline.dep.jackson"]
//    ),
//    RuntimeDependency(
//        value = "!com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.16.1",
//        relocate = ["!com.fasterxml.jackson", "!io.github.zzzyyylllty.sertraline.dep.jackson"]
//    ),
//    RuntimeDependency(
//        value = "!com.fasterxml.jackson.dataformat:jackson-dataformat-toml:2.16.1",
//        relocate = ["!com.fasterxml.jackson", "!io.github.zzzyyylllty.sertraline.dep.jackson"]
//    ),
//    RuntimeDependency(
//        value = "!top.maplex.arim:Arim:1.3.2",
//        repository = "https://repo.tabooproject.org/repository/releases",
//        relocate = ["!top.maplex.arim", "!io.github.zzzyyylllty.sertraline.dep.arim"]
//    ),
//    RuntimeDependency(
//        value = "!org.kotlincrypto.hash:sha2:0.7.0",
//        relocate = ["!org.kotlincrypto.hash", "!io.github.zzzyyylllty.sertraline.dep.hash"]
//    ),
//)
class RuntimeEnv


/*
 *  $$$$$$\                        $$\                         $$\ $$\
 * $$  __$$\                       $$ |                        $$ |\__|
 * $$ /  \__| $$$$$$\   $$$$$$\  $$$$$$\    $$$$$$\   $$$$$$\  $$ |$$\ $$$$$$$\   $$$$$$\
 * \$$$$$$\  $$  __$$\ $$  __$$\ \_$$  _|  $$  __$$\  \____$$\ $$ |$$ |$$  __$$\ $$  __$$\
 *  \____$$\ $$$$$$$$ |$$ |  \__|  $$ |    $$ |  \__| $$$$$$$ |$$ |$$ |$$ |  $$ |$$$$$$$$ |
 * $$\   $$ |$$   ____|$$ |        $$ |$$\ $$ |      $$  __$$ |$$ |$$ |$$ |  $$ |$$   ____|
 * \$$$$$$  |\$$$$$$$\ $$ |        \$$$$  |$$ |      \$$$$$$$ |$$ |$$ |$$ |  $$ |\$$$$$$$\
 *  \______/  \_______|\__|         \____/ \__|       \_______|\__|\__|\__|  \__| \_______|
 */
object Sertraline : Plugin() {


    @Deprecated("Use SertralineSettings instead")
    val config: Configuration
        get() = SertralineConfigManager.main.config

    @Deprecated("Use SertralineSettings instead")
    val experimentalConfig: Configuration
        get() = SertralineConfigManager.experimental.config

    val _api: SertralineAPI? by lazy { SertralineAPIImpl() }
    val plugin by lazy { this }
    val dataFolder by lazy { nativeDataFolder() }
    val console by lazy { console() }
    val consoleSender by lazy { console.castSafely<CommandSender>()!! }
    var fluxonInst: FluxonRuntime? = null

    @Volatile var itemMap = ConcurrentHashMap<String, AbstractSItem>()
    @Volatile var mappings = ConcurrentHashMap<String, List<String>?>()
    @Volatile var loreFormats = ConcurrentHashMap<String, AbstractLoreFormat>()
    @Volatile var craftingStations = ConcurrentHashMap<String, CraftingStation>()
    @Volatile var tiers = ConcurrentHashMap<String, Tier>()
    @Volatile var types = ConcurrentHashMap<String, Type>()
    @Volatile var levels = ConcurrentHashMap<String, Level>()
    val dateTimeFormatter: DateTimeFormatter by lazy { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss") }
    val itemManager by lazy { ItemProcessorManager() }
    val manager = io.github.zzzyyylllty.sertraline.manager.ItemManager()
    val tagManager by lazy { TagProcessorManager() }
    @Volatile var devMode = false
    @Volatile var allowAsyncLog = true
    var isEnabled = false
    val fileLastModified: ConcurrentHashMap<String, Long> = ConcurrentHashMap()
    /** item id → 来源文件绝对路径（增量加载合并用；仅 item 增量模式写入） */
    val itemSourceFile: ConcurrentHashMap<String, String> = ConcurrentHashMap()

    // Bounded script caches — Caffeine provides max-size + TTL eviction to prevent unbounded memory growth.
    //
    // !! Keep every Caffeine cache below LAZY !!
    // Caffeine is a runtime-injected dependency (see solveDependencies() + META-INF/dependencies/caffeine.json):
    // it is downloaded and relocated to io.github.zzzyyylllty.sertraline.dep.caffeine at LifeCycle.INIT. This
    // object's static initializer runs at LifeCycle.CONST, which is STRICTLY EARLIER (CONST -> INIT -> LOAD).
    // Creating these caches eagerly therefore throws NoClassDefFoundError while the plugin class is still
    // loading, before the dependency exists. Lazy delegation defers Caffeine resolution to first use, which is
    // always after INIT.
    val ketherScriptCache: ConcurrentMap<String, KetherShell.Cache> by lazy {
        Caffeine.newBuilder().maximumSize(1000).expireAfterAccess(1, TimeUnit.HOURS).build<String, KetherShell.Cache>().asMap()
    }
    val jsScriptCache: ConcurrentMap<String, CompiledScript> by lazy {
        Caffeine.newBuilder().maximumSize(500).expireAfterAccess(1, TimeUnit.HOURS).build<String, CompiledScript>().asMap()
    }
    // The declared type must NOT be a Caffeine type either. `by lazy` allocates its lambda during <clinit>,
    // and loading that lambda class makes the verifier resolve the lambda's return type — so a property typed
    // as Caffeine's Cache would still blow up at CONST even with lazy delegation. Keeping the type at
    // ConcurrentMap (via asMap(), like its siblings) keeps Caffeine out of every <clinit> signature.
    val gjsScriptCache: ConcurrentMap<String, Source> by lazy {
        Caffeine.newBuilder().maximumSize(500).expireAfterAccess(1, TimeUnit.HOURS).build<String, Source>().asMap()
    }
    val jexlScriptCache: ConcurrentMap<String, JexlCompiledScript> by lazy {
        Caffeine.newBuilder().maximumSize(500).expireAfterAccess(1, TimeUnit.HOURS).build<String, JexlCompiledScript>().asMap()
    }

    /**
     * Builds a fresh item cache.
     *
     * Deliberately a plain [ConcurrentHashMap] and NOT a Caffeine `asMap()`: Caffeine's
     * `asMap()` returns its own `ConcurrentMap` implementation (a package-private
     * `SSMSA`/`UnboundedLocalCache` type), which is *not* a `ConcurrentHashMap` subclass
     * and therefore throws `ClassCastException` at runtime when cast to one
     * (observed as "Task generated an exception" right after item loading).
     * The map is keyed by item id, so its size is naturally bounded by the item count.
     */
    fun newItemCacheMap(): ConcurrentHashMap<String, Map<String, Any?>> = ConcurrentHashMap()

    private val defaultItemCache: ConcurrentHashMap<String, Map<String, Any?>> by lazy { newItemCacheMap() }
    @Volatile private var itemCacheOverride: ConcurrentHashMap<String, Map<String, Any?>>? = null

    /** Reassignable: publishToLive() swaps in a new map atomically instead of clear+putAll. */
    // Declared as ConcurrentHashMap on purpose: the getter's return type is part of the method
    // descriptor, so widening it to ConcurrentMap is a binary break — third-party jars compiled
    // against the old getter would die with NoSuchMethodError.
    var itemCache: ConcurrentHashMap<String, Map<String, Any?>>
        get() = itemCacheOverride ?: defaultItemCache
        set(value) { itemCacheOverride = value }
    val itemExpectedRevision: ConcurrentHashMap<String, Int> = ConcurrentHashMap()

    fun api() : SertralineAPI {
        return _api ?: throw IllegalStateException("Sertraline API not present,or failed to load")
    }

    override fun onLoad() {
        // Bind config files before DatabaseManager (which reads config via lazy dbConfig) and
        // before any other potential consumer; PacketEventsUtil also binds early at LOAD, but
        // binding here makes the ordering explicit rather than relying on that side effect.
        SertralineConfigManager.bind()
        fluxonInst = FluxonRuntime.getInstance()
        DatabaseManager.init()
    }

    override fun onEnable() {
        isEnabled = true
        infoL("Enable")
        Language.enableSimpleComponent = true
//        try {
//            reloadCustomConfig()
//        } catch (e: Exception) {
//            severeL("Failed to load configurations during startup: ${e.message}")
//            e.printStackTrace()
//        }
        // FastStats metrics / error tracking (opt-in via config.yml metrics.*; no-op otherwise)
        SertralineMetrics.start()
    }

    override fun onDisable() {
        // Stop metrics submission first so shutdown noise is not reported
        SertralineMetrics.shutdown()
        // Stop the periodic item-update scanner
        try {
            ItemUpdateScheduler.cancel()
        } catch (_: Throwable) {
        }
        // Unregister custom event listeners
        try {
            CustomEventListener.unregisterAll()
        } catch (_: Throwable) {
        }
        // Unregister global listeners
        try {
            GlobalListenerManager.unregisterAll()
        } catch (_: Throwable) {
        }
        // Cancel all crafting tasks (persist data; restorable when the player next joins)
        CraftingStationManager.shutdownAll()
        // Remove the recipes injected into the NMS RecipeManager, otherwise they survive a
        // disable → enable cycle and keep being craftable from the previous config.
        //
        // Reflective on purpose: `util/SimpleRecipeHelper.kt` (which declares SertralineRecipeManager)
        // and `recipe/NMSRecipeFactory.kt` are excluded from the legacy12 source set, so a direct
        // reference does not compile for the 1.12.2 build — where custom recipes do not exist anyway.
        try {
            val managerClass = Class.forName("io.github.zzzyyylllty.sertraline.util.SertralineRecipeManager")
            val instance = managerClass.getField("INSTANCE").get(null)
            managerClass.getMethod("unregisterAll").invoke(instance)
        } catch (e: ClassNotFoundException) {
            // legacy12 build: no recipe support, nothing to clean up
        } catch (e: Throwable) {
            warningSSync("[onDisable] Failed to unregister injected recipes: ${e.message}")
        }
        // Clean up temporary items (private temporary items are destroyed on server shutdown)
        manager.shutdown()
        // Close the database last: the shutdowns above write through the mappers
        // (crafting sessions, private items), and every mapper call is blocking JDBC. Without this
        // the last writes were still in flight when the JVM exited.
        try {
            DatabaseManager.close()
        } catch (e: Throwable) {
            warningSSync("[onDisable] Failed to close the database: ${e.message}")
        }
        infoLSync("Disable")
    }
    /*
    fun compat() {
        if (Bukkit.getPluginManager().getPlugin("Chemdah") != null) {
            connectChemdah()
        }
    }*/

    @Awake(LifeCycle.ENABLE)
    fun onEnableLoad() {
        // The spigot platform layer emits i18n warnings via CompatLog (mapped to warningL); must be registered before loading
        CompatLog.register { node, args -> warningL(node, *args) }
        // Normal config files must be bound before any other consumer reads SertralineSettings
        SertralineConfigManager.bind()
        try {
            reloadCustomConfig(false)
        } catch (e: Exception) {
            severeL("Failed to load configurations during startup: ${e.message}")
            e.printStackTrace()
        }
        try {
            PlatformCompat.registerPaperEvents(BukkitPlugin.getInstance(), PaperEventBridge.hooks)
            PlatformCompat.registerArmorPolling(
                BukkitPlugin.getInstance(),
                SertralineSettings.attributeArmorChangePolling
            ) { handleArmorSlotChanged(it) }
        } catch (e: Exception) {
            severeL("Failed to register platform event hooks: ${e.message}")
        }
    }

    fun reloadCustomConfig(async: Boolean = true, sender: CommandSender? = null) {
        submit(async = async) {
            // Single-flight gate: a second concurrent reload is rejected instead of
            // racing the current one through the same global registries.
            val context = ReloadCoordinator.tryBegin()
            if (context == null) {
                warningL("Reload_Already_Running")
                sender?.sendStringAsComponent("<yellow>Reload already in progress.</yellow>")
                return@submit
            }
            try {
                runReload(context, sender)
            } finally {
                ReloadCoordinator.end()
            }
        }
    }

    private fun runReload(context: ReloadContext, sender: CommandSender?) {
            // Normal config files (config.yml / experimental.yml): release, auto-update missing nodes, refresh SertralineSettings
            try {
                SertralineConfigManager.reload()
            } catch (e: Exception) {
                // A corrupt config.yml must not abort the whole reload; keep last-good SertralineSettings and continue.
                severeL("Config_Load_Error_Parse", "config", e.message ?: "Unknown error")
            }
            devMode = SertralineSettings.debug
            allowAsyncLog = SertralineSettings.asyncLogging

            val fastMinimessage = SertralineSettings.fastMinimessage
            FastMiniMessage.applyConfig(
                fastMinimessage,
                SertralineSettings.fastMinimessageCacheSize
            )
            if (VersionHelper.isLegacy() && fastMinimessage) {
                warningL("Experimental_FastMiniMessage_Legacy_Unsupported")
            }

            // Recreate debounce/throttle functions that bind config values at creation time
            try { resetDebounceRefreshStat() } catch (_: Throwable) {}

            // Custom event registration config is an independent file
            try {
                CustomEventsConfig.config.reload()
            } catch (e: Exception) {
                severeL("Config_Load_Error_Parse", "custom-events.yml", e.message ?: "Unknown error")
            }
            // Item manager config (manager.yml) �?also reload so changes apply without a restart
            try {
                ManagerConfig.config.reload()
            } catch (e: Exception) {
                severeL("Config_Load_Error_Parse", "manager.yml", e.message ?: "Unknown error")
            }

            // save public-temporary items, restore after reload
            manager.preReload()

            // ---- 候选构建阶段：全部 loader 写入局�?LoadContext，不触碰 live registry ----
            // 任一 loader 抛出未捕获异常时本轮候选整体作废，live registry 保持上一轮完整状态（last-good）�?
            val loadContext = LoadContext()
            var candidateFailed = false

            // compiled-script caches are pure derived data �?safe to drop upfront
            ketherScriptCache.clear()
            jsScriptCache.clear()
            gjsScriptCache.clear()
            jexlScriptCache.clear()

            itemManager.unregisterAllProcessor()
            tagManager.unregisterAllProcessor()
            AttributeManager.unregisterAll()
            registerNativeAdapter()
            registerNativeTagAdapter()
            registerNativeAttributeProviders()

            // 全部文件加载流程开始
            SertralineAllFileLoadPreEvent(loadContext).call()

            // Templates load first; all subsequent configs may reference them
            try { TemplateManager.loadTemplates(loadContext) } catch (e: Exception) {
                severeL("Config_Load_Error_Parse", "templates", e.message ?: "Unknown error")
                candidateFailed = true
            }
            ReloadCollector.addStat(console.asLangText("Reload_Stat_Templates", loadContext.templates.size))

            try { loadMappingFiles(loadContext) } catch (e: Exception) {
                severeL("Mapping_Load_Error_Parse", "mappings", e.message ?: "Unknown error")
                candidateFailed = true
            }
            ReloadCollector.addStat(console.asLangText("Reload_Stat_Mappings", loadContext.mappings.size))

            // tier/type/level must load before items, otherwise items' FeatureLoadEvent can't find the matching data
            try { loadTierFiles(loadContext) } catch (e: Exception) {
                severeL("Tier_Load_Error_Parse", "tiers", e.message ?: "Unknown error")
                candidateFailed = true
            }
            ReloadCollector.addStat(console.asLangText("Reload_Stat_Tiers", loadContext.tiers.size))

            try { loadTypeFiles(loadContext) } catch (e: Exception) {
                severeL("Type_Load_Error_Parse", "types", e.message ?: "Unknown error")
                candidateFailed = true
            }
            ReloadCollector.addStat(console.asLangText("Reload_Stat_Types", loadContext.types.size))

            try { loadLevelFiles(loadContext) } catch (e: Exception) {
                severeL("Level_Load_Error_Parse", "levels", e.message ?: "Unknown error")
                candidateFailed = true
            }
            ReloadCollector.addStat(console.asLangText("Reload_Stat_Levels", loadContext.levels.size))

            try { loadItemFiles(loadContext) } catch (e: Exception) {
                severeL("Config_Load_Error_Parse", "items", e.message ?: "Unknown error")
                candidateFailed = true
            }
            ReloadCollector.addStat(console.asLangText("Reload_Stat_Items", loadContext.items.size))

            // loreFormats 在候选中构建，发布时由 publishToLive 整体替换引用；
            // 并发读侧在发布前始终看到旧的完整 map，无空 map 窗口
            try { loadLoreFormatFiles(loadContext) } catch (e: Exception) {
                severeL("LoreFormat_Load_Error_Parse", "lore-formats", e.message ?: "Unknown error")
                candidateFailed = true
            }
            ReloadCollector.addStat(console.asLangText("Reload_Stat_LoreFormats", loadContext.loreFormats.size))

            try { loadCraftingStationFiles(loadContext) } catch (e: Exception) {
                severeL("CraftingStation_Load_Error_Parse", "crafting-stations", e.message ?: "Unknown error")
                candidateFailed = true
            }
            ReloadCollector.addStat(console.asLangText("Reload_Stat_CraftingStations", loadContext.craftingStations.size))

            if (candidateFailed) {
                // 候选构建失败：不发布、不触发 SertralineReloadEvent / recipe/listener 重载�?
                // live registry 保持 last-good，仅恢复临时物品并输出本轮报�?
                severeL("Reload_Candidate_Failed")
                SertralineAllFileLoadPostEvent(loadContext, published = false).call()
                manager.postReload()
                ReloadCollector.printSummary(sender)
                return
            }

            // ---- 发布阶段：候选一次性替换 live registry ----
            // 注：曾有 `if (loadContext.itemsLoadedIncrementally) mergeLiveItemsForIncrementalPublish(...)`
            // 分支，但该标志只在 `context == null` 时才会被置位，reload 恒带 context，
            // 所以它从未执行过（incremental-loading 对 reload 不生效，候选总是全量解析）。
            try {
                loadContext.publishToLive()
                itemExpectedRevision.clear()

                // crafting sessions hold references to station objects from the old registry;
                // cancel them only after a successful publish (failed builds keep sessions alive)
                CraftingStationManager.cancelAll()
            } finally {
                // restore public-temporary items into the freshly published registry.
                // MUST be in `finally`: preReload() moved every public-temporary item into
                // survivalBuffer and cleared temporaryCache, so a throw between publishToLive() and
                // here would drop those items forever (they are not on disk anywhere).
                // postReload() clears the buffer, so this is safe to run on any path.
                manager.postReload()
            }

            // warm up GraalJS scripts: eval each cached Source once in a throwaway Context
            // to trigger Truffle AST compilation, so the first real eval after reload is faster
            if (SertralineSettings.preloadScriptGraalJs && !VersionHelper.isLegacy()) {
                try {
                    GraalJsUtil.warmUpAll()
                } catch (e: Exception) {
                    // warm-up failure does not affect normal operation
                }
            }

            // Auto-tracking off means no revision is ever computed for public items: only items that
            // explicitly declare `sertraline:revision-id` can be updated. Warn instead of failing silently.
            if (SertralineSettings.itemUpdateEnabled && !SertralineSettings.itemUpdateAutoTrack) {
                warningL("Warning_Item_Update_AutoTrack_Disabled")
            }
            try { initRevisionAutoTracker() } catch (e: Exception) {
                severeL("Config_Load_Error_Parse", "revision-auto-tracker", e.message ?: "Unknown error")
            }

            // Item updater: recreate the periodic scan with the (possibly changed) interval, then
            // refresh online players once — the revision table was just recomputed, so items in
            // their inventories are stale until the next login/hold/interact otherwise.
            try {
                ItemUpdateScheduler.restart()
                if (SertralineSettings.itemUpdateOnReload) ItemUpdateScheduler.scanOnlinePlayersLater()
            } catch (e: Exception) {
                severeL("Config_Load_Error_Parse", "item-update-scheduler", e.message ?: "Unknown error")
            }

            // ---- 异步 prepare：解�?recipe 候选（不触�?Bukkit registry�?----
            // The custom crafting system depends on 1.13+/1.14+ APIs; lower-version files are excluded, so we skip directly
            // null = prepare 失败，commit 阶段跳过以保留当前已注册�?recipe（last-good�?
            var recipeCandidates: List<Any?>? = null
            if (VersionHelper.isOrAbove114()) {
                try {
                    @Suppress("UNCHECKED_CAST")
                    recipeCandidates = Class.forName("io.github.zzzyyylllty.sertraline.config.LoadRecipeFilesKt")
                        .getMethod("prepareRecipeCandidates").invoke(null) as List<Any?>
                } catch (e: Exception) {
                    severeL("Config_Load_Error_Parse", "recipes", e.message ?: "Unknown error")
                }
            }

            // Bukkit API operations such as recipe registration and event firing must run on the main thread
            if (Bukkit.isPrimaryThread()) {
                runRecipeSyncTasks(sender, recipeCandidates, loadContext)
            } else {
                val plugin = Bukkit.getPluginManager().getPlugin("Sertraline")
                if (plugin != null) {
                    Bukkit.getScheduler().callSyncMethod(plugin, Callable {
                        runRecipeSyncTasks(sender, recipeCandidates, loadContext)
                        null
                    }).get()
                } else {
                    severeL("Config_Load_Error_Parse", "recipes", "Sertraline plugin instance not found for sync task")
                }
            }
    }

    private fun runRecipeSyncTasks(sender: CommandSender?, recipeCandidates: List<Any?>?, loadContext: LoadContext) {
        // 主线�?commit：旧 recipe 先注销，候选注册失败按条目记账，整体失败恢复旧集合�?
        // prepare 失败（null）时跳过，保留当前已注册 recipe
        if (VersionHelper.isOrAbove114() && recipeCandidates != null) {
            try {
                Class.forName("io.github.zzzyyylllty.sertraline.config.LoadRecipeFilesKt")
                    .getMethod("commitRecipeCandidates", List::class.java)
                    .invoke(null, recipeCandidates)
            } catch (e: Exception) {
                severeL("Config_Load_Error_Parse", "recipes", e.message ?: "Unknown error")
            }
        }
        try { ScriptHelper.loadScriptFiles() } catch (e: Exception) {
            severeL("Config_Load_Error_Parse", "recipes", e.message ?: "Unknown error")
        }
        // Custom event listeners re-register on the main thread after config load (custom-events.yml)
        try {
            CustomEventListener.registerAll()
        } catch (e: Exception) {
            severeL("Config_Load_Error_Parse", "custom-events", e.message ?: "Unknown error")
        }
        // Global listeners re-register on the main thread after config load (global-listeners/*.yml)
        try {
            GlobalListenerManager.registerAll()
        } catch (e: Exception) {
            severeL("Config_Load_Error_Parse", "global-listeners", e.message ?: "Unknown error")
        }
        SertralineReloadEvent().call()
        SertralineAllFileLoadPostEvent(loadContext, published = true).call()
        ReloadCollector.printSummary(sender)
    }
//
//
//    fun createCustomConfig() {
//        infoL("INTERNAL_INFO_CREATING_CONFIG")
//        try {
//            Configuration.loadFromFile(newFile(getDataFolder(), "placeholders.yml", create = true), Type.YAML)
//            Configuration.loadFromFile(newFile(getDataFolder(), "config.yml", create = true), Type.YAML)
//            infoL("INTERNAL_INFO_CREATED_CONFIG")
//        } catch (e: Exception) {
//            severeL("INTERNAL_SEVERE_CREATE_CONFIG_ERROR")
//            e.printStackTrace()
//        }
//    }


    @SubscribeEvent
    fun lang(event: PlayerSelectLocaleEvent) {
        event.locale = SertralineSettings.lang
    }

    @SubscribeEvent
    fun lang(event: SystemSelectLocaleEvent) {
        event.locale = SertralineSettings.lang
    }


    @Awake(LifeCycle.CONST)
    fun initDependenciesInit() {
        solveDependencies(dependencies, true)
    }

    @Awake(LifeCycle.ENABLE)
    fun initItemTags() {
        try {
            ItemTagManager.initializeVanillaTags()
            infoL("ItemTagManager initialized successfully")
        } catch (e: Exception) {
            severeL("Failed to initialize ItemTagManager: ${e.message}")
        }
    }


    fun solveDependencies(dependencies: List<String>, useTaboo: Boolean = false) {
        devLogSync("Starting loading dependencies...")
        for (name in dependencies) {
            try {
                infoSSync("Trying to load dependencies from file $name")
                val resource = Sertraline::class.java.classLoader.getResource("META-INF/dependencies/$name.json")
                if (resource == null) {
                    severeS("Resource META-INF/dependencies/$name.json not found!")
                    continue // skip this dependency file
                }

                if (useTaboo) RuntimeEnv.ENV_DEPENDENCY.loadFromLocalFile(resource) else SertralineLocalDependencyHelper().loadFromLocalFile(resource)

                infoSSync("Trying to load dependencies from file $name ... DONE.")
            } catch (e: Exception) {
                severeSSync("Trying to load dependencies from file $name FAILED.")
                severeSSync("Exception: $e")
                e.printStackTrace()
            }
        }
    }

}

fun registerNativeAttributeProviders() {
    // MythicLibAttributeProvider depends on io.lumine.*, which doesn't exist on the legacy12 compile surface (file excluded); registered reflectively, silently skipped when the class is missing
    try {
        Class.forName("io.github.zzzyyylllty.sertraline.attribute.MythicLibAttributeProvider")
            .getDeclaredConstructor()
            .newInstance()
            .let { AttributeManager.register(it as AttributeProvider) }
    } catch (_: Throwable) {
    }
    AttributeManager.register(ChotenAttributeProvider())
}
