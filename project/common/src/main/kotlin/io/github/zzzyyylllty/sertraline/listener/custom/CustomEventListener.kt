package io.github.zzzyyylllty.sertraline.listener.custom

import io.github.zzzyyylllty.sertraline.config.CustomEventsConfig
import io.github.zzzyyylllty.sertraline.debugMode.devLog
import io.github.zzzyyylllty.sertraline.debugMode.devLogBypassCheck
import io.github.zzzyyylllty.sertraline.listener.action.ThrottleActionLink
import io.github.zzzyyylllty.sertraline.listener.action.ThrottleActionParam
import io.github.zzzyyylllty.sertraline.logger.severeS
import io.github.zzzyyylllty.sertraline.util.ActionHelper.throttleAction
import io.github.zzzyyylllty.sertraline.util.ClassAliases
import io.github.zzzyyylllty.sertraline.util.EventPlayerResolver
import io.github.zzzyyylllty.sertraline.util.isNotExist
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.EventExecutor
import taboolib.library.configuration.ConfigurationSection
import taboolib.platform.BukkitPlugin

/**
 * Custom event registration
 *
 * Lets item actions listen to any Bukkit event, not just the built-in onClick / onConsume etc.
 * triggers from [io.github.zzzyyylllty.sertraline.listener.action.itemAction]. Inspired by Vulpecula's
 * dispatcher registration: define a "trigger name -> event class / priority / accept-cancelled / item source"
 * mapping in the custom-events section of config.yml, then items simply use that trigger name under
 * sertraline:actions.
 *
 * config.yml example:
 * ```
 * custom-events:
 *   onPlayerDeath:
 *     class: PlayerDeathEvent
 *     priority: NORMAL
 *     accept-cancelled: false
 *     item-source:
 *       - main-hand
 *       - off-hand
 * ```
 *
 * Optionally use custom-source to explicitly bind both the player and the trigger item with an event
 * expression, overriding the heuristic resolution of item-source.
 * Note: Bukkit events have no "killer" getter, so EntityDeathEvent cannot retrieve the killer; to bind a
 * melee attacker use the event.damager of EntityDamageByEntityEvent (must be a player to take effect).
 * ```
 * custom-events:
 *   onPlayerAttack:
 *     class: EntityDamageByEntityEvent
 *     custom-source:
 *       player: event.damager
 *       bItem: event.damager.itemInMainHand
 * ```
 */
object CustomEventListener {

    enum class ItemSource(val key: String) {
        MAIN_HAND("main-hand"),
        OFF_HAND("off-hand"),
        BOTH_HANDS("both-hands"),
        ARMOR("armor"),
        EVENT("event"),
        INVENTORY("inventory");

        companion object {
            private val byKey = entries.associateBy { it.key }
            fun parse(value: String): ItemSource? = byKey[value.lowercase().replace('_', '-')]
        }
    }

    /** Built-in trigger names (already registered by itemAction / itemActionRiptide); registering the same name in custom-events.yml is forbidden, otherwise the same Bukkit event would fire twice */
    private val builtInTriggers = setOf(
        "onClick", "onRightClick", "onLeftClick", "onShoot", "onLogin", "onMine",
        "onAttack", "onConsume", "onBreak", "onDrop", "onPickUp", "onInventoryClick",
        "onSwap", "onShootTrident", "onSneak"
    )

    /** Lowercased set of built-in trigger names, for case-insensitive collision detection */
    private val builtInTriggersLower = builtInTriggers.map { it.lowercase() }.toSet()

    private data class Registration(
        val trigger: String,
        val eventClass: Class<out Event>,
        val priority: EventPriority,
        val ignoreCancelled: Boolean,
        val itemSources: List<ItemSource>,
        val customSource: CustomSource? = null,
    )

    /**
     * custom-source: explicitly bind player and trigger item with an event expression, overriding the default heuristic resolution.
     * player / bItem are "event.<getter/field path>", e.g. event.player, event.killer, event.player.itemInMainHand.
     */
    private data class CustomSource(
        val playerExpr: String?,
        val bItemExpr: String?,
    ) {
        /** Splits "event.player.itemInMainHand" into [player, itemInMainHand]; the root must be event */
        val playerTokens: List<String>? = parseExprTokens(playerExpr)
        val bItemTokens: List<String>? = parseExprTokens(bItemExpr)
    }

