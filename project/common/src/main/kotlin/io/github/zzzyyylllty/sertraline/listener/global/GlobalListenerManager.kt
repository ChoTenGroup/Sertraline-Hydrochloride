package io.github.zzzyyylllty.sertraline.listener.global

import io.github.zzzyyylllty.sertraline.data.defaultData
import io.github.zzzyyylllty.sertraline.debugMode.devLog
import io.github.zzzyyylllty.sertraline.debugMode.devLogBypassCheck
import io.github.zzzyyylllty.sertraline.function.kether.evalKetherBoolean
import io.github.zzzyyylllty.sertraline.logger.infoS
import io.github.zzzyyylllty.sertraline.logger.severeS
import io.github.zzzyyylllty.sertraline.logger.warningS
import io.github.zzzyyylllty.sertraline.util.ClassAliases
import io.github.zzzyyylllty.sertraline.util.EventPlayerResolver
import io.github.zzzyyylllty.sertraline.util.ScriptHelper
import io.github.zzzyyylllty.sertraline.util.ScriptHelper.ScriptType
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.releaseResourceFolder
import taboolib.common.platform.function.submitAsync
import taboolib.library.configuration.ConfigurationSection
import taboolib.module.configuration.Configuration
import taboolib.platform.BukkitPlugin
import java.io.File
import com.github.benmanes.caffeine.cache.Caffeine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.TimeUnit

/**
 * Global listener system
 *
 * Loads YAML listeners from the global-listeners/ directory, binds them to arbitrary Bukkit events, and executes scripts.
 * Unrelated to item actions; suited to global behaviors (e.g. the sneaking toggle loreMode in the example).
 *
 * Configuration file format:
 * ```
 * sample_listener:
 *   bind: "PlayerToggleSneakEvent"   # event class, short alias or fully qualified name
 *   priority: NORMAL                 # optional: LOWEST/LOW/NORMAL/HIGH/HIGHEST/MONITOR
 *   accept-cancelled: false          # optional: whether to handle cancelled events (default false)
 *   async: false                     # optional: run the script off the main thread (default false)
 *                                    #   scripts receive `event`/`cancellableEvent` and the Bukkit API, so
 *                                    #   asynchronous execution is only safe for pure computation.
 *   throttle: 500                    # optional: minimum trigger interval in ms; 0/absent = no rate limiting
 *   condition:                       # optional: Kether condition, skipped when false
 *     - "..."
 *   eval:
 *     graaljs: |
 *       ...script...
 *     kether:
 *       - "..."
 * ```
 *
 * The keys of eval support all script engines: graaljs / javascript(js) / kether / fluxon(fx) / jexl.
 * Injected runtime variables: player / event / cancellableEvent / listener, merged with defaultData (DataUtil, Bukkit, SertralineAPI, etc.).
 * throttle rate-limits by (listener + player; listener-global when there is no player): under high-frequency events
 * (such as PlayerMoveEvent) only once per window passes, avoiding a condition evaluation and async dispatch on every event.
 */
object GlobalListenerManager {

    data class GlobalListener(
        val name: String,
        val eventClass: Class<out Event>,
        val priority: EventPriority,
        val ignoreCancelled: Boolean,
        val async: Boolean,
        val condition: String?,
        val scripts: List<Pair<ScriptType, String>>,
        val source: String,
        /** Minimum trigger interval in ms; <=0 means no rate limiting */
        val throttle: Long,
    )

    /** Shared Listener instance, used only for global listener registration and can be unregistered as a whole */
    private val dummyListener: Listener = object : Listener {}

    private val registrations = mutableListOf<GlobalListener>()

