package io.github.zzzyyylllty.sertraline.util.dependencies

import io.github.zzzyyylllty.sertraline.data.AbstractSItem
import io.github.zzzyyylllty.sertraline.debugMode.devLog
import io.github.zzzyyylllty.sertraline.item.itemSerializer
import io.github.zzzyyylllty.sertraline.listener.sertraline.mmoFilter
import io.github.zzzyyylllty.sertraline.util.DependencyHelper
import io.github.zzzyyylllty.sertraline.util.serialize.generateUUID
import io.github.zzzyyylllty.sertraline.util.serialize.toUUID
import io.github.zzzyyylllty.sertraline.util.toLowerCase
import io.lumine.mythic.lib.MythicLib
import io.lumine.mythic.lib.api.player.EquipmentSlot
import io.lumine.mythic.lib.api.player.MMOPlayerData
import io.lumine.mythic.lib.api.stat.StatMap
import io.lumine.mythic.lib.api.stat.modifier.StatModifier
import io.lumine.mythic.lib.player.modifier.ModifierSource
import io.lumine.mythic.lib.player.modifier.ModifierType
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerKickEvent
import org.bukkit.event.player.PlayerQuitEvent
import taboolib.common.platform.event.SubscribeEvent
import taboolib.common.platform.function.submit
import taboolib.common.platform.function.submitAsync

//val mmoDataCacheMap = LinkedHashMap<String, MMOPlayerData>() // UUID, MMOPlayerData
//val mmoStatCacheMap = LinkedHashMap<String, StatMap>() // UUID, StatMap
//
//@SubscribeEvent
//fun mmoAttributeInit(e: PlayerJoinEvent) {
//    submitAsync {
//        if (!DependencyHelper.mmLib) return@submitAsync
//        val playerData = MMOPlayerData(e.player.uniqueId)
//        mmoDataCacheMap[e.player.uniqueId.toString()] = playerData
//        mmoStatCacheMap[e.player.uniqueId.toString()] = playerData.statMap
//    }
//}
//
//@SubscribeEvent
//fun mmoAttributeRelease(e: PlayerQuitEvent) {
//    submitAsync {
//        if (!DependencyHelper.mmLib) return@submitAsync
//        mmoDataCacheMap.remove(e.player.uniqueId.toString())
//        mmoStatCacheMap.remove(e.player.uniqueId.toString())
//    }
//}
//@SubscribeEvent
//fun mmoAttributeReleaseKick(e: PlayerKickEvent) {
//    submitAsync {
//        if (!DependencyHelper().isPluginInstalled("MythicLib")) return@submitAsync
//
//        mmoDataCacheMap.remove(e.player.uniqueId.toString())
//        mmoStatCacheMap.remove(e.player.uniqueId.toString())
//    }
//}

object MMOUtil {

    private const val MMO_KEY = "mmo"
    private const val ALLOWED_KEY = "allowed"
    private const val HAND = "hand"

    fun mmoAttributeCalculate(
        item: AbstractSItem,
        playerData: MMOPlayerData,
        defSource: ModifierSource,
        defSlot: EquipmentSlot,
        actSource: String,
        baseItemMaterial: String,
        async: Boolean = true
    ) {
        submit(async = async) {
            val mmoDataRaw = item.data[MMO_KEY] as? Map<*, *> ?: return@submit
            val mmoData = mmoDataRaw.toMutableMap()
            devLog("mmoData: $mmoData")

            val allowed = (mmoData[ALLOWED_KEY] as? List<String>)?.toMutableList() ?: mutableListOf()

            // If allowed is empty, infer the slot automatically from the base material
            val normalizedAllowed = if (allowed.isEmpty()) {
                val autoState = when (val suffix = baseItemMaterial.lowercase().substringAfterLast("_")) {
                    "boots", "chestplate", "leggings", "helmet" -> suffix
                    else -> "mainhand"
                }
                mutableListOf(autoState)
            } else {
                allowed
            }
            devLog("Allowed list: $normalizedAllowed")

            if (!isActSourceAllowed(actSource, normalizedAllowed)) {
                devLog("actSource: $actSource not allowed. skipping attribute loading.")
                return@submit
            }

            val idString = mmoData["id"]?.toString() ?: return@submit
            val slot = mmoData["slot"]?.toString()?.let { runCatching { EquipmentSlot.valueOf(it) }.getOrNull() } ?: defSlot
            val source = mmoData["source"]?.toString()?.let { runCatching { ModifierSource.valueOf(it) }.getOrNull() } ?: defSource

            devLog("ID: $idString, Slot: $slot, Source: $source")

            // remove filtered fields
            mmoFilter.forEach { mmoData.remove(it) }

            mmoData.forEach { (key, value) ->
                val atb = solveStatModifier(key, value)

                val uuid = (idString + "_" + atb.atbID).generateUUID()

                val modifierSlot = mmoData["${atb.atbID}_SLOT"]?.toString()?.let { runCatching { EquipmentSlot.valueOf(it) }.getOrNull() } ?: slot
                val modifierSource = mmoData["${atb.atbID}_SOURCE"]?.toString()?.let { runCatching { ModifierSource.valueOf(it) }.getOrNull() } ?: source

                StatModifier(
                    uuid,
                    "sertraline_item_$actSource",
                    atb.atbID,
                    atb.atbValue,
                    atb.atbType,
                    modifierSlot,
                    modifierSource
                ).register(playerData)

            }
        }
    }

    private fun isActSourceAllowed(actSource: String, allowed: List<String>): Boolean {
        return if (actSource.contains(HAND)) allowed.contains(HAND) || allowed.contains(actSource) else allowed.contains(actSource)
    }

    // MythicLib 1.7 renamed ADDITIVE_MULTIPLIER to COMPOUND (same additive-multiplier semantics).
    // Resolve by name at runtime so one build works against both old (ADDITIVE_MULTIPLIER)
    // and new (COMPOUND) MythicLib servers.
    private val compoundModifierType: ModifierType by lazy {
        ModifierType.values().firstOrNull { it.name == "COMPOUND" || it.name == "ADDITIVE_MULTIPLIER" } ?: ModifierType.FLAT
    }

    fun solveStatModifier(key: Any?, value: Any?): MMOAttributeValue {
        val keyStr = key.toString()
        val str = value.toString()

        val modifierType = when (str.last()) {
            '%', 'a'  -> ModifierType.RELATIVE
            'c', 'm' -> compoundModifierType
            else -> ModifierType.FLAT
        }

        val numericValue = if (modifierType == ModifierType.FLAT) {
            str.toDoubleOrNull() ?: 1.0
        } else {
            str.dropLast(1).toDoubleOrNull() ?: 1.0
        }

        return MMOAttributeValue(keyStr, numericValue, modifierType)
    }

    data class MMOAttributeValue(
        val atbID: String,
        val atbValue: Double = 1.0,
        val atbType: ModifierType = ModifierType.FLAT,
    )
}