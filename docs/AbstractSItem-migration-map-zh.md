# AbstractSItem 迁移计划与位置映射

## 已确定决策

| 议题 | 决策 | 直接后果 |
|---|---|---|
| `buildItem()` 的 Player 参数 | 增加 | 所有实现与调用点都要传上下文；需定义 `Player?` 为空时的行为 |
| `getMaterial()` | 保留在 `AbstractSItem` | 必须为外部/Minecraft 实现定死语义，不能抛未捕获异常 |
| `ExternalPluginSItem` 构建方式 | 走 ItemBridge | 移除 `Material.valueOf(key)` / `ItemStack(Material)` |
| `itemMap` | 泛化为 `AbstractSItem` | 所有取回点需处理具体类型；键空间规则必须先定 |
| `shouldAdapt` 作用范围 | 只控制构建期 Adapter | 加载期 `itemModuleXXX` 不受其影响 |
| 外部对象持久化 | 保存完整数据 | 需持久化 `ExternalItemData`；provider 缺失时对象仍可读出但不可构建 |

### 由决策引出的两项必须先定的语义

**1. `getMaterial()` 的对外语义**

既然保留在接口，又要支持外部物品，就必须明确：

- 它返回的是"基础材质"还是"构建后物品的材质"；
- 外部 provider 未安装、ID 不存在、或该物品的 `ItemStack` 类型为 `AIR` 时返回什么；
- 它是否允许触发 ItemBridge 构建（若无 Player，构建可能失败）。

建议语义：返回 `Material`，无法确定时返回 `Material.AIR`，绝不抛异常；调用方需自行判断 `AIR`。

**2. `itemMap` 的键空间**

`itemMap` 泛化后，若 Sertraline 仍以裸 ID 为键、其他来源以 `storageKey` 为键，同一个 Map 会混用两套键规则。建议：

- 内部统一以 `storageKey` 索引（Sertraline 的 `storageKey` 建议等于裸 `key`，避免破坏现有查找）；
- 对外 API 与脚本保持裸 ID 查 Sertraline 的行为；
- 不允许 `minecraft::x` 之类的 key 被当作 Sertraline ID 直接查表。

前置约定（已确定）：

- `key` 是来源内部 ID；`sItemType` 是来源类别；`storageKey` 是跨来源规范标识。
- 裸 `ITEMID` 仍表示 Sertraline 物品，不引入 `sertraline::` 前缀。
- `data` / `config` 是所有实现共有的字段。
- `shouldAdapt` 是运行时策略，不序列化，由唯一工厂赋值。
- 旧 `SertralineAPI` 保留原签名并 deprecated，新分层 API 面向 `AbstractSItem`。
- 外部依赖只经 `ExternalItemHelper`，不泄漏到公共抽象层。
- 外部物品构建需要 `Player`。

## 步骤 1：固化抽象契约

改动点：

- `data/AbstractSItem.kt`：`buildItem` 增加构建上下文参数（至少 `Player?`）；保留 `getMaterial()` 并写明可失败语义。
- `data/MinecraftSItem.kt`：确定 `key` 与 `minecraftId` 的规范化规则；把 `Material.valueOf` 的异常路径纳入统一解析层。
- `data/ExternalPluginSItem.kt`：移除 `Material.valueOf(key)` 构建，改为通过 `ExternalItemData` + `ExternalItemHelper`；`storageKey` 改由工厂生成。
- `data/external/ExternalItemData.kt`：确认 `source` 是否就是 ItemBridge 的 provider 名，`id` 是否就是 provider 内 ID；`metaData` 是否参与构建。

完成判据：三个实现不再有"用 Material 假装外部物品"的逻辑；`buildItem(player)` 的三条实现路径都能正确构建或明确返回失败。

## 步骤 2：建立唯一工厂

新增统一工厂，负责：

- 由裸 ID 创建 `ModernSItem`。
- 由 `minecraft::x` / `minecraftId` 创建 `MinecraftSItem`。
- 由 `provider::id` 创建 `ExternalPluginSItem`。
- 从 JSON / 数据库记录恢复对象。
- 在以上所有路径上重新赋值 `shouldAdapt`。

必须覆盖的现有反序列化调用点：

- `data/ModernSItem.kt:193-195`（`deserializeSItem`）
- `item/itemSerializer.kt:21-22`、`:58-60`、`:69-70`
- `manager/PublicManager.kt:76`
- `manager/PrivateManager.kt:151`、`:167`

原则：任何直接 `jsonUtils.fromJson(..., ModernSItem::class.java)` 的调用都应改为经过工厂。旧 JSON 无类型字段时默认按 `ModernSItem` 恢复。

## 步骤 3：迁移加载期

