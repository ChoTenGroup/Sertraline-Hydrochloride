# AbstractSItem 测试与验收清单

本清单用于确认迁移后行为与现有系统一致，并覆盖新引入的多来源物品。

## 1. 类型与标识

- [ ] 裸 `ITEMID` 创建为 `ModernSItem`，`key == ITEMID`。
- [ ] `minecraft::stone` 创建为 `MinecraftSItem`，`key`、`minecraftId`、`storageKey` 三者符合同一套规范。
- [ ] `provider::id` 创建为 `ExternalPluginSItem`，其 `ExternalItemData.source` 与 `storageKey` 前缀一致。
- [ ] 两个不同 provider 使用相同本地 ID 时，`storageKey` 不冲突。
- [ ] 非法 key（空 ID、多段分隔、未知 provider）返回明确失败，不被静默当作 Sertraline 物品。

## 2. 序列化与恢复

- [ ] 旧 `ModernSItem` JSON（无类型字段）仍可读取，行为不变。
- [ ] 新 `MinecraftSItem` / `ExternalPluginSItem` JSON 可往返，且恢复出正确子类型。
- [ ] `shouldAdapt` 不出现在 JSON 中。
- [ ] 经工厂恢复的对象 `shouldAdapt` 按来源正确赋值，不因 `@Transient` 变成 `false`。
- [ ] `sertraline:context` 在序列化往返后仍按现有逻辑剥离并回填。
- [ ] 动态/占位符路径（`itemSerializer`）的往返结果与迁移前一致。
- [ ] `PublicManager` reload 前后的临时物品可正常恢复。
- [ ] `PrivateManager` 数据库读写、`normalizeItem` 的 key 规范化行为不变。

## 3. 加载期

- [ ] 配置文件加载后 `itemModuleXXX` 生成的 `data` 结构与迁移前一致。
- [ ] `itemModuleSave` 写入的对象类型正确，reload 候选表可正常发布。
- [ ] reload 中途失败时 live registry 保持上一轮完整状态（沿用现有 LoadContext 语义）。
- [ ] `__` 前缀私有物品仍不进公共 `itemMap`。

## 4. Adapter 管线

- [ ] `shouldAdapt = false` 时不执行任何 Sertraline Adapter。
- [ ] `shouldAdapt = true` 且 `config` 为空时仍进入管线。
- [ ] `shouldAdapt = true` 时 Processor 执行顺序与迁移前一致。
- [ ] 单个 Processor 的启用条件（`config.contains(name)`）行为不变。
- [ ] `modules.<name>.feature` 开关仍能禁用对应 Processor。
- [ ] `forceRegisterProcessor` 注册的插件段落不受模块开关影响。

## 5. 构建

- [ ] `sertralineItemBuilder` 对普通模板输出与迁移前逐项一致。
- [ ] `sertralineVarItemBuilder` 的 vars / context 行为不变。
- [ ] `sertralineItemBuilderTemporary` 行为不变。
- [ ] `ItemStack.rebuild` / `rebuildBypassKeepData` 保留 NBT 与组件的行为不变。
- [ ] 构建过程中不修改缓存模板（连续构建两次，结果相同且模板数据未变）。
- [ ] 外部物品经 ItemBridge 构建成功；provider 缺失、ID 不存在、抛异常时返回一致且可诊断。
- [ ] 无 Player 构建路径按约定成功或明确失败。
- [ ] Minecraft 物品在非法 `minecraftId` 时不产生错误类型对象。

## 6. 公开 API

- [ ] 旧 `SertralineAPI` 方法签名与迁移前完全一致（可用 `javap` 对比）。
- [ ] 旧方法标记 `@Deprecated` 且有替代说明。
- [ ] 已编译的附属插件调用旧 API 不出现 `NoSuchMethodError`。
- [ ] 新子 API 能返回三种类型的对象。
- [ ] `getVal`、`getVar`、`isValidItem`、`isRegisteredItem` 行为不变。

## 7. 脚本与外围

- [ ] JavaScript / Kether / JEXL / Fluxon 中 `sItem.key`、`sItem.data`、`sItem.config`、`sItem.getDeepData(...)` 仍可用。
- [ ] GUI（`ItemExplorer`）能正常展示与筛选。
- [ ] 配方解析（`ItemResolver`、`NMSRecipeFactory`、`SimpleRecipeHelper`）对外部与原版物品的结果与迁移前一致。
- [ ] Lore 渲染、属性、动作触发器在迁移后的物品对象上正常工作。
- [ ] `RevisionAutoTracker` 的内容哈希计算未因新增字段产生大规模误报变更。

## 8. 工程验证

- [ ] `./gradlew test` 通过。
- [ ] `./gradlew clean build` 通过。
- [ ] 测试服验证：三类物品的生成、重建、配方引用、reload 全流程。
- [ ] 回归旧存档/旧数据库：已有私有物品可正常读取。

## 9. 已确定决策对应的验收点

- [ ] `buildItem(player)` 的三条实现路径都能接收 Player；传入 `null` 时行为符合约定（成功或明确失败，不抛未捕获异常）。
- [ ] `getMaterial()` 在三种实现上都有返回值；外部 provider 缺失、ID 不存在时返回约定的兜底值（建议 `Material.AIR`）而非抛异常。
- [ ] `ExternalPluginSItem` 不再产生"Material 伪装的原版物品"。
- [ ] `itemMap` 泛化后，既有裸 ID 查找行为不变；`minecraft::x` / `provider::x` 不会被误当作 Sertraline ID 命中。
- [ ] `shouldAdapt = false` 时加载期 `itemModuleXXX` 仍照常生成 `data`，只有构建期 Adapter 被跳过。
- [ ] 外部对象持久化包含完整 `ExternalItemData`；provider 未安装时仍能反序列化成功，仅在构建时失败。

## 10. 仍需在实施中确认的细节

- [ ] `getMaterial()` 是否允许触发一次 ItemBridge 构建（涉及无 Player 场景与线程约束）。
- [ ] Sertraline 的 `storageKey` 是否直接等于裸 `key`（建议是，以保持现有查找语义）。
- [ ] 外部对象反序列化后，若 provider 从未安装过，是否需要在 UI/日志中显式提示，而不是静默失败。
