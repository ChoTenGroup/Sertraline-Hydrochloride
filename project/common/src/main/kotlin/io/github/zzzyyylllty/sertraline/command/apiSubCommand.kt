package io.github.zzzyyylllty.sertraline.command

import io.github.zzzyyylllty.sertraline.Sertraline
import io.github.zzzyyylllty.sertraline.logger.infoS
import io.github.zzzyyylllty.sertraline.data.defaultData
import io.github.zzzyyylllty.sertraline.function.kether.runKether
import io.github.zzzyyylllty.sertraline.util.GraalJsUtil
import io.github.zzzyyylllty.sertraline.util.ScriptHelper
import net.kyori.adventure.text.minimessage.MiniMessage
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

    /** Parse MiniMessage */
    @CommandBody
    val minimessage = subCommand {
        dynamic("content") {
            execute<CommandSender> { sender, context, argument ->
                submitAsync {
                    val mm = MiniMessage.miniMessage()
                    // Retrieve the value of the argument
                    val content = context["content"]
                    sender.infoS(content) }
            }
        }
    }

    /** Kether */
    @CommandBody
    val eval = subCommand {
        dynamic("script") {
            execute<CommandSender> { sender, context, argument ->
                submitAsync {
                    val mm = MiniMessage.miniMessage()
                    // Retrieve the value of the argument
                    val content = context["script"]
                    val ret = runKether(listOf(content), sender)
                    sender.infoS("<yellow>Kether: <gray>$content")
                    sender.infoS("<yellow>Return: <gray>${ret.get()}") }
            }
        }
    }

    /** Kether */
    @CommandBody
    val evalByPlayer = subCommand {
        player("player") {
            dynamic("script") {
                execute<CommandSender> { sender, context, argument ->
                    submitAsync {
                        val tabooPlayer = context.player("player")
                        val bukkitPlayer = tabooPlayer.castSafely<Player>() as CommandSender
                        val mm = MiniMessage.miniMessage()
                        // Retrieve the value of the argument
                        val content = context["script"]
                        val ret = runKether(listOf(content), bukkitPlayer)
                        sender.infoS("<yellow>Kether: <gray>$content")
                        sender.infoS("<yellow>Return: <gray>${ret.get()}") }
                }
            }
        }
    }
    /**
     * JavaScript（JSR 223 / Nashorn 路径）。
     *
     * 与 `eval $` 的区别：这里**不需要** `$` 前缀，整段参数就是 JS 源码，
     * 适合在控制台/RCON 里直接调试 API。
     *
     * 走 [ScriptHelper] 而不是 TabooLib 的 `compileJS`：后者用的是 TabooLib 自己的引擎，
     * 其类加载器看不到插件类，`Java.type('io.github.zzzyyylllty.sertraline...')` 会直接
     * `ClassNotFoundException`；而 [GraalJsUtil.nashornEngineHolder] 是用插件类加载器建引擎的。
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
     * GraalJS（`GraalJsUtil`，ES2023，性能更好）。
     *
     * 同样不需要 `$` 前缀；注入了与脚本系统一致的默认变量。
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
     * 这两个 eval 子命令都跑在 `submitAsync` 的异步线程上，而该线程的上下文类加载器是
     * 服务端/paperclip 的，不是插件类加载器。`Java.type` 与 Nashorn 的类解析都依赖它，
     * 不切换就会得到 `ClassNotFoundException` / `Access to host class ... not allowed`。
     * GraalJS 的白名单本身是允许 `io.github.zzzyyylllty.sertraline.*` 的，缺的正是这里。
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
            execute<CommandSender> { sender, context, argument ->
                submitAsync {
                    val mm = MiniMessage.miniMessage()
                    // Retrieve the value of the argument
                    val content = context["script"]
                    runKether(listOf(content), sender) }
            }
        }
    }

    /** Kether */
    @CommandBody
    val evalByPlayerSilent = subCommand {
        player("player") {
            dynamic("script") {
                execute<CommandSender> { sender, context, argument ->
                    submitAsync {
                        val tabooPlayer = context.player("player")
                        val bukkitPlayer = tabooPlayer.castSafely<Player>() as CommandSender
                        val mm = MiniMessage.miniMessage()
                        // Retrieve the value of the argument
                        val content = context["script"]
                        runKether(listOf(content), bukkitPlayer)
                    }
                }
            }
        }
    }


}
