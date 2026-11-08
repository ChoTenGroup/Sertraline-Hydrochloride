package io.github.zzzyyylllty.sertraline.util.loreformat

import io.github.zzzyyylllty.sertraline.Sertraline
import io.github.zzzyyylllty.sertraline.config.common.SertralineSettings
import io.github.zzzyyylllty.sertraline.Sertraline.loreFormats
import io.github.zzzyyylllty.sertraline.Sertraline.tiers
import io.github.zzzyyylllty.sertraline.Sertraline.types
import io.github.zzzyyylllty.sertraline.Sertraline.levels
import io.github.zzzyyylllty.sertraline.config.ConfigUtil
import io.github.zzzyyylllty.sertraline.config.asListEnhanced
import io.github.zzzyyylllty.sertraline.data.AbstractLoreElement
import io.github.zzzyyylllty.sertraline.data.AbstractLoreFormat
import io.github.zzzyyylllty.sertraline.data.CompareElementSetting
import io.github.zzzyyylllty.sertraline.data.CompareOp
import io.github.zzzyyylllty.sertraline.data.CompareSetting
import io.github.zzzyyylllty.sertraline.data.CompareTarget
import io.github.zzzyyylllty.sertraline.data.CompareTargetEquipmentSlot
import io.github.zzzyyylllty.sertraline.data.CompareTargetFilterDecision
import io.github.zzzyyylllty.sertraline.data.CompareTargetFilterCondition
import io.github.zzzyyylllty.sertraline.data.CompareTargetFixedPath
import io.github.zzzyyylllty.sertraline.data.parseCompareSlot
import io.github.zzzyyylllty.sertraline.data.ComplexLoreElement
import io.github.zzzyyylllty.sertraline.data.DataCompareCondition
import io.github.zzzyyylllty.sertraline.data.DataCompareGroup
import io.github.zzzyyylllty.sertraline.data.LineMode.*
import io.github.zzzyyylllty.sertraline.data.LoreFormat
import io.github.zzzyyylllty.sertraline.data.LoreSetting
import io.github.zzzyyylllty.sertraline.data.AbstractSItem
import io.github.zzzyyylllty.sertraline.data.ResultLimit
import io.github.zzzyyylllty.sertraline.data.ScriptCompareGroup
import io.github.zzzyyylllty.sertraline.data.SwitchableLoreFormat
import io.github.zzzyyylllty.sertraline.data.Tier
import io.github.zzzyyylllty.sertraline.data.Type
import io.github.zzzyyylllty.sertraline.data.Level
import io.github.zzzyyylllty.sertraline.data.defaultData
import io.github.zzzyyylllty.sertraline.function.data.contextValue
import io.github.zzzyyylllty.sertraline.function.kether.parseKether
import io.github.zzzyyylllty.sertraline.debugMode.devLog
import io.github.zzzyyylllty.sertraline.function.data.getSertralineId
import io.github.zzzyyylllty.sertraline.util.jsonUtils
import io.github.zzzyyylllty.sertraline.util.toLowerCase
import io.github.zzzyyylllty.sertraline.util.minimessage.toComponent
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.EntityEquipment
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import taboolib.common5.Coerce
import taboolib.module.nms.getItemTag
import taboolib.platform.compat.replacePlaceholder
import javax.script.SimpleBindings


fun handleLoreFormat(item: AbstractSItem, player: Player?, orgLore: List<Component>?, isVisual: Boolean = true, currentItem: ItemStack? = null): List<Component>? {
    // getDeepData returns null when the item has no sertraline:lore-format entry; passing null to
    // ConcurrentHashMap.get would throw NPE ("Cannot invoke Object.hashCode() because key is null")
    val loreFormatKey = item.getDeepData("sertraline:lore-format")?.toString() ?: return null
    val loreFormat = loreFormats[loreFormatKey] ?: return null
    return applyLoreFormat(item, player, orgLore, loreFormat, isVisual, currentItem)
}

internal fun applyLoreFormat(
    item: AbstractSItem,
    player: Player?,
    orgLore: List<Component>?,
    loreFormat: AbstractLoreFormat,
    isVisual: Boolean? = null,
    currentItem: ItemStack? = null,
): List<Component>? {
    val effective = resolveEffectiveFormat(item, player, loreFormat) ?: return null
    val targetCache = mutableMapOf<CompareTarget, ResolvedCompareTarget?>()
    return applyPlainLoreFormat(item, player, orgLore, effective, isVisual, targetCache, currentItem)
}

