# 宝石系统检查报告

检查方式：源码走查 + 在 `E:\MC\tra\Ets`（Leaf 1.21.4）真实服务器上用 mineflayer 假人跑
`/sgem list` / `socket` / `unsocket`，并用 `/sertralineapi evalJS`、`evalGraalJs` 直接断言插件内部状态。
复现脚本：`docs/test-gem.ps1`（起服→断言→停服，一条命令）+ `docs/node/bot-gem.js`。

## 结论速览

宝石系统在**默认配置下完全不可用**，与 `AbstractSItem` 重构无关：五个缺陷都是既有的，
其中前两个会让"插槽列表为空""镶嵌直接抛异常"。现在全部修复，端到端实测通过：
插槽、镶嵌、取下、颜色校验、lore 占位符、NBT 状态、**属性叠加与回退**都正确。

## 已修复

### 1. 宝石配置根本没被加载（致命，默认配置即触发）

`itemModuleSertraline` 用**白名单**重建 `sertraline:` 段，`gem` 与 `gem-slots` 不在名单里，
且 `internal/mapping/mappings.yml` 里也没有对应映射（`getFeatureDefault` 查不到映射会直接返回 null）。

后果：`getDeepData("sertraline:gem-slots")` 恒为 null，于是
`/sgem list` 无输出、镶嵌一律 `SLOT_NOT_FOUND`、lore 里 `${gem-slot:xxx}$` 原样留在物品上。

修复：`itemModuleSertraline.kt` 白名单补 `gem` / `gem-slots` / `gem-click` / `gem-click-socket`；
`mappings.yml` 补对应映射（含 `gemSlots` / `gemClick` 驼峰别名）。
`mappings.yml` 每次加载都会被 jar 内版本覆盖，所以老服务器自动生效。

实测：`getDeepData("sertraline:gem-slots")` →
`{slot_red={name=Red Socket, ...}, slot_any={...}, slot_locked={..., click=false}}`。

### 2. 镶嵌/取下必抛异常（致命，默认配置即触发）

```
IllegalStateException: Unsupported nbt: ["slot_redt","slot_anyt"] (class JsonArray)
  at ItemTagData$Companion.toNBT
  at ItemBuilderKt.rebuild(itemBuilder.kt:290)
  at GemManagerKt.socketGem(gemManager.kt:163)
```

默认 `rebuild.keep-data` 含 `sertraline_gem`，`rebuild()` 的通用保留循环用 `parseNBT()` 拿到的是
Gson 结构，TabbooLib 写回 NBT 时不接受。异常发生在物品已被污染之后，导致半完成状态：
物品改了、宝石没扣、玩家收不到任何反馈。

修复：`itemBuilder.kt` 抽出 `carryKeptNbt()`，跳过 `sertraline_gem`——宝石状态本来就由
`copyGemStateToTag()` 按基础类型显式携带。`rebuild()` 与 `rebuildUnsafe()` 两个入口共用。

### 3. 改动记录名里的点号导致记录被静默丢弃

`writeAlterationRecordsToTag` 用记录名当 NBT 键，而 `ItemTag.set` 把 `.` 当层级分隔符：

```
记录名 "probe_no_dot"                     -> 读回 1 条   ✓
记录名 "probe.with.dot"                   -> 读回 0 条   ✗ 被存成嵌套 probe→with→dot
记录名 "gem_slot_red_sertraline:vars.atk" -> 读回 0 条   ✗ 被存成嵌套 …vars→atk
```

宝石改动的名字必然是 `<前缀><数据路径>`，数据路径一定带点，所以**每一条宝石改动都被吞掉**。

修复：写入时 NBT 键做去点处理（`alterationNbtKey`），真实名字显式存进条目 `name` 字段，
读取优先用 `name`、回落到 NBT 键（兼容旧数据）。

实测：镶嵌后记录可读回，NBT 键 `gem_slot_red_sertraline:vars_atk`，
`compileGemAlterations` → `gem_slot_red_sertraline:vars.atk | ADD | 15`。

### 4. 保留循环把刚算出的变量值覆盖回旧值

