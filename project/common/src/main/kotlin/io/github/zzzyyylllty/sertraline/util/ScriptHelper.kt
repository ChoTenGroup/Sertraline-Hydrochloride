package io.github.zzzyyylllty.sertraline.util

import io.github.zzzyyylllty.sertraline.data.defaultData
import io.github.zzzyyylllty.sertraline.debugMode.devLog
import io.github.zzzyyylllty.sertraline.debugMode.devLogBypassCheck
import io.github.zzzyyylllty.sertraline.function.fluxon.FluxonShell
import io.github.zzzyyylllty.sertraline.logger.infoS
import io.github.zzzyyylllty.sertraline.logger.severeS
import io.github.zzzyyylllty.sertraline.logger.warningS
import org.bukkit.command.CommandSender
import taboolib.common.LifeCycle
import taboolib.common.env.RuntimeEnv
import taboolib.common.platform.Awake
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.releaseResourceFolder
import taboolib.module.configuration.Configuration
import taboolib.module.kether.KetherShell
import taboolib.module.kether.ScriptOptions
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import javax.script.ScriptEngineManager


/**
 * Script management system
 *
 * Supports loading YAML script files from the scripts/ directory and executing them by name.
 *
 * Script types: graaljs (GraalVM Polyglot), javascript/js (standard JSR 223), kether, fluxon, jexl
 *
 * Usage examples:
 *   ScriptHelper.eval("sample", "arg1_value")
 *   ScriptHelper.eval("sample", mapOf("player" to player, "amount" to 10))
 */
object ScriptHelper {

    val engineManager by lazy { ScriptEngineManager(this::class.java.classLoader) }

    private val scriptCache = ConcurrentHashMap<String, ScriptEntry>()
    private val contextHolder = ThreadLocal<ScriptContext>()

    data class ScriptEntry(
        val type: ScriptType,
        val script: String,
        val source: String
    )

    enum class ScriptType {
        /** GraalVM Polyglot API (high performance, supports ES2023) */
        GRAALJS,
        /** Standard JSR 223 javax.script JavaScript / Nashorn */
        JAVASCRIPT,
        KETHER, FLUXON, JEXL;

        companion object {
            fun fromString(s: String): ScriptType = when (s.lowercase()) {
                "graaljs", "gjs" -> GRAALJS
                "javascript", "js" -> JAVASCRIPT
                "kether" -> KETHER
                "fluxon", "fx" -> FLUXON
                "jexl" -> JEXL
                else -> throw IllegalArgumentException("Unknown script type: $s")
            }
        }
    }

    data class ScriptContext(
        val sender: CommandSender? = null,
        val vars: Map<String, Any?> = emptyMap()
    )

    @Awake(LifeCycle.INIT)
    private fun initDependency() {
        loadDependencies("graaljs")
    }

    internal fun loadDependencies(name: String) {
        RuntimeEnv.ENV_DEPENDENCY.loadFromLocalFile(
            this::class.java.classLoader.getResource("META-INF/dependencies/$name.json")
        )
    }

    // ==================== script loading ====================

    /**
     * Load all YAML script files from the dataFolder/scripts/ directory
     */
    fun loadScriptFiles() {
        scriptCache.clear()
        val scriptsDir = File(getDataFolder(), "scripts")

        if (!scriptsDir.exists()) {
            warningS("Scripts directory not found, releasing default...")
            try {
                releaseResourceFolder("scripts")
            } catch (_: Exception) {
                scriptsDir.mkdirs()
            }
        }

        if (!scriptsDir.exists()) {
            severeS("Failed to create scripts directory: ${scriptsDir.absolutePath}")
            return
        }

        var count = 0
        scriptsDir.walk()
            .filter { it.isFile && it.extension in setOf("yml", "yaml") }
            .forEach { file ->
                count += loadScriptFile(file)
            }

        infoS("Loaded $count scripts from ${scriptsDir.absolutePath}")
        devLog("Script cache entries: ${scriptCache.keys}")
    }

    /**
     * Reload the scripts
     */
    fun reloadScripts() {
        loadScriptFiles()
    }

    /**
     * Get the names of all loaded scripts
     */
    fun getScriptNames(): Set<String> = scriptCache.keys.toSet()

    /**
     * Check whether a script exists
     */
    fun hasScript(name: String): Boolean = scriptCache.containsKey(name)

    fun getScriptType(name: String): ScriptType? = scriptCache[name]?.type

    private fun loadScriptFile(file: File): Int {
        var count = 0
        try {
            val config = Configuration.loadFromFile(file)
            for (key in config.getKeys(false)) {
                val section = config.getConfigurationSection(key) ?: continue
                // legacy12 has no GraalJS (Nashorn compatible), so default to the built-in JSR 223; other platforms default to GraalJS
                val typeStr = section.getString("type") ?: if (VersionHelper.isLegacy()) "javascript" else "graaljs"
                val script = section.getString("script") ?: continue

                val type = try {
                    ScriptType.fromString(typeStr)
                } catch (e: IllegalArgumentException) {
                    warningS("Unknown script type '$typeStr' in script '$key' (${file.name}), skipping")
                    continue
                }

                scriptCache[key] = ScriptEntry(type, script, file.name)
                count++
            }
        } catch (e: Exception) {
            severeS("Failed to load script file: ${file.name} - ${e.message}")
        }
        return count
    }

    // ==================== context management ====================

    /**
     * Push a script execution context; subsequent eval calls will automatically inherit this context
     *
     * @param sender the command sender (player/console)
     * @param vars   context variables
     */
    fun pushContext(sender: CommandSender? = null, vars: Map<String, Any?> = emptyMap()) {
        contextHolder.set(ScriptContext(sender, vars))
    }

