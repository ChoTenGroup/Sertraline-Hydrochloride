package io.github.zzzyyylllty.sertraline.gui

import io.github.zzzyyylllty.sertraline.Sertraline.craftingStations
import io.github.zzzyyylllty.sertraline.compat.PlatformCompat
import io.github.zzzyyylllty.sertraline.config.common.SertralineSettings
import io.github.zzzyyylllty.sertraline.data.*
import io.github.zzzyyylllty.sertraline.function.kether.evalKetherBoolean
import io.github.zzzyyylllty.sertraline.function.kether.evalKether
import io.github.zzzyyylllty.sertraline.function.kether.evalKetherString
import io.github.zzzyyylllty.sertraline.logger.severeS
import io.github.zzzyyylllty.sertraline.logger.warningS
import io.github.zzzyyylllty.sertraline.util.GraalJsUtil
import io.github.zzzyyylllty.sertraline.util.ScriptHelper
import io.github.zzzyyylllty.sertraline.util.minimessage.toComponent
import io.github.zzzyyylllty.sertraline.util.textLines
import com.cryptomorin.xseries.XMaterial
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import taboolib.common.platform.service.PlatformExecutor.PlatformTask
import taboolib.common.platform.function.submit
import taboolib.common.platform.function.submitAsync
import taboolib.module.ui.openMenu
import taboolib.module.ui.type.Chest
import taboolib.platform.util.giveItem
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Crafting station GUI manager.
 * Responsibilities: opening the station interface, displaying recipes, and handling the crafting flow (condition check → wait → output).
 */
object CraftingStationManager {

    /** Active crafting sessions: playerId → CraftingSession */
    private val activeSessions = ConcurrentHashMap<String, CraftingSession>()

    /** Unique session token generator */
    private val nextSessionToken = AtomicLong(0)

    /** Cached JSR 223 JavaScript engine (lazily initialized, avoids repeated getEngineByName overhead) */
    private val jsEngine: javax.script.ScriptEngine? by lazy {
        ScriptHelper.engineManager.getEngineByName("js")
    }

    /**
     * Opens the crafting station GUI.
     */
    fun openStation(player: Player, stationId: String) {
        val station = craftingStations[stationId]
        if (station == null) {
            PlatformCompat.sendComponent(player, "<red>Station '$stationId' not found.</red>".toComponent())
            return
        }
        openStation(player, station, stationId, 0)
    }

