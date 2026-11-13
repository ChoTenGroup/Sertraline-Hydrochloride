package io.github.zzzyyylllty.sertraline.attribute

import io.github.zzzyyylllty.sertraline.attribute.data.AttributeExpression
import io.github.zzzyyylllty.sertraline.data.defaultData
import io.github.zzzyyylllty.sertraline.logger.warningS
import io.github.zzzyyylllty.sertraline.util.GraalJsUtil
import io.github.zzzyyylllty.sertraline.util.ScriptHelper
import org.bukkit.entity.LivingEntity
import java.util.concurrent.ConcurrentHashMap

/** Shared script engines. */
object AttributeExpressions {
    private val tokens = Regex(""""(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|`(?:\\.|[^`\\])*`|//[^\r\n]*|/\*[\s\S]*?\*/|(?<![\w.$])[a-z0-9_]+(?:[.-][a-z0-9_]+)*:[a-z0-9_./-]+|(?<![\w.$])(?:MIN|MAX|ABS|FLOOR|CEIL|ROUND|SQRT|POW)\s*\(""")
    private val numericTernary = Regex("""\d+(?:\.\d+)?:\d+(?:\.\d+)?""")
    private val warned = ConcurrentHashMap.newKeySet<String>()

    private fun isReference(token: String): Boolean =
        token.first() !in "\"'`/" && ':' in token && !numericTernary.matches(token)

    fun references(expression: AttributeExpression): Set<String> =
        if (expression.script) emptySet() else tokens.findAll(expression.code)
            .map { it.value }.filter(::isReference).toSet()

    fun rewrite(expression: AttributeExpression): String {
        if (expression.script) return expression.code
        return tokens.replace(expression.code) {
            val token = it.value
            when {
                token.first() in "\"'`/" -> token
                isReference(token) -> "attrs.get(\"$token\")"
                token.endsWith('(') -> "Math.${token.dropLast(1).trim().lowercase()}("
                else -> token
            }
        }
    }

    fun eval(expression: AttributeExpression, entity: LivingEntity?, vars: Map<String, Any?>): Any? {
        val bindings = LinkedHashMap(defaultData)
        bindings.remove("Math")
        bindings.putAll(vars)
        bindings["entity"] = entity
        bindings["player"] = entity as? org.bukkit.entity.Player
        bindings["AttributeAPI"] = io.github.zzzyyylllty.sertraline.attribute.api.SertralineAttributeAPI
        val code = rewrite(expression)
        return try {
            when (expression.engine) {
                "graaljs", "gjs" -> GraalJsUtil.cachedEval(wrap(code, expression.script), bindings)
                "js", "javascript", "nashorn" -> {
                    val engine = GraalJsUtil.nashornEngineHolder.get() ?: error("JS unavailable")
                    engine.eval(wrap(code, expression.script), engine.createBindings().also { it.putAll(bindings) })
                }
                "script" -> {
                    require(ScriptHelper.getScriptType(expression.code) != ScriptHelper.ScriptType.KETHER) { "Attribute scripts must be synchronous" }
                    ScriptHelper.eval(expression.code, bindings, entity)
                }
                else -> error("Unknown engine: ${expression.engine}")
            }
        } catch (ex: Exception) {
            warn(expression, ex.message ?: "Evaluation failed")
            null
        }
    }

    fun number(expression: AttributeExpression, entity: LivingEntity?, vars: Map<String, Any?>, fallback: Double): Double {
        val result = eval(expression, entity, vars)
        val number = (result as? Number)?.toDouble() ?: result?.toString()?.toDoubleOrNull()
        if (number != null && number.isFinite()) return number
        warn(expression, "Expected finite number")
        return fallback
    }

    fun clearWarnings() = warned.clear()

    private fun wrap(code: String, script: Boolean) =
        if (script) "(function(){\n$code\n})()" else "(function(){return ($code);})()"

    private fun warn(expression: AttributeExpression, message: String) {
        if (warned.size < 256 && warned.add(expression.toString())) warningS("Attribute expression '${expression.code}': $message")
    }
}
