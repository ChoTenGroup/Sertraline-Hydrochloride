package io.github.zzzyyylllty.sertraline.data

import com.cryptomorin.xseries.XMaterial
import com.google.gson.Gson
import ink.ptms.um.Mythic
import io.github.zzzyyylllty.sertraline.Sertraline
import io.github.zzzyyylllty.sertraline.Sertraline._api
import io.github.zzzyyylllty.sertraline.Sertraline.api
import io.github.zzzyyylllty.sertraline.Sertraline.jexlScriptCache
import io.github.zzzyyylllty.sertraline.Sertraline.jsScriptCache
import io.github.zzzyyylllty.sertraline.api.SertralineAPI
import io.github.zzzyyylllty.sertraline.debugMode.devLog
import io.github.zzzyyylllty.sertraline.event.SertralineCustomScriptDataLoadEvent
import io.github.zzzyyylllty.sertraline.function.fluxon.FluxonShell
import io.github.zzzyyylllty.sertraline.function.javascript.EventUtil
import io.github.zzzyyylllty.sertraline.function.javascript.ItemStackUtil
import io.github.zzzyyylllty.sertraline.function.javascript.PlayerUtil
import io.github.zzzyyylllty.sertraline.function.javascript.ThreadUtil
import io.github.zzzyyylllty.sertraline.function.kether.evalKether
import io.github.zzzyyylllty.sertraline.function.kether.evalKetherBoolean
import io.github.zzzyyylllty.sertraline.item.sertralineItemBuilder
import io.github.zzzyyylllty.sertraline.item.sertralineItemBuilderTemporary
import io.github.zzzyyylllty.sertraline.util.DependencyHelper
import io.github.zzzyyylllty.sertraline.util.ExternalItemHelper
import io.github.zzzyyylllty.sertraline.util.GraalJsUtil
import io.github.zzzyyylllty.sertraline.util.JexlUtil.prodJexlCompiler
import io.github.zzzyyylllty.sertraline.util.UUIDUtil
import io.github.zzzyyylllty.sertraline.util.data.DataUtil
import io.github.zzzyyylllty.sertraline.util.jsonUtils
import io.github.zzzyyylllty.sertraline.util.loreformat.LoreFormatUtil
import io.github.zzzyyylllty.sertraline.util.minimessage.mmJsonUtil
import io.github.zzzyyylllty.sertraline.util.minimessage.mmLegacyAmpersandUtil
import io.github.zzzyyylllty.sertraline.util.minimessage.mmLegacySectionUtil
import io.github.zzzyyylllty.sertraline.util.minimessage.mmUtil
import io.github.zzzyyylllty.sertraline.util.serialize.generateHash
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.inventory.ItemStack
import org.tabooproject.fluxon.Fluxon
import taboolib.common.LifeCycle
import taboolib.common.platform.Awake
import taboolib.common.platform.function.submit
import taboolib.common5.compileJS
import taboolib.platform.util.bukkitPlugin
import java.util.UUID
import javax.script.SimpleBindings

/**
 * Global variable
 */
@Volatile
var defaultData: LinkedHashMap<String, Any?> = LinkedHashMap()

@Awake(LifeCycle.ENABLE)
fun registerExternalData() {
    // Build the base variable map for the event; listeners may add entries to it.
    val built = linkedMapOf<String, Any?>(
        "mmUtil" to mmUtil,
        "mmJsonUtil" to mmJsonUtil,
        "mmLegacySectionUtil" to mmLegacySectionUtil,
        "mmLegacyAmpersandUtil" to mmLegacyAmpersandUtil,
        "jsonUtils" to jsonUtils,
        "ItemStackUtil" to ItemStackUtil,
        "EventUtil" to EventUtil,
        "ThreadUtil" to ThreadUtil,
        "PlayerUtil" to PlayerUtil,
        "ExternalItemHelper" to ExternalItemHelper,
        "SertralineAPI" to _api,
        "AttributeAPI" to io.github.zzzyyylllty.sertraline.attribute.api.SertralineAttributeAPI,
        "DataUtil" to DataUtil,
        "Math" to Math::class.java,
        "System" to System::class.java,
        "Bukkit" to Bukkit::class.java,
        "Gson" to Gson::class.java,
        "bukkitPlugin" to bukkitPlugin,
        "scheduler" to Bukkit.getScheduler(),
        "SertralineObj" to Sertraline,
        "FluxonShell" to FluxonShell,
        "UUIDUtil" to UUIDUtil,
        "LoreFormatUtil" to LoreFormatUtil,
        "TierUtil" to TierUtil,
        "TypeUtil" to TypeUtil,
    )
    val event = SertralineCustomScriptDataLoadEvent(built)
    event.call()

    // Publish the event's (possibly extended, e.g. MythicMobsHook) entries as a fresh mutable
    // LinkedHashMap. Must NOT be wrapped in Collections.unmodifiableMap: the getter's return type
    // is part of the public ABI that external plugins link against (see the defaultData docs).
    defaultData = LinkedHashMap(event.defaultData)
}

