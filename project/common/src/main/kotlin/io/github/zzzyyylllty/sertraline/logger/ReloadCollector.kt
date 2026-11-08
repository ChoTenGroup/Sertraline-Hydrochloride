package io.github.zzzyyylllty.sertraline.logger

import io.github.zzzyyylllty.sertraline.Sertraline.console
import io.github.zzzyyylllty.sertraline.Sertraline.consoleSender
import org.bukkit.command.CommandSender
import taboolib.module.lang.asLangText

/**
 * reload 报告收集入口（兼容保留）。
 *
 * 实际数据归属当轮 [ReloadContext]：由 [ReloadCoordinator.tryBegin] 绑定、`end()` 解绑。
 * 未绑定 context 时调用是 no-op —— 无主日志（如启动期 warning）不应被计入任何一轮报告。
 */
object ReloadCollector {

    @Volatile
    private var current: ReloadContext? = null

    fun bind(context: ReloadContext) {
        current = context
    }

    fun unbind() {
        current = null
    }

    fun addError(message: String) {
        current?.errors?.add(message)
    }

    fun addWarning(message: String) {
        current?.warnings?.add(message)
    }

    fun addStat(message: String) {
        current?.stats?.add(message)
    }

    fun hasErrors() = current?.errors?.isNotEmpty() == true
    fun hasWarnings() = current?.warnings?.isNotEmpty() == true

    private fun printTo(context: ReloadContext, target: CommandSender) {
        target.sendStringAsComponent(console.asLangText("Reload_Summary_Header"))

        if (context.errors.isNotEmpty()) {
            target.sendStringAsComponent(console.asLangText("Reload_Summary_Section_Errors"))
            context.errors.forEachIndexed { index, msg ->
                target.sendStringAsComponent(
                    console.asLangText("Reload_Summary_Error_Item", index + 1, msg)
                )
            }
        }

        if (context.warnings.isNotEmpty()) {
            target.sendStringAsComponent(console.asLangText("Reload_Summary_Section_Warnings"))
            context.warnings.forEachIndexed { index, msg ->
                target.sendStringAsComponent(
                    console.asLangText("Reload_Summary_Warn_Item", index + 1, msg)
                )
            }
        }

        target.sendStringAsComponent(console.asLangText("Reload_Summary_Separator"))

        for (stat in context.stats) {
            target.sendStringAsComponent(stat)
        }

        target.sendStringAsComponent(console.asLangText("Reload_Summary_Footer"))
    }

    /**
     * 输出本轮 summary。每轮 context 只打印一次（幂等）；
     * 未绑定 context 时为 no-op。
     */
    fun printSummary(sender: CommandSender? = null) {
        val context = current ?: return
        if (context.summaryPrinted) return
        context.markSummaryPrinted()

        if (sender != null && sender != consoleSender) {
            printTo(context, sender)
        }
        printTo(context, consoleSender)
    }
}
