package io.github.zzzyyylllty.sertraline.compat

import io.github.zzzyyylllty.embiancomponent.EmbianComponent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.BanList
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.OfflinePlayer
import org.bukkit.World
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarFlag
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.command.CommandSender
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.Event
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.Merchant
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.plugin.Plugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

/**
 * Sertraline platform adapter layer (Spigot implementation, binary-compatible with the same-named class in the paper source set).
 * Depends only on the Bukkit API + bundled adventure, zero reflection, pure string fallback.
 * Same-named methods keep signatures fully identical to the Paper implementation (common code compiles one of them depending on the build platform).
 */
object PlatformCompat {

    val isPaper: Boolean by lazy {
        try {
            Class.forName("io.papermc.paper.ServerBuildInfo")
            true
        } catch (_: Throwable) {
            false
        }
    }

    // Can't use legacySection() (adventure 4.17+ does not enable hex by default, hex would be downgraded to the nearest 16 colors);
    // can't use default hexColors() either (outputs the simplified §#RRGGBB format; the Spigot server's CraftChatMessage
    // legacy parser doesn't recognize §#, treating it as literal text); must use the §x full format, supported by both Spigot and Paper servers
    private val LEGACY: LegacyComponentSerializer by lazy {
        LegacyComponentSerializer.builder()
            .character('§')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build()
    }
    private val warnedItemModel = AtomicBoolean(false)
    private val warnedRestart = AtomicBoolean(false)

    private fun warnOnce(flag: AtomicBoolean, feature: String) {
        if (flag.compareAndSet(false, true)) {
            CompatLog.warning("Warning_Paper_Only_Feature", feature)
        }
    }

    // ── Messages ──────────────────────────────────────────────────────────
    // Messages/ActionBar go through bungee BaseComponents (hex colors, decorations, events preserved), not downgraded to § strings;
    // Title has no BaseComponent API, now goes through LEGACY (the §x full format, hex preserved after server-side parsing);
    // the LEGACY serializer is only left for places without a BaseComponent API like Title/ItemMeta/Inventory

    fun sendComponent(sender: CommandSender, component: Component) {
        sender.spigot().sendMessage(BungeeComponentConverter.convert(component))
    }