    /**
     * Pop the current thread's script execution context
     */
    fun popContext() {
        contextHolder.remove()
    }

    /**
     * Execute a code block with the specified context, automatically managing push/pop
     *
     * @param sender the context sender
     * @param vars   context variables
     * @param block  the code block to execute
     */
    fun <T> withContext(sender: CommandSender? = null, vars: Map<String, Any?> = emptyMap(), block: () -> T): T {
        val previous = contextHolder.get()
        contextHolder.set(ScriptContext(sender, vars))
        try {
            return block()
        } finally {
            if (previous != null) contextHolder.set(previous) else contextHolder.remove()
        }
    }

    /**
     * Get the current thread's script execution context
     */
    fun currentContext(): ScriptContext? = contextHolder.get()

    // ==================== execution API ====================

    /**
     * Execute a script by name, passing varargs (mapped to arg1, arg2, ...)
     *
     * @param name the script name (the key in the YAML)
     * @param args varargs, accessible in the script as arg1, arg2, ...
     * @return the execution result
     */
    fun eval(name: String, vararg args: Any?): Any? {
        val entry = scriptCache[name] ?: run {
            warningS("Script '$name' not found, available: ${scriptCache.keys}")
            return null
        }

        val ctx = currentContext()
        val vars = LinkedHashMap<String, Any?>()

        // inject global default data (mmUtil, Bukkit, PlayerUtil, etc.)
        vars.putAll(defaultData)

        // inherit context variables
        if (ctx != null) vars.putAll(ctx.vars)

        // map VarArgs to arg1, arg2, ...
        args.forEachIndexed { i, v -> vars["arg${i + 1}"] = v }

        return execute(entry, vars, ctx?.sender)
    }

    /**
     * Execute a script by name, passing named parameters
     *
     * @param name   the script name (the key in the YAML)
     * @param vars   named parameters
     * @param sender the command sender, overriding the sender in the context
     * @return the execution result
     */
    fun eval(name: String, vars: Map<String, Any?>, sender: CommandSender? = null): Any? {
        val entry = scriptCache[name] ?: run {
            warningS("Script '$name' not found, available: ${scriptCache.keys}")
            return null
        }

        val ctx = currentContext()
        val mergedVars = LinkedHashMap<String, Any?>()

        // inject global default data (mmUtil, Bukkit, PlayerUtil, etc.)
        mergedVars.putAll(defaultData)

        // inherit context variables
        if (ctx != null) mergedVars.putAll(ctx.vars)

        // explicitly passed variables override inherited variables
        mergedVars.putAll(vars)

        return execute(entry, mergedVars, sender ?: ctx?.sender)
    }

    /**
     * Execute a script asynchronously
     */
    fun evalAsync(name: String, vararg args: Any?): CompletableFuture<Any?> {
        return CompletableFuture.supplyAsync { eval(name, *args) }
    }

    /**
     * Execute a script asynchronously (named parameters)
     */
    fun evalAsync(name: String, vars: Map<String, Any?>, sender: CommandSender? = null): CompletableFuture<Any?> {
        return CompletableFuture.supplyAsync { eval(name, vars, sender) }
    }

    // ==================== internal execution ====================

    /**
     * Directly execute a piece of raw script by type (without going through scriptCache), for reuse by systems such as the global listeners.
     *
     * @param type   the script type
     * @param script the script content
     * @param vars   injected variables (merged with [defaultData])
     * @param sender the command sender (used for Kether permissions/context)
     */
    fun executeScript(type: ScriptType, script: String, vars: Map<String, Any?>, sender: CommandSender? = null): Any? {
        val merged = LinkedHashMap<String, Any?>()
        merged.putAll(defaultData)
        merged.putAll(vars)
        return execute(ScriptEntry(type, script, "raw"), merged, sender)
    }

    private fun execute(entry: ScriptEntry, vars: MutableMap<String, Any?>, sender: CommandSender?): Any? {
        devLogBypassCheck { "Executing script '${entry.source}/${entry.script.take(50)}' type=${entry.type} vars=$vars" }
        return try {
            when (entry.type) {
                ScriptType.GRAALJS -> GraalJsUtil.cachedEval(entry.script, vars)
                ScriptType.JAVASCRIPT -> evalStandardJs(entry.script, vars)
                ScriptType.KETHER -> {
                    KetherShell.eval(entry.script, ScriptOptions.builder().apply {
                        if (sender != null) sender(sender)
                        vars(vars)
                    }.build()).get()
                }
                ScriptType.FLUXON -> FluxonShell.invoke(entry.script, useCache = true) {
                    root.rootVariables += vars
                }
                ScriptType.JEXL -> evalJexlScript(entry.script, vars)
            }
        } catch (e: Exception) {
            severeS("Error executing script '${entry.source}/${entry.script.take(50)}...': ${e.message}")
            e.printStackTrace()
            null
        }
    }

    private fun evalStandardJs(script: String, vars: Map<String, Any?>): Any? {
        // reuse the per-thread cached JSR-223 engine in GraalJsUtil, avoiding creating an engine on every call
        val engine = GraalJsUtil.nashornEngineHolder.get() ?: run {
            severeS("JavaScript engine not available (Nashorn/GraalJS JSR 223)")
            return null
        }
        val bindings = engine.createBindings()
        vars.forEach { (k, v) -> if (k != "Math" || v != Math::class.java) bindings[k] = v }
        return engine.eval(script, bindings)
    }

    private fun evalJexlScript(script: String, vars: Map<String, Any?>): Any? {
        try {
            val compiled = JexlUtil.prodJexlCompiler.compileToScript(script)
            return compiled.eval(vars)
        } catch (e: Exception) {
            severeS("JEXL evaluation error: ${e.message}")
            return null
        }
    }
}
