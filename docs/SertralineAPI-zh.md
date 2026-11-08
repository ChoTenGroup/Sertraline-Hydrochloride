# SertralineAPI

`Sertraline.api()` 返回 `SertralineAPI` 接口实例，是外部插件与 JS 脚本访问 Sertraline 功能的统一入口。它覆盖物品查询与构建、玩家背包物品统计/扣除、物品重建、公共/私人物品管理器、模板系统与模板处理器注册。

所有同步 API 若涉及玩家背包/数据，调用方须在玩家所属线程（主线程或区域线程）内调用。

---

## 枚举

### ManagerRange

背包物品查询范围：

- `PUBLIC` — 只匹配公有物品（ID 不以 `__` 开头）
- `PRIVATE` — 只匹配私人物品（ID 以 `__` 开头）
- `BOTH` — 同时匹配公有与私人物品

### InventorySlotRange

背包槽位范围：

- `STORAGE` — 存储区（主背包 + 快捷栏）
- `ALL` — 全部槽位（额外包含盔甲栏与副手）

### SubManagerType

物品子管理器类型：

- `TEMPORARY` — 临时（内存，重启丢失）
- `PERSISTENT` — 持久（写入数据库）

---

## 物品查询与构建

### getItem

获取 Sertraline 物品对象（`ModernSItem`），未注册返回 `null`。

`getItem(id: String): ModernSItem?`

### getAllItems

获取所有已注册的公共物品，返回 `Map<ID, ModernSItem>`。

`getAllItems(): Map<String, ModernSItem>`

### buildItem

为玩家构建物品。

`buildItem(sItem: String, player: Player?, source: ItemStack? = null, amount: Int = 1, overrideData: Map<String, Any?>? = null): ItemStack?`

- `source`：源物品，`null` 则自动构建。
- `overrideData`：覆盖物品数据的字段。

### buildDataItem

为玩家构建物品（支持变量）。`vars` 为模板变量（持久化到 NBT），`context` 为运行时上下文（Player、Event 等对象，可通过 `{context:xxx}` 标签访问，不持久化到 NBT）。

`buildDataItem(sItem: String, player: Player?, source: ItemStack? = null, amount: Int = 1, overrideData: Map<String, Any?>? = null, vars: Map<String, Any?>? = null, context: Map<String, Any?>? = null): ItemStack?`

### getId

获取物品的 Sertraline ID，非 Sertraline 物品返回 `null`。

`getId(itemStack: ItemStack): String?`

### getVal

从 Sertraline 物品对象读取 val 数据（`sertraline:vals`），键不存在返回 `null`。

`getVal(sItem: ModernSItem, valId: String): Any?`

### getVar

从物品 NBT 中读取持久化的 var 数据（`sertraline_data`），键不存在返回 `null`。

`getVar(itemStack: ItemStack, varId: String): Any?`

### isValidItem

判断物品是否为 Sertraline 物品（存在 Sertraline ID）。

`isValidItem(itemStack: ItemStack): Boolean`

### isRegisteredItem

判断物品是否已在 `itemMap` 中注册。

`isRegisteredItem(itemStack: ItemStack): Boolean`

### isRegisteredItem

判断指定 ID 是否已在 `itemMap` 中注册。

`isRegisteredItem(id: String): Boolean`

---

## 玩家背包物品

以下方法按 Sertraline ID 精确匹配背包中的物品，私有范围会验证该 ID 是否属于目标玩家的私有管理器。所有方法均为同步 API，须在玩家所属线程调用。

### countItem

统计玩家背包中的 Sertraline 物品数量。

`countItem(id: String, player: Player, range: ManagerRange = ManagerRange.PUBLIC, slots: InventorySlotRange = InventorySlotRange.STORAGE): Int`

### hasItem

判断玩家背包中的物品是否不少于指定数量。非正 `amount` 始终返回 `false`。

`hasItem(id: String, player: Player, amount: Int = 1, range: ManagerRange = ManagerRange.PUBLIC, slots: InventorySlotRange = InventorySlotRange.STORAGE): Boolean`

### takeItem

尽可能从背包扣除物品，返回实际扣除数量。非正 `amount` 返回 `0`。

`takeItem(id: String, player: Player, amount: Int = 1, range: ManagerRange = ManagerRange.PUBLIC, slots: InventorySlotRange = InventorySlotRange.STORAGE): Int`

### takeItemExactly

仅当数量充足时扣除全部物品。数量不足或非正 `amount` 时不修改背包并返回 `false`。

`takeItemExactly(id: String, player: Player, amount: Int = 1, range: ManagerRange = ManagerRange.PUBLIC, slots: InventorySlotRange = InventorySlotRange.STORAGE): Boolean`

### countItemAnyOwner

统计物品数量，不验证私有物品归属（只按 ID 分类）。

`countItemAnyOwner(id: String, player: Player, range: ManagerRange = ManagerRange.PUBLIC, slots: InventorySlotRange = InventorySlotRange.STORAGE): Int`

### hasItemAnyOwner

判断物品数量是否达标，不验证私有物品归属。非正 `amount` 返回 `false`。

`hasItemAnyOwner(id: String, player: Player, amount: Int = 1, range: ManagerRange = ManagerRange.PUBLIC, slots: InventorySlotRange = InventorySlotRange.STORAGE): Boolean`

### takeItemAnyOwner

尽可能扣除物品，不验证私有物品归属，返回实际扣除数量。

`takeItemAnyOwner(id: String, player: Player, amount: Int = 1, range: ManagerRange = ManagerRange.PUBLIC, slots: InventorySlotRange = InventorySlotRange.STORAGE): Int`

### takeItemAnyOwnerExactly