    /**
     * Opens the station GUI (with a specific page).
     */
    fun openStation(player: Player, station: CraftingStation, stationId: String, page: Int) {
        // Check for an existing crafting session
        val oldSession = activeSessions[player.uniqueId.toString()]
        if (oldSession != null) {
            val oldRecipe = station.recipes.values.find { it.displayName == oldSession.recipeId }
            val cancellable = oldRecipe?.options?.get("cancellable") as? Boolean ?: true
            if (!cancellable) {
                PlatformCompat.sendComponent(player, "<yellow>Current craft cannot be cancelled!</yellow>".toComponent())
                return
            }
            // Cancel the old session and return the consumed materials
            activeSessions.remove(player.uniqueId.toString())
            oldSession.itemsReturned.set(true)
            oldSession.cancel() // Cancel the scheduled task to prevent duplicate output after delayed execution
            CraftingStationDataManager.clearSession(player)
            returnConsumedItems(player, oldSession)
        }

        val display = station.display
        val recipes = station.recipes.values.toList()
        val totalRecipes = recipes.size
        val pageSize = display.layout.joinToString("").count { it.toString() == display.key }

        val totalPages = if (pageSize > 0) ((totalRecipes - 1) / pageSize) + 1 else 1
        val currentPage = page.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
        val pageRecipes = if (pageSize > 0) {
            val from = currentPage * pageSize
            val to = (from + pageSize).coerceAtMost(totalRecipes)
            if (from < totalRecipes) recipes.subList(from, to) else emptyList()
        } else emptyList()

        // Compute all usable item slots
        val itemSlots = mutableListOf<Int>()
        for (rowIdx in display.layout.indices) {
            val row = display.layout[rowIdx]
            for (colIdx in row.indices) {
                if (row[colIdx].toString() == display.key) {
                    itemSlots.add(rowIdx * 9 + colIdx)
                }
            }
        }

        // Detect collisions between recipe slots and element slots
        val elementSlots = mutableSetOf<Int>()
        for ((_, elementConfig) in display.elements) {
            if (elementConfig.slot != null) {
                elementSlots.add(elementConfig.slot)
            } else if (elementConfig.char != null) {
                val c = elementConfig.char[0].toString()
                if (c != display.key) {
                    for (rowIdx in display.layout.indices) {
                        val row = display.layout[rowIdx]
                        for (colIdx in row.indices) {
                            if (row[colIdx].toString() == c) {
                                elementSlots.add(rowIdx * 9 + colIdx)
                            }
                        }
                    }
                }
            }
        }
        val overlap = itemSlots.toSet().intersect(elementSlots)
        if (overlap.isNotEmpty()) {
            warningS("CraftingStation '$stationId': slot collision between element and recipe slots: $overlap")
        }

        player.openMenu<Chest>(display.title) {
            rows(display.layout.size)

            // Render the layout
            map(*display.layout.toTypedArray())

            // Lock EVERY slot of this menu before binding the interactive ones.
            // Only slots passed to `set(...) { }` were cancelled, so any layout slot without a bound
            // element (and the empty recipe slots on the last page) accepted drags / shift-clicks
            // from the player's inventory: the items went into the station GUI and were destroyed
            // when it closed. ClickEvent cancellation does not stop other callbacks, so this does not
            // interfere with the per-slot handlers below.
            onClick(lock = true) { }

            // Render background elements
            for ((elementId, elementConfig) in display.elements) {
                if (elementConfig.slot != null) {
                    val item = buildElementItem(elementConfig, elementId, player, currentPage, totalPages)
                    set(elementConfig.slot, item) {
                        handleElementClick(clicker, station, stationId, elementConfig, elementId, currentPage, totalPages)
                    }
                } else if (elementConfig.char != null) {
                    val char = elementConfig.char[0]
                    val item = buildElementItem(elementConfig, elementId, player, currentPage, totalPages)
                    set(char, item) {
                        handleElementClick(clicker, station, stationId, elementConfig, elementId, currentPage, totalPages)
                    }
                }
            }

            // Populate recipe items
            for ((index, recipe) in pageRecipes.withIndex()) {
                if (index < itemSlots.size) {
                    val recipeSlot = itemSlots[index]
                    val item = buildRecipeItem(recipe, player)
                    set(recipeSlot, item) {
                        handleRecipeClick(clicker, station, stationId, recipe, recipeSlot)
                    }
                }
            }
        }
    }

    // ==================== Element Item Building ====================

    private fun buildElementItem(
        config: ElementConfig,
        elementId: String,
        player: Player,
        currentPage: Int,
        totalPages: Int
    ): ItemStack {
        val material = try {
            org.bukkit.Material.valueOf(config.material.uppercase())
        } catch (_: Exception) {
            // 1.13+ enum name, not present on 1.12.2; XMaterial resolves by server version (1.12.2 → STAINED_GLASS_PANE)
            XMaterial.GRAY_STAINED_GLASS_PANE.parseMaterial() ?: Material.STONE
        }
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return item

        val resolvedName = config.name
            .replace("{page}", (currentPage + 1).toString())
            .replace("{total_pages}", totalPages.toString())
            .replace("{next_page}", (currentPage + 2).coerceAtMost(totalPages).toString())
            .replace("{previous_page}", currentPage.toString())
            .toComponent()
        PlatformCompat.setDisplayName(meta, resolvedName)

        if (config.lore != null) {
            val resolvedLore = config.lore
                .replace("{page}", (currentPage + 1).toString())
                .replace("{total_pages}", totalPages.toString())
                .replace("{next_page}", (currentPage + 2).coerceAtMost(totalPages).toString())
                .replace("{previous_page}", currentPage.toString())
            // Support multi-line lore (separated by \n)
            val loreLines = resolvedLore.split("\n").map { it.toComponent() }
            PlatformCompat.setLore(meta, loreLines)
        }

        item.itemMeta = meta
        return item
    }

