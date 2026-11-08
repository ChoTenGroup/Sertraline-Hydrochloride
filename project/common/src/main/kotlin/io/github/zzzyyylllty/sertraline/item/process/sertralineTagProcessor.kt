package io.github.zzzyyylllty.sertraline.item.process

import io.github.zzzyyylllty.sertraline.config.asListEnhanced
import io.github.zzzyyylllty.sertraline.function.data.contextValue
import io.github.zzzyyylllty.sertraline.function.data.getSavedData
import io.github.zzzyyylllty.sertraline.function.kether.evalKether
import io.github.zzzyyylllty.sertraline.item.process.tag.ProcessItemTagData
import io.github.zzzyyylllty.sertraline.item.process.tag.processTagPrefix
import org.bukkit.entity.Player

fun sertralineTagProcessor(data: ProcessItemTagData,player: Player?, repl: Map<String, List<String>>, target: MutableMap<String, String?>) {

    val itemData = getSavedData(data.item, data.itemStack, false, player)
    val itemVal = itemData.itemVal
    val itemVar = itemData.itemVar
    val itemDynamic = itemData.itemDynamic
    val itemContext = itemData.itemContext
    val collect = itemData.collect()
    val name = data.item.key

    repl["val"]?.let {
        processTagPrefix(
            prefix = "val",
            dataSourceEmptyCheck = { itemVal?.isEmpty() ?: true },
            getReplaceValue = { parseResult, section, default, cleanedSection ->
                // Customize the logic based on the parse result; for this simplified example, look up the section directly in itemVal
                itemVal?.get(cleanedSection) ?: default
            },
            repl = it,
            target = target
        )
    }

    repl["var"]?.let {
        processTagPrefix(
            prefix = "var",
            dataSourceEmptyCheck = { itemVar?.isEmpty() ?: true },
            getReplaceValue = { parseResult, section, default, cleanedSection ->
                itemVar?.get(cleanedSection) ?: default
            },
            repl = it,
            target = target
        )
    }

    repl["context"]?.let {
        processTagPrefix(
            prefix = "context",
            dataSourceEmptyCheck = { itemContext?.isEmpty() ?: true },
            getReplaceValue = { parseResult, section, default, cleanedSection ->
                itemContext?.get(cleanedSection)?.contextValue() ?: default
            },
            repl = it,
            target = target
        )
    }

    repl["kether"]?.let {
        processTagPrefix(
            prefix = "kether",
            dataSourceEmptyCheck = { false }, // kether has no empty-check logic
            getReplaceValue = { parseResult, section, default, cleanedSection ->
                cleanedSection.evalKether(data.player, collect, cacheId = "${name}_kether").get() ?: default
            },
            repl = it,
            target = target
        )
    }

    repl["dynamic"]?.let {
        processTagPrefix(
            prefix = "dynamic",
            dataSourceEmptyCheck = { itemDynamic?.isEmpty() ?: true },
            getReplaceValue = { parseResult, section, default, cleanedSection ->
                itemDynamic?.get(cleanedSection)?.asListEnhanced()
                    ?.evalKether(data.player, collect, cacheId = "${name}_dynamic")
                    ?.get() ?: default
            },
            repl = it,
            target = target
        )
    }
}
