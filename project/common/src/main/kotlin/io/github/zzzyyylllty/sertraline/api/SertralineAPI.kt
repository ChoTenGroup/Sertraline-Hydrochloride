package io.github.zzzyyylllty.sertraline.api

import io.github.zzzyyylllty.embiancomponent.tools.getComponentsJavaNMS
import io.github.zzzyyylllty.embiancomponent.tools.getComponentsNMS
import io.github.zzzyyylllty.sertraline.Sertraline
import io.github.zzzyyylllty.sertraline.config.TemplateManager
import io.github.zzzyyylllty.sertraline.config.loadItemFile as loadSertralineItemFile
import io.github.zzzyyylllty.sertraline.data.ModernSItem
import io.github.zzzyyylllty.sertraline.data.componentHelper
import io.github.zzzyyylllty.sertraline.function.data.getSertralineId
import io.github.zzzyyylllty.sertraline.item.itemSerializer
import io.github.zzzyyylllty.sertraline.listener.sertraline.builder.ExternalItemProcessors
import io.github.zzzyyylllty.sertraline.listener.sertraline.builder.ItemProcessor
import io.github.zzzyyylllty.sertraline.listener.sertraline.builder.ItemProcessorManager
import io.github.zzzyyylllty.sertraline.item.rebuild
import io.github.zzzyyylllty.sertraline.item.rebuildLore
import io.github.zzzyyylllty.sertraline.item.rebuildName
import io.github.zzzyyylllty.sertraline.item.rebuildUnsafe
import io.github.zzzyyylllty.sertraline.item.sertralineItemBuilder
import io.github.zzzyyylllty.sertraline.item.sertralineVarItemBuilder
import io.github.zzzyyylllty.sertraline.manager.ManagerRange
import io.github.zzzyyylllty.sertraline.manager.ManagerType
import io.github.zzzyyylllty.sertraline.manager.SubManagerType
import io.github.zzzyyylllty.sertraline.util.ItemTagUtil.parseMapNBT
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack
import taboolib.module.nms.getItemTag
import taboolib.platform.util.serializeToByteArray
import java.io.File

public class SertralineAPIImpl : SertralineAPI {
}

public object SertralineApiVersionN {
    const val MAJOR = 3
    const val MINOR = 13
    const val PATCH = 0
    const val FIX = 2
    val SERTRALINE_API_VER = SertralineApiVersion(MAJOR, MINOR, PATCH, FIX)
}

public data class SertralineApiVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val fix: Int = 0,
) : Comparable<SertralineApiVersion> {
    override fun compareTo(other: SertralineApiVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }, { it.fix })

    override fun toString() = "$major.$minor.$patch.$fix"
}

public interface SertralineAPI {

    // ==================== Item Query & Build ====================

    public val version: SertralineApiVersion get() = SertralineApiVersionN.SERTRALINE_API_VER

    /**
     * check if the itemstack is fully same as minecraft vanilla materials
     * */
    fun isSameAsMinecraftMaterial(item: ItemStack): Boolean {
        val vanillaItem = ItemStack(item.type)
        item.serialize()
        return item.getComponentsNMS() == vanillaItem.getComponentsNMS()
    }

    /**
     * itemmanager
     */
    public fun itemManager(): ItemProcessorManager = Sertraline.itemManager

    /**
     * Registers an item segment processor that survives Sertraline reloads.
     *
     * Use this instead of `itemManager().registerProcessor(...)`: the raw manager drops every
     * processor on `/sreload` (only built-in adapters are re-added), which silently disables
     * plugin-provided segments such as `itemdef` / `model` / `mag` / `ammo`.
     *
     * The processor runs only for items whose raw config contains [name].
     * Registration is idempotent — re-registering the same name replaces the previous processor.
     */
    public fun registerItemProcessor(name: String, processor: ItemProcessor) {
        ExternalItemProcessors.register(name, processor)
    }