    /** A resolved item; discriminator distinguishes items from different sources/slots in the same event and becomes part of the throttle key */
    private data class ResolvedItem(val discriminator: String, val item: ItemStack)

    /** Shared Listener instance, used only for custom event registration and can be unregistered as a whole */
    private val dummyListener: Listener = object : Listener {}

    private val registrations = mutableListOf<Registration>()

    private val itemGetterNames = arrayOf(
        "getItem", "getItemStack", "getItemInHand", "getItemInMainHand", "getItemInOffHand",
        "getCurrentItem", "getBow", "getArrow", "getWeapon", "getItemDrop", "getBrokenItem"
    )

    /** Reads custom-events.yml and registers all custom event listeners.
     *
     * Prepare/commit order: all registrations are parsed first; only then is the old
     * set unregistered and the new set registered. A parse failure never tears down
     * the currently active listeners. Individual Bukkit registration failures are
     * logged and skipped without aborting the rest. */
    fun registerAll() {
        val root = CustomEventsConfig.config
        val plugin = BukkitPlugin.getInstance()

        // ---- prepare：全部解析，任何一条失败不影响现有注册 ----
        val parsed = mutableListOf<Registration>()
        for (key in root.getKeys(false)) {
            val sub = root.getConfigurationSection(key) ?: run {
                severeS("Invalid custom event \"$key\": expected a section.")
                continue
            }
            try {
                parsed += parseRegistration(key, sub)
            } catch (ex: Exception) {
                severeS("Failed to parse custom event \"$key\": ${ex.message}")
            }
        }

        // ---- commit：注销旧 + 注册新（主线程，dummyListener 整体注销） ----
        unregisterAll()
        for (reg in parsed) {
            try {
                plugin.server.pluginManager.registerEvent(
                    reg.eventClass,
                    dummyListener,
                    reg.priority,
                    EventExecutor { _, event -> onEvent(reg, event) },
                    plugin,
                    reg.ignoreCancelled
                )
                registrations += reg
                devLog("Registered custom event \"${reg.trigger}\" -> ${reg.eventClass.name}")
            } catch (ex: Exception) {
                severeS("Failed to register custom event \"${reg.trigger}\": ${ex.message}")
            }
        }
        if (registrations.isNotEmpty()) {
            devLog("Registered ${registrations.size} custom event listener(s) in total")
        }
    }

    /** Unregisters all custom event listeners */
    fun unregisterAll() {
        if (registrations.isEmpty()) return
        // HandlerList.unregisterAll(Listener) is an all-version API (cancelEvents is absent from the legacy12 compile surface)
        try {
            HandlerList.unregisterAll(dummyListener)
        } catch (_: Throwable) {
        }
        registrations.clear()
    }

    private fun parseRegistration(trigger: String, section: ConfigurationSection): Registration {
        // Built-in trigger names are already registered in itemAction / itemActionRiptide; a same-named custom trigger would double-execute the same event
        if (trigger.lowercase() in builtInTriggersLower) {
            error("trigger \"$trigger\" collides with a built-in trigger")
        }
        val className = section.getString("class") ?: section.getString("listen")
            ?: error("missing \"class\"")
        val clazz = ClassAliases.getClass(className)
            ?: error("invalid event class \"$className\"")
        if (!Event::class.java.isAssignableFrom(clazz)) {
            error("\"$className\" is not a Bukkit Event")
        }
        @Suppress("UNCHECKED_CAST")
        val eventClass = clazz as Class<out Event>
        val priority = section.getString("priority")?.let { name ->
            EventPriority.values().firstOrNull { it.name.equals(name, true) }
        } ?: EventPriority.NORMAL
        val acceptCancelled = section.getBoolean("accept-cancelled", false)
        val itemSources = parseItemSources(section.get("item-source"))
        val customSource = parseCustomSource(section.getConfigurationSection("custom-source"))
        return Registration(trigger, eventClass, priority, !acceptCancelled, itemSources, customSource)
    }