data class RevisionData(
    val firstBuild: Long,
    val lastUpdated: Long,
    val revisionId: Long,
    val firstBuildPlayer: String,
    val firstBuildPlayerUUID: String,
)

data class ModernSItem(
    override val key: String,
    override val data: LinkedHashMap<String, Any?> = linkedMapOf(),
    override val config: LinkedHashMap<String, Any?> = linkedMapOf(),
    /**
     * Serialized type discriminator field.
     *
     * Must be a real field rather than a computed property: Gson serializes fields only and does not write getters to JSON.
     * Otherwise, polymorphic restoration would always fall back to ModernSItem.
     *
     * Keep it at the end with a default value to preserve compatibility with existing positional constructor calls.
     */
    override val sItemType: String = "sertraline",
) : AbstractSItem {

    /** A Sertraline item's bare key is unique across sources, preserving existing lookup semantics. */
    override val storageKey: String get() = key

    /**
     * Runtime policy assigned by [SItemFactory] during creation/restoration; not serialized.
     * Controls only the build-time Adapter pipeline.
     */
    @Transient
    override var shouldAdapt: Boolean = true

    /**
     * Deep copy, without JSON serialization/deserialization; far faster than serialize+deserialize
     */
    override fun deepCopy(): ModernSItem = ModernSItem(
        key = key,
        data = deepCopyMap(data),
        config = deepCopyMap(config)
    ).also { it.shouldAdapt = shouldAdapt }

    /**
     * These maps are public and may be mutated directly, including nested maps, so caching these
     * flags can leave the serializer on the wrong fast path. Compute from the current contents.
     */
    override val hasPlaceholders: Boolean
        get() = configContainsPlaceholders(config) || configContainsPlaceholders(data)

    override val hasDynamics: Boolean
        get() = getDeepData("sertraline:dynamics") != null

    /**
     * Resolves the base material from config `itemdef.material` or data `minecraft:material` / `xbuilder:material`.
     * Returns AIR if it cannot be resolved.
     */
    override fun getMaterial(): Material {
        val raw = getDeepData("xbuilder:material")
            ?: getDeepData("minecraft:material")
            ?: (config["itemdef"] as? Map<*, *>)?.get("material")
        val normalized = raw?.toString()?.trim()?.substringAfter(':')?.uppercase()
        if (normalized.isNullOrEmpty() || normalized == "NULL") return Material.AIR
        return runCatching { Material.valueOf(normalized) }.getOrNull()
            ?: runCatching {
                XMaterial.matchXMaterial(normalized).orElse(null)?.parseItem()?.type
            }.getOrNull()
            ?: Material.AIR
    }

    override fun buildItem(player: Player?): ItemStack {
        return buildFromThisInstance(player) ?: ItemStack(Material.AIR)
    }

    /**
     * Builds the stack **from this instance's own `data`/`config`**, as the
     * This follows the [AbstractSItem.buildItem] contract: the source first builds the base ItemStack.
     *
     * It used to delegate to `sertralineItemBuilder(key, player)`, which re-resolves the template
     * from the registry (`itemMap[key].deepCopy()`) and therefore threw away every override carried
     * by this object — unlike [MinecraftSItem] / [ExternalPluginSItem], which both build from `this`.
     * When this object *is* the registry template (the common case) the cheap path is used.
     */
    private fun buildFromThisInstance(player: Player?): ItemStack? {
        if (Sertraline.itemMap[key] === this) return sertralineItemBuilder(key, player)
        return sertralineItemBuilderTemporary(this, player)
    }
}

