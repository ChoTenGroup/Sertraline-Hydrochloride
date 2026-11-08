# AbstractSItem 当前代码检查

检查范围：`project/common/src/main/kotlin`。本文记录的是**决策前**的代码现状与迁移注意点；已确认的设计决策与迁移顺序见 `AbstractSItem-migration-map-zh.md`。

已确定：`buildItem()` 增加 Player 参数、`getMaterial()` 保留、外部构建改走 ItemBridge、`itemMap` 泛化为 `AbstractSItem`、`shouldAdapt` 只控制构建期、外部对象完整持久化。

## 结论摘要

`AbstractSItem`、`MinecraftSItem`、`ExternalPluginSItem` 已进入工作区，但主业务链仍以 `ModernSItem` 为具体类型。当前最主要的注意项：

1. `AbstractSItem.buildItem()` 不接收 `Player`，而已确认外部 ItemBridge 构建需要 Player。
2. `ExternalPluginSItem` 仍按 Bukkit `Material` 构建，与"通过 `ExternalItemData` + ItemBridge 构建"的约定不符。
3. Gson 当前固定反序列化为 `ModernSItem`，无法自动按 `sItemType` 恢复多态类型。
4. `shouldAdapt` 标记 `@Transient` 后不会进入 JSON，必须由唯一工厂在所有恢复路径重新赋值。
5. 加载入口、registry、Adapter 管线、构建路径仍全部绑定 `ModernSItem`。

## 类型定义

### AbstractSItem

位置：`data/AbstractSItem.kt`

当前契约包含 `key`、`sItemType`、`data`、`config`、`storageKey`、`shouldAdapt`，以及 `getMaterial(): Material`、`buildItem(): ItemStack`。

注意：

- `buildItem()` 没有 Player 参数，与外部构建需要 Player 的结论冲突。
- `getMaterial(): Material` 对外部插件物品未必成立——ItemBridge 直接产出 `ItemStack`，不保证对应一个有效 Bukkit `Material`。
- `data`/`config` 是可变 `LinkedHashMap`，构建时必须持有副本，否则 Adapter 会污染缓存模板。

### MinecraftSItem

位置：`data/MinecraftSItem.kt`

- 含 `minecraftId`，`getMaterial()` 走 `Material.valueOf(minecraftId)`，`storageKey` 由 `minecraft::$key` 派生。
- 需规定 `key` 与 `minecraftId` 的职责差异、大小写和命名空间格式；`Material.valueOf` 对无效 ID 与低版本服务器的行为要统一到解析层。
- `storageKey` 是计算属性（getter）。Gson 默认按字段序列化，不会写入计算属性，因此不要假定它出现在 JSON 中。它应只作为运行时索引键。

### ExternalPluginSItem 与 ExternalItemData

位置：`data/ExternalPluginSItem.kt`、`data/external/ExternalItemData.kt`

- `ExternalItemData` 当前字段为 `source`、`id`、可选 `metaData`，可作为 ItemBridge 的输入描述。
- `ExternalPluginSItem` 当前 `getMaterial()` 用 `Material.valueOf(key)`，`buildItem()` 返回原版 `ItemStack`，必须改为通过 ItemBridge 构建。
- `storageKey` 目前由调用方传入，建议改由工厂按 `source + id` 统一生成，避免与实际来源不一致。
- `sItemType = "plugin"` 只能表达"外部插件"类别，不能区分具体 provider；跨来源唯一性必须来自 `ExternalItemData.source`。

## 处理链现状

### 加载期（itemModule）

- `listener/sertraline/itemModuleSave.kt:10-35`：在 `ItemLoadEvent` 的 MONITOR 阶段把 `e.itemData` 与 `e.arguments` 组装成 `ModernSItem`，写入 `e.context?.items` 或 live `itemMap`。
- `listener/sertraline/itemModule*.kt`：在 `ItemLoadEvent` 阶段把配置段解析进 `e.itemData`，生成 `data`。

注意：这是**加载期**，与**构建期** Adapter 是两条不同的链路，迁移时不要合成一个开关语义。

### reload 候选与发布

- `config/LoadContext.kt:26`（`items`）、`:49`（`liveItems`）、`:65-87`（`publishToLive`）仍全部使用 `ModernSItem`。
- 现有语义是先构建候选 map，成功后整体引用替换 live registry，失败则丢弃候选。迁移类型时须保留这一点。

### 构建期（Adapter）

