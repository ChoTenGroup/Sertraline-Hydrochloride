# AbstractSItem 重构 —— 子代理审查结论与处理

5 个审查子代理并行审查，各自覆盖独立风险面。本文汇总**去重后**的结论，并标注处理状态。

核查方式：每条都回到源码/git 基线复核，不直接采信代理结论；有两条代理结论被证伪，单独列出。

---

## 已修复

### 1. `SItemFactory.deserialize()` 出口无校验（4 个代理独立指向，最高共识）

**根因**：Gson 走 `ReflectiveTypeAdapterFactory`，用 `Unsafe` 分配对象、反射填字段，**不调用 Kotlin 构造函数、不执行默认值表达式**。于是 `data` / `config` / `externalItemData` 在 JSON 缺字段或为 `null` 时变成 `null`，并**绕过 Kotlin 非空检查**。之后第一次访问 `hasPlaceholders` / `getDeepData` / `storageKey` 就是 NPE，且没有任何日志。

同时类型分派是**区分大小写、不 trim** 的：`"Minecraft"` / `"PLUGIN"` / 未知值全部静默降级成 `ModernSItem`，而 `minecraftId` 不在 `ModernSItem` 的字段表里 → 被 Gson 丢弃 → **数据永久丢失**，且对象仍是"可加载"的。

**已修**：
- 分派前统一 `trim().lowercase()`，与 `classify()` / `parse()` 保持一致；
- 未知 `sItemType` 显式告警，不再静默降级；
- 新增 `validate()`：`data`/`config` 为 null、`externalItemData` 不完整、`minecraftId` 为空 → 告警并返回 null，坏数据不进注册表；
- 整个反序列化包进 `runCatching`，坏 JSON 返回 null 而不是把异常抛到构建线程。

### 2. 属性 modifier UUID 用裸 key，跨来源撞车

`attribute/ChotenAttributeProvider.kt:133` 原为 `sItem.key + "_" + slot + "_" + atbID`。类型放宽后 `MinecraftSItem.key` 可以是 `"stone"`，外部物品裸 key 与 Sertraline 同名是常态 → 两件不同物品生成**同一个 UUID**，`addModifier` 后者覆盖前者，玩家属性静默变少。

**已修**：改用 `sItem.storageKey`（接口定义里它才是"跨来源唯一 ID"）。

### 3. `SertralineItemAPI` 三处键域错误（我自己写的代码）

- `getSertralineItems()` / `getMinecraftItems()` / `getExternalItems()` 用 `it.key` 建键 → 不同来源同名物品互相覆盖，改用 `storageKey`。
- `getItem()` 有第三级 `itemMap[parsed.key]` 回退 → `getItem("myprovider::gun")` 会命中同名的 Sertraline `gun`，**跨来源串数据**。已删除该回退。
- `register()` 默认 `id = item.key` 会让同一物品同时存在于裸 key 与 `storageKey` 两个键下（`getItemCount()` 双重计数）。默认值改为 `item.storageKey`。

### 4. `PublicManager` 两处误删

- `remove(id, TEMPORARY)` 在临时表没有该 id 时仍无条件删注册表 → **误删持久物品**。改为先确认 `temporaryCache.remove(id)` 命中，再用 `itemMap.remove(id, removed)` 只删同一实例。
- `createTemporary()` 的回滚无条件 `temporaryCache.remove(id)` + `itemMap.remove(id)` → 当注册表里本来就有同 id 条目时，**把与本调用无关的条目删掉**。改为分两步 `putIfAbsent`，回滚只撤自己插入的那条。

### 5. `PrivateManager.loadFromDB()` 提前 return 短路 legacy 回退

`deserializeSertraline(...) ?: return null` 把"单条反序列化失败"升级成"整个查找失败"，**不再尝试 `legacyId`**，与 `loadAllFromDB` 的 `?: continue` 语义不一致。改为 `continue` + 告警。

### 6. `itemSource()` 的 `sertraline:` 前缀静默变草方块

`itemBridge` 已用 `.removeById("sertraline")` 摘掉该 provider，所以 `itemSource("sertraline:xxx")` 走外部分支必然返回 null → `devLog` + 草方块，**无任何警告**。项目里 `material` 字段与 `::` 引用语法混用时很容易写成这样。

**已修**：`classify` 结果为 SERTRALINE 时显式取模板 `getMaterial()`，取不到则 `warningS` 并回退；同时 `split(":", limit = 2)`，避免 `minecraft:stone:extra` 之类静默丢段。

### 7. 我引入的 8 个文件 BOM

早前用 `Set-Content -Encoding utf8` 批量替换 `ModernSItem → AbstractSItem` 时给 8 个文件加了 UTF-8 BOM。Kotlin 能编译，但属污染，已全部剥除（逐个校验首三字节）。

---

## 已确认存在、但未修（需你决策）

### A. ~~三条配方路径对同一引用给出不同结果~~ —— 已修复

`ItemResolver` 已改用 `classify()`，但 `NMSRecipeFactory` / `SimpleRecipeHelper` 仍各自 `when(namespace == "minecraft" || "vanilla")`。对 `mc:stone`：

| 路径 | 原结果 |
|---|---|
| SIMPLE provider（`ItemResolver`） | `MaterialChoice(STONE)` ✅ |
| COMPLEX provider（`NMSRecipeFactory`） | 落外部分支 → null → `IllegalArgumentException("No items resolved")` |
| `SimpleRecipeHelper.buildResultStack` | 外部分支 → null → 告警 + 不注册 |