    /**
     * Parses custom-source: player / bItem are bound explicitly with expressions.
     * When custom-source exists, the heuristic resolution of item-source is overridden (if both are configured, custom-source wins).
     */
    private fun parseCustomSource(section: ConfigurationSection?): CustomSource? {
        if (section == null) return null
        val player = section.getString("player")?.trim()
        val bItemRaw = section.getString("bItem") ?: section.getString("item")
        val bItem = bItemRaw?.trim()
        if (player.isNullOrBlank() && bItem.isNullOrBlank()) return null
        // The expression must start at event; otherwise it always resolves to null at runtime, so error early to ease config debugging
        if (player != null && !player.startsWith("event")) {
            error("custom-source.player must start with \"event.\", got \"$player\"")
        }
        if (player != null && player == "event") {
            error("custom-source.player must include a field path after \"event.\", got \"$player\"")
        }
        if (bItem != null && !bItem.startsWith("event")) {
            error("custom-source.bItem must start with \"event.\", got \"$bItem\"")
        }
        if (bItem != null && bItem == "event") {
            error("custom-source.bItem must include a field path after \"event.\", got \"$bItem\"")
        }
        return CustomSource(player, bItem)
    }

    /** Splits "event.player.itemInMainHand" into an evaluation path [player, itemInMainHand]; the root must be event, otherwise returns null */
    private fun parseExprTokens(expr: String?): List<String>? {
        if (expr == null) return null
        val parts = expr.trim().split('.')
        if (parts.firstOrNull() != "event") return null
        val tokens = parts.drop(1)
        // An empty list (e.g. "event" with no further fields) is treated as invalid
        if (tokens.isEmpty()) return null
        return tokens
    }

    private fun parseItemSources(raw: Any?): List<ItemSource> {
        val list = mutableListOf<ItemSource>()
        when (raw) {
            null -> {
                list += ItemSource.MAIN_HAND
                list += ItemSource.OFF_HAND
            }
            is String -> ItemSource.parse(raw)?.let { list += it }
            is List<*> -> raw.forEach { ItemSource.parse(it?.toString() ?: "")?.let { v -> list += v } }
        }
        return if (list.isEmpty()) listOf(ItemSource.MAIN_HAND, ItemSource.OFF_HAND) else list
    }

    private fun onEvent(reg: Registration, event: Event) {
        val cs = reg.customSource
        if (cs != null) {
            onEventWithCustomSource(reg, event, cs)
            return
        }
        val player = EventPlayerResolver.resolvePlayer(event)
        if (player == null) {
            // Lazy evaluation: no string is constructed when devMode is off, avoiding overhead on high-frequency events
            devLogBypassCheck { "Custom event \"${reg.trigger}\" (${event.eventName}): no player resolved, skipped." }
            return
        }
        for (resolved in resolveItems(player, event, reg.itemSources)) {
            triggerActions(reg, event, player, resolved.item, resolved.discriminator)
        }
    }

    /** custom-source: explicitly binds player and trigger item with expressions, bypassing heuristic resolution entirely */
    private fun onEventWithCustomSource(reg: Registration, event: Event, cs: CustomSource) {
        val player = resolveExpression(event, cs.playerTokens) as? Player
        if (player == null) {
            devLogBypassCheck { "Custom event \"${reg.trigger}\" (${event.eventName}): custom-source.player \"${cs.playerExpr}\" did not resolve to a Player, skipped." }
            return
        }
        val item = when (val v = resolveExpression(event, cs.bItemTokens)) {
            is ItemStack -> v
            is Item -> v.itemStack
            else -> null
        }
        if (item == null) {
            devLogBypassCheck { "Custom event \"${reg.trigger}\" (${event.eventName}): custom-source.bItem \"${cs.bItemExpr}\" did not resolve to an ItemStack, skipped." }
            return
        }
        triggerActions(reg, event, player, item, "custom")
    }

    /** Single entry point: filters out non-Sertraline items and triggers throttled actions */
    private fun triggerActions(reg: Registration, event: Event, player: Player, item: ItemStack, discriminator: String) {
        if (item.isNotExist()) return
        // Consistent with the built-in itemAction: NBT is not read in advance. sertraline_id resolution + the actions gate
        // live inside applyActions in the throttle callback (applyActions reads NBT only once); otherwise inventory/armor
        // sources would do a deep NBT parse per slot per event under high-frequency events, and most slots are not Sertraline items at all
        throttleAction(
            ThrottleActionLink(player.uniqueId.toString(), reg.trigger, discriminator),
            ThrottleActionParam(player, event, event as? Cancellable, item)
        )
    }

