package io.github.zzzyyylllty.sertraline.attribute

import io.github.zzzyyylllty.sertraline.debugMode.devLog
import io.github.zzzyyylllty.sertraline.logger.warningS
import io.github.zzzyyylllty.sertraline.util.serialize.generateUUID
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ChotenAttributeProvider : AttributeProvider {

    override val name: String = "choten"

    private val appliedModifiers = ConcurrentHashMap<UUID, MutableMap<UUID, ChoTenModifier>>()

    companion object {
        private val binding: ChoTenBinding? by lazy {
            val plugin = Bukkit.getPluginManager().getPlugin("ChoTenTech") ?: return@lazy null
            try {
                ChoTenBinding(plugin.javaClass.classLoader)
            } catch (e: ReflectiveOperationException) {
                warningS("ChoTenTech attribute integration is unavailable: ${e.message}")
                null
            } catch (e: LinkageError) {
                warningS("ChoTenTech attribute integration is unavailable: ${e.message}")
                null
            }
        }

        val available: Boolean get() = binding != null

        private val chotenFilter = setOf("id", "source", "slot", "allowed")

        private fun isSlotAllowed(actSource: String, allowed: List<String>): Boolean {
            return if (actSource.contains("hand")) {
                allowed.contains("hand") || allowed.contains(actSource)
            } else {
                allowed.contains(actSource)
            }
        }

        private fun parseModifierSource(raw: String?): String {
            return raw?.uppercase()?.takeIf { it in binding?.sourceNames.orEmpty() } ?: "ARMOR"
        }

        private fun parseAttributeSlot(raw: String): String {
            return when (raw) {
                "mainhand" -> "MAINHAND"
                "offhand" -> "OFFHAND"
                "helmet" -> "HEAD"
                "chestplate" -> "CHEST"
                "leggings" -> "LEGS"
                "boots" -> "FEET"
                else -> "OTHER"
            }
        }

        private fun solveModifier(key: Any?, value: Any?): ChotenAttributeValue {
            val keyStr = key.toString()
            val str = value.toString().trim()

            // `str.last()` threw NoSuchElementException on an empty config value ("atk: ''"),
            // which aborted the whole provider run and left every modifier of that refresh removed.
            if (str.isEmpty()) {
                devLog("Choten modifier '$keyStr' has an empty value; treated as +0.0 ADD")
                return ChotenAttributeValue(keyStr)
            }

            val modifierType = when (str.last()) {
                '%', 'c', 'm' -> "PERCENT"
                'a', 's' -> "MULTIPLY"
                else -> "ADD"
            }

            val numericValue = if (modifierType == "ADD") {
                str.toDoubleOrNull() ?: 1.0
            } else {
                str.dropLast(1).toDoubleOrNull() ?: 1.0
            }

            return ChotenAttributeValue(keyStr, numericValue, modifierType)
        }

        private data class ChotenAttributeValue(
            val atbID: String,
            val atbValue: Double = 1.0,
            val atbType: String = "ADD",
        )
    }

    override fun refreshAttributes(player: Player, itemList: Map<String, ItemBound>) {
        val api = binding ?: return
        val playerUuid = player.uniqueId

        val oldModifiers = appliedModifiers[playerUuid].orEmpty().toMap()
        val newModifiers = linkedMapOf<UUID, ChoTenModifier>()

        itemList.forEach { (slot, itemBound) ->
            val sItem = itemBound.sItem
            val chotenData = sItem.data.filterKeys { it.startsWith("choten:") }
                .mapKeys { (key, _) -> key.removePrefix("choten:") }
                .ifEmpty { return@forEach }
            val chotenMutable = chotenData.toMutableMap()

            devLog("chotenData: $chotenData")

            val allowed = (chotenData["allowed"] as? List<String>)?.toMutableList() ?: mutableListOf()
            val normalizedAllowed = if (allowed.isEmpty()) {
                val autoState = when (val suffix = itemBound.bItem.type.name.lowercase().substringAfterLast("_")) {
                    "boots", "chestplate", "leggings", "helmet" -> suffix
                    else -> "mainhand"
                }
                mutableListOf(autoState)
            } else {
                allowed
            }

            if (!isSlotAllowed(slot, normalizedAllowed)) {
                devLog("Choten slot $slot not allowed, skipping.")
                return@forEach
            }

            val source = chotenData["source"]?.toString()?.let { parseModifierSource(it) }
                ?: when (slot) {
                    "mainhand" -> "MAINHAND"
                    "offhand" -> "OFFHAND"
                    else -> "ARMOR"
                }

            val attributeSlot = parseAttributeSlot(slot)

            // Remove the filtered fields
            chotenFilter.forEach { chotenMutable.remove(it) }

            chotenMutable.forEach { (key, value) ->
                val atb = solveModifier(key, value)
                // Use the storageKey, which is unique across sources, rather than the bare key.
                // External or vanilla items commonly share a bare key with Sertraline items; reusing the UUID would make
                // addModifier overwrite the earlier modifier, silently reducing the player's attributes.
                val modifierUuid = (sItem.storageKey + "_" + slot + "_" + atb.atbID).generateUUID()

                val modifier = ChoTenModifier(
                    modifierUuid,
                    atb.atbID,
                    atb.atbValue,
                    atb.atbType,
                    source,
                    attributeSlot
                )

                newModifiers[modifierUuid] = modifier
            }
        }

        val added = mutableListOf<UUID>()
        val removed = mutableListOf<UUID>()
        try {
            for ((modifierUuid, modifier) in newModifiers) {
                val old = oldModifiers[modifierUuid]
                if (old == modifier) continue
                if (old != null) {
                    api.removeModifier(playerUuid, modifierUuid)
                    removed += modifierUuid
                }
                api.addModifier(playerUuid, modifier)
                added += modifierUuid
            }
            for (modifierUuid in oldModifiers.keys - newModifiers.keys) {
                api.removeModifier(playerUuid, modifierUuid)
                removed += modifierUuid
            }
            if (newModifiers.isEmpty()) appliedModifiers.remove(playerUuid)
            else appliedModifiers[playerUuid] = newModifiers.toMutableMap()
            api.updatePlayerAttributes(player)
        } catch (e: Throwable) {
            added.forEach { runCatching { api.removeModifier(playerUuid, it) } }
            removed.forEach { modifierUuid -> oldModifiers[modifierUuid]?.let { old ->
                runCatching { api.addModifier(playerUuid, old) }
            } }
            appliedModifiers[playerUuid] = oldModifiers.toMutableMap()
            throw e
        }

        devLog("ChotenAttribute refresh complete for ${player.name}, applied ${newModifiers.size} modifiers")
    }

    /**
     * Forgets the modifier ids tracked for [playerUuid].
     *
     * `appliedModifiers` was append-only: every player that ever joined kept a UUID set forever,
     * even after quitting. Modifiers themselves live in ChoTen's own player data, so the entry is
     * only needed while the player is online these days — call this on quit (or after a full
     * removal) to keep the map bounded.
     */
    fun forget(playerUuid: UUID) {
        val tracked = appliedModifiers[playerUuid] ?: return
        val api = binding ?: return
        val failed = linkedMapOf<UUID, ChoTenModifier>()
        tracked.forEach { (modifierUuid, modifier) ->
            try {
                api.removeModifier(playerUuid, modifierUuid)
            } catch (e: Exception) {
                failed[modifierUuid] = modifier
                warningS("[Sertraline] Failed to remove ChoTen modifier $modifierUuid for player $playerUuid: ${e.message}")
            }
        }

        if (failed.isEmpty()) appliedModifiers.remove(playerUuid, tracked)
        else appliedModifiers[playerUuid] = failed
    }
}

