package io.github.zzzyyylllty.sertraline.item.process

import io.github.zzzyyylllty.sertraline.attribute.AttributeRegistry
import io.github.zzzyyylllty.sertraline.attribute.data.formatAttributeValue
import io.github.zzzyyylllty.sertraline.attribute.data.parseAttributeValue
import io.github.zzzyyylllty.sertraline.item.process.tag.ProcessItemTagData
import io.github.zzzyyylllty.sertraline.item.process.tag.processTagPrefix
import org.bukkit.entity.Player

/**
 * Lore placeholder `${attribute:<id>}$`, rendering the value the item itself declares
 * (e.g. `+100`, `-50`, `+10%`). Use it inside a quoted lore line: `- "<gray>${attribute:attack_damage}$"`.
 *
 * Unknown ids and unparsable values resolve to the `?:default` fallback, or to an empty string —
 * returning null made the tag pipeline print a literal "null" into the lore.
 */
fun attributeTagProcessor(data: ProcessItemTagData, player: Player?, repl: Map<String, List<String>>, target: MutableMap<String, String?>) {
    val section = repl["attribute"] ?: return

    @Suppress("UNCHECKED_CAST")
    val declared = data.item.data["attribute"] as? Map<String, Any?> ?: emptyMap()

    processTagPrefix(
        prefix = "attribute",
        // Never skip: an item without the section must still resolve to "" instead of leaving a
        // literal ${attribute:...}$ in the lore.
        dataSourceEmptyCheck = { false },
        getReplaceValue = { _, _, default, cleanedSection ->
            val fallback = default ?: ""
            val id = cleanedSection?.let { AttributeRegistry.normalizeId(it) } ?: return@processTagPrefix fallback
            val raw = declared[id] ?: declared.entries
                .firstOrNull { AttributeRegistry.normalizeId(it.key) == id }?.value
                ?: return@processTagPrefix fallback

            val parsed = parseAttributeValue(raw) ?: return@processTagPrefix fallback
            formatAttributeValue(AttributeRegistry.get(id), parsed.first, parsed.second)
        },
        repl = section,
        target = target,
    )
}