**已修**：按"配方裸名先试原版、失败告警回落 Sertraline"的要求，新增 `recipe/RecipeReferences.kt` 作为配方路径唯一入口，`ItemResolver` / `NMSRecipeFactory`（配料与产物）/ `SimpleRecipeHelper` / `itemRecipe` 全部改用它，并去掉了 `loadRecipeFiles` 把裸名预包裹成 `minecraft:` 的逻辑。详见 `AbstractSitem-implementation-status-zh.md` 的判定表。

### B. ~~`parseId()` 把裸 ID 判成 `minecraft`~~ —— 已随 A 一并解决

三份 `parseId` 中，`ItemResolver` 的那份已删除；`NMSRecipeFactory` 与 `SimpleRecipeHelper` 各自保留一份（仍有 `unlockForPlayer` 等内部调用方），但裸名解析已不再经过它们。

### C. `updateSItem` 重载的语义陷阱

工作区里有 4 个 `updateSItem` 重载（相对 HEAD 都是未提交新增，非本次重构引入）：

- `updateSItem(uuid, item, sub = PERSISTENT)` —— 写私有管理器
- `updateSItem(id, item)` —— 写全局 `itemMap`

两参调用 `updateSItem(s, item)` 会解析到后者。想写私有管理器却漏了 `sub` 的调用方会**静默改到全局注册表**。建议给注册表那组改名（如 `updateRegisteredItem`）。

⚠️ 代理曾声称这里存在 Kotlin `OVERLOAD_RESOLUTION_AMBIGUITY`，**已证伪**：`updateSItem(item)` 内部就调用了 `updateSItem(item.key, item)`，正是被指为歧义的形状，而项目编译通过——Kotlin 优先选择不使用默认参数的候选。

### D. `deepCopy()` 对外部物品不彻底

`ExternalPluginSItem.deepCopy()` 直接复用 `externalItemData` 引用，而 `ExternalItemData.metaData` 是可变的 `LinkedHashMap`。`LoreFormatUtil` 的深拷贝注释明确是为了"避免污染共享模板"，但只覆盖了 `data`/`config`，`metaData` 仍是共享引用。目前无写入方，属埋雷。

### E. `MinecraftSItem` / `ExternalPluginSItem` 永远拿不到 Adapter 处理

`shouldAdapt` 在 `restoreRuntimeState()` 里被硬编码（Minecraft=false / External=true / Modern=true），`processItem()` 首句据此直接返回。所以 Minecraft 物品**完全跳过** Processor 管线。这可能是有意的（原版物品不该被 Sertraline config 改），但结果是：若某个 `MinecraftSItem` 的 `config` 里带了 `visual`/`lore` 段（`config` 是公共字段），会被静默忽略。建议明确约定或在校验期拒绝。

### F. `MinecraftSItem.buildItem()` 返回 AIR 物品

`getMaterial()` 失败时返回 `ItemStack(Material.AIR)`，而下游（`item.amount = amount`、NBT 写入、Lore 渲染）没有 AIR 检查。契约上"失败返回 AIR 而非 null"是既定决策，但下游需要配合判空，目前没有。

### G. `sertralineItemBuilderInternal` 无法构建非 Modern 条目

`itemMap` 已是 `AbstractSItem`，但材质推导只有 `xbuilder:material` / `minecraft:material` / `config.itemdef.material` 三条，**没有 `MinecraftSItem.minecraftId` 或 `ExternalPluginSItem.externalItemData` 分支**。经旧入口（`sertralineItemBuilder`、`ItemStack.rebuild*`）构建非 Modern 条目会静默产出 GRASS_BLOCK。目前 `itemMap` 只装 Sertraline 物品，所以是潜伏的。

---

## 被证伪的代理结论

1. **"`updateSItem` 重载导致 Kotlin 重载歧义，第三方源码重编译失败"** —— 见 C，项目自身就存在该调用形状且编译通过。
2. **"`itemSource("mc:stone")` 的行为变更是回归"** —— 属有意的修正（已在实施状态文档记录），与 `loadCraftingStationFiles` 默认 `source="mc"` 的语义一致。但前提是要把 `NMSRecipeFactory` / `SimpleRecipeHelper` 一并对齐，否则反而制造 A 中的不一致。

## 代理提出的、经核实无需处理的项

- `@Volatile var itemMap`：reload 用引用整体替换，必须 volatile，正确。
- `@Deprecated` 本身不影响字节码，旧 API 擦除签名全部未变，第三方已编译 jar 不会 `NoSuchMethodError`。
- `publishToLive()` 的整体替换语义成立，读侧看不到空窗口。
- `RevisionAutoTracker.computeItemHash` 只哈希 `config`/`data`，`sItemType` 是类字段不在其中 → **不会**造成 Sertraline 物品全量 revision 误报。
- `AttributeManager` / `applyActions` 等入口靠 NBT `sertraline_id` 前置过滤，非 Sertraline 物品天然进不来。

---

## 验证状态

- `:project:common:compileKotlin` 通过；完整 `build` 见构建日志。
- 用独立 JVM 探针验证了 `deserialize()`：`data:null`、缺 `data`、未知 `sItemType` 三个用例都命中了我新增的告警分支（探针因缺 TabooLib 依赖在 `warningS` 处抛 `ClassNotFoundException`，恰好证明代码走到了该分支）；合法 JSON 与 `"SERTRALINE"` 大小写变体均正常返回 `ModernSItem`。
- **未能**在裸 JVM 里验证 `MinecraftSItem` / `ExternalPluginSItem` 的分派与校验（需要 Bukkit + TabooLib 运行时）。这几条属静态推理 + 编译通过，建议在测试服按 `AbstractSItem-test-checklist-zh.md` 实机确认。
- `project/common/src/test` 仍为空，无自动化测试。