/**
 * Compatibility entry point: restores an object as `ModernSItem` for legacy callers.
 * New code should use [SItemFactory.deserialize] to support multiple source types.
 */
fun deserializeSItem(string: String): ModernSItem {
    val parsed = SItemFactory.deserialize(string)
        ?: error("Failed to deserialize item")
    return parsed as? ModernSItem
        ?: error("Item is not a Sertraline item: ${parsed.sItemType}")
}

/**
 * Restores only Sertraline items and returns null for data from other sources.
 * The private item table stores only Sertraline items, so this avoids treating external objects as templates.
 */
fun deserializeSertraline(string: String?): ModernSItem? {
    return SItemFactory.deserialize(string) as? ModernSItem
}

/**
 * Deep copy of a LinkedHashMap
 */
@Suppress("UNCHECKED_CAST")
fun deepCopyMap(original: LinkedHashMap<String, Any?>): LinkedHashMap<String, Any?> {
    val copy = LinkedHashMap<String, Any?>(original.size)
    for ((key, value) in original) {
        copy[key] = deepCopyValue(value)
    }
    return copy
}

@Suppress("UNCHECKED_CAST")
private fun deepCopyValue(value: Any?): Any? {
    return when (value) {
        is Map<*, *> -> LinkedHashMap<Any?, Any?>(value.size).also { copy ->
            value.forEach { (key, nested) -> copy[key] = deepCopyValue(nested) }
        }
        is List<*> -> value.mapTo(ArrayList(value.size)) { deepCopyValue(it) }
        is Set<*> -> value.mapTo(LinkedHashSet(value.size)) { deepCopyValue(it) }
        else -> value // String, Number, Boolean, null are immutable
    }
}

/**
 * Recursively checks whether the config contains ${...}$ placeholders
 */
fun configContainsPlaceholders(config: Map<String, Any?>): Boolean {
    for ((_, value) in config) {
        when (value) {
            is String -> {
                if (value.contains("\${")) return true
            }
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                if (configContainsPlaceholders(value as Map<String, Any?>)) return true
            }
            is List<*> -> {
                for (element in value) {
                    if (element is String && element.contains("\${")) return true
                    if (element is Map<*, *>) {
                        @Suppress("UNCHECKED_CAST")
                        if (configContainsPlaceholders(element as Map<String, Any?>)) return true
                    }
                }
            }
        }
    }
    return false
}