private fun resolveEffectiveFormat(item: AbstractSItem, player: Player?, fmt: AbstractLoreFormat): LoreFormat? {
    return when (fmt) {
        is LoreFormat -> fmt
        is SwitchableLoreFormat -> {
            val target = resolveSwitchTarget(fmt, item, player) ?: return null
            val resolved = resolveEffectiveFormat(item, player, target) ?: return null
            val ov = fmt.settingsOverride ?: run {
                devLog("resolveEffectiveFormat: fmt.settingsOverride is null, returning sub-format without override")
                return resolved
            }
            if (ov.compare == null) {
                devLog("resolveEffectiveFormat: ov.compare is NULL! compareEnabled=${ov.compareEnabled}, " +
                    "resolved.settings.compareEnabled=${resolved.settings.compareEnabled}, " +
                    "resolved.settings.compare=${resolved.settings.compare}")
            } else {
                devLog("resolveEffectiveFormat: ov.compare is non-null, compares keys=${ov.compare.compares.keys}")
            }
            resolved.copy(
                settings = LoreSetting(
                    overwrite = ov.overwrite ?: resolved.settings.overwrite,
                    visual = ov.visual ?: resolved.settings.visual,
                    skipBlank = ov.skipBlank ?: resolved.settings.skipBlank,
                    compareEnabled = if (ov.compare != null) true else resolved.settings.compareEnabled,
                    compare = ov.compare ?: resolved.settings.compare,
                )
            )
        }
        else -> null
    }
}

private fun newScriptBindings(item: Any?, player: Player?): SimpleBindings {
    // Snapshot the current defaultData once per invocation. The defaultData reference
    // can be swapped on reload; a snapshot avoids coupling to a concurrently-mutated map.
    //
    // The copy is mandatory, not an optimisation: SimpleBindings uses the map we hand it as its
    // BACKING store, so passing defaultData directly means every bindings[...] = ... below writes
    // into the shared global table (cross-contaminating concurrent async item builds), and passing
    // an unmodifiable view of it makes those writes throw UnsupportedOperationException — which
    // aborts the whole item build, leaving e.g. the item explorer GUI completely empty.
    val bindings = SimpleBindings(LinkedHashMap(defaultData))
    bindings["player"] = player
    bindings["sItem"] = item
    return bindings
}

private fun resolveSwitchTarget(sw: SwitchableLoreFormat, item: AbstractSItem, player: Player?): AbstractLoreFormat? {
    val bindings = newScriptBindings(item, player)

    for ((name, script) in sw.preVariables) {
        try {
            bindings[name] = script.eval(bindings)
        } catch (ex: Exception) {
            devLog("Switchable lore format: pre-variable \"$name\" eval failed: ${ex.message}")
            bindings[name] = null
        }
    }
    for (fork in sw.forks) {
        try {
            if (Coerce.toBoolean(fork.condition.eval(bindings))) {
                return fork.toLoreFormat
            }
        } catch (ex: Exception) {
            devLog("Switchable lore format: condition eval failed, skipping fork: ${ex.message}")
        }
    }
    return null
}

private fun applyPlainLoreFormat(
    item: AbstractSItem,
    player: Player?,
    orgLore: List<Component>?,
    loreFormat: LoreFormat,
    isVisual: Boolean? = null,
    targetCache: MutableMap<CompareTarget, ResolvedCompareTarget?>,
    currentItem: ItemStack? = null,
): List<Component>? {
    if (isVisual != null && loreFormat.settings.visual != isVisual) return null

    // Per-render compare-target filter: the compare target is render-scope constant, so the
    // filter is evaluated once here using the actual ItemStacks. A DENY verdict disables
    // comparison for the whole render (equivalent to compare-enabled = false).
    var settings = loreFormat.settings
    if (settings.compareEnabled) {
        val target = settings.compare?.target
        if (target?.filter != null) {
            val resolvedTarget = resolveTargetItem(item, player, target, targetCache)
            if (resolvedTarget != null && compareFilterDenied(target, player, currentSItem = item, currentItem = currentItem, compareSItem = resolvedTarget.sItem, compareItem = resolvedTarget.itemStack)) {
                settings = settings.copy(compareEnabled = false)
            }
        }
    }

    val lore = mutableListOf<String>()

    loreFormat.elements.forEach { element ->
        if (handleLoreExists(element, item)) {
            lore.addAll(handleKeyLore(item, player, element, settings, targetCache))
        }
    }

    val nLore = if (loreFormat.settings.skipBlank) mergeConsecutiveEmptyStrings(lore) else lore

    return if (loreFormat.settings.overwrite) {
        nLore.toComponent()
    } else {
        val orgList = orgLore?.toMutableList() ?: mutableListOf()
        orgList.addAll(nLore.toComponent())
        orgList
    }
}

/** The resolved comparison target: the AbstractSItem identity plus the physical ItemStack it was found with. */
data class ResolvedCompareTarget(val sItem: AbstractSItem, val itemStack: ItemStack)

// ═══════════════════════════════════════════════════════════════
//  Compare resolution
// ═══════════════════════════════════════════════════════════════

