package io.github.zzzyyylllty.sertraline.util.minimessage

import io.github.zzzyyylllty.sertraline.config.common.SertralineSettings
import io.github.zzzyyylllty.sertraline.item.adapter.transferBooleanToByte
import net.kyori.adventure.text.Component
import kotlin.collections.component1
import kotlin.collections.component2

fun String.toComponent(): Component {
    return FastMiniMessage.deserialize(this)
}
fun String.toComponentJson(): String {
    return mmJsonUtil.serialize(this.toComponent())
}

fun List<String>.toComponent(): List<Component> {
    return if (SertralineSettings.useSplitReplaceListSerialize) listOf(FastMiniMessage.deserialize(this.joinToString("<br>"))) else this.map { FastMiniMessage.deserialize(it) }
}

fun Any?.serializeComponent(): Any? {
    val input = this
    return when (input) {
        is Map<*, *> -> input.map { (k, v) ->
            k.toString() to v.serializeComponent()
        }.toMap() // directly use map and toMap

        is List<*> -> input.map { it.serializeComponent() } // use map

        is String -> input.toComponent()

        else -> input
    }
}