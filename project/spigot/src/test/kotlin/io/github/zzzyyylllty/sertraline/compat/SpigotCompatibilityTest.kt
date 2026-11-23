package io.github.zzzyyylllty.sertraline.compat

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.md_5.bungee.api.ChatColor
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitScheduler
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.UUID

class SpigotCompatibilityTest {
    @Test
    fun namedColorsSurviveComponentConversion() {
        for (color in NamedTextColor.NAMES.values()) {
            val converted = BungeeComponentConverter.convert(Component.text("message", color))
            val expected = ChatColor.valueOf(NamedTextColor.NAMES.key(color)!!.uppercase(java.util.Locale.ROOT))
            assertEquals(expected, converted.colorRaw)
        }
    }

    @Test
    fun hexColorsArePreservedOrDowngradedOnLegacyChat() {
        val color = TextColor.color(0xfefefe)
        val converted = BungeeComponentConverter.convert(Component.text("message", color))
        val factory = ChatColor::class.java.methods.firstOrNull {
            it.name == "of" && it.parameterTypes.contentEquals(arrayOf(String::class.java))
        }
        val expected = factory?.invoke(null, color.asHexString()) ?: ChatColor.WHITE
        assertEquals(expected, converted.colorRaw)
    }

    @Test
    fun smithingChecksEveryInputAndExcludesTheResultInBothLayouts() {
        val template = ItemStack(Material.PAPER)
        val equipment = ItemStack(Material.DIAMOND_CHESTPLATE)
        val addition = ItemStack(Material.DIAMOND)
        val result = ItemStack(Material.GOLD_INGOT)
        fun inputs(vararg contents: ItemStack?): List<ItemStack> {
            val inventory = proxy(Inventory::class.java) { method, _ ->
                if (method == "getContents") contents else null
            }
            return PlatformCompat.getSmithingInputs(inventory)
        }
        assertEquals(listOf(equipment, addition), inputs(equipment, addition, result))
        assertEquals(listOf(template, equipment, addition), inputs(template, equipment, addition, result))
        assertEquals(listOf(addition), inputs(null, null, addition, result))
        assertTrue(inputs(null, null, null, result).isEmpty())
        assertTrue(PlatformCompat.getSmithingInputs(null).isEmpty())
    }

    @Test
    fun armorPollingHandlesEmptySlotsAndDetectsEquipAndUnequip() {
        var task: Runnable? = null
        val armor = arrayOfNulls<ItemStack>(4)
        val inventory = proxy(PlayerInventory::class.java) { method, _ ->
            if (method == "getArmorContents") armor else null
        }
        val id = UUID.randomUUID()
        val player = proxy(Player::class.java) { method, _ ->
            when (method) {
                "getInventory" -> inventory
                "getUniqueId" -> id
                else -> null
            }
        }
        val scheduler = proxy(BukkitScheduler::class.java) { method, args ->
            if (method == "runTaskTimer") task = args!![1] as Runnable
            null
        }
        val server = proxy(Server::class.java) { method, _ ->
            when (method) {
                "getScheduler" -> scheduler
                "getOnlinePlayers" -> listOf(player)
                "getPlayer" -> player
                else -> null
            }
        }
        val plugin = proxy(Plugin::class.java) { method, _ ->
            if (method == "getServer") server else null
        }
        var changes = 0
        PlatformCompat.registerArmorPolling(plugin, true) { changes++ }
        val tick = requireNotNull(task)
        tick.run()
        tick.run()
        assertEquals(0, changes)
        // The mocked armor items have no item metadata.
        armor[0] = object : ItemStack(Material.DIAMOND_BOOTS) {
            override fun getItemMeta(): org.bukkit.inventory.meta.ItemMeta? = null
            override fun hasItemMeta(): Boolean = false
        }
        tick.run()
        assertEquals(1, changes)
        tick.run()
        assertEquals(1, changes)
        armor[0] = null
        tick.run()
        assertEquals(2, changes)
    }

    private fun <T> proxy(type: Class<T>, handler: (String, Array<out Any?>?) -> Any?): T =
        type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, args ->
            when (method.name) {
                "toString" -> type.simpleName
                "hashCode" -> System.identityHashCode(instance)
                "equals" -> instance === args!![0]
                else -> handler(method.name, args)
            }
        })
}
