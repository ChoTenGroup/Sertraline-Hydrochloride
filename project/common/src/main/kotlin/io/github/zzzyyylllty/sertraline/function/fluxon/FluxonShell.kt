package io.github.zzzyyylllty.sertraline.function.fluxon

import com.github.benmanes.caffeine.cache.Caffeine
import org.tabooproject.fluxon.Fluxon
import org.tabooproject.fluxon.interpreter.ReturnValue
import org.tabooproject.fluxon.parser.error.ParseException
import org.tabooproject.fluxon.parser.ParsedScript
import org.tabooproject.fluxon.runtime.Environment
import org.tabooproject.fluxon.runtime.FluxonRuntime
import org.tabooproject.fluxon.runtime.error.FluxonRuntimeError
import taboolib.common.platform.function.warning
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.TimeUnit

object FluxonShell {

    // Lazy AND typed as ConcurrentMap (not Caffeine's Cache): Caffeine is a runtime-injected, relocated
    // dependency available only at LifeCycle.INIT, while this object's <clinit> allocates the lazy lambda —
    // loading that lambda class makes the verifier resolve its return type, so a Caffeine-typed property
    // would throw NoClassDefFoundError at CONST. .asMap() keeps every <clinit> signature Caffeine-free.
    val scriptCache: ConcurrentMap<String, ParsedScript> by lazy {
        Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterAccess(1, TimeUnit.HOURS)
            .build<String, ParsedScript>()
            .asMap()
    }

    /**
     * Interprets the script but does not execute it
     */
    fun parse(script: String, env: Environment.() -> Unit = {}): ParseScript {
        return ParseScript(parse(script, FluxonRuntime.getInstance().newEnvironment().also(env)))
    }

    /**
     * Executes the script
     *
     * @param script      The script text
     * @param useCache    Whether to use the cache; disable it if the script changes frequently
     * @param env         The script execution environment
     */
    fun invoke(script: String, useCache: Boolean = true, env: Environment.() -> Unit = {}): Any? {
        val environment = FluxonRuntime.getInstance().newEnvironment().also(env)
        val parsed = if (useCache) {
            val cached = scriptCache[script]
            if (cached != null) cached else parse(script, environment)?.also { scriptCache[script] = it }
        } else {
            parse(script, environment)
        }
        if (parsed == null) return null
        return invoke(parsed, environment)
    }

    /**
     * Executes an already-parsed script
     *
     * @param parsed       The already-parsed script
     * @param environment  The script execution environment
     */
    fun invoke(parsed: ParsedScript, environment: Environment): Any? {
        return try {
            parsed.eval(environment)
        } catch (ex: ReturnValue) {
            ex.value
        } catch (ex: FluxonRuntimeError) {
            ex.printStackTrace()
            null
        }
    }

    fun parse(script: String, environment: Environment): ParsedScript? {
        return try {
            Fluxon.parse(script.removePrefix(";"), environment)
        } catch (ex: ParseException) {
            warning("Error in parsing FLUXON script: $script :\n${ex.formatDiagnostic()}")
            ex.printStackTrace()
            null
        }
    }
}