    /**
     * Resolves items and attaches a slot description (discriminator).
     * The discriminator enters the throttle key, so items in different slots under the same player and trigger
     * are throttled independently, preventing only the first item's action from firing with multiple item sources
     * (both-hands / armor / inventory).
     */
    private fun resolveItems(player: Player, event: Event, sources: List<ItemSource>): List<ResolvedItem> {
        val items = ArrayList<ResolvedItem>()
        val inv = player.inventory
        for (source in sources) {
            when (source) {
                ItemSource.MAIN_HAND -> items.add(ResolvedItem(ItemSource.MAIN_HAND.key, inv.itemInMainHand))
                ItemSource.OFF_HAND -> items.add(ResolvedItem(ItemSource.OFF_HAND.key, inv.itemInOffHand))
                ItemSource.BOTH_HANDS -> {
                    items.add(ResolvedItem(ItemSource.MAIN_HAND.key, inv.itemInMainHand))
                    items.add(ResolvedItem(ItemSource.OFF_HAND.key, inv.itemInOffHand))
                }
                ItemSource.ARMOR -> {
                    // armorContents order is fixed: boots, leggings, chestplate, helmet
                    val slotNames = arrayOf("boots", "leggings", "chestplate", "helmet")
                    val armor = inv.armorContents
                    for (i in armor.indices) {
                        val slot = armor[i] ?: continue
                        items.add(ResolvedItem("armor:${slotNames[i]}", slot))
                    }
                }
                ItemSource.INVENTORY -> {
                    val contents = inv.contents
                    for (i in contents.indices) {
                        val slot = contents[i] ?: continue
                        items.add(ResolvedItem("inventory:$i", slot))
                    }
                }
                ItemSource.EVENT -> resolveEventItem(event)?.let { items.add(ResolvedItem(ItemSource.EVENT.key, it)) }
            }
        }
        // The same source/slot may be listed repeatedly (e.g. both-hands + main-hand configured together); deduplicate by discriminator
        return items.distinctBy { it.discriminator }
    }

    private fun resolveEventItem(event: Event): ItemStack? {
        for (name in itemGetterNames) {
            when (val v = EventPlayerResolver.invokeGetter(event, name)) {
                is ItemStack -> return v
                is Item -> return v.itemStack
            }
        }
        return null
    }

    /** Evaluates an event expression by dot-separated path (e.g. "event.player.itemInMainHand"); the root must be event */
    private fun resolveExpression(event: Event, tokens: List<String>?): Any? {
        if (tokens == null) return null
        var current: Any? = event
        for (token in tokens) {
            current = resolveToken(current, token) ?: return null
        }
        return current
    }

    /**
     * Single-step resolution: tries getX / isX then the raw method name; if not found on the object, retries through
     * the getInventory / getEquipment containers (Player has no getItemInMainHand, but player.inventory.itemInMainHand does),
     * finally falling back to reading the field. Method names may include parentheses (e.g. getItemInMainHand()).
     */
    private fun resolveToken(target: Any?, rawToken: String): Any? {
        if (target == null) return null
        val token = rawToken.removeSuffix("()")
        val capitalized = token.replaceFirstChar { it.uppercase() }
        for (name in arrayOf("get$capitalized", "is$capitalized", token)) {
            EventPlayerResolver.invokeGetter(target, name)?.let { return it }
        }
        // Common Bukkit pattern: item-related getters hang off inventory/equipment (e.g. event.player.itemInMainHand)
        for (container in arrayOf("getInventory", "getEquipment")) {
            val holder = EventPlayerResolver.invokeGetter(target, container) ?: continue
            EventPlayerResolver.invokeGetter(holder, "get$capitalized")?.let { return it }
        }
        return readField(target, token)
    }

    /** Reads a field (including private fields); returns null if not found */
    private fun readField(target: Any, name: String): Any? {
        var clazz: Class<*>? = target.javaClass
        while (clazz != null && clazz != Any::class.java) {
            for (field in clazz.declaredFields) {
                if (field.name != name) continue
                try {
                    field.isAccessible = true
                    return field.get(target)
                } catch (_: Throwable) {
                    return null
                }
            }
            clazz = clazz.superclass
        }
        return null
    }
}