    private fun buildRecipeItem(recipe: StationRecipe, player: Player): ItemStack {
        val displayItem = recipe.display
        if (displayItem != null) {
            return displayItem.build(player)
        }
        // Use the first output as the display
        if (recipe.outputs.isNotEmpty()) {
            return recipe.outputs.first().build(player)
        }
        return ItemStack(XMaterial.GRASS_BLOCK.parseMaterial() ?: Material.STONE)
    }

    // ==================== Element Click Handling ====================

    private fun handleElementClick(
        player: Player,
        station: CraftingStation,
        stationId: String,
        config: ElementConfig,
        elementId: String,
        currentPage: Int,
        totalPages: Int
    ) {
        val roll = config.roll ?: false
        when (elementId) {
            "next_page", "next-page" -> {
                if (currentPage + 1 < totalPages) {
                    openStation(player, station, stationId, currentPage + 1)
                } else if (roll && totalPages > 1) {
                    // When roll: true, wrap back to the first page
                    openStation(player, station, stationId, 0)
                }
            }
            "previous_page", "previous-page", "prev_page", "prev-page" -> {
                if (currentPage > 0) {
                    openStation(player, station, stationId, currentPage - 1)
                } else if (roll && totalPages > 1) {
                    // When roll: true, wrap to the last page
                    openStation(player, station, stationId, totalPages - 1)
                }
            }
        }

        // Execute agents
        if (config.agents != null) {
            for ((key, script) in config.agents) {
                executeAgentScript(key, script, player, mapOf(
                    "page" to (currentPage + 1),
                    "total_pages" to totalPages,
                    "station_id" to stationId
                ))
            }
        }
    }

    // ==================== Recipe Click & Crafting Flow ====================