    /**
     * throttle key -> last release timestamp (ms); Caffeine-backed with 1-hour expiry to prevent unbounded growth.
     * Typed as ConcurrentMap (not Caffeine's Cache): this object's <clinit> allocates the lazy lambda, and loading
     * that lambda class makes the verifier resolve its return type — a Caffeine-typed property would therefore
     * throw NoClassDefFoundError at LifeCycle.CONST, before Caffeine is injected at LifeCycle.INIT.
     */
    private val lastFire: ConcurrentMap<String, Long> by lazy {
        Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(1, TimeUnit.HOURS)
            .build<String, Long>()
            .asMap()
    }

    /** Loads and registers all listeners under the global-listeners/ directory.
     *
     * Prepare/commit order: all listeners are parsed first; only then is the old set
     * unregistered and the new set registered. A parse failure never tears down the
     * currently active listeners. */
    fun registerAll() {
        val dir = File(getDataFolder(), "global-listeners")
        if (!dir.exists()) {
            infoS("Global listeners directory not found, releasing default...")
            try {
                releaseResourceFolder("global-listeners")
            } catch (_: Exception) {
                dir.mkdirs()
            }
        }
        if (!dir.exists()) {
            severeS("Failed to create global-listeners directory: ${dir.absolutePath}")
            return
        }

        // ---- prepare：全部解析，任何一条失败不影响现有注册 ----
        val plugin = BukkitPlugin.getInstance()
        val parsed = mutableListOf<GlobalListener>()
        dir.walk()
            .filter { it.isFile && it.extension in setOf("yml", "yaml") }
            .forEach { file ->
                try {
                    val config = Configuration.loadFromFile(file)
                    for (key in config.getKeys(false)) {
                        val section = config.getConfigurationSection(key) ?: continue
                        try {
                            parsed += parseListener(key, section, file.name)
                        } catch (ex: Exception) {
                            severeS("Failed to parse global listener \"$key\" (${file.name}): ${ex.message}")
                        }
                    }
                } catch (e: Exception) {
                    severeS("Failed to load global listener file: ${file.name} - ${e.message}")
                }
            }

        // ---- commit：注销旧 + 注册新（主线程，dummyListener 整体注销） ----
        unregisterAll()
        lastFire.clear()
        for (listener in parsed) {
            try {
                plugin.server.pluginManager.registerEvent(
                    listener.eventClass,
                    dummyListener,
                    listener.priority,
                    EventExecutor { _, event -> onEvent(listener, event) },
                    plugin,
                    listener.ignoreCancelled
                )
                registrations += listener
                devLog("Registered global listener \"${listener.name}\" -> ${listener.eventClass.name} (${listener.source})")
            } catch (ex: Exception) {
                severeS("Failed to register global listener \"${listener.name}\": ${ex.message}")
            }
        }
        infoS("Loaded ${registrations.size} global listener(s) from ${dir.absolutePath}")
        devLog("Global listeners: ${registrations.map { it.name }}")
    }

    /** Unregisters all global listeners */
    fun unregisterAll() {
        if (registrations.isEmpty()) return
        try {
            HandlerList.unregisterAll(dummyListener)
        } catch (_: Throwable) {
        }
        registrations.clear()
    }

    private fun parseListener(key: String, section: ConfigurationSection, file: String): GlobalListener {
        val className = section.getString("bind") ?: section.getString("class") ?: section.getString("listen")
            ?: error("missing \"bind\"")
        val clazz = ClassAliases.getClass(className)
            ?: error("invalid event class \"$className\"")
        if (!Event::class.java.isAssignableFrom(clazz)) {
            error("\"$className\" is not a Bukkit Event")
        }
        @Suppress("UNCHECKED_CAST")
        val eventClass = clazz as Class<out Event>
        val priority = section.getString("priority")?.let { name ->
            EventPriority.values().firstOrNull { it.name.equals(name, true) }
        } ?: EventPriority.NORMAL
        val acceptCancelled = section.getBoolean("accept-cancelled", false)
        // `async` defaults to FALSE: the scripts get `event`, `cancellableEvent` and the Bukkit API in
// `defaultData`, so running them off-thread was a thread-safety violation by default (and events
// that are only valid on the main thread, e.g. anything reading an inventory, could throw).
// Listeners that genuinely do pure computation can opt in with `async: true`.
        val async = section.getBoolean("async", false)
        val throttle = section.getLong("throttle", 0)
        val condition = parseCondition(section.get("condition"))
        val scripts = parseScripts(key, section.getConfigurationSection("eval"), file)
        return GlobalListener(key, eventClass, priority, !acceptCancelled, async, condition, scripts, file, throttle)
    }