    /**
     * Segments currently registered through [registerItemProcessor] (debugging aid).
     */
    public fun registeredItemProcessors(): List<String> = ExternalItemProcessors.names()

    /**
     * Load item definitions from one YAML/JSON file through Sertraline's normal item parser.
     * ItemLoadEvent processors (including plugin-provided item segments) run exactly as they do
     * for workspace files. This API publishes directly to the live persistent item registry.
     *
     * Call after Sertraline has completed startup configuration loading and after all item
     * processors have been registered. Re-loading the same file is idempotent by item ID.
     *
     * @return true if the file was accepted and parsed; false if unsupported, empty, or invalid.
     */
    public fun loadItemsFromFile(file: File): Boolean {
        if (!file.isFile || !file.canRead()) return false
        val extension = file.extension.lowercase()
        if (extension !in setOf("yml", "yaml", "json", "toml", "tml")) return false
        return loadSertralineItemFile(file, Regex(".*"))
    }

    /**
     * Get Sertraline Item Object
     */
    @Deprecated("Use SertralineItemAPI instead", ReplaceWith("SertralineItemAPI.getItem"))
     public fun getItem(s: String): ModernSItem? {
        // 注册表已泛化为 AbstractSItem；旧签名保持 JVM ABI 不变，非 Sertraline 物品返回 null
        return Sertraline.itemMap[s] as? ModernSItem
    }

    /**
     * Get Sertraline Item Object from ItemStack
     */
    @Deprecated("Use SertralineItemAPI instead", ReplaceWith("SertralineItemAPI.getItemFromStack"))
     public fun getItemFromStack(item: ItemStack): ModernSItem? {
        return Sertraline.itemMap[item.getSertralineId()] as? ModernSItem
    }

    /**
     * Get all registered items.
     * Returns an immutable copy of the current registry — mutations of the returned
     * map have no effect on the live registry, and concurrent reloads never surface
     * as partial views in the returned snapshot.
     *
     * 仅包含 Sertraline 物品；其他来源请使用 [SertralineItemAPI]。
     */
    @Deprecated("Use SertralineItemAPI instead", ReplaceWith("SertralineItemAPI.getAllItems"))
     public fun getAllItems(): Map<String, ModernSItem> {
        val snapshot = java.util.HashMap<String, ModernSItem>()
        for ((key, value) in Sertraline.itemMap) {
            if (value is ModernSItem) snapshot[key] = value
        }
        return java.util.Collections.unmodifiableMap(snapshot)
    }

    /**
     * Build an item for a player
     * @param [sItem] Sertraline item ID
     * @param [player] Player
     * @param [source] Source item, null for auto
     * @param [amount] Item amount
     * @param [overrideData] Override item data
     */
    public fun buildItem(sItem: String, player: Player?, source: ItemStack? = null, amount: Int = 1, overrideData: Map<String, Any?>? = null): ItemStack? {
        return sertralineItemBuilder(sItem, player, source, amount, overrideData)
    }

    /**
     * Build an item for a player with variables
     * @param [sItem] Sertraline item ID
     * @param [player] Player
     * @param [source] Source item, null for auto
     * @param [amount] Item amount
     * @param [overrideData] Override item data
     * @param [vars] Template variables (persisted to NBT)
     * @param [context] Runtime context (Player, Event, etc.), accessible via {context:xxx}, NOT persisted to NBT
     */
    public fun buildDataItem(sItem: String, player: Player?, source: ItemStack? = null, amount: Int = 1, overrideData: Map<String, Any?>? = null, vars: Map<String, Any?>? = null, context: Map<String, Any?>? = null): ItemStack? {
        return sertralineVarItemBuilder(sItem, player, source, amount, overrideData, vars, context)
    }

    /**
     * Get Sertraline ID from ItemStack, null if not a Sertraline item
     */
    public fun getId(itemStack: ItemStack): String? {
        return itemStack.getSertralineId()
    }

