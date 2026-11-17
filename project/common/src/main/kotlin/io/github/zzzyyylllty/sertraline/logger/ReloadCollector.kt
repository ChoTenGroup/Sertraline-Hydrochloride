package io.github.zzzyyylllty.sertraline.logger

import io.github.zzzyyylllty.sertraline.Sertraline.console
import io.github.zzzyyylllty.sertraline.Sertraline.consoleSender
import org.bukkit.command.CommandSender
import taboolib.module.lang.asLangText

/**
 * Reload report collection entry point (retained for compatibility).
 *
 * The current [ReloadContext] owns the actual data; [ReloadCoordinator.tryBegin] binds it and `end()` unbinds it.
 * Calls are no-ops when no context is bound, so unowned logs (such as startup warnings) are not counted in any reload report.
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
     * Prints the current reload's summary once per context (idempotent); does nothing when no context is bound.
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
