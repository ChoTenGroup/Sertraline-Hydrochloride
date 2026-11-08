package io.github.zzzyyylllty.sertraline.command

import io.github.zzzyyylllty.embiancomponent.EmbianComponent.SafetyComponentSetter
import io.github.zzzyyylllty.embiancomponent.tools.getComponentsNMSFiltered
import io.github.zzzyyylllty.sertraline.config.common.SertralineConfigManager
import io.github.zzzyyylllty.sertraline.config.common.SertralineSettings
import io.github.zzzyyylllty.sertraline.Sertraline.craftingStations
import io.github.zzzyyylllty.sertraline.Sertraline.itemCache
import io.github.zzzyyylllty.sertraline.Sertraline.itemManager
import io.github.zzzyyylllty.sertraline.Sertraline.itemMap
import io.github.zzzyyylllty.sertraline.Sertraline.mappings
import io.github.zzzyyylllty.sertraline.Sertraline.tagManager
import io.github.zzzyyylllty.sertraline.compat.PlatformCompat
import io.github.zzzyyylllty.sertraline.function.update.ItemUpdateScheduler
import io.github.zzzyyylllty.sertraline.function.update.resetRevisionAutoTracker
import io.github.zzzyyylllty.sertraline.item.rebuild
import io.github.zzzyyylllty.sertraline.item.rebuildLore
import io.github.zzzyyylllty.sertraline.item.sertralineItemBuilder
import io.github.zzzyyylllty.sertraline.logger.sendStringAsComponent
import io.github.zzzyyylllty.sertraline.logger.warningS
import io.github.zzzyyylllty.sertraline.impl.getComponent
import io.github.zzzyyylllty.sertraline.impl.getComponentsFilteredNMS
import io.github.zzzyyylllty.sertraline.impl.getComponentsNMS
import io.github.zzzyyylllty.sertraline.util.ComponentFormatter
import io.github.zzzyyylllty.sertraline.util.ItemTagManager
import io.github.zzzyyylllty.sertraline.util.ItemTagUtil.parseNBT
import io.github.zzzyyylllty.sertraline.attribute.AttributeManager
import io.github.zzzyyylllty.sertraline.item.rebuildDisplay
import io.github.zzzyyylllty.sertraline.item.rebuildName
import io.github.zzzyyylllty.sertraline.util.minimessage.mmUtil
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import taboolib.common.PrimitiveSettings
import taboolib.common.TabooLib
import taboolib.common.io.taboolibId
import taboolib.common.platform.command.CommandBody
import taboolib.common.platform.command.CommandHeader
import taboolib.common.platform.command.PermissionDefault
import taboolib.common.platform.command.bool
import taboolib.common.platform.command.mainCommand
import taboolib.common.platform.command.player
import taboolib.common.platform.command.subCommand
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.submitAsync
import taboolib.common.util.asList
import taboolib.module.configuration.Configuration
import taboolib.module.configuration.Type
import taboolib.module.nms.NMSItemTag.Companion.asNMSCopy
import taboolib.module.nms.getItemTag
import taboolib.platform.util.asLangText
import taboolib.platform.util.giveItem
import taboolib.platform.util.isAir
import java.io.File

@CommandHeader(
    name = "sertralinedebug",
    aliases = ["itemdebug","needyitemdebug","depazdebug"],
    permission = "sertraline.command.debug",
    description = "DEBUG Command of DepazItems.",
    permissionMessage = "",
    permissionDefault = PermissionDefault.OP,
    newParser = false,
)
object DebugCommand {

    @CommandBody
    val main = mainCommand {
        createModernHelper()
    }

    @CommandBody
    val help = subCommand {
        createModernHelper()
    }

    @CommandBody
    val testTags = subCommand {
        execute<CommandSender> { sender, context, argument ->
            // Register a custom tag
            ItemTagManager.registerCustomTag("myplugin:special_items", listOf("minecraft:diamond_sword", "minecraft:netherite_sword"))

            // Add a custom tag to the item
            ItemTagManager.addItemToCustomTag("minecraft:golden_apple", "myplugin:food_items")

            // Get all items with a specific tag
            val axes = ItemTagManager.getItemsByTag("minecraft:axes")
            sender.sendStringAsComponent("Axes: $axes")

            val specialItems = ItemTagManager.getItemsByTag("myplugin:special_items")
            sender.sendStringAsComponent("Special items: $specialItems")

            // Check whether the item has a tag
            val hasTag = ItemTagManager.hasItemTag("minecraft:diamond_sword", "minecraft:axes")
            sender.sendStringAsComponent("Diamond sword is an axe: $hasTag")
        }
    }
    @CommandBody
    val getMappings = subCommand {
        execute<CommandSender> { sender, context, argument ->
            var message = mappings.toString()
            sender.sendStringAsComponent(message)
        }
    }
    @CommandBody
    val getTBVersion = subCommand {
        execute<CommandSender> { sender, context, argument ->
            var message = PrimitiveSettings.TABOOLIB_VERSION
            sender.sendStringAsComponent(message)
        }
    }
    @CommandBody
    val getItems = subCommand {
        execute<CommandSender> { sender, context, argument ->
            var message = itemMap.toString()
            sender.sendStringAsComponent(message)
        }
    }
    @CommandBody
    val getItemCaches = subCommand {
        execute<CommandSender> { sender, context, argument ->
            var message = itemCache.toString()
            sender.sendStringAsComponent(message)
        }
    }