/**
 * Evaluate the compare-target filter gate once per render. Returns true when comparison
 * should be skipped entirely (an entry decided DENY — equivalent to compare-enabled =
 * false). A matching ALLOW, or no matched entry at all, proceeds. Entries are evaluated
 * top-to-bottom; the first match wins.
 *
 * Conditions are evaluated with these variables bound:
 *   - player       the player viewing the item (may be null)
 *   - currentItem  the current item as an ItemStack (the actual physical item)
 *   - compareItem  the target item as an ItemStack (the actual equipped item; may be null)
 *   - currentSItem the current item as a AbstractSItem (for getDeepData / .key etc.)
 *   - compareSItem the target item as a AbstractSItem (may be null)
 * plus every global JS helper from `defaultData` (ItemStackUtil, PlayerUtil, ...).
 */
private fun compareFilterDenied(
    target: CompareTarget?,
    player: Player?,
    currentSItem: AbstractSItem,
    currentItem: ItemStack?,
    compareSItem: AbstractSItem?,
    compareItem: ItemStack?,
): Boolean {
    val filter = target?.filter ?: return false
    // Copy: SimpleBindings treats the supplied map as its backing store (see newScriptBindings).
    val bindings = SimpleBindings(LinkedHashMap(defaultData))
    bindings["player"] = player
    bindings["currentSItem"] = currentSItem
    bindings["currentItem"] = currentItem
    bindings["compareSItem"] = compareSItem
    bindings["compareItem"] = compareItem
    for (entry in filter.whens) {
        val matches = when (val cond = entry.condition) {
            is CompareTargetFilterCondition.Always -> true
            is CompareTargetFilterCondition.Script -> {
                try {
                    Coerce.toBoolean(cond.compiled.eval(bindings))
                } catch (ex: Exception) {
                    devLog("Compare filter JS condition eval failed: ${ex.message}")
                    false
                }
            }
            is CompareTargetFilterCondition.SelfCompare -> {
                // Same physical ItemStack instance (reference equality).
                // This is NOT about same sertraline template — sertraline items
                // have random properties, so only the exact same stack counts.
                currentItem != null && compareItem != null && currentItem == compareItem
            }
            is CompareTargetFilterCondition.LoreFormat -> {
                val lf = compareSItem?.getDeepData("sertraline:lore-format")?.toString()
                lf != null && lf in cond.names
            }
        }
        if (!matches) continue
        // First matching entry decides: ALLOW -> proceed (no further entries evaluated).
        return entry.then == CompareTargetFilterDecision.DENY
    }
    return false
}

/**
 * Try to resolve a compare-based content override.
 * Returns the overridden content (with diff placeholders already processed) and the diff value,
 * or null if no compare applies.
 */
