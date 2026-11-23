# LoreFormatUtil

`LoreFormatUtil` provides utilities for applying lore formats (configured in the `lore-formats` directory): generating a list of lore components from a format, or directly replacing an item's lore. Goes through `PlatformCompat` for consistent Paper/Spigot behavior.

---

## generateLore

Generate a list of lore components from the given format. Returns `null` if the format does not exist.

`generateLore(item: ItemStack, sItem: AbstractSItem, format: String, player: Player? = null, defaultVars: Map<String, Any?>? = null): List<Component>?`

- `item`: the item to generate lore for.
- `sItem`: the item's `AbstractSItem` data.
- `format`: the lore format name.
- `player`: used for placeholder parsing (optional).
- `defaultVars`: extra default data merged into the item's vars for placeholder parsing.

## applyLore

Generate lore from the given format and set it directly on the item, returning the modified item. If the format does not exist, returns the original item unmodified.

`applyLore(item: ItemStack, sItem: AbstractSItem, format: String, player: Player? = null, defaultVars: Map<String, Any?>? = null): ItemStack`

Parameters mean the same as `generateLore`.

## getFormat

Get a loaded `AbstractLoreFormat` object; returns `null` if it does not exist.

`getFormat(format: String): AbstractLoreFormat?`

## getFormatNames

Get the names of all loaded lore formats.

`getFormatNames(): Set<String>`
