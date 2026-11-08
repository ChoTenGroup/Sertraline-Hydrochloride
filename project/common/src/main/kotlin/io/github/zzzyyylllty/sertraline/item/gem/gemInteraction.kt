package io.github.zzzyyylllty.sertraline.item.gem

import io.github.zzzyyylllty.sertraline.config.common.SertralineSettings
import io.github.zzzyyylllty.sertraline.data.GemDefinition
import io.github.zzzyyylllty.sertraline.data.GemSlotDefinition
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType

/**
 * How a socket request reached the system.
 *
 * Every interaction route carries its own identity so rules can be applied per route
 * instead of being baked into one listener. [CLICK] honours `gem.interaction.click.*`
 * plus the per-item / per-socket / per-gem `click` flags; [COMMAND] and [API] are
 * administrative routes and bypass those restrictions.
 *
 * Adding a new route (a GUI, a crafting-station action, a kether function, …) means:
 * 1. add an entry here,
 * 2. give it a route gate in [GemSocketPolicy.denialOf] (or `null` when unrestricted),
 * 3. call [socketGem] with it, and add definition-level rules in
 *    [GemSocketPolicy.isAllowedByDefinition] if the route should honour them.
 */
enum class GemSocketMethod {
    /** Player holds a gem on the cursor and clicks a socketable item. */
    CLICK,

    /** `/sertralinegem socket …` — administrative. */
    COMMAND,

    /** Plugin API call — administrative. */
    API,
}

/** Why a route refused a socket request. */
enum class GemRouteDenial {
    /** The route is switched off in `config.yml`. */
    DISABLED,

    /** The click gesture is not one of the configured `gem.interaction.click.types`. */
    CLICK_TYPE,

    /** The player lacks `gem.interaction.click.permission`. */
    PERMISSION,
}

/**
 * `gem.interaction.*` settings.
 *
 * These are dynamic config nodes (no strongly typed snapshot field), so they follow the
 * same pattern as `tags.<name>` and are hot-reloadable through [SertralineSettings].
 */
object GemInteractionSettings {

    private val DEFAULT_CLICK_TYPES = setOf("RIGHT")

    /** Master switch for click-to-socket. */
    val clickEnabled: Boolean
        get() = SertralineSettings.rawBoolean("gem.interaction.click.enable", true)

    /**
     * Click gestures that may socket a gem, e.g. `RIGHT`, `SHIFT_RIGHT`, `LEFT`.
     * Accepts a YAML list or a delimited string (`"RIGHT,SHIFT_RIGHT"`); empty / unreadable
     * values fall back to `RIGHT` so the feature never dies silently.
     */
    val clickTypes: Set<String>
        get() {
            val path = "gem.interaction.click.types"
            val raw = SertralineSettings.rawList(path) ?: SertralineSettings.raw(path)
            val tokens = when (raw) {
                is List<*> -> raw.mapNotNull { it?.toString() }
                is String -> raw.split(',', '|', ' ')
                else -> emptyList()
            }
            return tokens
                .map { it.trim().uppercase() }
                .filter { it.isNotEmpty() }
                .toSet()
                .takeIf { it.isNotEmpty() }
                ?: DEFAULT_CLICK_TYPES
        }

    /** Permission required for click-to-socket; blank = no permission check. */
    val clickPermission: String
        get() = SertralineSettings.rawString("gem.interaction.click.permission", "").trim()
}

/** Route-level and definition-level rules for socketing. */
object GemSocketPolicy {

    /**
     * Returns why [method] may not be used right now, or `null` when it is allowed.
     *
     * @param click the gesture that triggered the request, when the route is click-driven
     */
    fun denialOf(method: GemSocketMethod, player: Player?, click: ClickType? = null): GemRouteDenial? =
        when (method) {
            GemSocketMethod.CLICK -> {
                val settings = GemInteractionSettings
                when {
                    !settings.clickEnabled -> GemRouteDenial.DISABLED
                    click != null && click.name !in settings.clickTypes -> GemRouteDenial.CLICK_TYPE
                    !hasPermission(player, settings.clickPermission) -> GemRouteDenial.PERMISSION
                    else -> null
                }
            }
            // Administrative routes are always available; they are gated by their own permission.
            GemSocketMethod.COMMAND, GemSocketMethod.API -> null
        }

    /** Convenience wrapper for [denialOf]. */
    fun isRouteEnabled(method: GemSocketMethod, player: Player?, click: ClickType? = null): Boolean =
        denialOf(method, player, click) == null

    /**
     * Whether the item / socket / gem definitions allow [method].
     *
     * `click: false` on any of the three levels forbids the click route only; commands and
     * API calls stay available so an admin or another feature can still socket the gem.
     */
    fun isAllowedByDefinition(
        method: GemSocketMethod,
        itemClickEnabled: Boolean,
        slot: GemSlotDefinition,
        gem: GemDefinition,
    ): Boolean = when (method) {
        GemSocketMethod.CLICK -> itemClickEnabled && slot.clickSocket && gem.clickSocket
        GemSocketMethod.COMMAND, GemSocketMethod.API -> true
    }

    /** Blank permission = allowed; a missing player (console) is treated as allowed. */
    fun hasPermission(player: Player?, permission: String): Boolean {
        if (permission.isBlank()) return true
        return player?.hasPermission(permission) ?: true
    }
}