private fun resolveCompareContent(
    item: AbstractSItem,
    player: Player?,
    element: AbstractLoreElement,
    settings: LoreSetting,
    targetCache: MutableMap<CompareTarget, ResolvedCompareTarget?>? = null,
): Pair<String, Double>? {
    val compareSetting = settings.compare ?: run {
        devLog("resolveCompareContent: settings.compare is null")
        return null
    }

    val elCompare = when (element) {
        is ComplexLoreElement -> element.compare
        else -> {
            devLog("resolveCompareContent: element is ${element::class.simpleName}, not ComplexLoreElement, key=${element.key}")
            null
        }
    } ?: run {
        devLog("resolveCompareContent: element.compare is null, key=${element.key}")
        return null
    }

    // 1. Resolve current value (nullable — null means the stat is absent on this item)
    val currentPath = elCompare.currentId ?: element.key ?: run {
        devLog("resolveCompareContent: current path is null, element has no key")
        return null
    }
    val currentValue = resolveNumericValue(item, currentPath)
    devLog("resolveCompareContent: currentPath=$currentPath, currentValue=$currentValue, item=${item.key}")

    // 2. Resolve target item (with cache across elements in the same render)
    val targetItem = resolveTargetItem(item, player, compareSetting.target, targetCache) ?: run {
        devLog("resolveCompareContent: target item resolution failed, target=${compareSetting.target}, player=${player?.name}")
        return null
    }
    devLog("resolveCompareContent: target resolved, targetKey=${targetItem.sItem.key}, targetStack=${targetItem.itemStack.type}")

    // 3. Resolve compare value
    val comparePath = elCompare.compareId ?: element.key ?: run {
        devLog("resolveCompareContent: compare path is null, element key=${element.key}")
        return null
    }
    val compareValue = resolveNumericValue(targetItem.sItem, comparePath)
    devLog("resolveCompareContent: comparePath=$comparePath, compareValue=$compareValue, target=${targetItem.sItem.key}")

    // 4. If both values are absent, there's nothing to compare
    if (currentValue == null && compareValue == null) {
        devLog("resolveCompareContent: both values are null, skipping comparison")
        return null
    }

    // 5. Compute difference (null → 0.0 for the missing side)
    val effectiveCurrent = currentValue ?: 0.0
    val effectiveCompare = compareValue ?: 0.0
    val differentValue = effectiveCurrent - effectiveCompare

    // 6. Evaluate result-limit conditions
    val conditionGroup = compareSetting.compares[elCompare.compareName]
        ?: compareSetting.compares.values.firstOrNull()?.also {
            // The named group is missing but a fallback exists — surface the misconfiguration
            // instead of silently using the first group on every render.
            devLog("Compare group \"${elCompare.compareName}\" not found in compares, using first available group as fallback")
        }
        ?: run {
            devLog("Compare group \"${elCompare.compareName}\" not found in compares, and no fallback group available")
            return null
        }
    val result = when (conditionGroup) {
        is DataCompareGroup -> evaluateDataCompare(currentValue, compareValue, differentValue, conditionGroup.conditions)
        is ScriptCompareGroup -> evaluateScriptCompare(item, player, currentValue, compareValue, differentValue, conditionGroup)
    } ?: run {
        devLog("resolveCompareContent: condition group produced null, group=${elCompare.compareName}, current=$currentValue, compare=$compareValue, diff=$differentValue")
        return null
    }
    devLog("resolveCompareContent: result=$result, diff=$differentValue, current=$currentValue, compare=$compareValue")

    // 7. Look up content
    val content = elCompare.contents[result] ?: run {
        devLog("resolveCompareContent: contents has no key '$result', available=${elCompare.contents.keys}; falling back to element.content")
        element.content
    }
    devLog("resolveCompareContent: selected compare content for key '$result': $content")

    // 8. Process diff placeholders
    val processed = processDiffPlaceholders(content, differentValue, effectiveCurrent, effectiveCompare)

    // 9. Apply normal placeholders ({plus}, {minus}, {value}, {auto}, {round:}, {data:}, {config:})
    //    using the current stat value as the receiver
    val withNormalPlaceholders = effectiveCurrent.performNormalPlaceholders(processed, player, item, skipGeneralPlaceholders = true)

    // 10. Apply general placeholders (tier, type, level, val, var, context, PlaceholderAPI, kether)
    val fullyProcessed = withNormalPlaceholders.performPlaceholders(item, player) ?: withNormalPlaceholders

    return Pair(fullyProcessed, differentValue)
}

private fun resolveNumericValue(item: AbstractSItem, path: String): Double? {
    return item.getDeepData(path)?.toString()?.toDoubleOrNull()
}

private fun resolveTargetItem(
    item: AbstractSItem,
    player: Player?,
    target: CompareTarget?,
    cache: MutableMap<CompareTarget, ResolvedCompareTarget?>? = null,
): ResolvedCompareTarget? {
    if (target == null) return null
    if (cache != null) {
        cache[target]?.let { return it }
    }
    val result = resolveTargetItemImpl(item, player, target)
    if (cache != null) cache[target] = result
    return result
}

/**
 * Internal implementation of target item resolution (no cache).
 */
private fun resolveTargetItemImpl(item: AbstractSItem, player: Player?, target: CompareTarget): ResolvedCompareTarget? {
    for (whenEntry in target.whens) {
        when (whenEntry) {
            is CompareTargetFixedPath -> {
                // compare-path names the target EQUIPMENT SLOT (e.g. "MAIN_HAND", "OFF_HAND").
                // The target is the item the player currently has equipped in that slot.
                if (player == null) { devLog("ct: player is null"); continue }
                val slotName = item.getDeepData("sertraline:compare-path")?.toString()
                devLog("ct: getDeepData(sertraline:compare-path)=$slotName")
                devLog("ct: compare-path='$slotName'")
                if (slotName == null) continue
                val slot = parseCompareSlot(slotName)
                devLog("ct: parsed slot=$slot")
                if (slot == null) continue
                val eq = player.equipment
                devLog("ct: equipment=${eq != null}")
                if (eq == null) continue
                val equipmentStack = equipmentItem(eq, slot)
                devLog("ct: slot=[$slot] stack=${equipmentStack?.type} amount=${equipmentStack?.amount} id=${equipmentStack?.getSertralineId()}")
                if (equipmentStack == null) continue
                val eqKey = equipmentStack.getSertralineId()
                devLog("ct: eqKey='$eqKey'")
                if (eqKey == null) continue
                val sItem = Sertraline.itemMap[eqKey]
                devLog("ct: itemMap[$eqKey]=${sItem?.key}")
                if (sItem == null) return@resolveTargetItemImpl null
                return@resolveTargetItemImpl ResolvedCompareTarget(sItem, equipmentStack)
            }
            is CompareTargetEquipmentSlot -> {
                if (player == null) continue
                val findSlot = findEquipmentSlot(player, item)
                devLog("ct: equipment-slot finder->$findSlot, allowed=${whenEntry.slots}")
                if (findSlot == null) continue
                if (findSlot in whenEntry.slots) {
                    val eq = player.equipment ?: continue
                    val equipmentStack = equipmentItem(eq, findSlot) ?: continue
                    val eqKey = equipmentStack.getSertralineId() ?: continue
                    val sItem = Sertraline.itemMap[eqKey] ?: return@resolveTargetItemImpl null
                    return@resolveTargetItemImpl ResolvedCompareTarget(sItem, equipmentStack)
                }
            }
        }
    }
    return null
}