    /**
     * Get a val value from a Sertraline item object
     * @param [sItem] Sertraline item object
     * @param [valId] Val key
     * @return The val value, or null if not found
     */
    @Deprecated("Use SertralineItemAPI instead", ReplaceWith("SertralineItemAPI"))
     public fun getVal(sItem: ModernSItem, valId: String): Any? {
        return (sItem.getDeepData("sertraline:vals") as? Map<String, Any?>)?.get(valId)
    }

    /**
     * Get a val value from a Sertraline ItemStack
     * Reads the val by first resolving the underlying ModernSItem from the item (full serialization + tag processing)
     * @param [itemStack] Sertraline item
     * @param [valId] Val key
     * @param [player] Player context for dynamic/template resolution, required only when the item contains placeholders/dynamics
     * @return The val value, or null if the item is not a Sertraline item or the val is absent
     */
    public fun getValItemStack(itemStack: ItemStack, valId: String, player: Player? = null): Any? {
        val sItem = itemSerializer(itemStack, player) ?: return null
        return getVal(sItem as? ModernSItem ?: return null, valId)
    }

    /**
     * Get a var value from a Sertraline ItemStack
     * Reads the persisted var value from the item's NBT
     * @param [itemStack] Sertraline item
     * @param [varId] Var key
     * @return The var value, or null if not found
     * @param [itemStack] Sertraline item
     * @param [varId] Var key
     * @return The var value, or null if not found
     */
    public fun getVar(itemStack: ItemStack, varId: String): Any? {
        return itemStack.getItemTag(true)["sertraline_data"]?.parseMapNBT()?.get(varId)
    }

    /**
     * Check if ItemStack is a Sertraline item
     */
    public fun isValidItem(itemStack: ItemStack): Boolean {
        return itemStack.getSertralineId() != null
    }

    /**
     * Check if ItemStack is registered in itemMap
     * `true` - Registered
     * `false` - Not registered
     */
    public fun isRegisteredItem(itemStack: ItemStack): Boolean {
        return Sertraline.itemMap[itemStack.getSertralineId()] != null
    }

    /**
     * Check if the given ID is registered in itemMap
     * `true` - Registered
     * `false` - Not registered
     */
    public fun isRegisteredItem(s: String): Boolean {
        return Sertraline.itemMap[s] != null
    }

    // ==================== Player Inventory Items ====================

    /**
     * Count Sertraline items in the player's inventory.
     * In private scope, verifies the ID currently belongs to the target player's private manager (temporary first, persistent fallback).
     * This synchronous method must be called on the player's thread.
     */
    fun countItem(
        id: String,
        player: Player,
        range: ManagerRange = ManagerRange.PUBLIC,
        slots: InventorySlotRange = InventorySlotRange.STORAGE
    ): Int = countInventoryItems(id, player, range, slots, verifyPrivateOwner = true)

    /**
     * Check whether the player's inventory has at least the specified amount of Sertraline items.
     * Non-positive amounts always return false; this synchronous method must be called on the player's thread.
     */
    fun hasItem(
        id: String,
        player: Player,
        amount: Int = 1,
        range: ManagerRange = ManagerRange.PUBLIC,
        slots: InventorySlotRange = InventorySlotRange.STORAGE
    ): Boolean = hasInventoryItems(id, player, amount, range, slots, verifyPrivateOwner = true)

    /**
     * Remove as many Sertraline items as possible from the player's inventory and return the amount actually removed.
     * Non-positive amounts return 0; this synchronous method must be called on the player's thread.
     */
    fun takeItem(
        id: String,
        player: Player,
        amount: Int = 1,
        range: ManagerRange = ManagerRange.PUBLIC,
        slots: InventorySlotRange = InventorySlotRange.STORAGE
    ): Int = takeInventoryItems(id, player, amount, range, slots, verifyPrivateOwner = true, exactly = false)