    private fun handleRecipeClick(player: Player, station: CraftingStation, stationId: String, recipe: StationRecipe, slot: Int) {
        val sessionKey = player.uniqueId.toString()

        // Check whether a crafting process is already active
        val existing = activeSessions[sessionKey]
        if (existing != null) {
            val msg = SertralineSettings.msgCraftingAlreadyActive
            PlatformCompat.sendComponent(player, msg.toComponent())
            return
        }

        // Check conditions
        val conditions = recipe.conditions
        if (conditions != null && conditions.isNotEmpty()) {
            val conditionResults = checkConditions(conditions, player, recipe)
            val failed = conditionResults.filter { !it.passed }
            if (failed.isNotEmpty()) {
                for (fail in failed) {
                    val conditionType = fail.condition.type.lowercase()
                    val message = recipe.messages?.let { msgs ->
                        msgs["ConditionNotMet${fail.condition.type}"]
                            ?: msgs["ConditionNotMet${conditionType.replaceFirstChar { it.uppercase() }}"]
                            ?: msgs["ConditionNotMet${conditionType}"]
                    } ?: SertralineSettings.msgConditionNotMet
                        ?: "<red>Condition not met: {condition_name}</red>"
                    PlatformCompat.sendComponent(player, message
                        .replace("{condition_name}", fail.condition.name)
                        .replace("{condition_amount}", fail.condition.amount)
                        .replace("{condition_required}", fail.condition.required)
                        .replace("{condition_type}", fail.condition.type)
                        .toComponent()
                    )
                    if (fail.condition.agents != null) {
                        for ((key, script) in fail.condition.agents) {
                            if (key.equals("onFail", ignoreCase = true)) {
                                executeAgentScript(key, script, player, mapOf("recipe" to recipe.displayName))
                            }
                        }
                    }
                }
                return
            }
            for (condition in conditions) {
                if (condition.agents != null) {
                    for ((key, script) in condition.agents) {
                        if (key.equals("onSuccess", ignoreCase = true)) {
                            executeAgentScript(key, script, player, mapOf("recipe" to recipe.displayName))
                        }
                    }
                }
            }
        }

        // Atomically check and consume input items (fixes the TOCTOU race condition)
        val consumed = tryConsumeInputs(player, recipe)
        if (consumed == null) {
            val missingName = recipe.inputs.firstOrNull()?.displayName?.ifEmpty { recipe.inputs.firstOrNull()?.input?.item } ?: "unknown"
            val msg = SertralineSettings.msgMissingIngredients
            PlatformCompat.sendComponent(player, msg.replace("{display_name}", missingName).toComponent())
            return
        }

        // Parse the crafting time
        val timeSeconds = try {
            recipe.time.evalKetherString(player)?.toDoubleOrNull() ?: 0.0
        } catch (_: Exception) {
            try { recipe.time.toDouble() } catch (_: Exception) { 0.0 }
        }
        val timeTicks = (timeSeconds * 20).toInt().coerceAtLeast(1)

        // Execute onStart agents (recipe level → station level)
        fireRecipeAgents(recipe, "onStart", player)
        fireStationAgents(station, recipe, "onStart", player)

        // Cancel flag + session token
        val itemsReturned = AtomicBoolean(false)
        val sessionToken = nextSessionToken.incrementAndGet()

        // Use a scheduled delay instead of Thread.sleep() to avoid occupying pool threads
        val task = submit(async = true, delay = timeTicks.toLong()) {
            // Check whether the session was cancelled (openStation / cancelAll already handled the refund)
            val currentSession = activeSessions[sessionKey]
            if (currentSession == null || currentSession.sessionToken != sessionToken) {
                return@submit
            }

            // Remove the session to prevent duplicate processing
            activeSessions.remove(sessionKey, currentSession)

            // Grant output items (must run on the main thread)
            submit(async = false) {
                // The player may have disconnected during the crafting delay. Without this check the
                // outputs, the completion message and the lifecycle agents all ran against an
                // offline Player — items vanished and `onClaim` scripts ran on a stale entity.
                if (!player.isOnline) {
                    CraftingStationDataManager.clearSession(player)
                    return@submit
                }
                try {
                    for (output in recipe.outputs) {
                        val outputStack = output.build(player)
                        player.giveItem(outputStack)
                    }

                    fireRecipeAgents(recipe, "onClaim", player)
                    fireStationAgents(station, recipe, "onClaim", player)

                    val msg = SertralineSettings.msgCraftingComplete
                    PlatformCompat.sendComponent(player, msg.toComponent())
                } finally {
                    CraftingStationDataManager.clearSession(player)
                }
            }
        }

        activeSessions[sessionKey] = CraftingSession(
            playerId = sessionKey,
            sessionToken = sessionToken,
            stationId = stationId,
            recipeId = recipe.displayName,
            totalTicks = timeTicks,
            task = task,
            consumedItems = consumed.toMutableList(),
            itemsReturned = itemsReturned
        )

        // Persist the crafting session to the database
        val startTime = System.currentTimeMillis()
        CraftingStationDataManager.saveSession(
            player = player,
            stationId = stationId,
            recipeId = recipe.displayName,
            startTime = startTime,
            totalSeconds = timeSeconds,
            consumedItems = consumed.map { it.item }
        )

        val msg = SertralineSettings.msgCraftingStarted
        PlatformCompat.sendComponent(player, msg.replace("{time}", "%.1f".format(timeSeconds)).toComponent())
    }

    // ==================== Condition Checking ====================

    private data class ConditionResult(val condition: ConditionConfig, val passed: Boolean)

    private fun checkConditions(conditions: List<ConditionConfig>, player: Player, recipe: StationRecipe): List<ConditionResult> {
        return conditions.map { condition ->
            val passed = try {
                when (condition.type.lowercase()) {
                    "scaled" -> {
                        val amount = condition.amount.evalKetherString(player)
                        val required = condition.required.evalKetherString(player)
                        (amount?.toDoubleOrNull() ?: 0.0) >= (required?.toDoubleOrNull() ?: 0.0)
                    }
                    "boolean", "kether" -> {
                        condition.condition.evalKetherBoolean(player)
                    }
                    "permission" -> {
                        player.hasPermission(condition.condition)
                    }
                    else -> {
                        condition.condition.evalKetherBoolean(player)
                    }
                }
            } catch (_: Exception) {
                false
            }
            ConditionResult(condition, passed)
        }
    }