/**
 * Read the item in the given [EquipmentSlot] from the player's equipment.
 *
 * Maps the slot to the corresponding typed getter ([EntityEquipment.helmet], [EntityEquipment.itemInMainHand], …)
 * instead of the generic slot-based [EntityEquipment.getItem] overload, which only exists from MC 1.13+ and is absent
 * on the legacy12 (1.12.2) API surface this project is also compiled against.
 */
private fun equipmentItem(eq: EntityEquipment, slot: EquipmentSlot): ItemStack? =
    when (slot) {
        EquipmentSlot.HEAD -> eq.helmet
        EquipmentSlot.CHEST -> eq.chestplate
        EquipmentSlot.LEGS -> eq.leggings
        EquipmentSlot.FEET -> eq.boots
        EquipmentSlot.HAND -> eq.itemInMainHand
        EquipmentSlot.OFF_HAND -> eq.itemInOffHand
        // BODY (added in newer MC) and any future slot: not addressable via typed getters on either API surface
        else -> null
    }

/**
 * Find which equipment slot the given item is in on the player.
 *
 * If the player has the same item id in multiple slots (e.g. main-hand and off-hand),
 * the FIRST matching slot from [EquipmentSlot.entries] iteration order is returned.
 * The caller's [CompareTargetEquipmentSlot] filter then validates whether that slot
 * is in the allowed list.
 */
private fun findEquipmentSlot(player: Player, item: AbstractSItem): EquipmentSlot? {
    val eq = player.equipment ?: return null
    for (slot in EquipmentSlot.entries) {
        val slotItem = equipmentItem(eq, slot) ?: continue
        if (slotItem.amount == 0) continue
        val slotKey = slotItem.getSertralineId()
        if (slotKey == item.key) return slot
    }
    return null
}

/**
 * Evaluate data-based compare conditions.
 *
 * @param currentValue  the current item's numeric value, or null if the stat is absent
 * @param compareValue  the target item's numeric value, or null if the stat is absent
 * @param differentValue  currentValue - compareValue (with nulls treated as 0.0)
 */
private fun evaluateDataCompare(
    currentValue: Double?,
    compareValue: Double?,
    differentValue: Double,
    conditions: List<DataCompareCondition>,
): String? {
    for (cond in conditions) {
        when (cond.resultLimit) {
            ResultLimit.LOST -> {
                // Current value is absent (item lost this stat) but compare value exists
                if (currentValue == null && compareValue != null) return cond.result
            }
            ResultLimit.NEW -> {
                // Compare value is absent (target doesn't have this stat) but current value exists
                if (compareValue == null && currentValue != null) return cond.result
            }
            ResultLimit.DEFAULT -> {
                if (cond.operator == null) {
                    devLog("evaluateDataCompare: condition with DEFAULT result-limit has null operator, skipping")
                    continue
                }
                if (cond.value == null) continue
                // Both values must exist for DEFAULT comparison
                if (currentValue == null || compareValue == null) continue
                if (matchesCompare(differentValue, cond)) return cond.result
            }
        }
    }
    return null
}

private fun matchesCompare(differentValue: Double, cond: DataCompareCondition): Boolean {
    return when (cond.operator) {
        CompareOp.EQUAL -> {
            val compareVal = cond.parsedValue ?: return false
            differentValue == compareVal
        }
        CompareOp.NOT_EQUAL -> {
            val compareVal = cond.parsedValue ?: return false
            differentValue != compareVal
        }
        CompareOp.GREATER_THAN -> {
            val compareVal = cond.parsedValue ?: return false
            differentValue > compareVal
        }
        CompareOp.GREATER_EQUAL -> {
            val compareVal = cond.parsedValue ?: return false
            differentValue >= compareVal
        }
        CompareOp.LESS_THAN -> {
            val compareVal = cond.parsedValue ?: return false
            differentValue < compareVal
        }
        CompareOp.LESS_EQUAL -> {
            val compareVal = cond.parsedValue ?: return false
            differentValue <= compareVal
        }
        CompareOp.BETWEEN -> {
            val low = cond.parsedLow ?: return false
            val high = cond.parsedHigh ?: return false
            differentValue in low..high
        }
        null -> false
    }
}