仅当数量充足时扣除全部物品，不验证私有物品归属。数量不足或非正 `amount` 时不修改背包并返回 `false`。

`takeItemAnyOwnerExactly(id: String, player: Player, amount: Int = 1, range: ManagerRange = ManagerRange.PUBLIC, slots: InventorySlotRange = InventorySlotRange.STORAGE): Boolean`

---

## 物品重建

### rebuildLore

重新生成物品的 Lore。**会直接修改传入的物品**。

`rebuildLore(itemStack: ItemStack, player: Player?): Unit`

### rebuildName

重新生成物品的显示名称。**会直接修改传入的物品**。

`rebuildName(itemStack: ItemStack, player: Player?): Unit`

### rebuild

重建整个 Sertraline 物品并返回新物品。**不会修改传入的物品**。

`rebuild(itemStack: ItemStack, player: Player?): ItemStack`

### rebuildUnsafe

通过 ItemMeta 重建物品并写入原物品。**会直接修改传入的物品**。注意：这是不安全的方法，会丢失部分 DataComponent（1.21.4 中 76 个组件丢失 3 个），仅在明确知道后果时使用。

`rebuildUnsafe(itemStack: ItemStack, player: Player?): Unit`

---

## 物品管理器

### createPublicItem

创建公共物品。

`createPublicItem(id: String, data: Map<String, Any?>, sub: SubManagerType = SubManagerType.TEMPORARY): Unit`

### getPublicItem

获取公共物品，默认从持久管理器读取。

`getPublicItem(id: String, sub: SubManagerType = SubManagerType.PERSISTENT): ModernSItem?`

### deletePublicItem

删除公共物品，返回是否删除成功。

`deletePublicItem(id: String, sub: SubManagerType): Boolean`

### getAllPublicItems

获取指定子类型的全部公共物品。

`getAllPublicItems(sub: SubManagerType): Map<String, ModernSItem>`

### createPrivateItem

为指定 UUID 创建私人物品。

`createPrivateItem(uuid: String, id: String, data: Map<String, Any?>, sub: SubManagerType = SubManagerType.TEMPORARY): Unit`

### getPrivateItem

获取指定 UUID 的私人物品，默认从持久管理器读取。

`getPrivateItem(uuid: String, id: String, sub: SubManagerType = SubManagerType.PERSISTENT): ModernSItem?`

### deletePrivateItem

删除指定 UUID 的私人物品。

`deletePrivateItem(uuid: String, id: String, sub: SubManagerType): Unit`

### getAllPrivateItems

获取指定 UUID 的子类型下的全部私人物品。

`getAllPrivateItems(uuid: String, sub: SubManagerType): Map<String, ModernSItem>`

### updateSItem（私有物品）

更新指定 UUID 的私人物品。持久管理器会同步更新数据库；返回旧物品，不存在时返回 `null`。

`updateSItem(uuid: String, id: String, item: ModernSItem, sub: SubManagerType = SubManagerType.PERSISTENT): ModernSItem?`

也可以直接使用 `ModernSItem.key` 作为 ID：

`updateSItem(uuid: String, item: ModernSItem, sub: SubManagerType = SubManagerType.PERSISTENT): ModernSItem?`

### registerItem

直接注册物品到 `itemMap`（持久化公共物品）。

`registerItem(id: String, item: ModernSItem): Unit`

### updateSItem

直接更新 `itemMap` 中的公共物品。返回被替换的旧物品；如果原 ID 不存在则返回 `null`。

`updateSItem(id: String, item: ModernSItem): ModernSItem?`

也可以直接使用 `ModernSItem.key` 作为 ID：

`updateSItem(item: ModernSItem): ModernSItem?`

### unregisterItem

从 `itemMap` 移除物品，返回被移除的物品（不存在返回 `null`）。

`unregisterItem(id: String): ModernSItem?`

### getItemCount

获取 `itemMap` 中物品总数。

`getItemCount(): Int`

### resolvePrivateUuid

解析私有 UUID（自动兜底：优先给定值，缺失时回退到玩家 UUID）。

`resolvePrivateUuid(uuid: String?, playerUuid: String?): String`

---

## 模板系统

### getTemplate

获取已加载的模板（不触发解析），不存在返回 `null`。

`getTemplate(name: String): Map<String, Any?>?`

### getTemplateNames

获取所有已加载的模板名。

`getTemplateNames(): Set<String>`

### getAllTemplates

获取所有已加载的模板（不可变快照）。

`getAllTemplates(): Map<String, Map<String, Any?>>`

### resolveTemplate

手动解析模板：深拷贝 → 参数替换 → 递归解析。

`resolveTemplate(name: String, args: Map<String, String>): Map<String, Any?>?`

### getTemplateCount

获取已加载模板数量。

`getTemplateCount(): Int`

---

## 模板处理器注册

用于注册自定义 `$t` 变换器、`$c` 转换器和参数级指令。

### registerTransformer

注册自定义 `$t` 变换器类型。

`registerTransformer(type: String, provider: TemplateManager.TransformerProvider): Unit`

### unregisterTransformer

注销自定义 `$t` 变换器类型。

`unregisterTransformer(type: String): Unit`

### registerConverter

注册自定义 `$c` 转换器类型。

`registerConverter(type: String, provider: TemplateManager.ConverterProvider): Unit`

### unregisterConverter

注销自定义 `$c` 转换器类型。

`unregisterConverter(type: String): Unit`

### registerDirective

注册自定义参数级指令（与 `$t`/`$c` 同级，如 `$myDirective`）。

`registerDirective(name: String, provider: TemplateManager.DirectiveProvider): Unit`

### unregisterDirective

注销自定义参数级指令。

`unregisterDirective(name: String): Unit`
