package io.github.zzzyyylllty.sertraline.listener.sertraline

import taboolib.common5.compileJS
import io.github.zzzyyylllty.sertraline.item.sertralineItemBuilder
import io.github.zzzyyylllty.sertraline.logger.severeS
import io.github.zzzyyylllty.sertraline.logger.warningS
import io.github.zzzyyylllty.sertraline.data.RecipeFunction
import io.github.zzzyyylllty.sertraline.recipe.RecipeSourceKind
import io.github.zzzyyylllty.sertraline.recipe.resolveRecipeReference
import io.github.zzzyyylllty.sertraline.util.SertralineRecipeManager
import io.github.zzzyyylllty.sertraline.util.textLines
import org.bukkit.Keyed
import org.bukkit.entity.Player
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.inventory.PrepareItemCraftEvent
import taboolib.common.platform.event.EventPriority
import taboolib.common.platform.event.SubscribeEvent
import org.bukkit.Bukkit
import taboolib.common.platform.function.adaptCommandSender
import taboolib.module.kether.KetherShell
import taboolib.module.kether.ScriptOptions

/**
 * Recipe crafting event listener.
 * Responsibilities:
 * 1. PrepareItemCraftEvent — instantly rebuilds the Sertraline item result (with player context)
 * 2. CraftItemEvent — executes the result functions after crafting (kether / JS / command)
 */

private const val NAMESPACE = "sertraline"

// HIGH, not HIGHEST: `itemRestrictionListener.onCommonPrepareCraft` runs at HIGHEST and sets
// `inventory.result = null` to block a restricted craft. With both at HIGHEST the winner depended on
// TabooLib's registration order, so a blocked recipe could still show (and preview) a crafted
// Sertraline item. Rebuild first here; let the restriction listener run last and win.
@SubscribeEvent(priority = EventPriority.HIGH)
fun onPrepareCraft(event: PrepareItemCraftEvent) {
    val recipe = event.recipe ?: return
    if (recipe !is Keyed) return
    if (recipe.key.namespace != NAMESPACE) return
    val recipeData = SertralineRecipeManager.getRecipeData(recipe.key) ?: return

    val player = event.view.player as? Player ?: return

    // Only rebuild the Sertraline item result (with player variables).
    // 走共享解析器：裸名会先按原版材质解析，解析不到才回落 Sertraline，
    // 与配方注册路径保持完全一致的判定。
    val ref = resolveRecipeReference(recipeData.result.itemId) ?: return
    if (ref.kind != RecipeSourceKind.SERTRALINE) return

    val rebuilt = sertralineItemBuilder(ref.id, player)
        ?.apply { amount = recipeData.result.count }
        ?: return

    event.inventory.result = rebuilt
}

@SubscribeEvent(priority = EventPriority.MONITOR, ignoreCancelled = true)
fun onCraftItem(event: CraftItemEvent) {
    val recipe = event.recipe ?: return
    if (recipe !is Keyed) return
    if (recipe.key.namespace != NAMESPACE) return
    val recipeData = SertralineRecipeManager.getRecipeData(recipe.key) ?: return

    if (recipeData.result.functions.isEmpty()) return
    val player = event.whoClicked as? Player ?: return

    recipeData.result.functions.forEach { func ->
        runCatching {
            executeFunction(func, player)
        }.onFailure { e ->
            severeS("Failed to execute recipe function for ${recipeData.id}: ${e.message}")
        }
    }
}

private fun executeFunction(func: RecipeFunction, player: Player) {
    when (func) {
        is RecipeFunction.Kether -> {
            // textLines: Java 8 safe implementation (String.lines is a Java 11 member method, see StringUtil.kt)
            val script = func.script.textLines()
            if (script.isNotEmpty()) {
                KetherShell.eval(script, ScriptOptions(sender = adaptCommandSender(player)))
            }
        }
        is RecipeFunction.JavaScript -> {
            func.script.compileJS()?.eval()
                ?: warningS("Failed to compile JS for recipe")
        }
        is RecipeFunction.Command -> {
            Bukkit.dispatchCommand(
                Bukkit.getConsoleSender(),
                func.command.replace("%player%", player.name)
            )
        }
    }
}