    /**
     * Only remove Sertraline items from the player's inventory when the full amount is available.
     * If the amount is insufficient or non-positive, the inventory is left unchanged and false is returned; this synchronous method must be called on the player's thread.
     */
    fun takeItemExactly(
        id: String,
        player: Player,
        amount: Int = 1,
        range: ManagerRange = ManagerRange.PUBLIC,
        slots: InventorySlotRange = InventorySlotRange.STORAGE
    ): Boolean = amount > 0 &&
        takeInventoryItems(id, player, amount, range, slots, verifyPrivateOwner = true, exactly = true) == amount

    /**
     * Count Sertraline items in the player's inventory, without verifying whether a private item belongs to that player.
     * This synchronous method must be called on the player's thread.
     */
    fun countItemAnyOwner(
        id: String,
        player: Player,
        range: ManagerRange = ManagerRange.PUBLIC,
        slots: InventorySlotRange = InventorySlotRange.STORAGE
    ): Int = countInventoryItems(id, player, range, slots, verifyPrivateOwner = false)

    /**
     * Check whether the player's inventory has at least the specified amount of Sertraline items, without verifying private-item ownership.
     * Non-positive amounts always return false; this synchronous method must be called on the player's thread.
     */
    fun hasItemAnyOwner(
        id: String,
        player: Player,
        amount: Int = 1,
        range: ManagerRange = ManagerRange.PUBLIC,
        slots: InventorySlotRange = InventorySlotRange.STORAGE
    ): Boolean = hasInventoryItems(id, player, amount, range, slots, verifyPrivateOwner = false)

    /**
     * Remove as many Sertraline items as possible from the player's inventory, without verifying private-item ownership.
     * Returns the amount actually removed; this synchronous method must be called on the player's thread.
     */
    fun takeItemAnyOwner(
        id: String,
        player: Player,
        amount: Int = 1,
        range: ManagerRange = ManagerRange.PUBLIC,
        slots: InventorySlotRange = InventorySlotRange.STORAGE
    ): Int = takeInventoryItems(id, player, amount, range, slots, verifyPrivateOwner = false, exactly = false)

    /**
     * Only deducts the full Sertraline item amount when it is sufficient, without verifying private-item ownership.
     * Returns false without touching the inventory when the amount is insufficient or non-positive; this synchronous method must be called on the player's thread.
     */
    fun takeItemAnyOwnerExactly(
        id: String,
        player: Player,
        amount: Int = 1,
        range: ManagerRange = ManagerRange.PUBLIC,
        slots: InventorySlotRange = InventorySlotRange.STORAGE
    ): Boolean = amount > 0 &&
        takeInventoryItems(id, player, amount, range, slots, verifyPrivateOwner = false, exactly = true) == amount

    /**
     * Re-generate lore for a Sertraline item
     * Will modify the input item in-place
     */
    fun rebuildLore(itemStack: ItemStack, player: Player?) {
        itemStack.rebuildLore(player)
    }

    /**
     * Re-generate display name for a Sertraline item
     * Will modify the input item in-place
     */
    fun rebuildName(itemStack: ItemStack, player: Player?) {
        itemStack.rebuildName(player)
    }

    /**
     * Re-build entire Sertraline item and return a new ItemStack
     * Will NOT modify the input item
     */
    fun rebuild(itemStack: ItemStack, player: Player?): ItemStack {
        return itemStack.rebuild(player)
    }

    /**
     * Re-build Sertraline item via ItemMeta and write to original item
     * Will modify the input item in-place
     * WARNING: Unsafe method — will lose some DataComponents (3 out of 76 in 1.21.4)
     */
    fun rebuildUnsafe(itemStack: ItemStack, player: Player?) {
        itemStack.rebuildUnsafe(player)
    }

    // ==================== Item Manager ====================
    // Supports public/private, persistent/temporary

    // ---- Public Items ----