- `listener/sertraline/builder/builderInterface.kt:9-47`：`ItemProcessor.process` 与 `ItemProcessorManager.processItem` 均接收 `ModernSItem`，并按 `item.config.contains(processor.key)` 逐个筛选执行。
- `item/itemBuilder.kt:115-239`：`sertralineItemBuilderInternal` 与 `sertralineItemBuilderTemporary` 两条路径都以 `ModernSItem` 为模板，先 `deepCopy()`，再 `itemSerializer(...)`、`applyAlterationsToTemplate(...)`，最后 `itemManager.processItem(...)`。
- 已确定的目标规则：管线入口先判断 `shouldAdapt`；为 `false` 时跳过整个 Sertraline Adapter 管线，为 `true` 时再走 Processor 逐项筛选。

### 全局缓存

- `Sertraline.kt:231`：`itemMap` 仍是 `ConcurrentHashMap<String, ModernSItem>`。
- `manager/PublicManager.kt`、`manager/PrivateManager.kt`、`manager/ItemManager.kt` 的缓存与查询签名同样限定为 `ModernSItem`。

## 序列化与 shouldAdapt

现有 JSON 恢复入口：

- `data/ModernSItem.kt:106-108`（`serialize()`）与 `:193-195`（`deserializeSItem`，固定 `ModernSItem::class.java`）。
- `item/itemSerializer.kt:16-71`：动态/占位符路径会"序列化 → `tagManager.processItem` → 反序列化"，并在往返前后剥离/回填 `sertraline:context`。
- `manager/PublicManager.kt:56-83`：reload 前后把临时物品序列化进 `survivalBuffer`，再恢复。
- `manager/PrivateManager.kt:145-227`：数据库读取、缓存、`normalizeItem`。

注意：

- Gson 不会根据接口或 `sItemType` 自动选择实现类，需要显式多态策略或自定义 TypeAdapter。
- 旧 JSON 没有类型字段，必须默认恢复为 `ModernSItem`，否则已有数据库与缓存数据会解析失败。
- `shouldAdapt` 被 `@Transient` 排除，反序列化后是 JVM 默认 `false`。按约定由唯一工厂在恢复时赋值，且不能有绕过工厂的直接 Gson 调用。
- `@Transient` 只影响序列化，不影响 Kotlin `data class` 的 `equals/hashCode/copy/componentN`；若 `shouldAdapt` 是主构造属性，它仍参与对象身份比较。

## API 与调用面

- `api/SertralineAPI.kt`：`getItem`、`getItemFromStack`、`getAllItems`、`getVal`、public/private manager 系列、`registerItem`、`updateSItem`、`unregisterItem` 等签名使用 `ModernSItem`。旧签名应保留并加 `@Deprecated`，新分层 API 使用 `AbstractSItem`，不能原地改参数类型。
- `gui/ItemExplorer.kt`、`recipe/ItemResolver.kt`、`recipe/NMSRecipeFactory.kt`、`util/loreformat/*`、`attribute/*`、`function/action/*`、`item/process/tag/*`、`function/update/RevisionAutoTracker.kt` 等也直接引用 `ModernSItem`。应逐处判断是"Sertraline 模板专属"还是"对所有来源通用"，只迁移后者。

## 多来源解析重复点

- `data/OmniItem.kt:23-62`：按 `minecraft/mc/vanilla`、`sertraline/depazitems/depaz`、其他来源三分支构建。
- `item/itemBuilder.kt:44-72`：`itemSource()` 自己的冒号分割与来源判断。
- `recipe/ItemResolver.kt`、`recipe/NMSRecipeFactory.kt`、`util/SimpleRecipeHelper.kt`：另有外部/原版物品解析路径。

需要确认是否把这些入口统一到同一个工厂/解析器，否则同一 key 在不同入口可能得到不同类型或不同失败行为。

## 检查清单

1. `key` 是来源内部 ID，`storageKey` 是跨来源唯一 ID；明确各 registry 使用哪一个。
2. 外部唯一身份来自 `ExternalItemData.source + id`，不能只靠 `sItemType = "plugin"`。
3. 外部物品不要伪造 Bukkit `Material`；确认 `getMaterial()` 是否应留在公共接口。
4. 构建与 Processor 接口需要传递 Player 上下文，并定义 nullable / 无玩家时的行为。
5. `shouldAdapt` 的生效范围：是否只控制构建期 Adapter，加载期模块如何处理，需要在设计上写清。
6. 深拷贝必须保留具体子类型、`ExternalItemData`、`minecraftId` 和 `storageKey`。
7. 构建副本不得写回缓存模板的 `data`/`config`（现有 `itemBuilder` 已有副本保护，迁移时要保留）。
