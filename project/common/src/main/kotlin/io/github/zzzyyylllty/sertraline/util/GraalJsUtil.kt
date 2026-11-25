package io.github.zzzyyylllty.sertraline.util

import io.github.zzzyyylllty.sertraline.Sertraline.gjsScriptCache
import io.github.zzzyyylllty.sertraline.config.common.SertralineSettings
import io.github.zzzyyylllty.sertraline.logger.severeS
import io.github.zzzyyylllty.sertraline.util.serialize.generateHash
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import taboolib.common.LifeCycle
import taboolib.common.env.RuntimeEnv
import taboolib.common.platform.Awake
import java.util.Collections
import java.util.WeakHashMap
import javax.script.*
import kotlin.String
import kotlin.collections.set


val GJS_LANG_ID = "js"

/**
 * Runs [block] with the plugin class loader as the thread's context class loader.
 *
 * GraalJS resolves host classes (`Java.type`) through the context class loader that was current when the Context was
 * created. Bukkit does not set that loader to the plugin's on every thread, so the main server thread's loader (the
 * server/paperclip one) cannot see plugin classes. Because [GraalJsUtil.contextHolder] caches one Context per thread, the
 * first eval on such a thread permanently binds that thread to the wrong loader, and every later `Java.type` of a plugin
 * class fails with "Access to host class ... is not allowed or does not exist" -- typically on every event tick, from the
 * global listeners. Nashorn already builds its engine from the plugin loader; this keeps GraalJS equivalent.
 */
internal inline fun <T> withPluginClassLoader(block: () -> T): T {
    val thread = Thread.currentThread()
    val pluginLoader = GraalJsUtil::class.java.classLoader
    val previous = thread.contextClassLoader
    if (previous === pluginLoader) return block()
    return try {
        thread.contextClassLoader = pluginLoader
        block()
    } finally {
        thread.contextClassLoader = previous
    }
}

val globalGJSEngine: Engine by lazy {
    // Deliberately created without the class-loader swap: TabooLib loads the GraalJS jars into its own dependency
    // loader, and the Engine discovers the `js` language through the current context class loader. Swapping it to the
    // plugin loader hides that language and fails with "Could not find option with name js.ecmascript-version".
    Engine.newBuilder(GJS_LANG_ID)
        .allowExperimentalOptions(true)
        .option("js.ecmascript-version", "latest")
        .option("js.nashorn-compat", "true") // Nashorn compatibility mode
        .build()
}

val hostAccess: HostAccess? by lazy {
    // Falling back to a context without host access would be far worse than the access they intended: the plugin's own
    // scripts (global listeners) then fail on every event, which is the failure this file exists to prevent.
    runCatching { hostAccessBuilder() }.getOrElse { failure ->
        severeS("GraalJS host access configuration failed, falling back to HostAccess.EXPLICIT: ${failure.message}")
        HostAccess.EXPLICIT
    }
}

private fun hostAccessBuilder(): HostAccess = HostAccess.newBuilder()
//Allow unrestricted access to all public constructors, methods, or fields of public classes
        .allowPublicAccess(true)
//Allow client languages to implement any Java interface
        .allowAllImplementations(true)
//Allow client languages to implement (extend) any Java class
        .allowAllClassImplementations(true)
//Allow access to arrays
        .allowArrayAccess(true)
//Allow access to Lists
        .allowListAccess(true)
//Allow client applications to access ByteBuffers as buffer elements
        .allowBufferAccess(false)
//Allow client applications to access iterables as values using iterators
        .allowIterableAccess(false)
//Allow client applications to access iterators as iterator values.
        .allowIteratorAccess(true)
//Allow client applications to access Map objects as hash values
        .allowMapAccess(true)
//Allow client applications to inherit access to allowed methods
        .allowAccessInheritance(false)
        .build()

object GraalJsUtil {

    /**
     * A weak-reference set of all live Graal Contexts, used only to deterministically close them on DISABLE (releasing native memory).
     * Weak references: a discarded one-off Context dropped by the caller does not block the set from being cleared once it is GC'd.
     */
    private val liveContexts: MutableSet<Context> =
        Collections.newSetFromMap(Collections.synchronizedMap(WeakHashMap<Context, Boolean>()))

