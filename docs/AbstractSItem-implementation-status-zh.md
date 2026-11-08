# AbstractSItem 实施状态

本文记录本轮已经落地的改动，与 `AbstractSItem-migration-map-zh.md` 的步骤对应。

## 已完成

### 步骤 1：抽象契约

`data/AbstractSItem.kt`：

- `buildItem(player: Player?)`：新增 Player 上下文，符合"外部构建需要 Player"的决策。
- `getMaterial(): Material`：保留，契约明确为"无法确定时返回 `Material.AIR`，不抛异常"。
- `deepCopy(): AbstractSItem`：新增，要求保留具体子类型。
- 把 `getDeepData` / `getMajorData` / `setDeepData` / `setMajorData` / `hasPlaceholders` / `hasDynamics` / `serialize()` 提升为接口默认实现，使 Adapter 等通用代码只依赖抽象类型。
- `shouldAdapt` 文档明确：只控制构建期 Adapter，不影响加载期 `itemModule`。

### 具体实现

| 类型 | `sItemType` | `storageKey` | 构建方式 | `shouldAdapt` 默认 |
|---|---|---|---|---|
| `ModernSItem` | `sertraline` | 裸 `key` | `sertralineItemBuilder` | `true` |
| `MinecraftSItem` | `minecraft` | `minecraft::$key` | `ItemStack(getMaterial())` | `false` |
| `ExternalPluginSItem` | `plugin` | `provider::$key` | `ExternalItemHelper` + ItemBridge | `true` |

- `MinecraftSItem.getMaterial()`：`Material.valueOf` → `XMaterial` → `Material.AIR` 三级兜底。
- `ExternalPluginSItem`：移除 `Material.valueOf(key)` 的原版伪装，构造 `ItemStack` 只发生在 ItemBridge 成功后；`getMaterial()` 通过无 Player 构建试探性取得，失败返回 AIR。
- 两者 `deepCopy()` 都保留来源字段（`minecraftId` / `externalItemData`）。

### 步骤 2：唯一工厂

新增 `data/SItemFactory.kt`：

- `sertraline()` / `minecraft()` / `external()`：统一创建并赋值 `shouldAdapt`。
- `parse(reference)`：裸 ID → Sertraline；`minecraft::` / `mc::` / `vanilla::` → 原版；其他前缀 → 外部物品。
- `deserialize(json)`：读取 `sItemType` 分发；缺失该字段时默认 `ModernSItem`（旧数据兼容）。
- `restoreRuntimeState()`：按类型重建 `@Transient shouldAdapt`。

### 步骤 4：Adapter 管线

- `ItemProcessor.process(item: AbstractSItem, ...)`。
- `ItemProcessorManager.processItem()` 入口统一判断：`shouldAdapt == false` 直接返回原 ItemStack，不执行任何 Processor。
- 7 个 Adapter（common / minecraft / sertraline / customData / chotenComplex / xbuilder / mmo）签名改为 `AbstractSItem`。
- 连带放宽为抽象类型的通用工具：lore format 全家族、`GemData` 的三个扩展函数、`applyAlterationsToTemplate`、`getSavedData`、tag processor、属性提供者、`ItemExplorer` 等。

### 步骤 3：注册表

- `Sertraline.itemMap: ConcurrentHashMap<String, AbstractSItem>`。
- `LoadContext.items` / `liveItems` / `publishToLive()` 同步泛化，reload 的"候选构建 + 整体替换"语义未变。
- `PublicManager` 全部泛化；临时物品恢复改经工厂。
- `itemModuleSave` 改经 `SItemFactory.sertraline(...)` 创建。
- `PrivateManager` 数据库恢复改用 `deserializeSertraline()`（私有物品表只存 Sertraline 物品）。

### 步骤 5 / 6：序列化与 API

