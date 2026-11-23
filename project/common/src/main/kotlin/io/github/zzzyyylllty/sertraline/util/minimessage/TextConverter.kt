package io.github.zzzyyylllty.sertraline.util.minimessage

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer

fun List<Any?>.legacyToMiniMessage(): List<Any?> {
    if ((this as? List<String>) == null) return this
    val list = mutableListOf<String>()
    this.forEach { list.add(it.legacyToMiniMessage()) }
    return list
}
private val legacyHexPattern = Regex("&x((&[0-9a-fA-F]){6})")
private val modernHexPattern = Regex("&#([0-9a-fA-F]{6})")
private val simpleLegacyPattern = Regex("[&§]([0-9a-fk-or])", RegexOption.IGNORE_CASE)
private val legacyTags = listOf(
    "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
    "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white",
    "obfuscated", "bold", "strikethrough", "underlined", "italic", "reset",
)
private const val LEGACY_CODES = "0123456789abcdefklmnor"

fun String.legacyToMiniMessage(): String {
    if ('&' !in this && '§' !in this) return this
    var text = this

    // 1. Handle hex color codes first - legacy &x&6&6&c&c&f&f
    // Matches the form: &x&6&6&c&c&f&f (fixed six hex characters)
    text = legacyHexPattern.replace(text) { matchResult ->
        if (isInsidePlaceholder(text, matchResult.range)) {
            matchResult.value
        } else {
            val hexChars = matchResult.groupValues[1].chunked(2).joinToString("") { it.substring(1) }
            "<#$hexChars>"
        }
    }

    // 2. Handle modern hex color codes: &#66ccff or &#ABCDEF
    text = modernHexPattern.replace(text) { matchResult ->
        if (isInsidePlaceholder(text, matchResult.range)) {
            matchResult.value
        } else {
            "<#${matchResult.groupValues[1].lowercase()}>"
        }
    }

    text = simpleLegacyPattern.replace(text) { match ->
        if (isInsidePlaceholder(text, match.range)) match.value
        else "<" + legacyTags[LEGACY_CODES.indexOf(match.groupValues[1].single().lowercaseChar())] + ">"
    }

    return text
}

fun isInsidePlaceholder(text: String, range: IntRange): Boolean {
    val start = text.lastIndexOf("\${", range.first)
    val end = text.indexOf("}$", range.last)

    return start != -1 && end != -1 && start < range.first && end > range.last
}