    /**
     * Create a public item
     */
    fun createPublicItem(id: String, data: Map<String, Any?>, sub: SubManagerType = SubManagerType.TEMPORARY) {
        Sertraline.manager.createItem(ManagerType.PUBLIC, sub, id, data)
    }

    /**
     * Get a public item
     */
    @Deprecated("Use SertralineItemAPI instead")
     fun getPublicItem(id: String, sub: SubManagerType = SubManagerType.PERSISTENT): ModernSItem? {
        return Sertraline.manager.public.getItem(id, sub) as? ModernSItem
    }

    /**
     * Delete a public item
     */
    fun deletePublicItem(id: String, sub: SubManagerType): Boolean {
        return Sertraline.manager.public.remove(id, sub)
    }

    /**
     * Get all public items by sub-type
     */
    @Deprecated("Use SertralineItemAPI instead")
     fun getAllPublicItems(sub: SubManagerType): Map<String, ModernSItem> {
        return Sertraline.manager.public.getAll(sub).filterValues { it is ModernSItem }.mapValues { it.value as ModernSItem }
    }

    // ---- Private Items ----

    /**
     * Create a private item
     */
    fun createPrivateItem(uuid: String, id: String, data: Map<String, Any?>, sub: SubManagerType = SubManagerType.TEMPORARY) {
        Sertraline.manager.createItem(ManagerType.PRIVATE, sub, id, data, uuid)
    }

    /**
     * Get a private item
     */
    fun getPrivateItem(uuid: String, id: String, sub: SubManagerType = SubManagerType.PERSISTENT): ModernSItem? {
        return Sertraline.manager.privateManager.getItem(uuid, id, sub)
    }

    /**
     * Delete a private item
     */
    fun deletePrivateItem(uuid: String, id: String, sub: SubManagerType) {
        Sertraline.manager.deleteItem(ManagerType.PRIVATE, sub, id, uuid)
    }

    /**
     * Get all private items for the given UUID
     */
    fun getAllPrivateItems(uuid: String, sub: SubManagerType): Map<String, ModernSItem> {
        return Sertraline.manager.privateManager.getAll(uuid, sub)
    }

    /**
     * Update a private item in the selected manager.
     * Persistent updates are written back to the private-item database.
     * @return The previous item, or null if the ID was not registered
     */
    @Deprecated("Use SertralineItemAPI instead")
    fun updateSItem(
        uuid: String,
        id: String,
        item: ModernSItem,
        sub: SubManagerType = SubManagerType.PERSISTENT
    ): ModernSItem? {
        return Sertraline.manager.privateManager.updateItem(uuid, id, item, sub)
    }

    /**
     * Update a private item using the item's key as its ID.
     * @return The previous item, or null if the ID was not registered
     */
    @Deprecated("Use SertralineItemAPI instead")
    fun updateSItem(
        uuid: String,
        item: ModernSItem,
        sub: SubManagerType = SubManagerType.PERSISTENT
    ): ModernSItem? {
        return updateSItem(uuid, item.key, item, sub)
    }

    // ---- General Management ----

    /**
     * Register an item directly into itemMap (public-persistent)
     */
    @Deprecated("Use SertralineItemAPI instead")
    fun registerItem(id: String, item: ModernSItem) {
        Sertraline.itemMap[id] = item
    }

    /**
     * Update an item directly in itemMap (public-persistent).
     *
     * ⚠ Overload trap: `updateSItem("uuid-or-id", item)` binds to **this** method and writes to the
     * public registry, not to the private-item database. For private items use
     * [updateSItem]`(uuid, id, item, sub)` — three or four arguments. The two-argument and
     * three-argument `(uuid, item, sub)` forms are easy to confuse because both take a [String]
     * first, so the private-item variant is spelled out here deliberately.
     * @return The previous item, or null if the ID was not registered
     */
    @Deprecated("Use SertralineItemAPI instead")
    fun updateSItem(id: String, item: ModernSItem): ModernSItem? {
        return Sertraline.itemMap.put(id, item) as? ModernSItem
    }