- `deserializeSItem()` 内部改为工厂 + 断言 Sertraline，旧调用点行为不变。
- 新增 `deserializeSertraline(): ModernSItem?`。
- 旧 `SertralineAPI` 签名完全保留，仅加 `@Deprecated`；取回注册表内容处加 `as? ModernSItem`，非 Sertraline 返回 null。
- 新增 `api/SertralineItemAPI.kt`，面向 `AbstractSItem`：`getItem` / `getItemFromStack` / `getAllItems` / `getSertralineItems` / `getMinecraftItems` / `getExternalItems` / `register` / `update` / `unregister` / `parse` / `build`。

## 关键验证

用独立 JVM 探针对 `ModernSItem` 做 Gson 往返，结果：

```text
JSON={"key":"ITEMID","data":{},"config":{},"sItemType":"sertraline"}
restored shouldAdapt=false
```

由此确认两件事：

1. `sItemType` **必须**是真实字段。最初写成计算属性（`get() = "sertraline"`）时，`javap` 显示只有 `getSItemType()`，Gson 不会写出该字段，多态恢复会永远退化回 `ModernSItem`。已改为带默认值的构造参数并置于末尾，保持既有的位置参数调用兼容。
2. `shouldAdapt` 反序列化后确实是 `false`（JVM 默认值），所以工厂的重新赋值不是可选项。

## 构建状态

`./gradlew build` 全模块通过（含全部 6 个 jar 变体）。

两点构建环境要求：

1. `JAVA_HOME` 必须指向 JDK 21（`C:\Program Files\Eclipse Adoptium\jdk-21.0.11+10`）。本机默认 `JAVA_HOME` 是 JDK 25，Gradle 8.10 无法在 JDK 25 下启动。
2. `plugin:buildLegacy` 会再起一个 Gradle 进程，整机内存紧张时会以 `insufficient memory ... G1 virtual space` 失败。这不是代码问题；先 `--stop` 停掉残留 daemon，或用 `GRADLE_OPTS=-Xmx1g` 降低占用即可通过。

## 步骤 7：来源解析收敛（已完成）

命名空间别名表收敛为 `SItemFactory.classify(namespace): SourceKind` 一处：

| 调用点 | 改动 |
|---|---|
| `data/OmniItem.kt` | 删除本地的 `specialItemNamespace` / `sertralineNamespace` 两份列表，改用 `classify()` |
| `item/itemBuilder.kt` `itemSource()` | 原版判定从 `str.startsWith("minecraft:")` 改为按 `classify()` 分支，并显式处理 SERTRALINE |
| `recipe/` 全部解析入口 | 统一走新增的 `resolveRecipeReference()`（见下） |

**`itemSource()` 的语法差异需要保留**：它解析的是物品的 `material` 字段，裸名称（`STONE`）表示原版材质；而 `SItemFactory.parse()` 处理的是 `AbstractSItem` 引用语法，裸 ID 表示 Sertraline 物品。两者共用别名表，但裸名称的含义不同，不能直接互相替换。

**一处行为变化**：`itemSource("mc:stone")` / `itemSource("vanilla:stone")` 之前落入"外部插件"分支并因找不到 provider 而回退成草方块，现在会正确解析为 `STONE`。这与 `OmniItem` 既有的 `mc`/`vanilla` 语义一致，属于修正。

### 配方引用的特殊语义（向后兼容）

配方路径的裸名语义与 `SItemFactory.parse()` **相反**，这是刻意的：

> **裸名先按原版材质解析；解析不到才告警并回落到 Sertraline 物品。**

原因：旧配方里 `stone`、`grass_block` 表示原版材质，而 `doe_card` 这类不存在的材质一直是被当作 Sertraline 物品使用的。若统一成"裸 ID = Sertraline"，所有裸写原版材质的既有配方会瞬间失效。

实现：`recipe/RecipeReferences.kt` 的 `resolveRecipeReference()` 是配方路径的**唯一**入口，`ItemResolver`、`NMSRecipeFactory`（配料与产物）、`SimpleRecipeHelper`、`itemRecipe` 全部改用它。

实测判定表（用 spigot-api + Guava 真实运行 `resolveRecipeReference`）：

