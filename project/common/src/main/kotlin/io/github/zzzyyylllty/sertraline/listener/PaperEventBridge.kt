package io.github.zzzyyylllty.sertraline.listener

import io.github.zzzyyylllty.sertraline.compat.PaperEventHooks
import io.github.zzzyyylllty.sertraline.listener.sertraline.checkItemForBlock
import io.github.zzzyyylllty.sertraline.listener.update.handleArmorSlotChanged
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * Paper-only event bridge.
 * On Paper, real listeners registered by PlatformCompat call these hooks; on Spigot they do not fire (or are simulated by polling).
 */
object PaperEventBridge {

    val hooks: PaperEventHooks = object : PaperEventHooks {

        override fun onArmorChange(player: Player) {
            // attribute refresh + armor item update (single entry point, shared with Spigot polling)
            handleArmorSlotChanged(player)
        }

        override fun onPlayerTrade(ingredients: List<ItemStack>): Boolean {
            return ingredients.any { checkItemForBlock(it, "disable-village-trade", isVanilla = true) }
        }
    }
}