    @CommandBody
    val getItem = subCommand {
        dynamic("id") {
            execute<CommandSender> { sender, context, argument ->
                val id = context["id"]
                val message = itemMap[id].toString()
                sender.sendStringAsComponent(message)
            }
            suggestion<CommandSender>(uncheck = true) { sender, context ->
                itemMap.keys.asList()
            }
        }
    }

    @CommandBody
    val getItemIDs = subCommand {
        execute<CommandSender> { sender, context, argument ->
            val message = itemMap.keys.toString()
            sender.sendStringAsComponent(message)
        }

    }

    @CommandBody
    val getStations = subCommand {
        execute<CommandSender> { sender, context, argument ->
            val message = craftingStations.toString()
            sender.sendStringAsComponent(message)
        }

    }
    @CommandBody
    val getConfig = subCommand {
        execute<CommandSender> { sender, context, argument ->
            sender.sendStringAsComponent(SertralineConfigManager.main.config.toString())
        }
    }
    @CommandBody
    val giveTestItem = subCommand {
        execute<CommandSender> { sender, context, argument ->
            val player = sender as? Player ?: run {
                warningS("/sertralinedebug give-test-item must be run by a player")
                return@execute
            }
            // sertralineItemBuilder is nullable; `giveItem(null)` was an NPE on an item id that
            // was renamed or removed from the workspace.
            val built = sertralineItemBuilder("depaz_pills", player) ?: run {
                warningS("Test item 'depaz_pills' could not be built (is it still in the workspace?)")
                return@execute
            }
            player.giveItem(built)
        }
    }
    @CommandBody
    val generateItemNameFile = subCommand {
        execute<CommandSender> { sender, context, argument ->
            val nameConf = Configuration.empty(Type.YAML)
            itemMap.forEach {
                val name = it.value.getDeepData("xbuilder:name")
                    ?: it.value.getDeepData("minecraft:item_name")
                    ?: it.value.getDeepData("minecraft:custom_name")
                    ?: it.value.getDeepData("visual:item_name")
                    ?: it.value.getDeepData("visual:custom_name")
                    ?: "No name provided."
                nameConf[it.key] = name
            }
            nameConf.saveToFile(File(getDataFolder(), "itemoutputnames.yml"))
            sender.sendMessage("saved all item names to itemoutputnames.yml")
        }
    }


    @CommandBody
    val generateItemTierFile = subCommand {
        execute<CommandSender> { sender, context, argument ->
            val nameConf = Configuration.empty(Type.YAML)
            itemMap.forEach {
                val name = it.value.getDeepData("sertraline:tier")
                    ?: "No tier provided."
                nameConf[it.key] = name
            }
            nameConf.saveToFile(File(getDataFolder(), "itemoutputtiers.yml"))
            sender.sendMessage("saved all item names to itemoutputtiers.yml")
        }
    }
    @CommandBody
    val processors = subCommand {
        execute<CommandSender> { sender, context, argument ->
            sender.sendStringAsComponent(itemManager.listProcessors().toString())
        }
    }
    @CommandBody
    val tagProcessors = subCommand {
        execute<CommandSender> { sender, context, argument ->
            sender.sendStringAsComponent(tagManager.listProcessors().toString())
        }
    }
    @CommandBody
    val rebuild = subCommand {
        execute<CommandSender> { sender, context, argument ->
            // Commands already run on the main thread. The old `submitAsync` moved the inventory
            // write off-thread, and `sender as Player` threw ClassCastException for the console.
            val player = sender as? Player ?: run {
                warningS("/sertralinedebug rebuild must be run by a player")
                return@execute
            }
            val inv = player.inventory
            val hand = inv.itemInMainHand.rebuild(player)
            inv.setItemInMainHand(hand)
        }
    }