    // ==================== Input Checking & Consumption ====================

    data class ConsumedItem(
        val slot: Int,
        val item: ItemStack
    )

    /**
     * Atomically check and consume the input items.
     * Iterates all recipe inputs; if they can all be satisfied, consumes them and returns a ConsumedItem list;
     * if any input is insufficient, rolls back all changes made and returns null (fixes the TOCTOU race condition).
     */
    private fun tryConsumeInputs(player: Player, recipe: StationRecipe): List<ConsumedItem>? {
        val inventory = player.inventory
        val snapshot = mutableMapOf<Int, ItemStack>()
        val allConsumed = mutableListOf<ConsumedItem>()

        for (input in recipe.inputs) {
            val targetStack = input.input.build(player)
            var remaining = targetStack.amount
            val toRemove = mutableListOf<Int>()

            val contents = inventory.contents
            for ((i, item) in contents.withIndex()) {
                if (remaining <= 0) break
                if (item != null && item.isSimilar(targetStack)) {
                    if (i !in snapshot) snapshot[i] = item.clone()

                    val takeAmount = minOf(item.amount, remaining)
                    allConsumed.add(ConsumedItem(i, item.clone().also { it.amount = takeAmount }))

                    if (item.amount <= remaining) {
                        remaining -= item.amount
                        toRemove.add(i)
                    } else {
                        item.amount -= remaining
                        remaining = 0
                    }
                }
            }

            if (remaining > 0) {
                for ((slot, original) in snapshot) {
                    inventory.setItem(slot, original)
                }
                return null
            }

            for (slot in toRemove) {
                inventory.setItem(slot, null)
            }
        }

        return allConsumed
    }

    /**
     * Return the consumed items to the player's inventory (called when crafting is cancelled/interrupted).
     */
    private fun returnConsumedItems(player: Player, consumed: List<ConsumedItem>, recipeName: String? = null) {
        if (consumed.isEmpty()) return
        if (!player.isOnline) {
            warningS("Cannot return items to offline player ${player.name} (recipe: ${recipeName ?: "unknown"})")
            return
        }
        for (ci in consumed) {
            player.inventory.addItem(ci.item).values.forEach { leftover ->
                // If the inventory is full, drop on the ground
                player.world.dropItem(player.location, leftover)
            }
        }
    }

    /**
     * Return the consumed items from the old session (called when cancelling the old craft).
     */
    private fun returnConsumedItems(player: Player, session: CraftingSession) {
        if (!player.isOnline) return
        returnConsumedItems(player, session.consumedItems, session.recipeId)
    }

    // ==================== Agent Execution ====================

    /**
     * Execute station-level agents.
     */
    private fun fireStationAgents(station: CraftingStation, recipe: StationRecipe, action: String, player: Player) {
        station.agents?.forEach { (key, script) ->
            if (key.equals(action, ignoreCase = true)) {
                executeAgentScript(key, script, player, mapOf(
                    "recipe" to recipe.displayName,
                    "station_id" to (station.display.title)
                ))
            }
        }
    }

    /**
     * Execute recipe-level agents.
     */
    private fun fireRecipeAgents(recipe: StationRecipe, action: String, player: Player) {
        recipe.agents?.forEach { (key, script) ->
            if (key.equals(action, ignoreCase = true)) {
                executeAgentScript(key, script, player, mapOf("recipe" to recipe.displayName))
            }
        }
    }

    /**
     * Public entry point: lets restoreSession trigger lifecycle agents when a session is restored.
     */
    fun fireLifecycleAgents(station: CraftingStation, recipe: StationRecipe, action: String, player: Player) {
        fireRecipeAgents(recipe, action, player)
        fireStationAgents(station, recipe, action, player)
    }