`rebuild.keep-data` 里的 `sertraline_data` 在构建完成之后被旧 NBT 覆盖，
把这一轮刚折叠进去的改动抹掉。

修复：`carryKeptNbt` 同时跳过 `sertraline_data`——实例变量本来就通过
`overrideData["sertraline:vars"]` 传入构建，适配器再把合并结果写回。

## 仍存在的问题（已定位，未修完）

### 5. 属性叠加取错基数、取下单边扣减、lore 慢一步（已修）

修复前实测（剑模板攻击 100，红宝石 +15，蓝宝石 +25；取值自 `sertraline_data.atk`）：

| 操作 | 期望 | 修复前 |
|---|---|---|
| 镶红宝石 | 115 | 115 ✓ |
| 再镶蓝宝石 | 140 | 125 ✗ |
| 取下蓝宝石 | 115 | 100 ✗ |

成因有两个，都出在构建入口 `sertralineItemBuilderInternal`：

**（a）实例变量被丢弃。** 重建路径用的是 `itemSerializer(rebuild, player)`，它会从 `itemMap`
缓存重新解析模板，于是前面刚 apply 到 `pTemplate` 的 `overrideData`（实例 vars）被整段丢弃。
而折叠过的宝石改动带有 `persisted` 标记、后续构建会被跳过，于是：
上一颗的加成消失 + 新的加成叠加在模板默认值上 → `100 + 25 = 125`。

**（b）渲染读到的是旧值。** `${var:...}$` 由 `tagManager.processItem(json, item, itemStack, player)`
用**栈上的 NBT** 解析，而重建时栈上还是上一次构建的值，所以 lore 永远慢一步。

修法：`itemSerializer` 增加一个「模板 + 栈上下文」重载，重建路径保留 `pTemplate` 作为构建源
（它已经是 deep copy，覆盖都在），并把改动折叠**提前到序列化之前**，同时用一份写入了合并后 vars
的栈副本作为渲染上下文。`itemUpdater` 里那条"overrideData 会被绕过、只能靠拷回旧 NBT 保留"
的注释也随之失效，一并更新。

修复后实测（lore 与 NBT 逐步一致）：

| 操作 | 期望 | 实测 |
|---|---|---|
| 新物品 | 100 | 100 ✓ |
| 镶红宝石 | 115 | 115 ✓ |
| 再镶蓝宝石 | 140 | 140 ✓ |
| 蓝宝石镶进红槽（颜色校验拒绝） | 115 | 115 ✓ |
| 取下蓝宝石 | 115 | 115 ✓ |
| 再镶蓝宝石 + 取下红宝石 | 125 | 125 ✓ |
| 全部取下 | 100 | 100 ✓ |

回归验证：`rebuild` / 连做两次 `rebuild` / `rebuildLore` / `rebuildName` / `rebuildDisplay`
之后数值全部保持不变（幂等，不重复叠加也不清零）；`/sertralinedebug update true`
强制更新器重建后 115→115、140→140；`bot-test.js` 端到端（发放 / reload / 重进）通过。


## 未验证 / 附带发现

- 槽位 id 同样被当作 NBT 键（`slots[slotId]`），含点号的槽位 id 会有和上面第 3 条一样的问题。
  示例配置用的是下划线，暂未受影响，但没有做防护。
- `gem-slot` 标签处理器的 `name` / `desc` 不做 legacy 颜色转换，写 `&c` 会原样显示，需用 MiniMessage。
- 点击镶嵌（`GemSocketListener`）未做端到端验证，本次只测了命令路径。
- 非持久化目标的改动（例如把宝石加成写进 `xbuilder` 而不是 `sertraline:vars`）没有覆盖到，
  这类改动每轮构建都会重放，逻辑上自洽，但没有对应示例可测。

## 测试环境改动（已还原）

- `config.yml` 的 `debug` 临时开过，已还原为 `false`。
- 假人 `SrTestBot` 临时加入 `ops.json`，已移除（12 → 11）。
- 新增 `plugins/Sertraline/workspace/default/default_gem.yml`（仓库里本来就有这份示例，
  但服务器上缺这一份，所以宝石系统一直没被测到）——**保留**，否则示例物品无法使用。