    @CommandBody
    val rebuildLore = subCommand {
        execute<CommandSender> { sender, context, argument ->
            val player = sender as? Player ?: run {
                warningS("/sertralinedebug rebuild-lore must be run by a player")
                return@execute
            }
            player.inventory.itemInMainHand.rebuildLore(player)
        }
    }


    @CommandBody
    val rebuildName = subCommand {
        execute<CommandSender> { sender, context, argument ->
            val player = sender as? Player ?: run {
                warningS("/sertralinedebug rebuild-name must be run by a player")
                return@execute
            }
            player.inventory.itemInMainHand.rebuildName(player)
        }
    }


    @CommandBody
    val rebuildDisplay = subCommand {
        execute<CommandSender> { sender, context, argument ->
            val player = sender as? Player ?: run {
                warningS("/sertralinedebug rebuild-display must be run by a player")
                return@execute
            }
            player.inventory.itemInMainHand.rebuildDisplay(player)
        }
    }

    @CommandBody
    val refreshAttributes = subCommand {
        execute<CommandSender> { sender, context, argument ->
            // `sender as Player` threw ClassCastException from the console; refreshAttributes is
            // also a main-thread-only inventory read.
            val player = sender as? Player ?: run {
                warningS("/sertralinedebug refresh-attributes must be run by a player")
                return@execute
            }
            AttributeManager.refreshAttributes(player)
        }
    }

    /**
     * 手动触发物品更新扫描：`/sertralinedebug update` 按 revision 检查，
     * `/sertralinedebug update true` 无视 revision 强制重建一遍（用于 tracker 丢失、
     * 或引用的 tier/type/level/lore-format 文件变更这类不会改变物品哈希的情况）。
     */
    @CommandBody
    val update = subCommand {
        execute<CommandSender> { sender, context, argument ->
            if (!itemUpdateUsable(sender)) return@execute
            ItemUpdateScheduler.scanOnlinePlayersLater()
            sender.sendStringAsComponent("<green>Item update scan queued for online players.</green>")
        }
        bool("force") {
            execute<CommandSender> { sender, context, argument ->
                if (!itemUpdateUsable(sender)) return@execute
                ItemUpdateScheduler.scanOnlinePlayersLater(force = true)
                sender.sendStringAsComponent("<green>Forced item rebuild queued for online players.</green>")
            }
            suggestion<CommandSender>(uncheck = true) { _, _ ->
                listOf("true", "false")
            }
        }
    }

    /**
     * 重置自动追踪：删除 revision/auto-track.json 并清空期望版本表。
     * 下一次 reload 会重新建立追踪（现有物品被当作新物品，从 1 开始），
     * 版本号高于 1 的物品会在刷新时按 `!=` 判定重建一次。
     */
    @CommandBody
    val resetRevision = subCommand {
        execute<CommandSender> { sender, context, argument ->
            resetRevisionAutoTracker()
            sender.sendStringAsComponent("<yellow>Revision tracker reset. It is rebuilt on the next reload; use '/sertralinedebug update true' for a full rebuild now.</yellow>")
        }
    }

    /** 物品更新系统关闭时给出明确反馈，而不是静默什么都不做。 */
    private fun itemUpdateUsable(sender: CommandSender): Boolean {
        if (SertralineSettings.itemUpdateEnabled) return true
        sender.sendStringAsComponent("<red>item-update.enabled is false - the item update system is disabled.</red>")
        return false
    }

    /**
     * 堆叠诊断：把手持物品与「现场重新生成的同款物品」以及「副手物品」对比，
     * 逐键列出不同的 NBT 路径与组件，直接回答「为什么这两件物品不能堆叠」。
     */
    @CommandBody
    val stackDiff = subCommand {
        execute<CommandSender> { sender, context, argument ->
            val player = sender as? Player
            if (player == null) {
                sender.sendStringAsComponent("<red>Player only.</red>")
                return@execute
            }
            val held = player.inventory.itemInMainHand
            if (held.isAir || held.type == Material.AIR) {
                sender.sendStringAsComponent("<red>先把要检查的物品拿在主手。</red>")
                return@execute
            }
            val heldTag = held.getItemTag(true)
            val id = heldTag["sertraline_id"]?.asString()
            if (id == null) {
                sender.sendStringAsComponent("<red>主手物品没有 sertraline_id。</red>")
                return@execute
            }
            sender.sendStringAsComponent("<yellow>=== 主手 '$id' (rev ${heldTag["sertraline_revision"]?.asInt() ?: 0}) ===")
            val fresh = sertralineItemBuilder(id, player)
            if (fresh == null) {
                sender.sendStringAsComponent("<red>无法重新生成 '$id'（模板不存在？）。</red>")
            } else {
                sender.sendStringAsComponent("<yellow>--- 对比：现场重新生成的同款物品 (rev ${fresh.getItemTag(true)["sertraline_revision"]?.asInt() ?: 0}) ---")
                compareForStack(sender, held, fresh)
            }
            val off = player.inventory.itemInOffHand
            if (!off.isAir && off.getItemTag(true)["sertraline_id"]?.asString() == id) {
                sender.sendStringAsComponent("<yellow>--- 对比：副手物品 (rev ${off.getItemTag(true)["sertraline_revision"]?.asInt() ?: 0}) ---")
                compareForStack(sender, held, off)
            } else {
                sender.sendStringAsComponent("<gray>（副手没有同 id 物品；把另一件放在副手可以对比两件实际物品）</gray>")
            }
        }
    }

