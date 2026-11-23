package io.github.zzzyyylllty.sertraline.command

import io.github.zzzyyylllty.sertraline.Sertraline
import io.github.zzzyyylllty.sertraline.logger.infoS
import io.github.zzzyyylllty.sertraline.data.defaultData
import io.github.zzzyyylllty.sertraline.function.kether.runKether
import io.github.zzzyyylllty.sertraline.util.GraalJsUtil
import io.github.zzzyyylllty.sertraline.util.ScriptHelper
import io.github.zzzyyylllty.sertraline.compat.PlatformCompat
import io.github.zzzyyylllty.sertraline.util.minimessage.mmUtil
import taboolib.common.platform.function.submit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import taboolib.common.platform.command.CommandBody
import taboolib.common.platform.command.CommandHeader
import taboolib.common.platform.command.PermissionDefault
import taboolib.common.platform.command.mainCommand
import taboolib.common.platform.command.player
import taboolib.common.platform.command.subCommand
import taboolib.common.platform.function.submitAsync

@CommandHeader(
    name = "sertralineapi",
    aliases = ["itemapi","needyitemapi","depazapi"],
    permission = "sertraline.command.api",
    description = "api Command of DepazItems.",
    permissionMessage = "",
    permissionDefault = PermissionDefault.OP,
    newParser = false,
)
object ApiCommand {

    @CommandBody
    val main = mainCommand {
        createModernHelper()
    }

    @CommandBody
    val help = subCommand {
        createModernHelper()
    }

    /** Parse MiniMessage. */
    @CommandBody
    val minimessage = subCommand {
        dynamic("content") {
            execute<CommandSender> { sender, context, _ ->
                PlatformCompat.sendComponent(sender, mmUtil.deserialize(context["content"]))
            }
        }
    }

    @CommandBody
    val eval = subCommand {
        dynamic("script") {
            execute<CommandSender> { sender, context, _ ->
                evaluateKether(sender, sender, context["script"])
            }
        }
    }

    @CommandBody
    val evalByPlayer = subCommand {
        player("player") {
            dynamic("script") {
                execute<CommandSender> { sender, context, _ ->
                    val player = context.player("player").castSafely<Player>() ?: return@execute
                    evaluateKether(sender, player, context["script"])
                }
            }
        }
    }

    private fun evaluateKether(sender: CommandSender, executor: CommandSender, script: String, silent: Boolean = false) {
        runKether(listOf(script), executor).whenComplete { result, error ->
            if (!silent || error != null) submit {
                if (error != null) sender.infoS("<red>Kether failed: ${error.message}")
                else sender.infoS("<yellow>Return: <gray>$result")
            }
        }
    }

    /**
     * JavaScript (JSR 223 / Nashorn path).
     *
     * Unlike `eval $`, this command **does not require** the `$` prefix; the entire argument is JavaScript source,
     * making it suitable for debugging the API directly from the console or RCON.
     *
     * It uses [ScriptHelper] instead of TabooLib's `compileJS`. The latter uses TabooLib's own engine,
     * whose class loader cannot see plugin classes, so `Java.type('io.github.zzzyyylllty.sertraline...')` throws
     * `ClassNotFoundException`. [GraalJsUtil.nashornEngineHolder] creates the engine with the plugin class loader.
     */
    @CommandBody
    val evalJS = subCommand {
        dynamic("script") {
            execute<CommandSender> { sender, context, _ ->
                submitAsync {
                    val content = context["script"]
                    val ret = withPluginClassLoader {
                        runCatching {
                            ScriptHelper.executeScript(
                                ScriptHelper.ScriptType.JAVASCRIPT, content, emptyMap(), sender
                            )
                        }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }
                    }
                    sender.infoS("<yellow>JS: <gray>$content")
                    sender.infoS("<yellow>Return: <gray>$ret")
                }
            }
        }
    }

    /**
     * GraalJS (`GraalJsUtil`, ES2023, with better performance).
     *
     * This command also does not require the `$` prefix and receives the same default variables as the script system.
     */
    @CommandBody
    val evalGraalJs = subCommand {
        dynamic("script") {
            execute<CommandSender> { sender, context, _ ->
                submitAsync {
                    val content = context["script"]
                    val ret = withPluginClassLoader {
                        runCatching { GraalJsUtil.cachedEval(content, LinkedHashMap(defaultData)) }
                            .getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }
                    }
                    sender.infoS("<yellow>GraalJS: <gray>$content")
                    sender.infoS("<yellow>Return: <gray>$ret")
                }
            }
        }
    }

    /**
     * Both eval subcommands run on the asynchronous `submitAsync` thread, whose context class loader belongs to
     * the server/paperclip rather than the plugin. `Java.type` and Nashorn class resolution depend on it;
     * without switching loaders, they fail with `ClassNotFoundException` or `Access to host class ... not allowed`.
     * GraalJS already allows `io.github.zzzyyylllty.sertraline.*`; switching the loader here is the missing step.
     */
    private inline fun <T> withPluginClassLoader(block: () -> T): T {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        return try {
            thread.contextClassLoader = Sertraline::class.java.classLoader
            block()
        } finally {
            thread.contextClassLoader = previous
        }
    }

    /** Kether */
    @CommandBody
    val evalSilent = subCommand {
        dynamic("script") {
            execute<CommandSender> { sender, context, _ ->
                evaluateKether(sender, sender, context["script"], silent = true)
            }
        }
    }

    /** Kether */
    @CommandBody
    val evalByPlayerSilent = subCommand {
        player("player") {
            dynamic("script") {
                execute<CommandSender> { sender, context, _ ->
                    val player = context.player("player").castSafely<Player>() ?: return@execute
                    evaluateKether(sender, player, context["script"], silent = true)
                }
            }
        }
    }


}
