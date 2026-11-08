package io.github.zzzyyylllty.sertraline.util

import io.github.zzzyyylllty.sertraline.Sertraline.gjsScriptCache
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

val globalGJSEngine: Engine by lazy {
    Engine.newBuilder(GJS_LANG_ID)
        .allowExperimentalOptions(true)
        .option("js.ecmascript-version", "latest")
        .option("js.nashorn-compat", "true") // Nashorn compatibility mode
        .build()
}

val hostAccess: HostAccess? by lazy {
    HostAccess.newBuilder()
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
}

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
            ctx.eval(source)
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
            for ((_, source) in gjsScriptCache) {
                try {
                    ctx.eval(source)
                } catch (_: Exception) {
                    // expected per-source warm-up failure, continue with the next
                }
            }
        } finally {
            ctx.close(true)
        }
    }

    fun newGraalContext(): Context {

        val context = Context.newBuilder(GJS_LANG_ID)
//#            .allowAllAccess(true) // too permissive, replaced by the fine-grained config below
//            .allowHostAccess(hostAccess)
            .engine(globalGJSEngine)
            .allowHostAccess(hostAccess ?: HostAccess.ALL) // use fine-grained config
            .allowHostClassLookup { name -> name.startsWith("io.github.zzzyyylllty.sertraline") } // only allow lookup of plugin classes; System/Bukkit/Runtime cannot be discovered by name
            .allowHostClassLoading(false) // prevent scripts from loading arbitrary classes via Class.forName
            .build()
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
        val (context, bindings) = prepareContext(vars)
        val result: Value = context.eval(GJS_LANG_ID, script)
        return result?.toString() ?: result
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
        vars.forEach { (k, v) -> bindings[k] = v }
        return engine.eval(script, bindings)
    }

    /** Graal context re-used per-thread, avoiding creating/destroying a Context on every eval (context creation is the main overhead) */
    private val contextHolder = ThreadLocal<Context>()
    private val addedKeysHolder = ThreadLocal<MutableSet<String>>()

    /** Get (or create) the current thread's context, inject the variables, and return the context and bindings */
    private fun prepareContext(vars: Map<String, Any?>): Pair<Context, Value> {
        val context = contextHolder.get() ?: newGraalContext().also { contextHolder.set(it) }
        val bindings: Value = try {
            context.getBindings(GJS_LANG_ID)
        } catch (e: IllegalStateException) {
            // the context was closed by DISABLE (old thread-pool threads still alive after hot reload), rebuild it
            val fresh = newGraalContext()
            contextHolder.set(fresh)
            fresh.getBindings(GJS_LANG_ID)
        }
        val addedKeys = addedKeysHolder.get() ?: mutableSetOf<String>().also { addedKeysHolder.set(it) }
        // Only clean up the keys added last time (i.e. the variables injected this time) to avoid variable residue pollution; do not clear globals declared by the script itself
        for (key in addedKeys) {
            try {
                bindings.removeMember(key)
            } catch (_: Exception) {
            }
        }
        addedKeys.clear()
        vars.forEach {
            bindings.putMember(it.key, it.value)
            addedKeys.add(it.key)
        }
        return context to bindings
    }

    private fun executeScriptString(script: String, vars: Map<String, Any?>): Any? {
        val (context, _) = prepareContext(vars)
        val result: Value = context.eval(GJS_LANG_ID, script)
        return result.`as`(Any::class.java)
    }

    private fun executeScriptSource(source: Source, vars: Map<String, Any?>): Any? {
        val (context, _) = prepareContext(vars)
        val result: Value = context.eval(source)
        return result.`as`(Any::class.java)
    }

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
        addedKeysHolder.remove()
    }

    internal fun loadDependencies(name: String) {
        RuntimeEnv.ENV_DEPENDENCY.loadFromLocalFile(
            this::class.java.classLoader.getResource("META-INF/dependencies/$name.json")
        )
    }

}