private data class ChoTenModifier(
    val uuid: UUID,
    val attribute: String,
    val amount: Double,
    val type: String,
    val source: String,
    val slot: String,
)

/** Links to the optional plugin at runtime; its distribution is not needed to compile Sertraline. */
private class ChoTenBinding(private val loader: ClassLoader) {
    private fun type(name: String): Class<*> =
        Class.forName("io.github.zzzyyylllty.chotentech.attribute.$name", false, loader)

    private val api = type("api.ChoTenAttributeAPI")
    private val instance = api.getMethod("getInstance")
    private val modifier = type("data.AttributeModifier")
    private val modifierType = type("data.ModifierType")
    private val modifierSource = type("data.ModifierSource")
    private val attributeSlot = type("data.AttributeSlot")
    private val constructor = modifier.getConstructor(
        UUID::class.java, String::class.java, Double::class.javaPrimitiveType, modifierType,
        modifierSource, attributeSlot, Long::class.javaObjectType,
    )
    private val add = api.getMethod("addModifier", UUID::class.java, modifier)
    private val remove = api.getMethod("removeModifier", UUID::class.java, UUID::class.java)
    private val update = api.getMethod("updatePlayerAttributes", Player::class.java)

    private val types = constants(modifierType)
    private val sources = constants(modifierSource)
    private val slots = constants(attributeSlot)
    val sourceNames: Set<String> get() = sources.keys

    private fun constants(type: Class<*>): Map<String, Any> =
        type.enumConstants.associateBy { (it as Enum<*>).name }

    fun addModifier(player: UUID, input: ChoTenModifier) {
        val value = constructor.newInstance(
            input.uuid, input.attribute, input.amount, types.getValue(input.type),
            sources.getValue(input.source), slots.getValue(input.slot), null,
        )
        add.invoke(instance.invoke(null), player, value)
    }

    fun removeModifier(player: UUID, id: UUID) {
        remove.invoke(instance.invoke(null), player, id)
    }

    fun updatePlayerAttributes(player: Player) {
        update.invoke(instance.invoke(null), player)
    }
}
