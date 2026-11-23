package io.github.zzzyyylllty.sertraline.config

import io.github.zzzyyylllty.sertraline.data.AbstractSItem
import io.github.zzzyyylllty.sertraline.event.ItemLoadEvent
import io.github.zzzyyylllty.sertraline.listener.sertraline.itemModuleSave
import org.bukkit.Bukkit
import org.bukkit.Server
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test
import java.lang.reflect.Proxy

class CachedWorkspaceItemTest {
    @Test
    fun itemSaveCopiesReadOnlyArgumentsForEachReload() {
        @Suppress("UNCHECKED_CAST")
        val arguments = TemplateManager.freeze(linkedMapOf(
            "xbuilder" to linkedMapOf("material" to "STONE", "lore" to listOf("original")),
            "custom_data" to mapOf("id" to 1),
        )) as Map<String, Any?>

        val serverField = Bukkit::class.java.getDeclaredField("server").also { it.isAccessible = true }
        val previous = serverField.get(null)
        val server = Proxy.newProxyInstance(Server::class.java.classLoader, arrayOf(Server::class.java)) { _, method, _ ->
            check(method.name == "isPrimaryThread")
            true
        }
        serverField.set(null, server)
        try {
            val firstItem = load(arguments)
            val first = firstItem.config
            assertEquals(arguments.keys.toList(), first.keys.toList())
            @Suppress("UNCHECKED_CAST")
            val lore = (first["xbuilder"] as Map<*, *>)["lore"] as MutableList<String>
            lore += "added to runtime config"
            first.remove("custom_data")
            @Suppress("UNCHECKED_CAST")
            val customData = firstItem.data["custom_data"] as MutableMap<String, Any?>
            customData["id"] = 2

            val secondItem = load(arguments)
            val second = secondItem.config
            assertNotSame(first, second)
            assertEquals(arguments, second)
            assertEquals(listOf("original"), (second["xbuilder"] as Map<*, *>)["lore"])
            assertEquals(mapOf("id" to 1), secondItem.data["custom_data"])
        } finally {
            serverField.set(null, previous)
        }
    }

    private fun load(arguments: Map<String, Any?>): AbstractSItem {
        val context = LoadContext()
        val data = linkedMapOf("xbuilder:material" to "STONE", "custom_data" to arguments["custom_data"])
        itemModuleSave(ItemLoadEvent("cached_item", arguments, data, context))
        return context.items.getValue("cached_item")
    }
}