| 输入 | 结果 |
|---|---|
| `stone` / `STONE` | MINECRAFT → STONE |
| `grass_block` / `paper` | MINECRAFT → GRASS_BLOCK / PAPER |
| `minecraft:paper` / `mc:stone` / `vanilla:stone` | MINECRAFT → PAPER / STONE / STONE |
| `:stone` | MINECRAFT → STONE（空命名空间按裸名处理） |
| `doe_card` | SERTRALINE + 一次性告警 |
| `sertraline:doe_card` | SERTRALINE（无告警） |
| `itemsadder:gun` | EXTERNAL（provider=itemsadder，id=gun） |
| `minecraft:not_a_real_material` | null（显式原版**不**回落，避免掩盖配置错误） |
| 空串 / 空白 | null |

告警按裸名去重（`ConcurrentHashSet`），避免每条配方、每次 reload 刷屏。

## 默认配置更新

重构让 `registerProcessor` 开始读配置开关（`modules.<name>.feature` / `tags.<name>`），而默认配置只覆盖了部分处理器名，其余靠"键缺失默认 true"侥幸生效。已补齐，注释用英文（仅保留中英对照标题行）：

| 文件 | 改动 |
|---|---|
| `config.yml` `modules:` | 补 `custom_data` / `chotenatb` / `chotenatb_complex`，并说明键必须与处理器名一致、缺失默认开启 |
| `config.yml` `tags:` | 补 `nbt` / `gem-slot`，并说明 `val`/`var`/`dynamic`/`kether`/`context` 是 `sertraline` 处理器的别名 |
| `recipes/sample.yml` | 头部补"引用如何解析"规则：显式命名空间优先、`minecraft:` 不回落、裸名原版优先且失败告警回落；新增 Example 15 展示原版材料写法 |

补齐后 `modules` 的 10 个键覆盖了 8 个已注册 processor（`visual`/`eco` 是既有键），`tags` 的 8 个键覆盖了 4 个已注册 tag processor 及其别名。

配置文件改动已用 `yaml.safe_load` 校验可解析，并断言新键与新配方确实出现在解析结果里。

## 本地服务器实测

已在 `E:\MC\tra\Ets`（Leaf 1.21.4）用真实服务器 + mineflayer 假人跑通端到端验证，
覆盖配方引用解析全部分支、`AbstractSItem` 多态的注册/序列化往返、处理器开关。
详细结果与发现的两个缺陷见 `docs/local-server-test-report-zh.md`。

新增调试命令 `/sertralineapi evalJS <code>` 与 `/sertralineapi evalGraalJs <code>`
（无需 `$` 前缀），可直接在控制台调用 `SertralineItemAPI` / `SItemFactory` 验证行为。

## 尚未完成

- `util/SimpleRecipeHelper.kt` 的 `parseId` 仍保留（已被 `buildResultStack` 之外的调用方使用），但其 `idx == -1 → "minecraft"` 的裸名语义与配方解析器不同，若后续有新的裸名调用点应改走 `resolveRecipeReference()`。
- 旧 `SertralineAPI` 的 private manager 系列方法仍是 `ModernSItem` 签名（按约定保留）。
- 未补充自动化测试；`project/common/src/test` 目前为空。
- 脚本侧（JS / Kether / JEXL / Fluxon）对 `sItem` 的兼容性只做了类型层面的保证，未做运行时验证。

## 需要注意的行为变化

1. `SertralineAPI.getAllItems()` 现在**只返回 Sertraline 物品**。若注册表里出现其他来源对象，旧调用方不会看到它们——这是为保持旧签名返回类型所做的取舍。
2. `AbstractSItem.getMaterial()` 对外部物品可能触发一次无 Player 的 ItemBridge 构建，属于潜在开销；高频调用处应自行缓存。
3. `itemMap` 的键仍是"裸 ID"约定（配置定义的 YAML 物品必然是 Sertraline，`storageKey == key`）。外部/Minecraft 对象若要以 `storageKey` 注册，需要在写入侧显式使用 `storageKey`，目前尚无该调用点。
