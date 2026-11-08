package io.github.zzzyyylllty.sertraline.item.process

import io.github.zzzyyylllty.sertraline.data.getGemSlotDefinitions
import io.github.zzzyyylllty.sertraline.item.gem.getSocketedGem
import io.github.zzzyyylllty.sertraline.item.process.tag.ProcessItemTagData
import io.github.zzzyyylllty.sertraline.item.process.tag.processTagPrefix
import org.bukkit.entity.Player

/**
 * Lore placeholder for gem slots: `${gem-slot:<slotId>}$`.
 *
 * Renders the slot state at build time:
 * - empty slot -> the slot's `desc` (e.g. "Can apply Red Gem.")
 * - occupied slot -> the slot's `name` plus the socketed gem id
 *
 * The placeholder reads the instance stack (`data.itemStack`) when available so
 * the rendered lore reflects the gems actually socketed into the item.
 *
 * Use it inline inside a quoted lore line (e.g. `- "<gray>${gem-slot:attack}$"`):
 * the tag pipeline substitutes the value into the item JSON, so quotes and
 * backslashes coming from the slot config are escaped to keep that JSON valid.
 */
fun gemSlotTagProcessor(data: ProcessItemTagData, player: Player?, repl: Map<String, List<String>>, target: MutableMap<String, String?>) {
    val slots = data.item.getGemSlotDefinitions()

    repl["gem-slot"]?.let {
        processTagPrefix(
            prefix = "gem-slot",
            dataSourceEmptyCheck = { slots.isEmpty() },
            getReplaceValue = { _, _, default, cleanedSection ->
                val slotId = cleanedSection ?: return@processTagPrefix default
                val slot = slots[slotId] ?: return@processTagPrefix default
                val socketed = data.itemStack?.getSocketedGem(slotId)
                val text = if (socketed != null) "◆ ${slot.name}: ${socketed.gemId ?: "?"}"
                else "◇ ${slot.name} — ${slot.desc}"
                jsonEscape(text)
            },
            repl = it,
            target = target,
        )
    }
}

/** Escapes a plain text value for embedding into a JSON string. */
private fun jsonEscape(text: String): String {
    if (text.none { it == '"' || it == '\\' || it < ' ' }) return text
    val out = StringBuilder(text.length + 8)
    for (ch in text) {
        when {
            ch == '"' -> out.append("\\\"")
            ch == '\\' -> out.append("\\\\")
            ch == '\n' -> out.append("\\n")
            ch == '\r' -> out.append("\\r")
            ch == '\t' -> out.append("\\t")
            ch < ' ' -> out.append("\\u").append(String.format("%04x", ch.code))
            else -> out.append(ch)
        }
    }
    return out.toString()
}