data class Action(
    val condition: List<String>? = null,
    val async: Boolean? = null,
    val kether: List<String>? = null,
    val javaScript: String? = null,
    val jexl: String? = null,
    val fluxon: String? = null,
    val gjs: String? = null,
    val mythic: List<String>? = null,
//    val kotlinScript: String? = null,
) {



    fun runAction(player: Player, data: Map<String, Any?>, i: ItemStack?, e: Event?, cancellableEvent: Cancellable?, sqlI: AbstractSItem, abItem: ItemStack?) {

        // Build a small overlay map with only the action-specific keys (7 entries),
        // then layer it over defaultData via a non-copying composite view.
        // defaultData entries (20+ keys) take precedence for conflicting keys,
        // matching the original putAll ordering.
        val actionVars = LinkedHashMap<String, Any?>(data.size + 7)
        actionVars.putAll(data)
        actionVars["sItem"] = sqlI
        actionVars["bItem"] = i
        actionVars["abItem"] = abItem
        actionVars["event"] = e
        actionVars["cancellableEvent"] = cancellableEvent
        actionVars["player"] = player
        val parsedData: Map<String, Any?> = OverlayMap(actionVars, defaultData)

        if (condition?.evalKetherBoolean(player, parsedData) ?: true) {
            kether?.evalKether(player, parsedData)

            javaScript?.let {
                // JS engines write top-level `var`/function declarations into the engine-scope bindings,
                // and SimpleBindings uses the map it is given as its BACKING store. parsedData is a
                // read-only OverlayMap view, so handing it over directly makes those writes throw
                // UnsupportedOperationException. Give the engine a private writable copy instead.
                val jsBindings = SimpleBindings(LinkedHashMap(parsedData))
                val hash = it.generateHash()
                val cache = jsScriptCache[hash]
                if (cache != null) {
                    cache.eval(jsBindings)
                } else {
                    val compiled = it.compileJS()
                    compiled?.let { c ->
                        jsScriptCache[hash] = c
                        c.eval(jsBindings)
                    }
                }
            }

            jexl?.let {
                val hash = it.generateHash()
                val cache = jexlScriptCache[hash]
                if (cache != null) {
                    cache.eval(parsedData)
                } else {
                    val compiled = prodJexlCompiler.compileToScript(it)
                    compiled.let { it ->
                        jexlScriptCache[hash] = it
                        it.eval(parsedData)
                    }
                }
            }

            fluxon?.let {
                FluxonShell.invoke(it) {
                    root.rootVariables += parsedData
                }
            }

            gjs?.let {
                GraalJsUtil.cachedEval(it, parsedData)
            }

            if (DependencyHelper.mm) mythic?.let {
                it.forEach { str ->
                    val filterData = parsedData.filter { (key, value) -> value != null } as Map<String, Any>
                    val skillLine = str.split("~")
                    val triggerName = skillLine.getOrNull(1)
                    val trigger = triggerName?.let { name -> Mythic.API.getSkillTrigger(name) } ?: Mythic.API.getDefaultSkillTrigger()
                    val skill = Mythic.API.getSkillMechanic(skillLine[0])
                    devLog("skill: $skill | skillLine: $skillLine | trigger: $trigger | triggerName: $triggerName")
                    skill?.execute(
                        trigger,
                        player,
                        player,
                        emptySet(),
                        emptySet(),
                        0f,
                        filterData
                    )
                }
            }

//        kotlinScript?.let {
//            runKotlinScriptJsr223(it, parsedData, bukkitPlugin::class.java.classLoader)
//        }

        }
    }
}

data class ItemData(
    val itemVal: Map<String, Any?>? = mapOf(),
    val itemVar: Map<String, Any?>? = mapOf(),
    val itemDynamic: Map<String, Any?>? = mapOf(),
    val itemContext: Map<String, Any?>? = mapOf(),
    val itemId: String? = null,
) {
    fun collect(): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        itemVal?.let { map.putAll(it) }
        itemVar?.let { map.putAll(it) }
        itemDynamic?.let { map.putAll(it) }
        itemContext?.let { map.putAll(it) }
        itemId?.let { map.put("_itemname", it) }
        return map
    }

}

/**
 * Read-only two-layer composite map: [primary] entries take precedence over [fallback].
 * Avoids copying large maps (e.g. defaultData) on every call — only a small overlay
 * is allocated per invocation. Implements [Map] directly to avoid AbstractMap's
 * delegation from get() → entries (which would cause lazy-init overhead on every read).
 */
internal class OverlayMap(
    private val primary: Map<String, Any?>,
    private val fallback: Map<String, Any?>,
) : Map<String, Any?> {

    override val entries: Set<Map.Entry<String, Any?>> by lazy {
        val merged = LinkedHashMap<String, Any?>(fallback.size + primary.size)
        merged.putAll(fallback)
        merged.putAll(primary)
        merged.entries
    }

    override fun get(key: String): Any? =
        if (primary.containsKey(key)) primary[key] else fallback[key]
    override fun containsKey(key: String): Boolean = primary.containsKey(key) || fallback.containsKey(key)
    override fun containsValue(value: Any?): Boolean = primary.containsValue(value) || fallback.containsValue(value)
    override val size: Int get() = entries.size
    override fun isEmpty(): Boolean = primary.isEmpty() && fallback.isEmpty()
    override val keys: Set<String> get() = entries.mapTo(LinkedHashSet()) { it.key }
    override val values: Collection<Any?> get() = entries.map { it.value }
}
