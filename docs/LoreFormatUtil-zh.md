# LoreFormatUtil

`LoreFormatUtil` 提供 Lore 格式（`lore-formats` 目录下配置）的应用工具：按格式生成 lore 组件列表，或直接替换物品的 lore。内部经 `PlatformCompat`，Paper / Spigot 行为一致。

---

## generateLore

按指定格式生成 lore 组件列表。格式不存在返回 `null`。

`generateLore(item: ItemStack, sItem: AbstractSItem, format: String, player: Player? = null, defaultVars: Map<String, Any?>? = null): List<Component>?`

- `item`：要生成 lore 的物品。
- `sItem`：物品对应的 `AbstractSItem` 数据。
- `format`：lore 格式名称。
- `player`：用于占位符解析（可选）。
- `defaultVars`：额外的默认数据，会合并到物品的 vars 中供占位符解析使用。

## applyLore

按指定格式生成 lore 并直接设置到物品上，返回修改后的物品。格式不存在则返回原物品（不修改）。

`applyLore(item: ItemStack, sItem: AbstractSItem, format: String, player: Player? = null, defaultVars: Map<String, Any?>? = null): ItemStack`

参数含义同 `generateLore`。

## getFormat

获取已加载的 `AbstractLoreFormat` 对象，不存在返回 `null`。

`getFormat(format: String): AbstractLoreFormat?`

## getFormatNames

获取所有已加载的 Lore 格式名称。

`getFormatNames(): Set<String>`