    private fun parseCondition(raw: Any?): String? {
        return when (raw) {
            null -> null
            is String -> raw.trim().ifBlank { null }
            is List<*> -> raw.mapNotNull { it?.toString() }.joinToString("\n").trim().ifBlank { null }
            else -> raw.toString().trim().ifBlank { null }
        }
    }

    /** Parses the eval section into a "script type -> script content" list; values may be multiline strings or line lists */
    private fun parseScripts(key: String, eval: ConfigurationSection?, file: String): List<Pair<ScriptType, String>> {
        val scripts = mutableListOf<Pair<ScriptType, String>>()
        if (eval == null) return scripts
        for (engineKey in eval.getKeys(false)) {
            val type = try {
                ScriptType.fromString(engineKey)
            } catch (e: IllegalArgumentException) {
                warningS("Unknown script type \"$engineKey\" in global listener \"$key\" (${file}), skipping")
                continue
            }
            val raw = eval.get(engineKey)
            val script = when (raw) {
                is String -> raw.trim()
                is List<*> -> raw.mapNotNull { it?.toString() }.joinToString("\n")
                else -> null
            }?.ifBlank { null } ?: continue
            scripts += type to script
        }
        return scripts
    }

    private fun onEvent(listener: GlobalListener, event: Event) {
        // Resolve the player once: shared between the throttle key and script variable injection, avoiding double reflection resolution for the same event
        val player = EventPlayerResolver.resolvePlayer(event)
        // Continue only if the throttle allows it: avoids a condition evaluation + async dispatch for every event under high-frequency events
        if (listener.throttle > 0 && !throttleAcquire(listener, player)) {
            devLogBypassCheck { "Global listener \"${listener.name}\": throttled, skipped." }
            return
        }
        val vars = LinkedHashMap<String, Any?>()
        // Consistent with the eval scripts: the condition evaluation also injects defaultData (DataUtil/Bukkit/SertralineAPI, etc.)
        vars.putAll(defaultData)
        vars["player"] = player
        vars["event"] = event
        vars["cancellableEvent"] = event as? Cancellable
        vars["listener"] = listener.name

        val condition = listener.condition
        if (condition != null && !condition.evalKetherBoolean(player, vars, def = false)) {
            devLogBypassCheck { "Global listener \"${listener.name}\": condition not met, skipped." }
            return
        }

        devLogBypassCheck { "Firing global listener \"${listener.name}\" on ${event.eventName}" }
        if (listener.async) {
            submitAsync { runScripts(listener, vars) }
        } else {
            runScripts(listener, vars)
        }
    }

    /**
     * Determines the throttle window by (listener file:listener name + player uuid, listener only when there is no player).
     * Returns true to allow this event and updates the release time to now (atomic, avoids concurrent releases within the same window).
     * @param player resolved and passed in by the caller, avoiding repeated reflection resolution for the same event
     */
    private fun throttleAcquire(listener: GlobalListener, player: Player?): Boolean {
        val key = if (player != null) "${listener.source}:${listener.name}:${player.uniqueId}" else "${listener.source}:${listener.name}"
        val now = System.currentTimeMillis()
        val result = lastFire.compute(key) { _, last ->
            val lastTime = last ?: 0L
            if (now - lastTime < listener.throttle) lastTime else now
        }
        return result == now
    }

    private fun runScripts(listener: GlobalListener, vars: Map<String, Any?>) {
        for ((type, script) in listener.scripts) {
            ScriptHelper.executeScript(type, script, vars)
        }
    }
}