    private fun executeAgentScript(key: String, script: String, player: Player, vars: Map<String, Any?>) {
        submitAsync {
            try {
                val trimmed = script.trimStart()
                when {
                    // GraalJS: prefix with "gjs:" or "graaljs:"
                    trimmed.startsWith("gjs:") || trimmed.startsWith("graaljs:") -> {
                        val jsVars = defaultData + vars + mapOf("player" to player, "bukkitPlayer" to player)
                        GraalJsUtil.cachedEval(trimmed.substringAfter(":").trimStart(), jsVars)
                    }
                    // Standard JSR 223 JavaScript: prefix with "js:" or "javascript:"
                    trimmed.startsWith("js:") || trimmed.startsWith("javascript:") -> {
                        val jsVars = defaultData + vars + mapOf("player" to player, "bukkitPlayer" to player)
                        val jsScript = trimmed.substringAfter(":").trimStart()
                        val engine = jsEngine
                        if (engine != null) {
                            val bindings = engine.createBindings()
                            jsVars.forEach { (k, v) -> bindings[k] = v }
                            engine.eval(jsScript, bindings)
                        } else {
                            severeS("JavaScript engine not available for crafting station agent '$key'")
                        }
                    }
                    // Default: Kether (backward compatible)
                    else -> {
                        // textLines: a Java 8-safe implementation (String.lines is a Java 11 member method; see StringUtil.kt)
                        script.textLines().evalKether(player, vars)
                    }
                }
            } catch (e: Exception) {
                severeS("CraftingStation agent '$key' execution failed: ${e.message}")
            }
        }
    }

    // ==================== Crafting Session ====================

    data class CraftingSession(
        val playerId: String,
        val sessionToken: Long,
        val stationId: String,
        val recipeId: String,
        val totalTicks: Int,
        private val task: PlatformTask,
        val consumedItems: MutableList<ConsumedItem> = mutableListOf(),
        /** Flag indicating the items were cancelled/returned, preventing the async task from returning them twice */
        val itemsReturned: AtomicBoolean = AtomicBoolean(false)
    ) {
        fun cancel() {
            task.cancel()
        }
    }

    /** Cancel all online crafting sessions and return items (used on reload) */
    fun cancelAll() {
        // NOT necessarily on the main thread: reload runs its publish phase inside an async
        // `runReload`, so this method may be called off-thread. `activeSessions` is a
        // ConcurrentHashMap, so snapshot+clear is safe from any thread, and the world/DB work below
        // is explicitly hopped to the main thread via callSyncMethod. (The previous comment claimed
        // this method already ran on the main thread, which was wrong — and it made the `if
        // (isPrimaryThread())` branch below look like dead code.)
        val snapshot = HashMap(activeSessions)
        activeSessions.clear()

        val onlinePlayers = mutableMapOf<String, CraftingSession>()
        for ((_, session) in snapshot) {
            session.cancel()
            val player = org.bukkit.Bukkit.getPlayer(java.util.UUID.fromString(session.playerId))
            if (player != null) {
                onlinePlayers[session.playerId] = session
            }
            // Offline players: keep the DB data; restoreSession handles it on join
        }

        if (onlinePlayers.isEmpty()) return

        // Synchronously return items + clear the DB, blocking until done. Prevents a race between the subsequent craftingStations.clear() and restoreSession.
        try {
            val plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("Sertraline")
            if (plugin != null) {
                val returnItems = java.util.concurrent.Callable {
                    for ((playerId, session) in onlinePlayers) {
                        val player = org.bukkit.Bukkit.getPlayer(java.util.UUID.fromString(playerId))
                        if (player != null) {
                            returnConsumedItems(player, session)
                            CraftingStationDataManager.clearSession(player)
                        }
                    }
                    null
                }
                if (org.bukkit.Bukkit.isPrimaryThread()) {
                    returnItems.call()
                } else {
                    org.bukkit.Bukkit.getScheduler().callSyncMethod(plugin, returnItems).get()
                }
            }
        } catch (e: Exception) {
            severeS("Failed to synchronously cancel crafting sessions: ${e.message}")
        }
    }

    /** Shut down all crafting tasks without clearing persisted data (used on server shutdown) */
    fun shutdownAll() {
        val snapshot = HashMap(activeSessions)
        activeSessions.clear()
        snapshot.values.forEach { it.cancel() }
    }

    /** Cancel a specific player's crafting session (called when the player disconnects) */
    fun cancelPlayerSession(playerId: String) {
        val session = activeSessions.remove(playerId) ?: return
        session.cancel()
    }
}