private fun evaluateScriptCompare(
    item: AbstractSItem,
    player: Player?,
    currentValue: Double?,
    compareValue: Double?,
    differentValue: Double,
    group: ScriptCompareGroup,
): String? {
    val bindings = newScriptBindings(item, player)
    bindings["currentValue"] = currentValue
    bindings["compareValue"] = compareValue
    bindings["differentValue"] = differentValue
    return try {
        group.script.eval(bindings)?.toString()
    } catch (ex: Exception) {
        devLog("Script compare eval failed: ${ex.message}")
        null
    }
}

private val DIFF_PLACEHOLDER_REGEX = Regex("\\{diff:([^}]+)}")
// Bare {diff} (no colon) — the signed difference value
private val DIFF_BARE_REGEX = Regex("\\{diff}")
// Legacy aliases: {diff_abs}, {diff_percent_abs} (still supported for backward compatibility)
private val DIFF_LEGACY_REGEX = Regex("\\{diff_(abs|percent_abs)}")

private fun processDiffPlaceholders(
    content: String,
    differentValue: Double,
    currentValue: Double,
    compareValue: Double,
): String {
    // Fast-path: no diff placeholders at all → nothing to replace.
    // Match the real patterns ({diff}, {diff:...}, {diff_...}) instead of the loose
    // "{diff" prefix, so unrelated placeholders like {difficulty} skip this entirely.
    if (!content.contains("{diff}") && !content.contains("{diff:") && !content.contains("{diff_")) {
        return content
    }

    val diffAbs = kotlin.math.abs(differentValue)
    val diffSymbol = if (differentValue > 0) "+" else if (differentValue < 0) "-" else ""
    // Compute the percentage only when the content actually needs it, to avoid the
    // division + String.format cost on every render for elements using {diff:abs} only.
    val wantsPercent = content.contains("percent_abs")
    val diffPercentAbs = if (wantsPercent && compareValue != 0.0) {
        String.format("%.1f", diffAbs / compareValue * 100)
    } else "0"

    // Step 0: process bare {diff} placeholder
    var result = content.replace(DIFF_BARE_REGEX) { differentValue.toString().removeSuffix(".0") }

    // Step 1: process {diff:...} placeholders
    result = result.replace(DIFF_PLACEHOLDER_REGEX) { match ->
        when (match.groupValues[1]) {
            "diff" -> differentValue.toString().removeSuffix(".0")
            "abs" -> diffAbs.toString().removeSuffix(".0")
            "symbol" -> diffSymbol
            "percent_abs" -> diffPercentAbs
            else -> match.value
        }
    }

    // Step 2: process legacy {diff_abs} and {diff_percent_abs} aliases
    result = result.replace(DIFF_LEGACY_REGEX) { match ->
        when (match.groupValues[1]) {
            "abs" -> diffAbs.toString().removeSuffix(".0")
            "percent_abs" -> diffPercentAbs
            else -> match.value
        }
    }

    return result
}

// ═══════════════════════════════════════════════════════════════
//  Placeholder handling
// ═══════════════════════════════════════════════════════════════

private val NORMAL_PLACEHOLDER_REGEX = "\\{(.*?)}".toRegex()
private val VAL_PLACEHOLDER_REGEX = Regex("\\{val:([^}]+)}")
private val VAR_PLACEHOLDER_REGEX = Regex("\\{var:([^}]+)}")
private val CONTEXT_PLACEHOLDER_REGEX = Regex("\\{context:([^}]+)}")