    fun compile(script: String): Source? {
        return try {
            Source.newBuilder(GJS_LANG_ID, script, "script.js").build()
        } catch (e: Exception) {
            e.printStackTrace()
            null // return null on compilation failure
        }
    }

    /**
     * Warm up a single cached [Source] by evaluating it once in a temporary Context.
     * This triggers Truffle's partial evaluation and compilation of the AST, so the
     * first real eval via [cachedEval] pays less (or no) compilation cost.
     *
     * Scripts that reference variables only available at runtime will fail during
     * warm-up -- that is expected and silently ignored; the script will still work
     * normally when eval'd with the correct variables later.
     */
    fun warmUpSource(source: Source) {
        val ctx = newGraalContext()
        try {
            withPluginClassLoader { ctx.eval(source) }
        } catch (_: Exception) {
            // expected: script may reference runtime-only variables, or Graal may
            // decide not to fully compile a one-shot eval; either case is benign
        } finally {
            ctx.close(true)
        }
    }

    /**
     * Warm up every cached GraalJS script at once.
     * Iterates over [Sertraline.gjsScriptCache] and evals each [Source] in a single
     * temporary Context, minimizing Engine overhead across all scripts.
     *
     * Intended to be called from [Sertraline.reloadCustomConfig] after all scripts
     * have been loaded and pre-compiled into [Source] objects.
     */
    fun warmUpAll() {
        if (gjsScriptCache.isEmpty()) return
        val ctx = newGraalContext()
        try {
            withPluginClassLoader {
                for ((_, source) in gjsScriptCache) {
                    try {
                        ctx.eval(source)
                    } catch (_: Exception) {
                        // expected per-source warm-up failure, continue with the next
                    }
                }
            }
        } finally {
            ctx.close(true)
        }
    }

    fun newGraalContext(): Context = newGraalContext(javaTypeAllowed())

    private fun javaTypeAllowed(): Boolean = SertralineSettings.rawBoolean("script.graaljs.allow-java-type", true)

    private fun newGraalContext(allowJavaType: Boolean): Context {

        // Read (and so lazily build) the shared engine before swapping the class loader: engine creation discovers the
        // `js` language through the current context class loader, and TabooLib keeps the GraalJS jars in its own
        // dependency loader.
        val engine = globalGJSEngine
        // The Context itself is created under the plugin loader so host class lookups (Java.type) can see plugin classes.
        val context = withPluginClassLoader {
            Context.newBuilder(GJS_LANG_ID)
//#            .allowAllAccess(true) // too permissive, replaced by the fine-grained config below
//            .allowHostAccess(hostAccess)
                .engine(engine)
                .allowHostAccess(hostAccess ?: HostAccess.EXPLICIT) // fine-grained config; never HostAccess.ALL
                .allowHostClassLookup { allowJavaType }
                .allowHostClassLoading(false) // prevent scripts from loading arbitrary classes via Class.forName
                .build()
        }
        liveContexts.add(context)
        return context
    }


    fun directEval(script: String, vars: Map<String, Any?>): Any? {
        // legacy12 (Java 8): GraalJS is not bundled and its runtime download description is excluded,
        // lower versions map to the JDK's built-in Nashorn (JSR 223), avoiding NoClassDefFoundError
        if (VersionHelper.isLegacy()) return evalNashorn(script, vars)
        return executeScriptString(script, vars)

    }
    fun cachedEval(script: String, vars: Map<String, Any?>): Any? {
        if (VersionHelper.isLegacy()) return evalNashorn(script, vars)

        val hash = script.generateHash()
        var source = gjsScriptCache[hash]
        if (source == null) {
            source = compile(script)
            if (source != null) {
                gjsScriptCache.put(hash, source)
            }
        }

        if (source == null) {
            // compilation failed
            severeS("Script compilation failed for script: $script")
            return null
        }

        return executeScriptSource(source, vars)

    }

    /**
     * Returns the execution result as a JS-semantics string (equivalent to GraalJS's Value.toString),
     * used to preserve the old evalGraalJs output format (e.g. integer 2 �?"2" rather than "2.0").
     */
    fun evalToJsString(script: String, vars: Map<String, Any?>): Any? {
        if (VersionHelper.isLegacy()) return evalNashorn(script, vars)
        return withBindings(vars) { context -> context.eval(GJS_LANG_ID, script).toString() }
    }

