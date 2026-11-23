package io.github.zzzyyylllty.sertraline.compat

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.ComponentLike
import net.kyori.adventure.text.KeybindComponent as AdventureKeybind
import net.kyori.adventure.text.NBTComponent as AdventureNBT
import net.kyori.adventure.text.ScoreComponent as AdventureScore
import net.kyori.adventure.text.ScopedComponent as AdventureScoped
import net.kyori.adventure.text.SelectorComponent as AdventureSelector
import net.kyori.adventure.text.TextComponent as AdventureText
import net.kyori.adventure.text.TranslatableComponent as AdventureTranslatable
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.md_5.bungee.api.ChatColor
import net.md_5.bungee.api.chat.BaseComponent
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.HoverEvent
import net.md_5.bungee.api.chat.KeybindComponent
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.chat.TranslatableComponent
import java.lang.reflect.Method
import java.util.Locale

/**
 * adventure Component → bungee BaseComponent (Spigot-specific).
 * Spigot's sendComponent/sendActionBar go through high-fidelity BaseComponent conversion:
 * hex colors, decorations, child elements, and click/hover events are all preserved instead of being
 * degraded to § strings (LEGACY serialize is only used where there is no BaseComponent API,
 * such as Title/ItemMeta/Inventory titles).
 * bungee has no Selector/Score/NBT components, so these fall back to plain text.
 */
object BungeeComponentConverter {

    fun convert(component: Component): BaseComponent {
        val node = convertNode(component)
        component.children().forEach { node.addExtra(convert(it)) }
        return node
    }

    fun convertOrNull(component: Component?): BaseComponent? = component?.let { convert(it) }

    private fun convertNode(component: Component): BaseComponent {
        val node: BaseComponent = when (component) {
            is AdventureText -> TextComponent(component.content())
            is AdventureTranslatable ->
                TranslatableComponent(component.key(), *component.arguments().map { translateArg(it) }.toTypedArray())
            is AdventureKeybind -> KeybindComponent(component.keybind())
            is AdventureSelector -> TextComponent(component.pattern())
            is AdventureScore -> TextComponent(listOfNotNull(component.name(), component.value()).joinToString(" "))
            is AdventureNBT<*, *> -> TextComponent(component.nbtPath())
            // adventure 4.21+ removed DecoratedComponent; delegated components are uniformly handled as ScopedComponent.asComponent()
            is AdventureScoped<*> -> convertNode(component.asComponent())
            else -> TextComponent(component.toString())
        }
        applyStyle(node, component.style())
        return node
    }

    /** Translation arguments: component types convert to bungee components; scalars (String/Number/Boolean) are passed to the client as-is for interpolation */
    private fun translateArg(arg: net.kyori.adventure.text.TranslationArgument): Any {
        val value = arg.value()
        return if (value is ComponentLike) convert(value.asComponent()) else value
    }

    private fun applyStyle(node: BaseComponent, style: net.kyori.adventure.text.format.Style) {
        style.color()?.let { node.color = toBungeeColor(it) }

        val obf = style.decoration(TextDecoration.OBFUSCATED)
        if (obf == TextDecoration.State.TRUE) node.isObfuscated = true
        else if (obf == TextDecoration.State.FALSE) node.isObfuscated = false
        val bold = style.decoration(TextDecoration.BOLD)
        if (bold == TextDecoration.State.TRUE) node.isBold = true
        else if (bold == TextDecoration.State.FALSE) node.isBold = false
        val strike = style.decoration(TextDecoration.STRIKETHROUGH)
        if (strike == TextDecoration.State.TRUE) node.isStrikethrough = true
        else if (strike == TextDecoration.State.FALSE) node.isStrikethrough = false
        val underline = style.decoration(TextDecoration.UNDERLINED)
        if (underline == TextDecoration.State.TRUE) node.isUnderlined = true
        else if (underline == TextDecoration.State.FALSE) node.isUnderlined = false
        val italic = style.decoration(TextDecoration.ITALIC)
        if (italic == TextDecoration.State.TRUE) node.isItalic = true
        else if (italic == TextDecoration.State.FALSE) node.isItalic = false

        style.clickEvent()?.let { e ->
            val action = toBungeeAction(e.action()) ?: return@let
            node.clickEvent = ClickEvent(action, e.value())
        }
        style.hoverEvent()?.let { e ->
            if (e.action() == net.kyori.adventure.text.event.HoverEvent.Action.SHOW_TEXT) {
                val text = (e.value() as? Component) ?: return@let
                node.hoverEvent = HoverEvent(HoverEvent.Action.SHOW_TEXT, arrayOf(convert(text)))
            }
        }
    }

    private fun toBungeeAction(action: net.kyori.adventure.text.event.ClickEvent.Action): ClickEvent.Action? =
        when (action) {
            net.kyori.adventure.text.event.ClickEvent.Action.OPEN_URL -> ClickEvent.Action.OPEN_URL
            net.kyori.adventure.text.event.ClickEvent.Action.OPEN_FILE -> ClickEvent.Action.OPEN_FILE
            net.kyori.adventure.text.event.ClickEvent.Action.RUN_COMMAND -> ClickEvent.Action.RUN_COMMAND
            net.kyori.adventure.text.event.ClickEvent.Action.SUGGEST_COMMAND -> ClickEvent.Action.SUGGEST_COMMAND
            net.kyori.adventure.text.event.ClickEvent.Action.CHANGE_PAGE -> ClickEvent.Action.CHANGE_PAGE
            net.kyori.adventure.text.event.ClickEvent.Action.COPY_TO_CLIPBOARD -> copyToClipboardAction
            // bungee 1.20-R0.2 has no SHOW_DIALOG/CUSTOM, cannot pass them through, so skip
            else -> null
        }

    // ── bungee 1.16+ API reflection bridge (v11200 = MC 1.12.2 compile surface lacks these, correctly falls back) ──

    // ChatColor.of(String) is a 1.16+ API; older servers need the nearest legacy color.
    private val chatColorOfMethod: Method? by lazy {
        try { ChatColor::class.java.getMethod("of", String::class.java) } catch (_: Throwable) { null }
    }

    private fun toBungeeColor(color: TextColor): ChatColor {
        if (color is NamedTextColor) return toLegacyColor(color)
        val modernColor = try {
            chatColorOfMethod?.invoke(null, color.asHexString()) as? ChatColor
        } catch (_: Throwable) {
            null
        }
        return modernColor ?: toLegacyColor(NamedTextColor.nearestTo(color))
    }

    private fun toLegacyColor(color: NamedTextColor): ChatColor =
        ChatColor.valueOf(NamedTextColor.NAMES.key(color)!!.uppercase(Locale.ROOT))

    // ClickEvent.Action.COPY_TO_CLIPBOARD is a 1.16+ enum constant; when absent on 1.12.2, the whole click event is dropped
    private val copyToClipboardAction: ClickEvent.Action? by lazy {
        try {
            @Suppress("UNCHECKED_CAST")
            ClickEvent.Action::class.java.getField("COPY_TO_CLIPBOARD").get(null) as? ClickEvent.Action
        } catch (_: Throwable) {
            null
        }
    }
}