fun Any?.performNormalPlaceholders(content: String, player: Player?, sItem: AbstractSItem, skipGeneralPlaceholders: Boolean = false): String {
    // Fast-path: no brace patterns at all, nothing to replace
    if (!content.contains('{')) return content

    val numeral = this.toString().toDoubleOrNull() ?: 0.0
    val string = this.toString()

    // Single-pass regex replacement: finds all {key} patterns and replaces handled ones in one pass
    var content = NORMAL_PLACEHOLDER_REGEX.replace(content) { match ->
        val key = match.groupValues[1].lowercase()
        when {
            key == "plus" -> if (numeral > 0.0) SertralineSettings.placeholderPlus else ""
            key == "minus" -> if (numeral < 0.0) SertralineSettings.placeholderMinus else ""
            key == "value" -> string
            key == "auto" -> string.removeSuffix(".0")
            key.startsWith("round:") -> {
                val format = key.removePrefix("round:")
                try {
                    "%${format}.2f".format(string.toDoubleOrNull() ?: 0.0)
                } catch (ex: Exception) {
                    // An invalid format specifier (e.g. {round:invalid}) would otherwise throw
                    // UnknownFormatConversionException and crash the whole element render.
                    devLog("Invalid round format \"$format\" for value \"$string\": ${ex.message}")
                    match.value
                }
            }
            key.startsWith("data:") -> {
                sItem.getDeepData(key.removePrefix("data:"))?.toString() ?: ""
            }
            key.startsWith("config:") -> {
                ConfigUtil.getDeep(sItem.config, key.removePrefix("config:"))?.toString() ?: ""
            }
            // diff placeholders are only meaningful in compare context, handled by processDiffPlaceholders.
            // In non-compare paths, replace them with empty string to avoid literal {diff} in output.
            // Also covers legacy aliases {diff_abs} / {diff_percent_abs}.
            key == "diff" || key.startsWith("diff:") || key == "diff_abs" || key == "diff_percent_abs" -> ""
            // All other placeholders ({type:name}, {tier:format}, {val:...}, {var:...}, {context:...}, etc.)
            // are handled by performPlaceholders below — keep as-is for now.
            else -> match.value
        }
    }
    if (!skipGeneralPlaceholders) {
        content = content.performPlaceholders(sItem, player) ?: content
    }
    return content
}

fun String.replaceTierPlaceholders(tier: Tier): String {
    return this
        .replace("{tier:name}", tier.name)   // 最高优先级，纯名称
        .replace("{tier:desc}", tier.description) // 最高优先级，纯描述
        .replace("{tier}", tier.name)        // 次优先级，纯名称
        .replace("{tier:format}", tier.color + tier.name)
        .replace("{tier:color}", tier.color)
        .replace("{tier:id}", tier.id)
        .replace("{tier:weight}", tier.weight.toString())
        .replace("{tier:description}", tier.description)
}

fun String.replaceTypePlaceholders(type: Type): String {
    return this
        .replace("{type}", type.name)
        .replace("{type:name}", type.name)
        .replace("{type:id}", type.id)
        .replace("{type:parent}", type.parent ?: "")
        .replace("{type:description}", type.description)
}

fun String.replaceLevelPlaceholders(level: Level): String {
    return this
        .replace("{level:name}", level.name)   // 明确支持 {level:name}
        .replace("{level}", level.name)
        .replace("{level:color}", level.color)
        .replace("{level:weight}", level.weight.toString())
        .replace("{level:id}", level.id)
        .replace("{level:description}", level.description)
}

fun String?.performPlaceholders(sItem: AbstractSItem, player: Player?): String? {
    var content = this ?: run {
        return null
    }

    // Fast-path: no brace patterns at all, nothing to replace
    if (!content.contains('{')) return content

    // The replace() calls below are O(n) no-ops when the pattern doesn't match,
    // so the redundant outer contains() checks have been removed to avoid extra
    // string scanning. Each replace() already handles the absent-pattern case.

    val tierData = sItem.getDeepData("sertraline:tier")
    val tierKey = tierData?.toString()
    val tier = when (tierData) {
        is Tier -> tierData
        else -> if (tierKey != null) tiers[tierKey] else null
    }
    tier?.let { content = content.replaceTierPlaceholders(it) }

    val typeData = sItem.getDeepData("sertraline:type")
    val typeKey = typeData?.toString()
    val type = when (typeData) {
        is Type -> typeData
        else -> if (typeKey != null) types[typeKey] else null
    }
    type?.let { content = content.replaceTypePlaceholders(it) }

    val levelData = sItem.getDeepData("sertraline:level")
    val levelKey = levelData?.toString()
    val level = when (levelData) {
        is Level -> levelData
        else -> if (levelKey != null) levels[levelKey] else null
    }
    level?.let { content = content.replaceLevelPlaceholders(it) }

    content = content.replace(VAL_PLACEHOLDER_REGEX) { match ->
        val vals = sItem.getDeepData("sertraline:vals") as? Map<*, *>
        vals?.get(match.groupValues[1])?.toString() ?: match.value
    }

    content = content.replace(VAR_PLACEHOLDER_REGEX) { match ->
        val vars = sItem.getDeepData("sertraline:vars") as? Map<*, *>
        vars?.get(match.groupValues[1])?.toString() ?: match.value
    }

    content = content.replace(CONTEXT_PLACEHOLDER_REGEX) { match ->
        val context = sItem.getDeepData("sertraline:context") as? Map<*, *>
        context?.get(match.groupValues[1])?.contextValue() ?: match.value
    }

    player?.let { if (content.contains('%')) content = content.replacePlaceholder(it) }

    if (content.contains("{{")) content = content.parseKether(player, cacheId = "${sItem.key}_placeholder")
    return content
}

// ═══════════════════════════════════════════════════════════════
//  Element key resolution
// ═══════════════════════════════════════════════════════════════