    fun sendActionBar(player: Player, component: Component) {
        // spigot-api 1.21.4 removed sendActionBar(String); only the bungee legacy API remains
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, BungeeComponentConverter.convert(component))
    }

    fun sendActionBarString(player: Player, string: String) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent(string))
    }

    fun sendTitle(player: Player, title: Component?, subtitle: Component?, fadeIn: Int, stay: Int, fadeOut: Int) {
        // spigot-api 1.21.4 has no BaseComponent title API, so only the String overload is possible;
        // LEGACY is the §x full format; hex colors remain faithful after CraftChatMessage parses them
        player.sendTitle(title?.let { LEGACY.serialize(it) }, subtitle?.let { LEGACY.serialize(it) }, fadeIn, stay, fadeOut)
    }

    fun kick(player: Player, component: Component?) {
        player.kickPlayer(component?.let { LEGACY.serialize(it) })
    }

    fun setPlayerDisplayName(player: Player, component: Component?) {
        player.setDisplayName(component?.let { LEGACY.serialize(it) })
    }

    fun setPlayerListName(player: Player, component: Component?) {
        player.setPlayerListName(component?.let { LEGACY.serialize(it) })
    }

    fun getPlayerListName(player: Player): Component? = LEGACY.deserialize(player.playerListName)

    // ── ItemMeta / ItemStack ──────────────────────────────────────────────

    fun setDisplayName(meta: ItemMeta, component: Component): ItemMeta {
        meta.setDisplayName(LEGACY.serialize(component))
        return meta
    }

    fun setCustomName(meta: ItemMeta, component: Component): ItemMeta {
        meta.setDisplayName(LEGACY.serialize(component))
        return meta
    }

    fun setItemName(meta: ItemMeta, component: Component): ItemMeta {
        meta.setDisplayName(LEGACY.serialize(component))
        return meta
    }

    fun setLore(meta: ItemMeta, components: List<Component>?): ItemMeta {
        meta.lore = components?.map { LEGACY.serialize(it) }
        return meta
    }

    fun setItemModel(meta: ItemMeta, key: NamespacedKey?): ItemMeta {
        warnOnce(warnedItemModel, "item model")
        return meta
    }

    fun setCustomModelData(meta: ItemMeta, value: Int): ItemMeta {
        // setCustomModelData is a 1.14+ Bukkit API, absent in the legacy12 (v11200) compile surface.
        // Reflectively call it to be compatible with both compile surfaces; silently ignored when absent (the visual module is disabled on lower versions, so this is normally never reached)
        try {
            meta::class.java.getMethod("setCustomModelData", Int::class.java).invoke(meta, value)
        } catch (_: Throwable) {
        }
        return meta
    }

    fun getDisplayName(item: ItemStack): Component {
        return item.itemMeta?.displayName?.let { LEGACY.deserialize(it) }
            ?: Component.translatable(translationKey(item.type))
    }

    // effectiveName: 1.21.4+ prefers the ITEM_NAME component (rendered without [], while displayName/CUSTOM_NAME render with white []);
    // itemName() is a 1.21.4+ API, absent in the v11200 compile surface and on lower-version runtimes; on a missing reflective call it falls back to getDisplayName
    private val itemNameMethod: Method? by lazy {
        try { ItemMeta::class.java.getMethod("itemName") } catch (_: Throwable) { null }
    }

    fun getEffectiveName(item: ItemStack): Component {
        return item.itemMeta?.let { meta ->
            try {
                itemNameMethod?.invoke(meta) as? Component
            } catch (_: Throwable) {
                null
            }
        } ?: getDisplayName(item)
    }

    // Material.getTranslationKey is a 1.13+ API; absent in v11200 (1.12.2), falling back to the legacy key (item.xxx / tile.xxx)
    private val getTranslationKeyMethod: Method? by lazy {
        try { org.bukkit.Material::class.java.getMethod("getTranslationKey") } catch (_: Throwable) { null }
    }

    private fun translationKey(material: org.bukkit.Material): String {
        return try {
            getTranslationKeyMethod?.invoke(material) as? String
                ?: legacyTranslationKey(material)
        } catch (_: Throwable) {
            legacyTranslationKey(material)
        }
    }

    private fun legacyTranslationKey(material: org.bukkit.Material): String {
        val name = material.name.lowercase()
        return if (material.isBlock) "tile.$name" else "item.$name"
    }

    fun getLore(item: ItemStack): List<Component>? {
        return item.itemMeta?.lore?.map { LEGACY.deserialize(it) }
    }

    fun setLore(item: ItemStack, components: List<Component>?): ItemStack {
        val meta = item.itemMeta ?: return item
        meta.lore = components?.map { LEGACY.serialize(it) }
        item.setItemMeta(meta)
        return item
    }

    fun setDataComponent(item: ItemStack, typeName: String, value: Any): ItemStack {
        // data components are uniformly handled by EmbianComponent (NMS reflection path, safely ignored when the component doesn't exist)
        return EmbianComponent.SafetyComponentSetter.setComponent(item, typeName.lowercase(), value) ?: item
    }

    fun createInventory(owner: InventoryHolder?, type: InventoryType, component: Component): Inventory {
        return Bukkit.createInventory(owner, type, LEGACY.serialize(component))
    }

    fun createInventory(owner: InventoryHolder?, size: Int, component: Component): Inventory {
        return Bukkit.createInventory(owner, size, LEGACY.serialize(component))
    }

    fun createMerchant(component: Component?): Merchant {
        return Bukkit.createMerchant(component?.let { LEGACY.serialize(it) })
    }

    fun createMerchant(): Merchant {
        // Bukkit.createMerchant() no-arg overload is a 1.21 API; v11200 (1.12.2) only has createMerchant(String); uniformly use the String overload
        return Bukkit.createMerchant("")
    }

    // ── Script bridge: Paper extensions of Server (Spigot fallback values) ─

    fun getMinecraftVersion(): String? = Bukkit.getVersion().substringBefore("-")

    fun getTPS(): DoubleArray? = doubleArrayOf(-1.0, -1.0, -1.0)

    fun getAverageTickTime(): Double? = -1.0

    fun getCurrentTick(): Int? = -1

    fun getOfflinePlayerIfCached(name: String): OfflinePlayer? = Bukkit.getOfflinePlayer(name)

    fun getPlayerUniqueId(name: String): UUID? = Bukkit.getOfflinePlayer(name).uniqueId

    fun broadcast(component: Component): Unit? {
        Bukkit.broadcastMessage(LEGACY.serialize(component))
        return Unit
    }

    fun broadcast(component: Component, permission: String): Unit? {
        Bukkit.broadcast(LEGACY.serialize(component), permission)
        return Unit
    }

    fun getCommandMap(): Any? = null

    fun isTickingWorlds(): Boolean? = null

    fun getWorldByKey(key: NamespacedKey): World? =
        if (key.namespace == "minecraft") Bukkit.getWorld(key.key) else null

    fun updateResources(): Unit? = Unit

    fun getPluginsFolder(): File? = java.io.File(Bukkit.getWorldContainer(), "plugins")

    fun restart(): Unit? {
        // Spigot has no restart API; after shutdown the startup script restarts the server (close to Paper's behavior)
        warnOnce(warnedRestart, "server restart (falling back to shutdown)")
        Bukkit.shutdown()
        return Unit
    }

    fun isStopping(): Boolean? = false

    fun getMotdComponent(): Component? = null

    fun setMotdComponent(component: Component) {
        // Bukkit.setMotd is a 1.13+ API, absent in v11200 (1.12.2); reflectively call it, silently ignore when missing
        try {
            setMotdMethod?.invoke(null, LEGACY.serialize(component))
        } catch (_: Throwable) {
        }
    }

    fun getShutdownMessage(): Component? = null

    fun getPermissionMessageComponent(): Component? = null

    fun getMobGoals(): Any? = null

    fun getDatapackManager(): Any? = null

    private val getStructureManagerMethod: Method? by lazy {
        try { Bukkit::class.java.getMethod("getStructureManager") } catch (_: Throwable) { null }
    }

    fun getStructureManager(): Any? = invokeGet(getStructureManagerMethod)

    fun getPotionBrewer(): Any? = null

    fun getUnsafe(): Any? = null

    fun getVersionMessage(): String? = null

    fun getPermissionMessage(): String? = null

    fun reloadPermissions(): Unit? = Unit

    fun reloadCommandAliases(): Unit? = Unit

    fun suggestPlayerNamesWhenNullTabCompletions(): Boolean? = null

    // Bukkit.createPlayerProfile (org.bukkit.profile.PlayerProfile) is a 1.18.2+ API, absent in v11200 (1.12.2);
    // reflectively call it, returning null when missing (script bridge fallback)
    private val createPlayerProfileMethod: Method? by lazy {
        try { Bukkit::class.java.getMethod("createPlayerProfile", UUID::class.java) } catch (_: Throwable) { null }
    }
    private val createPlayerProfileWithNameMethod: Method? by lazy {
        try { Bukkit::class.java.getMethod("createPlayerProfile", UUID::class.java, String::class.java) } catch (_: Throwable) { null }
    }
    private val createPlayerProfileByNameMethod: Method? by lazy {
        try { Bukkit::class.java.getMethod("createPlayerProfile", String::class.java) } catch (_: Throwable) { null }
    }
    private val setMotdMethod: Method? by lazy {
        try { Bukkit::class.java.getMethod("setMotd", String::class.java) } catch (_: Throwable) { null }
    }

    fun createProfile(uuid: UUID?, name: String?): Any? {
        if (uuid == null) return null
        return try {
            if (name == null) createPlayerProfileMethod?.invoke(null, uuid)
            else createPlayerProfileWithNameMethod?.invoke(null, uuid, name)
        } catch (_: Throwable) {
            null
        }
    }

    fun createProfile(name: String): Any? {
        return try {
            createPlayerProfileByNameMethod?.invoke(null, name)
        } catch (_: Throwable) {
            null
        }
    }

    fun createProfileExact(uuid: UUID?, name: String?): Any? = uuid?.let { createProfile(it, name) }

    fun createPlayerProfile(uuid: UUID?, name: String?): Any? = createProfile(uuid, name)

    fun createPlayerProfile(name: String): Any? = createProfile(name)

    // ── Script bridge: 1.13+ Bukkit API (absent in the legacy12 (v11200) compile surface, all reflectively called, silently degraded when missing at runtime) ──

    private fun invokeGet(method: Method?): Any? = try { method?.invoke(null) } catch (_: Throwable) { null }

    private val setMaxPlayersMethod: Method? by lazy {
        try { Bukkit::class.java.getMethod("setMaxPlayers", Int::class.javaPrimitiveType) } catch (_: Throwable) { null }
    }
    private val getSimulationDistanceMethod: Method? by lazy {
        try { Bukkit::class.java.getMethod("getSimulationDistance") } catch (_: Throwable) { null }
    }
    private val getMaxWorldSizeMethod: Method? by lazy {
        try { Bukkit::class.java.getMethod("getMaxWorldSize") } catch (_: Throwable) { null }
    }
    private val isLoggingIPsMethod: Method? by lazy {
        try { Bukkit::class.java.getMethod("isLoggingIPs") } catch (_: Throwable) { null }
    }
    private val isWhitelistEnforcedMethod: Method? by lazy {
        try { Bukkit::class.java.getMethod("isWhitelistEnforced") } catch (_: Throwable) { null }
    }
    private val setWhitelistEnforcedMethod: Method? by lazy {
        try { Bukkit::class.java.getMethod("setWhitelistEnforced", Boolean::class.javaPrimitiveType) } catch (_: Throwable) { null }
    }
    private val getTicksPerWaterSpawnsMethod: Method? by lazy {
        try { Bukkit::class.java.getMethod("getTicksPerWaterSpawns") } catch (_: Throwable) { null }
    }
    private val getTicksPerWaterAmbientSpawnsMethod: Method? by lazy {
        try { Bukkit::class.java.getMethod("getTicksPerWaterAmbientSpawns") } catch (_: Throwable) { null }
    }
    private val getTicksPerAmbientSpawnsMethod: Method? by lazy {
        try { Bukkit::class.java.getMethod("getTicksPerAmbientSpawns") } catch (_: Throwable) { null }
    }
    private val createWorldBorderMethod: Method? by lazy {
        try { Bukkit::class.java.getMethod("createWorldBorder") } catch (_: Throwable) { null }
    }
    private val getRecipeMethod: Method? by lazy {
        try { Bukkit::class.java.getMethod("getRecipe", NamespacedKey::class.java) } catch (_: Throwable) { null }
    }
    private val removeRecipeMethod: Method? by lazy {
        try { Bukkit::class.java.getMethod("removeRecipe", NamespacedKey::class.java) } catch (_: Throwable) { null }
    }

    fun setMaxPlayers(value: Int): Unit? {
        try { setMaxPlayersMethod?.invoke(null, value) } catch (_: Throwable) { }
        return Unit
    }

    fun getSimulationDistance(): Int? = invokeGet(getSimulationDistanceMethod) as? Int

    fun getMaxWorldSize(): Int? = invokeGet(getMaxWorldSizeMethod) as? Int

    fun isLoggingIPs(): Boolean? = invokeGet(isLoggingIPsMethod) as? Boolean

    fun isWhitelistEnforced(): Boolean? = invokeGet(isWhitelistEnforcedMethod) as? Boolean

    fun setWhitelistEnforced(value: Boolean): Unit? {
        try { setWhitelistEnforcedMethod?.invoke(null, value) } catch (_: Throwable) { }
        return Unit
    }

    fun getTicksPerWaterSpawns(): Int? = invokeGet(getTicksPerWaterSpawnsMethod) as? Int

    fun getTicksPerWaterAmbientSpawns(): Int? = invokeGet(getTicksPerWaterAmbientSpawnsMethod) as? Int

    fun getTicksPerAmbientSpawns(): Int? = invokeGet(getTicksPerAmbientSpawnsMethod) as? Int

    fun createWorldBorder(): Any? = invokeGet(createWorldBorderMethod)

    fun getRecipe(key: NamespacedKey): Any? = try { getRecipeMethod?.invoke(null, key) } catch (_: Throwable) { null }

    fun removeRecipe(key: NamespacedKey): Unit? {
        try { removeRecipeMethod?.invoke(null, key) } catch (_: Throwable) { }
        return Unit
    }

    // getBanList(BanList.Type) exists in both versions (1.0 API); the PROFILE enum constant is 1.20.2+, obtained via reflection, returns null when unobtainable
    private val banListProfileType: BanList.Type? by lazy {
        try { BanList.Type::class.java.getField("PROFILE").get(null) as? BanList.Type } catch (_: Throwable) { null }
    }

    fun getBanListIP(): Any? = Bukkit.getBanList(BanList.Type.IP)

    fun getBanListProfile(): Any? = banListProfileType?.let { Bukkit.getBanList(it) }

    fun getBanListName(): Any? = Bukkit.getBanList(BanList.Type.NAME)

    // createBossBar(NamespacedKey, ...) is 1.16+; v11200 only has the String version (1.9 API), the key is ignored
    private val createBossBarKeyedMethod: Method? by lazy {
        try {
            Bukkit::class.java.getMethod(
                "createBossBar",
                NamespacedKey::class.java,
                String::class.java,
                BarColor::class.java,
                BarStyle::class.java,
                Array<BarFlag>::class.java
            )
        } catch (_: Throwable) { null }
    }
    private val getBossBarsMethod: Method? by lazy {
        try { Bukkit::class.java.getMethod("getBossBars") } catch (_: Throwable) { null }
    }
    private val getBossBarMethod: Method? by lazy {
        try { Bukkit::class.java.getMethod("getBossBar", NamespacedKey::class.java) } catch (_: Throwable) { null }
    }
    private val removeBossBarMethod: Method? by lazy {
        try { Bukkit::class.java.getMethod("removeBossBar", NamespacedKey::class.java) } catch (_: Throwable) { null }
    }

    fun createBossBar(key: NamespacedKey, title: String, color: BarColor, style: BarStyle, flags: Array<BarFlag>): BossBar? {
        return try {
            createBossBarKeyedMethod?.invoke(null, key, title, color, style, flags) as? BossBar
                ?: Bukkit.createBossBar(title, color, style, *flags)
        } catch (_: Throwable) {
            null
        }
    }

    fun getBossBars(): Any? = invokeGet(getBossBarsMethod)

    fun getBossBar(key: NamespacedKey): Any? = try { getBossBarMethod?.invoke(null, key) } catch (_: Throwable) { null }

    fun removeBossBar(key: NamespacedKey): Unit? {
        try { removeBossBarMethod?.invoke(null, key) } catch (_: Throwable) { }
        return Unit
    }

    // BlockData is 1.13+; v11200 has no org.bukkit.block.data package at all; all-reflective, returns null on lower versions
    private val createBlockDataMethod: Method? by lazy {
        try { Bukkit::class.java.getMethod("createBlockData", String::class.java) } catch (_: Throwable) { null }
    }
    private val createBlockDataMaterialMethod: Method? by lazy {
        try { Bukkit::class.java.getMethod("createBlockData", org.bukkit.Material::class.java) } catch (_: Throwable) { null }
    }
    private val createBlockDataMaterialStringMethod: Method? by lazy {
        try { Bukkit::class.java.getMethod("createBlockData", org.bukkit.Material::class.java, String::class.java) } catch (_: Throwable) { null }
    }
    private val createBlockDataMaterialConsumerMethod: Method? by lazy {
        try { Bukkit::class.java.getMethod("createBlockData", org.bukkit.Material::class.java, Consumer::class.java) } catch (_: Throwable) { null }
    }

    fun createBlockData(materialOrData: Any, consumerOrData: Any?): Any? {
        return when (materialOrData) {
            is org.bukkit.Material -> when (consumerOrData) {
                null -> try { createBlockDataMaterialMethod?.invoke(null, materialOrData) } catch (_: Throwable) { null }
                is String -> try { createBlockDataMaterialStringMethod?.invoke(null, materialOrData, consumerOrData) } catch (_: Throwable) { null }
                is Consumer<*> -> try { createBlockDataMaterialConsumerMethod?.invoke(null, materialOrData, consumerOrData) } catch (_: Throwable) { null }
                else -> null
            }
            is String -> try { createBlockDataMethod?.invoke(null, materialOrData) } catch (_: Throwable) { null }
            else -> null
        }
    }

    private val selectEntitiesMethod: Method? by lazy {
        try { Bukkit::class.java.getMethod("selectEntities", CommandSender::class.java, String::class.java) } catch (_: Throwable) { null }
    }
    private val getLootTableMethod: Method? by lazy {
        try { Bukkit::class.java.getMethod("getLootTable", NamespacedKey::class.java) } catch (_: Throwable) { null }
    }
    private val getRegistryMethod: Method? by lazy {
        try { Bukkit::class.java.getMethod("getRegistry", Class::class.java) } catch (_: Throwable) { null }
    }
    private val getTagMethod: Method? by lazy {
        try { Bukkit::class.java.getMethod("getTag", String::class.java, NamespacedKey::class.java, Class::class.java) } catch (_: Throwable) { null }
    }
    private val getTagsMethod: Method? by lazy {
        try { Bukkit::class.java.getMethod("getTags", String::class.java, Class::class.java) } catch (_: Throwable) { null }
    }

    @Suppress("UNCHECKED_CAST")
    fun selectEntities(sender: CommandSender, selector: String): List<Entity>? =
        try { selectEntitiesMethod?.invoke(null, sender, selector) as? List<Entity> } catch (_: Throwable) { null }

    fun getLootTable(key: NamespacedKey): Any? = try { getLootTableMethod?.invoke(null, key) } catch (_: Throwable) { null }

    fun getRegistry(clazz: Class<*>): Any? = try { getRegistryMethod?.invoke(null, clazz) } catch (_: Throwable) { null }

    fun getTag(registry: String, tagKey: NamespacedKey, clazz: Class<*>): Any? =
        try { getTagMethod?.invoke(null, registry, tagKey, clazz) } catch (_: Throwable) { null }

    fun getTags(registry: String, clazz: Class<*>): Any? =
        try { getTagsMethod?.invoke(null, registry, clazz) } catch (_: Throwable) { null }

    fun setMotdLegacy(motd: String): Unit? {
        try { setMotdMethod?.invoke(null, motd) } catch (_: Throwable) { }
        return Unit
    }

    // Attribute / AttributeModifier are 1.13+, absent in the v11200 compile surface; all-reflective, silently ignored on lower versions
    fun fixMmoAttackSpeed(meta: ItemMeta): ItemMeta {
        try {
            val attributeClass = Class.forName("org.bukkit.attribute.Attribute")
            val modifierClass = Class.forName("org.bukkit.attribute.AttributeModifier")
            val operationClass = Class.forName("org.bukkit.attribute.AttributeModifier\$Operation")
            val attackSpeed = attributeClass.getField("ATTACK_SPEED").get(null)
            val addNumber = operationClass.getField("ADD_NUMBER").get(null)
            val ctor = modifierClass.getConstructor(String::class.java, Double::class.javaPrimitiveType, operationClass)
            val modifier = ctor.newInstance("mmoitems:decoy", 0.0, addNumber)
            meta.javaClass.getMethod("addAttributeModifier", attributeClass, modifierClass).invoke(meta, attackSpeed, modifier)
        } catch (_: Throwable) {
        }
        return meta
    }

    // The PotionEffect 6-boolean-arg constructor is 1.13+; v11200 only has 5/4-arg versions, degrading step by step
    private val potionEffectCtor6: Constructor<PotionEffect>? by lazy {
        try {
            PotionEffect::class.java.getConstructor(
                PotionEffectType::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            )
        } catch (_: Throwable) { null }
    }
    private val potionEffectCtor5: Constructor<PotionEffect>? by lazy {
        try {
            PotionEffect::class.java.getConstructor(
                PotionEffectType::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            )
        } catch (_: Throwable) { null }
    }
    private val potionEffectCtor4: Constructor<PotionEffect>? by lazy {
        try {
            PotionEffect::class.java.getConstructor(
                PotionEffectType::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            )
        } catch (_: Throwable) { null }
    }

    fun addPotionEffect(player: Player, type: String, duration: Int, amplifier: Int, ambient: Boolean, particles: Boolean, icon: Boolean): Unit? {
        val effectType = PotionEffectType.getByName(type) ?: return Unit
        val effect = try {
            potionEffectCtor6?.newInstance(effectType, duration, amplifier, ambient, particles, icon)
                ?: potionEffectCtor5?.newInstance(effectType, duration, amplifier, ambient, particles)
                ?: potionEffectCtor4?.newInstance(effectType, duration, amplifier, ambient)
        } catch (_: Throwable) {
            null
        }
        if (effect != null) {
            try { player.addPotionEffect(effect) } catch (_: Throwable) { }
        }
        return Unit
    }

    // ── Attack info (damageSource 1.13+ / AbstractArrow.weapon 1.16+, reflection fallback) ──

    private val getDamageSourceMethod: Method? by lazy {
        try { EntityDamageByEntityEvent::class.java.getMethod("getDamageSource") } catch (_: Throwable) { null }
    }

    fun getDamageCausingPlayer(e: EntityDamageByEntityEvent): Player? {
        val causing = try {
            val source = getDamageSourceMethod?.invoke(e) ?: return legacyCausingPlayer(e)
            source.javaClass.getMethod("causingEntity").invoke(source) as? Player
        } catch (_: Throwable) {
            null
        }
        return causing ?: legacyCausingPlayer(e)
    }

    fun getDamageDirectEntity(e: EntityDamageByEntityEvent): Entity? {
        val direct = try {
            val source = getDamageSourceMethod?.invoke(e) ?: return legacyDirectEntity(e)
            source.javaClass.getMethod("directEntity").invoke(source) as? Entity
        } catch (_: Throwable) {
            null
        }
        return direct ?: legacyDirectEntity(e)
    }

    private fun legacyCausingPlayer(e: EntityDamageByEntityEvent): Player? {
        val damager = e.damager
        return when (damager) {
            is Player -> damager
            is Projectile -> damager.shooter as? Player
            else -> null
        }
    }

    private fun legacyDirectEntity(e: EntityDamageByEntityEvent): Entity? = e.damager

    fun getProjectileWeapon(entity: Entity?): ItemStack? {
        if (entity == null) return null
        return try {
            entity.javaClass.getMethod("getWeapon").invoke(entity) as? ItemStack
        } catch (_: Throwable) {
            null
        }
    }

    // ── Folia scheduler (not present on Spigot) ────────────────────────

    fun getRegionScheduler(): Any? = null

    fun getAsyncScheduler(): Any? = null

    fun getGlobalRegionScheduler(): Any? = null

    fun isGlobalTickThread(): Boolean? = false

    fun isOwnedByCurrentRegion(obj: Any, vararg args: Any?): Boolean = false

    // ── Events ────────────────────────────────────────────────────────

    fun callEvent(event: Event): Boolean {
        // spigot-api's PluginManager.callEvent returns void, while paper's Event.callEvent returns boolean
        Bukkit.getPluginManager().callEvent(event)
        return true
    }

    fun getConsumeReplacement(event: PlayerItemConsumeEvent): ItemStack? = null

    // SmithingInventory is a 1.16+ class, absent in the v11200 (1.12.2) compile surface; the param is degraded to Any? and cast to Inventory internally (present in all versions).
    // On legacy12 the smithing listener isn't compiled at all (see common's sourceSets exclude), so this method is never called
    // Both layouts put the result last: [base, addition, result] or [template, base, addition, result].
    fun getSmithingInputs(inventory: Any?): List<ItemStack> =
        (inventory as? Inventory)?.contents?.dropLast(1)?.filterNotNull() ?: emptyList()

    fun registerPaperEvents(plugin: Plugin, hooks: PaperEventHooks) {
        CompatLog.warning("Warning_Paper_Only_Events")
    }

    fun registerArmorPolling(plugin: Plugin, enabled: Boolean, onArmorChange: (Player) -> Unit) {
        if (!enabled) return
        val cache = HashMap<UUID, Array<ItemStack?>>()
        plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            val online = plugin.server.onlinePlayers
            if (online.isEmpty()) {
                cache.clear()
                return@Runnable
            }
            for (p in online) {
                val current = p.inventory.armorContents
                val prev = cache[p.uniqueId]
                if (prev != null && prev.contentEquals(current)) continue
                cache[p.uniqueId] = current.map { it?.clone() }.toTypedArray()
                if (prev != null) {
                    try {
                        onArmorChange(p)
                    } catch (e: Exception) {
                        CompatLog.warning("Warning_Armor_Polling_Failed", e.message ?: e.javaClass.simpleName)
                    }
                }
            }
            cache.keys.removeIf { id -> plugin.server.getPlayer(id) == null }
        }, 20L, 20L)
    }
}