    /**
     * Update an item directly in itemMap using the item's key as its ID.
     * @return The previous item, or null if the ID was not registered
     */
    @Deprecated("Use SertralineItemAPI instead")
    fun updateSItem(item: ModernSItem): ModernSItem? {
        return updateSItem(item.key, item)
    }

    /**
     * Update a **private** item, unambiguous alias of [updateSItem]`(uuid, id, item, sub)`.
     *
     * Provided so callers never hit the `String`-first overload trap above by accident.
     */
    @Deprecated("Use SertralineItemAPI instead")
    fun updatePrivateItem(
        uuid: String,
        id: String,
        item: ModernSItem,
        sub: SubManagerType = SubManagerType.PERSISTENT
    ): ModernSItem? = Sertraline.manager.privateManager.updateItem(uuid, id, item, sub)

    /**
     * Remove an item from itemMap
     * @return The removed item, or null if not found
     */
    @Deprecated("Use SertralineItemAPI instead")
    fun unregisterItem(id: String): ModernSItem? {
        return Sertraline.itemMap.remove(id) as? ModernSItem
    }

    /**
     * Get total item count in itemMap
     *
     * Counts **all** registered sources (Sertraline + Minecraft + external). That is deliberately
     * wider than the deprecated [getAllItems], which only returns Sertraline items; use
     * [SertralineItemAPI.getAllItems]`.size` (or `getSertralineItems().size`) when the two numbers
     * are expected to agree.
     */
    fun getItemCount(): Int = Sertraline.itemMap.size

    /**
     * Resolve a private UUID with automatic fallback
     */
    fun resolvePrivateUuid(uuid: String?, playerUuid: String?): String {
        return Sertraline.manager.resolvePrivateUuid(uuid, playerUuid)
    }

    // ==================== Template System ====================

    /**
     * Get a loaded template by name (no parsing)
     */
    fun getTemplate(name: String): Map<String, Any?>? {
        return TemplateManager.getTemplate(name)
    }

    /**
     * Get all loaded template names
     */
    fun getTemplateNames(): Set<String> {
        return TemplateManager.getTemplateNames()
    }

    /**
     * Get all loaded templates (immutable snapshot)
     */
    fun getAllTemplates(): Map<String, Map<String, Any?>> {
        return TemplateManager.getAllTemplates()
    }

    /**
     * Manually resolve a template: deep-copy → param substitution → recursive resolve
     */
    fun resolveTemplate(name: String, args: Map<String, String>): Map<String, Any?>? {
        return TemplateManager.resolveTemplate(name, args)
    }

    /**
     * Get the number of loaded templates
     */
    fun getTemplateCount(): Int = TemplateManager.templateCount()

    // ==================== Template Processor Registration ====================
    // Register custom $t transformers, $c converters, and argument-level directives

    /**
     * Register a custom $t transformer type
     */
    fun registerTransformer(type: String, provider: TemplateManager.TransformerProvider) {
        TemplateManager.registerTransformer(type, provider)
    }

    /**
     * Unregister a custom $t transformer type
     */
    fun unregisterTransformer(type: String) {
        TemplateManager.unregisterTransformer(type)
    }

    /**
     * Register a custom $c converter type
     */
    fun registerConverter(type: String, provider: TemplateManager.ConverterProvider) {
        TemplateManager.registerConverter(type, provider)
    }

    /**
     * Unregister a custom $c converter type
     */
    fun unregisterConverter(type: String) {
        TemplateManager.unregisterConverter(type)
    }

    /**
     * Register a custom argument-level directive (same level as $t/$c, e.g. $myDirective)
     */
    fun registerDirective(name: String, provider: TemplateManager.DirectiveProvider) {
        TemplateManager.registerDirective(name, provider)
    }

    /**
     * Unregister a custom argument-level directive
     */
    fun unregisterDirective(name: String) {
        TemplateManager.unregisterDirective(name)
    }
}