fun handleLoreExists(element: AbstractLoreElement, item: AbstractSItem): Boolean {
    return when (element) {
        is ComplexLoreElement -> {
            val require = element.lineRequire
            // Fast path: empty/null lineRequire — visibility depends only on lineMode
            if (require.isNullOrEmpty()) {
                return element.lineMode == null || element.lineMode == ALL || element.lineMode == NOT
            }
            when (element.lineMode) {
                ANY -> {
                    for (e in require) {
                        if (handleExistLore(e, item)) return true
                    }
                    false
                }
                ALL -> {
                    for (e in require) {
                        if (!handleExistLore(e, item)) return false
                    }
                    true
                }
                NOT -> {
                    for (e in require) {
                        if (handleExistLore(e, item)) return false
                    }
                    true
                }
                NOT_ALL -> {
                    for (e in require) {
                        if (!handleExistLore(e, item)) return true
                    }
                    false
                }
                else -> true
            }
        }
        else -> true
    }
}

private val LORE_LINE_SPLIT = Regex("\\\\n|\\n")

private fun String.splitLoreLines(): List<String> {
    return split(LORE_LINE_SPLIT)
}

fun handleKeyLore(
    item: AbstractSItem,
    player: Player?,
    element: AbstractLoreElement,
    settings: LoreSetting,
    targetCache: MutableMap<CompareTarget, ResolvedCompareTarget?>? = null,
): List<String> {
    // Try compare resolution first
    if (settings.compareEnabled) {
        if (settings.compare == null) {
            devLog("handleKeyLore: compareEnabled=true but settings.compare is NULL! element.key=${element.key}")
        } else {
            devLog("handleKeyLore: compareEnabled=true, compare has target=${settings.compare?.target != null}, compares keys=${settings.compare?.compares?.keys}")
        }
        val compareResult = resolveCompareContent(item, player, element, settings, targetCache)
        if (compareResult != null) {
            val (content, _) = compareResult
            devLog("handleKeyLore: compare resolved, content='$content'")
            return content.splitLoreLines()
        }
        devLog("handleKeyLore: resolveCompareContent returned null for element.key=${element.key}")
    } else {
        devLog("handleKeyLore: compareEnabled=false for element.key=${element.key}")
    }

    // Fallback to normal key resolution
    val key = element.key
    val raw = if (key != null) {
        val keyValueList = if (key.startsWith("*")) {
            ConfigUtil.getDeep(item.config, key.removePrefix("*")).asListEnhanced()
        } else {
            item.getDeepData(key).asListEnhanced()
        } ?: emptyList()

        if (keyValueList.isEmpty()) return emptyList()

        val intermediateList = keyValueList.map { value ->
            value.performNormalPlaceholders(element.content, player, item, skipGeneralPlaceholders = true)
        }

        // Apply general placeholders (tier/type/level/val/var/context/PlaceholderAPI/kether)
        // to each element directly, avoiding expensive JSON serialization+deserialization round-trip.
        intermediateList.map { it.performPlaceholders(item, player) ?: it }
    } else {
        element.content.performPlaceholders(item, player)?.let { listOf(it) } ?: emptyList()
    }
    return raw.flatMap { line ->
        if ('\\' in line || '\n' in line) line.splitLoreLines() else listOf(line)
    }
}

fun handleExistLore(key: String, item: AbstractSItem): Boolean {
    return if (key.startsWith("*")) {
        ConfigUtil.existDeep(item.config, key.removePrefix("*"))
    } else {
        item.getDeepData(key) != null
    }
}

fun mergeConsecutiveEmptyStrings(list: List<String>): List<String> {
    if (list.isEmpty()) {
        return emptyList()
    }

    // Fast path: scan for consecutive empty strings — if none, return the original list
    var hasConsecutiveEmpty = false
    var lastWasEmpty = false
    for (item in list) {
        if (item.isEmpty()) {
            if (lastWasEmpty) {
                hasConsecutiveEmpty = true
                break
            }
            lastWasEmpty = true
        } else {
            lastWasEmpty = false
        }
    }
    if (!hasConsecutiveEmpty) return list

    val result = mutableListOf<String>()
    lastWasEmpty = false

    for (item in list) {
        if (item.isEmpty()) {
            if (!lastWasEmpty) {
                result.add("")
                lastWasEmpty = true
            }
        } else {
            result.add(item)
            lastWasEmpty = false
        }
    }

    return result
}

// Performs placeholders on a list of strings.
// Uses per-element processing instead of JSON round-trip to avoid
// serialization/deserialization overhead.
fun List<String>.performPlaceholders(sItem: AbstractSItem, player: Player?): List<String> {
    if (isEmpty()) return emptyList()
    return map { it.performPlaceholders(sItem, player) ?: it }
}