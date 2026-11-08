package io.github.zzzyyylllty.sertraline.recipe

import io.github.zzzyyylllty.sertraline.Sertraline
import io.github.zzzyyylllty.sertraline.data.SItemFactory
import io.github.zzzyyylllty.sertraline.data.AbstractSItem
import io.github.zzzyyylllty.sertraline.data.RecipeIngredient
import io.github.zzzyyylllty.sertraline.item.sertralineItemBuilder
import io.github.zzzyyylllty.sertraline.util.ExternalItemHelper
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.RecipeChoice

/**
 * Resolves a [RecipeIngredient] into a Bukkit [RecipeChoice],
 * supporting Sertraline items, vanilla items, tags, and external plugin items.
 */
object ItemResolver {

    /** Known external plugin namespaces (not resolved through Sertraline's itemMap) */
    private val knownExternalNamespaces = setOf(
        "itemsadder", "oraxen", "nexo", "mythicmobs",
        "mmoitems", "ecoitems", "executableitems", "neigeitems",
        "azureflow", "craftengine"
    )

    /**
     * Resolves an ingredient definition into a [RecipeChoice].
     * - [RecipeIngredient.Item] → ExactChoice (Sertraline / external) or MaterialChoice (vanilla)
     * - [RecipeIngredient.Tag] → MaterialChoice(Tag)
     * - [RecipeIngredient.Choice] → merge all options
     */
    fun resolve(ingredient: RecipeIngredient): RecipeChoice {
        return when (ingredient) {
            is RecipeIngredient.Item -> resolveItem(ingredient.itemId, ingredient.amount)
            is RecipeIngredient.Tag -> resolveTag(ingredient.tagId, ingredient.amount)
            is RecipeIngredient.Choice -> resolveChoice(ingredient)
        }
    }

    private fun resolveItem(itemId: String, amount: Int): RecipeChoice {
        val ref = resolveRecipeReference(itemId)
            ?: throw IllegalArgumentException("Unknown item reference: $itemId")

        return when (ref.kind) {
            // Vanilla item → MaterialChoice
            RecipeSourceKind.MINECRAFT -> {
                val material = ref.material
                    ?: throw IllegalArgumentException("Unknown vanilla material: $itemId")
                RecipeChoice.MaterialChoice(material)
            }
            // Sertraline's own item → ExactChoice (with NBT)
            RecipeSourceKind.SERTRALINE -> {
                val sItem = Sertraline.itemMap[ref.id]
                    ?: throw IllegalArgumentException("Unknown Sertraline item: $itemId")
                val stack = buildSertralineStack(sItem, amount)
                RecipeChoice.ExactChoice(stack)
            }
            // External plugin item → ExactChoice
            RecipeSourceKind.EXTERNAL -> {
                val stack = buildExternalStack(ref.provider, ref.id, amount)
                RecipeChoice.ExactChoice(stack)
            }
        }
    }

    private fun resolveTag(tagId: String, amount: Int): RecipeChoice {
        val normalized = normalizeTagKey(tagId)
        val tag = resolveBukkitTag(normalized)
            ?: throw IllegalArgumentException("Unknown tag: $tagId (normalized: $normalized)")
        return RecipeChoice.MaterialChoice(tag)
    }

    @Suppress("UNCHECKED_CAST")
    private fun resolveChoice(choice: RecipeIngredient.Choice): RecipeChoice {
        val choices = choice.options.map { resolve(it) }
        if (choices.isEmpty()) throw IllegalArgumentException("Choice must have at least one option")

        // Collect MaterialChoice / ExactChoice
        val materials = mutableListOf<Material>()
        val exactStacks = mutableListOf<ItemStack>()
        var materialChoiceCount = 0
        var exactChoiceCount = 0

        for (c in choices) {
            when (c) {
                is RecipeChoice.MaterialChoice -> {
                    @Suppress("DEPRECATION")
                    materials.addAll(c.choices)
                    materialChoiceCount++
                }
                is RecipeChoice.ExactChoice -> {
                    exactStacks.addAll(c.choices)
                    exactChoiceCount++
                }
            }
        }

        return when {
            exactChoiceCount > 0 && materialChoiceCount == 0 ->
                RecipeChoice.ExactChoice(exactStacks)
            materialChoiceCount > 0 && exactChoiceCount == 0 ->
                RecipeChoice.MaterialChoice(materials)
            else -> error("Mixed choice types cannot be merged: ${materialChoiceCount} MaterialChoice + ${exactChoiceCount} ExactChoice")
        }
    }

    // ---- Internal helpers ----

    /**
     * Normalizes a tag key:
     * - #minecraft:planks  →  minecraft:planks
     * - tag:planks        →  minecraft:planks
     * - planks            →  minecraft:planks
     */
    private fun normalizeTagKey(key: String): String {
        var n = key.trim()
        if (n.startsWith('#')) n = n.substring(1)
        if (n.startsWith("tag:")) n = "minecraft:" + n.substring(4)
        if (!n.contains(":")) n = "minecraft:$n"
        return n
    }

    @Suppress("UNCHECKED_CAST")
    private fun resolveBukkitTag(normalized: String): Tag<Material>? {
        return try {
            // Try the Tag in the Bukkit registry
            val key = org.bukkit.NamespacedKey.fromString(normalized) ?: return null
            val tagField = Tag::class.java.getDeclaredField(normalized
                .substringAfter(':')
                .uppercase()
                .replace('/', '_')
            )
            tagField.isAccessible = true
            tagField.get(null) as? Tag<Material>
        } catch (_: Exception) {
            // Fall back to Bukkit.getTag
            try {
                val key = org.bukkit.NamespacedKey.fromString(normalized) ?: return null
                @Suppress("DEPRECATION")
                org.bukkit.Bukkit.getTag(Tag.REGISTRY_ITEMS, key, Material::class.java)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun buildSertralineStack(sItem: AbstractSItem, amount: Int): ItemStack {
        val stack = sertralineItemBuilder(sItem.key, null)
            ?: throw IllegalArgumentException("Failed to build Sertraline item: ${sItem.key}")
        stack.amount = amount
        return stack
    }

    private fun buildExternalStack(namespace: String, key: String, amount: Int): ItemStack {
        val stack = ExternalItemHelper.buildNoPlayer(namespace, key)
            ?: throw IllegalArgumentException("Failed to build external item: $namespace:$key")
        stack.amount = amount
        return stack
    }

    /**
     * Parses a string-formatted ingredient description directly into a [RecipeChoice].
     * This method is for external callers (e.g. temporary use during config parsing).
     */
    fun parseString(choiceStr: String): RecipeChoice {
        val trimmed = choiceStr.trim()
        return when {
            trimmed.startsWith("#") || trimmed.startsWith("tag:") -> {
                resolveTag(trimmed, 1)
            }
            // 裸名交给 resolveRecipeReference 处理：原版优先，失败回落 Sertraline
            else -> {
                resolveItem(trimmed, 1)
            }
        }
    }
}