    private fun compareForStack(sender: CommandSender, a: ItemStack, b: ItemStack) {
        sender.sendStringAsComponent("  isSimilar(原版堆叠判定) = <white>${a.isSimilar(b)}</white>  amount: ${a.amount} / ${b.amount}")
        reportDiff(sender, "NBT", dumpTagPaths(a), dumpTagPaths(b))
        reportDiff(sender, "组件", dumpComponents(a), dumpComponents(b))
    }

    private fun reportDiff(sender: CommandSender, kind: String, a: Map<String, String>, b: Map<String, String>) {
        val onlyA = a.keys - b.keys
        val onlyB = b.keys - a.keys
        val different = (a.keys intersect b.keys).filter { a[it] != b[it] }
        if (onlyA.isEmpty() && onlyB.isEmpty() && different.isEmpty()) {
            sender.sendStringAsComponent("  <green>$kind: 完全一致</green>")
            return
        }
        val limit = 15
        onlyA.take(limit).forEach { sender.sendStringAsComponent("  <gray>$kind 仅主手有:</gray> <white>$it</white> <dark_gray>= ${mmSafe(a[it])}</dark_gray>") }
        onlyB.take(limit).forEach { sender.sendStringAsComponent("  <gray>$kind 仅对方有:</gray> <white>$it</white> <dark_gray>= ${mmSafe(b[it])}</dark_gray>") }
        different.take(limit).forEach {
            sender.sendStringAsComponent("  <gray>$kind 值不同:</gray> <white>$it</white> <dark_gray>主手=${mmSafe(a[it])} | 对方=${mmSafe(b[it])}</dark_gray>")
        }
        val hidden = (onlyA.size - limit).coerceAtLeast(0) + (onlyB.size - limit).coerceAtLeast(0) + (different.size - limit).coerceAtLeast(0)
        if (hidden > 0) sender.sendStringAsComponent("  <dark_gray>… 还有 $hidden 项未显示</dark_gray>")
    }