    /**
     * Nashorn engine reused per-thread.
     * JSR-223 engines are not thread-safe (concurrent evals on a single instance cause problems), and creating a new ScriptEngineManager + engine each time is costly,
     * so it is cached per-thread rather than shared globally.
     */
    internal val nashornEngineHolder = object : ThreadLocal<ScriptEngine>() {
        override fun initialValue(): ScriptEngine =
            ScriptEngineManager(GraalJsUtil::class.java.classLoader).getEngineByName("js")
    }

    private fun evalNashorn(script: String, vars: Map<String, Any?>): Any? {
        val engine = nashornEngineHolder.get() ?: run {
            severeS("JavaScript engine not available (Nashorn)")
            return null
        }
        val bindings = engine.createBindings()
        vars.forEach { (k, v) -> if (k != "Math" || v != Math::class.java) bindings[k] = v }
        return engine.eval(script, bindings)
    }

    /** Graal context re-used per-thread, avoiding creating/destroying a Context on every eval (context creation is the main overhead) */
    private data class CachedContext(val context: Context, val allowJavaType: Boolean)
    private val contextHolder = ThreadLocal<CachedContext>()
    /**
     * Restore bindings after nested calls.
     *
     * Wrapped in [withPluginClassLoader] because the cached Context is created on the calling thread: without the swap, a
     * main-thread (event) eval would bind that thread's Context to the server class loader and lose `Java.type` access to
     * plugin classes permanently. See [withPluginClassLoader].
     */
    internal fun <T> withBindings(vars: Map<String, Any?>, block: (Context) -> T): T = withPluginClassLoader {
        val allowJavaType = javaTypeAllowed()
        val cached = contextHolder.get()
        val context = if (cached != null && cached.allowJavaType == allowJavaType) cached.context else {
            // Java.type caches resolved classes in a Context. Recreate it when the
            // setting changes so reload can also revoke previously resolved classes.
            cached?.context?.close()
            newGraalContext(allowJavaType).also { contextHolder.set(CachedContext(it, allowJavaType)) }
        }
        val bindings = context.getBindings(GJS_LANG_ID)
        val previous = linkedMapOf<String, Value?>()
        try {
            for ((key, value) in vars) {
                if (key == "Math" && value == Math::class.java) continue
                previous[key] = if (bindings.hasMember(key)) bindings.getMember(key) else null
                bindings.putMember(key, value)
            }
            block(context)
        } finally {
            for ((key, value) in previous) {
                if (value == null) bindings.removeMember(key) else bindings.putMember(key, value)
            }
        }
    }

    private fun executeScriptString(script: String, vars: Map<String, Any?>): Any? =
        withBindings(vars) { it.eval(GJS_LANG_ID, script).`as`(Any::class.java) }

    private fun executeScriptSource(source: Source, vars: Map<String, Any?>): Any? =
        withBindings(vars) { it.eval(source).`as`(Any::class.java) }

    fun createContext(vars: Map<String, Any?>): Context {
        // initialize the warm-up context
        val context = newGraalContext()
        return context
    }

    private fun createScriptSource(script: String, cached: Boolean = true): Source {
        return Source.newBuilder(GJS_LANG_ID, script, "<eval>").cached(cached).build()
    }

    @Awake(LifeCycle.INIT)
    private fun initialize() {
        loadDependencies("graaljs")
    }

    @Awake(LifeCycle.DISABLE)
    private fun shutdown() {
        // Close all live Graal Contexts to deterministically release their native memory.
        // Without explicit close, ThreadLocal strong references keep Contexts from ever being GC'd, and native memory leaks as thread-pool threads persist
        synchronized(liveContexts) {
            liveContexts.forEach { ctx -> runCatching { ctx.close(true) } }
            liveContexts.clear()
        }
        contextHolder.remove()
    }

    internal fun loadDependencies(name: String) {
        RuntimeEnv.ENV_DEPENDENCY.loadFromLocalFile(
            this::class.java.classLoader.getResource("META-INF/dependencies/$name.json")
        )
    }

}
