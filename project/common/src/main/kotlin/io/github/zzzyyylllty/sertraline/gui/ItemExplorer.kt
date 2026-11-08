package io.github.zzzyyylllty.sertraline.gui

import io.github.zzzyyylllty.sertraline.Sertraline
import io.github.zzzyyylllty.sertraline.Sertraline.console
import io.github.zzzyyylllty.sertraline.Sertraline.itemMap
import io.github.zzzyyylllty.sertraline.compat.PlatformCompat
import io.github.zzzyyylllty.sertraline.config.asListEnhanced
import io.github.zzzyyylllty.sertraline.data.Level
import io.github.zzzyyylllty.sertraline.data.AbstractSItem
import io.github.zzzyyylllty.sertraline.data.Tier
import io.github.zzzyyylllty.sertraline.data.Type
import io.github.zzzyyylllty.sertraline.data.TypeUtil
import io.github.zzzyyylllty.sertraline.item.sertralineItemBuilder
import io.github.zzzyyylllty.sertraline.logger.sendStringAsComponent
import io.github.zzzyyylllty.sertraline.logger.warningS
import io.github.zzzyyylllty.sertraline.util.gui.GuiItem
import io.github.zzzyyylllty.sertraline.util.gui.build
import io.github.zzzyyylllty.sertraline.util.minimessage.toComponent
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType.*
import org.bukkit.inventory.ItemStack
import taboolib.common.platform.function.submit
import taboolib.module.lang.asLangText
import taboolib.module.nms.getItemTag
import taboolib.module.nms.setItemTag
import taboolib.module.ui.openMenu
import taboolib.module.ui.type.Anvil
import taboolib.module.ui.type.Chest
import taboolib.module.ui.type.PageableChest
import taboolib.platform.util.giveItem
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.PatternSyntaxException

/**
 * Item explorer GUI: paginated item list with a per-player filter session
 * (type / tier / level), anvil-based keyword & regex search, and live
 * re-filtering on every page turn (elements{} is re-evaluated per build).
 */
class ItemExplorer {

    // region per-player session

    /**
     * Filter session of one player. Kept across menu opens until the player quits.
     */
    class Session {
        var typeId: String? = null
        var tierId: String? = null
        var levelId: String? = null
        var query: String? = null

        val hasActiveFilters: Boolean
            get() = typeId != null || tierId != null || levelId != null || !query.isNullOrBlank()

        fun clear() {
            typeId = null
            tierId = null
            levelId = null
            query = null
        }
    }

    companion object {
        private val sessions = ConcurrentHashMap<UUID, Session>()

        /**
         * Session of [player], creating it on demand.
         *
         * Sessions were only ever pruned when *another* player opened the explorer, so the map grew
         * once per player that ever opened the GUI and never shrank for players who did not come
         * back (an unbounded leak on long-lived servers). Prune offline entries here, on the most
         * frequent entry point.
         */
        fun session(player: Player): Session {
            pruneOfflineSessions()
            return sessions.computeIfAbsent(player.uniqueId) { Session() }
        }

        /** Drops sessions whose player is no longer online. Cheap; safe to call often. */
        fun pruneOfflineSessions() {
            if (sessions.isEmpty()) return
            sessions.keys.removeIf { Bukkit.getPlayer(it) == null }
        }

        /** Drops the session of [uuid] (called when the player quits). */
        fun forget(uuid: UUID) {
            sessions.remove(uuid)
        }
    }

    // endregion

    // region shared list logic
    /**
     * Display-name fallback chain (mirrors debugSubCommand.kt) for name search.
     */
    private fun rawDisplay(item: AbstractSItem): String? {
        val v = item.getDeepData("xbuilder:name")
            ?: item.getDeepData("minecraft:item_name")
            ?: item.getDeepData("minecraft:custom_name")
            ?: item.getDeepData("visual:item_name")
            ?: item.getDeepData("visual:custom_name")
        return when (v) {
            null -> null
            is String -> v
            else -> v.toString()
        }
    }

    private fun itemTierId(item: AbstractSItem): String? = when (val v = item.getDeepData("sertraline:tier")) {
        is Tier -> v.id
        is String -> v
        else -> null
    }

    private fun itemLevelId(item: AbstractSItem): String? = when (val v = item.getDeepData("sertraline:level")) {
        is Level -> v.id
        is String -> v
        else -> null
    }