    /** 把物品的 NBT 展开成 `路径 -> 值`，便于逐键对比（含 sertraline_data 这类嵌套 compound）。 */
    private fun dumpTagPaths(item: ItemStack): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val tag = runCatching { item.getItemTag(true) }.getOrNull() ?: return out
        tag.forEach { (key, value) ->
            flattenInto(out, key, runCatching { value.parseNBT() }.getOrNull())
        }
        return out
    }

    private fun flattenInto(out: MutableMap<String, String>, prefix: String, value: Any?) {
        if (value is Map<*, *>) {
            if (value.isEmpty()) out[prefix] = "{}"
            else value.forEach { (k, v) -> flattenInto(out, "$prefix.$k", v) }
        } else {
            out[prefix] = value?.toString() ?: "null"
        }
    }

    /** 组件的 `名称 -> JSON`（用 JSON 对比，避开 NMS 对象 toString 的噪声）。 */
    private fun dumpComponents(item: ItemStack): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val components = runCatching { item.getComponentsFilteredNMS() }.getOrNull() ?: return out
        components.keys.forEach { key ->
            val json = runCatching { item.getComponent(key)?.toString() }.getOrNull()
            val raw = runCatching { components[key]?.toString() }.getOrNull()
            out[key] = json ?: raw ?: "null"
        }
        return out
    }

    /** 发给玩家的字符串要转义 MiniMessage 的尖括号，否则物品 lore 里的标签会被解析掉。 */
    private fun mmSafe(value: String?): String {
        val v = value ?: return "null"
        val trimmed = if (v.length > 200) v.take(200) + "…" else v
        return trimmed.replace("\\", "\\\\").replace("<", "\\<").replace("\n", " ")
    }

    @CommandBody
    val dumpComponent = subCommand {
        execute<CommandSender> { sender, context, argument ->
            val player = sender as? Player ?: run {
                warningS("/sertralinedebug dump-component must be run by a player")
                return@execute
            }
            val inv = player.inventory
            val item = inv.itemInMainHand
            if (item.isAir || item.type == Material.AIR || item.amount <= 0) {
                sender.sendMessage("item cannot be null")
                return@execute
            }
            val components = asNMSCopy(item).getComponentsNMSFiltered()
            PlatformCompat.sendComponent(sender, ComponentFormatter.formatComponentMap(components))
        }
        bool("full") {
            execute<CommandSender> { sender, context, argument ->
                val player = sender as? Player ?: run {
                    warningS("/sertralinedebug dump-component must be run by a player")
                    return@execute
                }
                val inv = player.inventory
                val item = inv.itemInMainHand
                if (item.isAir || item.type == Material.AIR || item.amount <= 0) {
                    sender.sendMessage("item cannot be null")
                    return@execute
                }
                if (context.bool("full")) {
                    val components = asNMSCopy(item).getComponentsNMS()
                    PlatformCompat.sendComponent(sender, ComponentFormatter.formatComponentMap(components))
                } else {
                    val components = asNMSCopy(item).getComponentsNMSFiltered()
                    PlatformCompat.sendComponent(sender, ComponentFormatter.formatComponentMap(components))
                }
            }
            suggestion<CommandSender>(uncheck = true) { sender, context ->
                listOf("true", "false")
            }
        }
    }

    @CommandBody
    val testNewComponent = subCommand {
        execute<CommandSender> { sender, context, argument ->
            var item = ItemStack(Material.DIAMOND_CHESTPLATE)
            item = SafetyComponentSetter.setComponent(item,"minecraft:custom_data", mapOf<String, Any>("test" to "abc"))!!
            item = SafetyComponentSetter.removeComponent(item,"minecraft:attribute_modifiers")!! // 163
            sender.sendStringAsComponent("<yellow>Filtered: <gray>${SafetyComponentSetter.getAllComponentsFiltered(item)}")
            sender.sendStringAsComponent("<yellow>Original: <gray>${SafetyComponentSetter.getAllComponents(item)}")
            sender.sendStringAsComponent("<yellow>GetJava: <gray>${SafetyComponentSetter.getComponentJava<Map<*,*>>(item, "minecraft:custom_data")}")
            sender.sendStringAsComponent("<yellow>GetJson: <gray>${SafetyComponentSetter.getComponent(item, "minecraft:custom_data")}")
            val player = sender as? Player ?: run {
                warningS("/sertralinedebug test-new-component must be run by a player")
                return@execute
            }
            player.giveItem(item)
        }
    }
    @CommandBody
    val testTabooComponent = subCommand {
        execute<CommandSender> { sender, context, argument ->
            val player = sender as? Player ?: run {
                warningS("/sertralinedebug test-taboo-component must be run by a player")
                return@execute
            }
            val item = player.inventory.itemInMainHand
            sender.sendMessage("GetTag: ${item.getItemTag(true)}")
        }
    }
    @CommandBody
    val testTabooComponentAll = subCommand {
        execute<CommandSender> { sender, context, argument ->
            val player = sender as? Player ?: run {
                warningS("/sertralinedebug test-taboo-component-all must be run by a player")
                return@execute
            }
            val item = player.inventory.itemInMainHand
            sender.sendMessage("GetTag - NOT OnlyCustom: ${item.getItemTag(false)}")
        }
    }

    @CommandBody
    val buildDisplay = subCommand {
        dynamic("id") {
            execute<CommandSender> { sender, context, argument ->
                val id = context["id"]
                if (sender is Player) {
                    sertralineItemBuilder(
                        id,
                        sender
                    )?.let {
                        PlatformCompat.sendComponent(sender, PlatformCompat.getDisplayName(it))
                    } ?: sender.sendStringAsComponent("Item not exist.")
                } else {
                    sender.sendStringAsComponent("Must a player.")
                }
            }
            suggestion<CommandSender>(uncheck = true) { sender, context ->
                itemMap.keys.asList()
            }
            player("player") {
                execute<CommandSender> { sender, context, argument ->
                    val id = context["id"]
                    val tabooPlayer = context.player("player")
                    // Convert to a Bukkit Player
                    val bukkitPlayer = tabooPlayer.castSafely<Player>()
                    sertralineItemBuilder(
                        id,
                        bukkitPlayer
                    )?.let {
                        PlatformCompat.sendComponent(sender, PlatformCompat.getDisplayName(it))
                    } ?: sender.sendStringAsComponent("Item not exist.")
                }
            }
        }
    }

}