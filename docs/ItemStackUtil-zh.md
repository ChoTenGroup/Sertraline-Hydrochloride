# ItemStackUtil

`ItemStackUtil` 提供物品 NBT 读写与重建相关工具，基于 TabooLib 的 `ItemTag` 和 Sertraline 的物品重建管线。

> vars 系列方法（`getVar` / `hasVar` / `setVar` / `setVarDirect` / `removeVar` / `removeVarDirect`）需要插件版本 **3.11.14+**。

---

## getItemTag

获取物品的 `ItemTag`（物品 NBT 的读写句柄）。可用 `tag["key"]` 读取、`tag["key"] = ...` 写入。

`getItemTag(itemStack: ItemStack): ItemTag`

## setItemTag

用给定的 `ItemTag` 覆盖物品的 NBT。**不会修改原物品**，返回写入后的新物品，需接收返回值。

`setItemTag(itemStack: ItemStack, tag: ItemTag): ItemStack`

## setItemTagDirect

用给定的 `ItemTag` 覆盖物品的 NBT。**会直接修改原物品**，返回值同样是修改后的物品。

`setItemTagDirect(itemStack: ItemStack, tag: ItemTag): ItemStack`

## transferToByte

TabooLib 不支持 boolean 类型的 NBT，传入 `true`/`false` 时可用此函数转换为对应的 `Byte` 值（`1`/`0`），再写入 NBT。

`transferToByte(input: Any?): Any?`

## rebuildLore

按物品模板重新构建该物品的 lore（重算变量、占位符、条件行等）。直接修改原物品，无返回值。

`rebuildLore(itemStack: ItemStack, player: Player?): Unit`

## rebuildName

按物品模板重新构建该物品的名称。直接修改原物品，无返回值。

`rebuildName(itemStack: ItemStack, player: Player?): Unit`

## rebuild

按物品模板完整重建该物品（名称 + lore + 属性等），返回新物品。用于物品数据/模板更新后刷新旧物品。

`rebuild(itemStack: ItemStack, player: Player?): ItemStack`

## rebuildBypassKeepData

快速重建：跳过 `keep-data` 保留逻辑，直接从模板重建并返回新物品。适合已知物品来源、无需保留运行时数据的场景。

`rebuildBypassKeepData(itemStack: ItemStack, player: Player?): ItemStack`

## rebuildUnsafe

强制重建，跳过部分安全校验。直接修改原物品，无返回值。仅在明确知道自己在做什么时使用。

`rebuildUnsafe(itemStack: ItemStack, player: Player?): Unit`

---

## vars 系列方法

> 需要插件版本 **3.11.14+**。

对底层 `ItemTag` 深度路径方法的封装，作用域固定为物品的持久化 vars（`sertraline_data` compound）。键支持 `.` 嵌套，如 `"count"` → `sertraline_data.count`、`"foo.bar"` → `sertraline_data.foo.bar`。

相比裸用 `getItemTag` + `putDeep` + `setItemTagDirect`，这些方法自动处理 Tag 的读取与保存，并遵循与上方 Tag 方法相同的 Direct / 非 Direct 约定：

```js
// 旧写法
var tag = ItemStackUtil.getItemTag(bItem);
tag.putDeep("sertraline_data.count", newValue);
ItemStackUtil.setItemTagDirect(bItem, tag);

// 新写法
ItemStackUtil.setVarDirect(bItem, "count", newValue);
```

## getVar

按 `.` 分层键读取 var，不存在返回 `null`。

`getVar(itemStack: ItemStack, key: String): Any?`

## hasVar

判断指定键的 var 是否存在。

`hasVar(itemStack: ItemStack, key: String): Boolean`

## setVar

写入 var。**不会修改原物品**，返回写入后的新物品，需接收返回值。value 传 `null` 时删除该键（嵌套路径同理）。

`setVar(itemStack: ItemStack, key: String, value: Any?): ItemStack`

## setVarDirect

写入 var。**会直接修改原物品**，返回值同样是修改后的物品。value 传 `null` 时删除该键。

`setVarDirect(itemStack: ItemStack, key: String, value: Any?): ItemStack`

## removeVar

删除 var。**不会修改原物品**，返回删除后的新物品，需接收返回值。

`removeVar(itemStack: ItemStack, key: String): ItemStack`

## removeVarDirect

删除 var。**会直接修改原物品**，返回值同样是修改后的物品。

`removeVarDirect(itemStack: ItemStack, key: String): ItemStack`

### 注意事项

- 写入值遵循 TabooLib `ItemTag` 的 NBT 语义：`Boolean`/`UUID` 会降级为字符串存储（NBT 没有 boolean 类型）；`String`/`Number`/`Map`/`List` 原生存储。
- 这里写入的 vars 与模板管线回读的持久化 vars 是同一份数据（`rebuildLore` 等重建方法会从 `sertraline_data` 重算占位符），因此改完 var 后调用 `rebuildLore` 即可刷新物品显示的 lore。