- `listener/sertraline/itemModuleSave.kt:26-30`：改为调用工厂创建对象，而不是 `ModernSItem(...)`。
- `config/LoadContext.kt:26`、`:49`、`:69`：`items` / `liveItems` / `publishToLive` 的类型需与新的 registry 策略一致。
- `Sertraline.kt:231`：`itemMap` 泛化为 `ConcurrentHashMap<String, AbstractSItem>`。

注意：`itemMap` 的裸 key 语义对现有大量调用（`api/SertralineAPI.kt`、`item/itemSerializer.kt`、`recipe/*`、`gui/*`）是隐式契约。泛化后必须逐个处理取回点的类型：

- `SertralineAPI.getItem/getItemFromStack/getAllItems`：返回类型不能原地改。
- `item/itemSerializer.kt`：模板解析处取到 `MinecraftSItem`/`ExternalPluginSItem` 时，`hasPlaceholders`/`hasDynamics` 等 Sertraline 专属成员不可用。
- `recipe/*`、`gui/ItemExplorer.kt`、`function/update/*`：按需向下转型，或先过滤 `sItemType`。
- `manager/*`：缓存与查询签名同步调整。

同时确认键空间：Sertraline 用裸 ID、其他来源用 `storageKey`，两者不能互相误查。

## 步骤 4：迁移构建期与 Adapter

- `listener/sertraline/builder/builderInterface.kt:9-47`：`ItemProcessor.process` 与 `ItemProcessorManager.processItem` 参数改为 `AbstractSItem`；在 `processItem` 入口增加 `shouldAdapt` 判断。

`shouldAdapt` 只控制本阶段：加载期 `itemModuleXXX` 与 `itemModuleSave` 不读取该字段，无论其值如何，配置模块都会照常生成 `data`。
- 所有 Adapter 实现需检查对 `key`/`data`/`config` 的假设：
  - `item/adapter/commonAdapter.kt`
  - `item/adapter/minecraftAdapter.kt`
  - `item/adapter/sertralineAdapter.kt`
  - `item/adapter/customDataAdapter.kt`
  - `item/adapter/chotenComplexAdapter.kt`
  - `item/adapter/xbuilderAdapter.kt`
  - `item/adapter/mmoAdapter.kt`
  - 以及 `premium` 模块内注册的 Adapter
- `item/itemBuilder.kt:115-239`：两条构建路径都基于 `ModernSItem`；若 Minecraft/外部物品也要走构建，需要拆分"取模板"与"应用 Adapter"两段。

保留的现有语义：

- `shouldAdapt=false` 时完全跳过 Sertraline Adapter。
- `shouldAdapt=true` 且 config 为空时仍进入管线，由各 Processor 自行判断。

## 步骤 5：迁移序列化与持久化

- 为 `AbstractSItem` 设计多态编码：写入类型判别字段，反序列化时按字段分发。
- 旧数据兼容：无判别字段 → `ModernSItem`。
- `shouldAdapt` 保持 `@Transient`，由工厂恢复，且只影响构建期。
- 外部对象保存完整数据：`ExternalPluginSItem` 需把 `ExternalItemData`（`source`/`id`/`metaData`）、`data`、`config` 一起写入持久化内容。
- 恢复时的边界：provider 未安装或物品已下线时，对象仍应可反序列化（数据完整），仅在 `buildItem()` 时明确失败。
- 检查 `manager/PrivateManager.kt:178-208` 的两处 `item.serialize()`，确认对非 Sertraline 类型同样可用。

## 步骤 6：新分层 API

- 新增 `SertralineItemAPI` 等子 API，方法接收/返回 `AbstractSItem`。
- 旧 `SertralineAPI` 方法全部加 `@Deprecated`，签名与行为不变，内部转调新实现。
- 脚本侧（`function/javascript/*`、`function/kether/*`、`FluxonShell`）暴露的 `sItem` 变量需验证旧属性仍可用。

## 步骤 7：收敛来源解析

需要统一核对的重复解析点：

- `data/OmniItem.kt:23-62`（namespace 判断）
- `item/itemBuilder.kt:44-72`（`itemSource`）
- `recipe/ItemResolver.kt:52`、`:163`
- `recipe/NMSRecipeFactory.kt:234`
- `util/SimpleRecipeHelper.kt:240`、`:256`

目标：同一 key 在所有入口解析为同一类型；解析失败行为一致（不要一边返回 `null`、一边返回草方块兜底）。

## 建议提交顺序

1. 契约调整（步骤 1）——纯类型改动，可先编译通过。
2. 工厂与序列化（步骤 2、5）——保证旧数据可读。
3. 加载期（步骤 3）。
4. 构建期与 Adapter（步骤 4）。
5. 新 API（步骤 6）。
6. 解析收敛与清理（步骤 7）。

每一步都应能独立构建通过，避免一次性大改导致无法定位回归。