    /**
     * Applies the session filters to the registry and sorts by key (case-insensitive).
     * Called inside elements{} so every page turn re-filters for free.
     * The regex (if any) is compiled ONCE per call, not per item.
     */
    private fun sourceItems(session: Session): List<AbstractSItem> {
        var list = itemMap.values.toList()
        session.typeId?.let { t -> list = list.filter { TypeUtil.isItemIsExtendType(it, t) } }
        session.tierId?.let { t -> list = list.filter { itemTierId(it)?.equals(t, ignoreCase = true) == true } }
        session.levelId?.let { l -> list = list.filter { itemLevelId(it)?.equals(l, ignoreCase = true) == true } }
        session.query?.trim()?.takeIf { it.isNotEmpty() }?.let { q ->
            if (q.startsWith("re:")) {
                // Invalid regex matches nothing (validated live in the anvil)
                val regex = try {
                    Regex(q.substring(3), RegexOption.IGNORE_CASE)
                } catch (e: PatternSyntaxException) {
                    return emptyList()
                }
                list = list.filter { m ->
                    regex.containsMatchIn(m.key) || rawDisplay(m)?.let { regex.containsMatchIn(it) } == true
                }
            } else {
                list = list.filter { m ->
                    m.key.contains(q, ignoreCase = true) || (rawDisplay(m)?.contains(q, ignoreCase = true) == true)
                }
            }
        }
        return list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.key })
    }

    // endregion

    // region helpers

    /**
     * GuiItem with placeholder-substituted lore (GuiItem alone cannot fill {0}..{n}).
     */
    private fun guiItem(nodeName: String, material: String, vararg loreArgs: Any): ItemStack =
        GuiItem(
            nodeName,
            material,
            lore = console.asLangText("Editor_${nodeName}_Lore", *loreArgs).asListEnhanced() ?: emptyList()
        ).build()

    private fun reopenMain(player: Player) {
        submit(delay = 1) { mainItemExplorer(player) }
    }

    // endregion

    // region main menu

    fun mainItemExplorer(player: Player) {
        // Prune sessions of players who have left
        sessions.keys.removeIf { it != player.uniqueId && Bukkit.getPlayer(it) == null }
        val session = session(player)

        player.openMenu<PageableChest<AbstractSItem>>(console.asLangText("Editor_Title")) {

            val suffix by lazy { console.asLangText("Editor_Item_Suffix").asListEnhanced()?.toComponent() ?: emptyList() }

            rows(6)

            map(
                "#########",
                "#########",
                "#########",
                "#########",
                "#########",
                "S-FB-C---"
            )

            set('-', GuiItem("background", "BLACK_STAINED_GLASS_PANE").build())

            // Set usable slots (by character)
            slotsBy('#')

            // Element list re-filtered from the live session on every build
            elements { sourceItems(session) }

            // Generate the item for each element
            // async = false: the builder walks the item tag / NMS layer, which must stay on the main
            // thread; rendering a page off-thread also raced the session-state mutations below.
            onGenerate(async = false) { player, element, index, slot ->
                val item = sertralineItemBuilder(element.key, player) ?: GuiItem("error", "ARROW").build()
                val lore = PlatformCompat.getLore(item)?.toMutableList() ?: mutableListOf()
                lore.addAll(suffix)
                PlatformCompat.setLore(item, lore)
                val tag = item.getItemTag(true)
                tag["sertraline_browse_item"] = "yes"
                item.setItemTag(tag)
                item
            }

            // Element click event
            onClick { event, element ->
                val bukkitPlayer = event.clicker
                if (itemMap.get(element.key) == null) {
                    warningS("GUI item ${element.key} not found.")
                    return@onClick
                }
                // `clickEvent()` is only non-null for inventory clicks. Picking up the display item
                // (number keys, drop, drag) emits other actions whose `.click` is null / a non-CLICK
                // action, and the old `when` silently fell through to `else -> {}` — allowing the
                // click to continue and letting the player drag the browse-only item out of the GUI.
                val clickType = event.clickEvent()?.click ?: return@onClick
                when (clickType) {
                    LEFT -> bukkitPlayer.giveItem(sertralineItemBuilder(element.key, bukkitPlayer, amount = 1))
                    SHIFT_LEFT -> {
                        // `sertralineItemBuilder` is nullable: the old `!!` crashed the click handler
                        // (and the whole GUI) as soon as one item failed to build.
                        val itemStack = sertralineItemBuilder(element.key, bukkitPlayer, amount = 1)
                        if (itemStack == null) {
                            warningS("Failed to build item ${element.key} for ${bukkitPlayer.name}.")
                            return@onClick
                        }
                        itemStack.amount = itemStack.maxStackSize
                        bukkitPlayer.giveItem(itemStack)
                    }
                    // Non-CLICK inventory actions on a browse slot: cancel by returning.
                    else -> {}
                }
            }

            // Search button
            set('S', guiItem("search", "NAME_TAG", session.query ?: "", sourceItems(session).size)) {
                openSearchMenu(clicker)
            }

            // Filter button
            set('F', guiItem("filter", "HOPPER",
                session.typeId ?: console.asLangText("Editor_filter_all"),
                session.tierId ?: console.asLangText("Editor_filter_all"),
                session.levelId ?: console.asLangText("Editor_filter_all"),
                sourceItems(session).size,
                itemMap.size)) {
                openFilterMenu(clicker)
            }

            // Set up the next page button
            setNextPage(50) { page, hasNextPage ->
                GuiItem("nextpage", "ARROW").build()
            }

            // Set the previous page button
            setPreviousPage(48) { page, hasPreviousPage ->
                GuiItem("previouspage", "ARROW").build()
            }
        }
    }

    // endregion

    // region filter submenu

    private enum class Dimension { TYPE, TIER, LEVEL }

    /** Localized dimension label (lang keys Editor_Filter_Dim_TYPE / _TIER / _LEVEL). */
    private fun Dimension.label(): String = console.asLangText("Editor_Filter_Dim_$name")

    private fun Dimension.current(session: Session): String? = when (this) {
        Dimension.TYPE -> session.typeId
        Dimension.TIER -> session.tierId
        Dimension.LEVEL -> session.levelId
    }

    private fun Dimension.material(): String = when (this) {
        Dimension.TYPE -> "PAPER"
        Dimension.TIER -> "NETHER_STAR"
        Dimension.LEVEL -> "EXPERIENCE_BOTTLE"
    }

    private fun openFilterMenu(player: Player) {
        val session = session(player)
        player.openMenu<Chest>(console.asLangText("Editor_Filter_Title")) {
            rows(3)
            map(
                "---------",
                "-T-R-L-X-",
                "----B----"
            )
            set('-', GuiItem("background", "BLACK_STAINED_GLASS_PANE").build())
            // Lock every click (cancels background pickups; char-bound handlers still run)
            onClick(lock = true) { }

            set('T', guiItem("Filter_Type", "PAPER", Dimension.TYPE.current(session) ?: console.asLangText("Editor_filter_all"))) {
                openValueMenu(clicker, Dimension.TYPE)
            }
            set('R', guiItem("Filter_Tier", "NETHER_STAR", Dimension.TIER.current(session) ?: console.asLangText("Editor_filter_all"))) {
                openValueMenu(clicker, Dimension.TIER)
            }
            set('L', guiItem("Filter_Level", "EXPERIENCE_BOTTLE", Dimension.LEVEL.current(session) ?: console.asLangText("Editor_filter_all"))) {
                openValueMenu(clicker, Dimension.LEVEL)
            }
            set('X', GuiItem("Filter_Clear", "BARRIER").build()) {
                session.clear()
                reopenMain(clicker)
            }
            set('B', GuiItem("Filter_Back", "ARROW").build()) {
                reopenMain(clicker)
            }
        }
    }

    private fun openValueMenu(player: Player, dimension: Dimension) {
        val session = session(player)
        val label = dimension.label()
        val entries: List<Pair<String, String>> = when (dimension) {
            Dimension.TYPE -> Sertraline.types.values.map { it.id to it.name }
            Dimension.TIER -> Sertraline.tiers.values.map { it.id to it.name }
            Dimension.LEVEL -> Sertraline.levels.values.map { it.id to it.name }
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.first })
        val displayById = entries.toMap()

        player.openMenu<PageableChest<String>>(console.asLangText("Editor_Filter_Value_Title", label)) {
            rows(3)
            map(
                "---------",
                "#########",
                "P---BRN-X"
            )
            set('-', GuiItem("background", "BLACK_STAINED_GLASS_PANE").build())
            onClick(lock = true) { }

            slotsBy('#')
            elements { entries.map { it.first } }
            onGenerate { _, id, _, _ ->
                val display = displayById[id] ?: id
                val lore = console.asLangText("Editor_Filter_Value_Lore", id).asListEnhanced()?.toMutableList() ?: mutableListOf()
                if (id.equals(dimension.current(session), ignoreCase = true)) {
                    lore.addAll(console.asLangText("Editor_Filter_Value_Selected_Lore").asListEnhanced() ?: emptyList())
                }
                GuiItem(
                    "Filter_Value",
                    dimension.material(),
                    name = console.asLangText("Editor_Filter_Value_Name", display),
                    lore = lore
                ).build()
            }
            onClick { event, id ->
                when (dimension) {
                    Dimension.TYPE -> session.typeId = id
                    Dimension.TIER -> session.tierId = id
                    Dimension.LEVEL -> session.levelId = id
                }
                reopenMain(event.clicker)
            }

            set('B', GuiItem("Filter_Back", "ARROW").build()) {
                reopenMain(clicker)
            }
            set('R', GuiItem("Filter_Reset", "BARRIER").build()) {
                when (dimension) {
                    Dimension.TYPE -> session.typeId = null
                    Dimension.TIER -> session.tierId = null
                    Dimension.LEVEL -> session.levelId = null
                }
                reopenMain(clicker)
            }
            set('N', if (entries.isEmpty()) GuiItem("Filter_Value_None", "GRAY_DYE",
                    name = console.asLangText("Editor_Filter_Value_None_Name", label)).build()
                else GuiItem("background", "BLACK_STAINED_GLASS_PANE").build())

            setPreviousPage(18) { _, _ ->
                GuiItem("previouspage", "ARROW").build()
            }
            setNextPage(26) { _, _ ->
                GuiItem("nextpage", "ARROW").build()
            }
        }
    }

    // endregion

    // region search (anvil)

    private fun openSearchMenu(player: Player) {
        val session = session(player)

        // Prefill the anvil rename slot with the current query (or a hint paper).
        // Built directly: lang has Editor_Search_Prefill (no _Name suffix), so GuiItem
        // would fall back to a raw key string.
        val prefill = ItemStack(Material.PAPER)
        val prefillMeta = prefill.itemMeta
        if (prefillMeta != null) {
            val currentQuery: String? = session.query
            val prefillName =
                if (currentQuery.isNullOrBlank()) console.asLangText("Editor_Search_Prefill")
                else console.asLangText("Editor_Search_Prefill_Current", currentQuery)
            PlatformCompat.setDisplayName(prefillMeta, prefillName.toComponent())
            prefill.itemMeta = prefillMeta
        }

        // onRename fires live per keystroke (packet-driven); capture the latest text
        val currentText = AtomicReference(session.query ?: "")
        var lastWarnAt = 0L

        player.openMenu<Anvil>(console.asLangText("Editor_Search_Title")) {
            map("ISR")
            // Input slot must not be lootable: taking the paper would desync currentText
            set('I', prefill) { }
            set('S', ItemStack(Material.BARRIER)) {
                // Cancel: keep the old query and go back
                clicker.closeInventory()
                reopenMain(clicker)
            }
            set('R', GuiItem("Filter_Back", "ARROW").build())
            // Result-slot click = confirm
            onClick(bind = 'R') { event ->
                val query = currentText.get().trim().takeIf { it.isNotEmpty() }
                session.query = query
                event.clicker.closeInventory()
                if (query != null) {
                    event.clicker.sendStringAsComponent(
                        console.asLangText("Editor_Search_Result", query, sourceItems(session).size)
                    )
                }
                reopenMain(event.clicker)
            }
            onRename { player, text, _ ->
                currentText.set(text)
                // Live regex validation (throttled)
                if (text.startsWith("re:")) {
                    try {
                        Regex(text.substring(3))
                    } catch (e: PatternSyntaxException) {
                        val now = System.currentTimeMillis()
                        if (now - lastWarnAt > 1500) {
                            lastWarnAt = now
                            submit {
                                player.sendStringAsComponent(
                                    console.asLangText("Editor_Search_Invalid_Regex", e.message ?: text)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // endregion
}
