# 品质（Tier）、等级（Level）与类型（Type）

Sertraline 提供三个互相独立、均可选的物品分类维度，用于给物品打上"品阶""档次""类别"标签，并在 Lore 展示、脚本判断中使用：

| 维度 | 典型用途 | 示例 |
|---|---|---|
| **Tier 品质** | 稀有度 / 品阶展示 | E / D / C / B / A / S |
| **Level 等级** | 档次划分 | 普通 / 进阶 / 超级 |
| **Type 类型** | 物品类别（支持继承） | 剑 → 武器，镐 → 工具 |

> 注意：三者只是"标签"，**不会**自动改变物品的属性数值；数值仍由 `xbuilder.attributes` 等字段决定。如需"按品质给不同属性"，请自行在配置或脚本中处理（见下文"常见问题"）。

---

## 一、定义品质 / 等级 / 类型

三个维度分别定义在插件数据文件夹下的 `tiers/`、`levels/`、`types/` 目录中：

```
plugins/Sertraline/
├── tiers/      # 品质
├── levels/     # 等级
└── types/      # 类型
```

- 目录不存在时，首次启动会自动生成示例文件 `default.yml`，可直接参考修改。
- 支持 `.yml` / `.yaml` / `.toml`（扩展名写作 `.tml`）/ `.json` / `.conf`，支持子目录分类存放。
- 一个文件里可以定义多个条目，也可以拆成多个文件。
- 修改后需执行 `/sertraline reload` 生效。
- 可选：在 `config.yml` 中添加 `file-load.tier` / `file-load.level` / `file-load.type` 正则来过滤加载的文件（默认全部加载）。

条目 ID 就是 YAML 的键名（下例中的 `S`、`advanced`、`SWORD`），物品引用时写这个 ID。同 ID 的条目，后加载的文件会覆盖先加载的。

### Tier（品质）字段

```yaml
S:
  name: "<gold>S</gold>"         # 显示名，支持 MiniMessage；不写时显示 ID
  description: "<gold>传说品质"   # 描述，默认为空
  color: "<gold>"                # 颜色前缀，供 {tier:format} 占位符使用
  weight: 100                    # 权重（预留字段，当前版本暂无实际作用）
  extra:                         # 附加数据（可选，自由填写）
    sort: 6
  meta:                          # 元数据（可选），脚本中用 TierUtil.getMeta 读取
    allow-reforge: true
```

### Level（等级）字段

字段与 Tier 相同（`name` / `description` / `color` / `weight` / `extra`），但**没有 `meta`**。

```yaml
advanced:
  name: "<blue>进阶"
  description: "<gray>进阶物品"
  color: "<blue>"
  weight: 50
```

### Type（类型）字段

```yaml
WEAPON:
  name: "武器"
  description: "用于战斗"
SWORD:
  name: "剑"
  parent: WEAPON       # 继承自 WEAPON
DAGGER:
  name: "匕首"
  parent: SWORD        # 继承可以成链：匕首 → 剑 → 武器
```

| 字段 | 说明 |
|---|---|
| `name` | 显示名；不写时显示 ID |
| `parent` | 父类型 ID（可选），构成继承链 |
| `description` | 描述 |
| `extra` | 附加数据 |
| `meta` | 元数据，脚本中用 `TypeUtil.getMeta` 读取 |

Type 没有颜色与权重字段。

---

## 二、在物品上使用

在物品配置的 `sertraline` 节点下引用，三个字段均可选：

```yaml
my_sword:
  sertraline:
    tier: S
    level: advanced
    type: SWORD
  xbuilder:
    name: "<!i><gold>圣剑"
    material: DIAMOND_SWORD
```

- 值写条目 ID；不写的维度即不启用。
- 引用了未定义的 ID 时，控制台会警告（如 `Tier 'X' not found for item ...`），物品仍会加载，只是该字段无效。
- **大小写**：`tier` / `level` 的 ID 必须与定义完全一致（区分大小写）；`type` 不区分大小写，但建议统一写法。

---

## 三、类型继承

通过 `parent` 可把类型组织成树，例如 `SWORD → WEAPON`、`DAGGER → SWORD`。继承主要用于**脚本判断**："这件物品是否属于武器大类"时，剑、匕首都会命中。相关方法见 [TypeUtil 文档](TypeUtil-zh.md)（`isItemIsExtendType` / `isAssignableFrom`）。

---

## 四、在 Lore 格式中显示

在 [Lore 格式](LoreFormatUtil-zh.md) 中，`sertraline:tier` / `sertraline:level` / `sertraline:type` 可作为行的 key：**物品没有设置对应字段时，该行自动隐藏**。

```yaml
elements:
  - key: "sertraline:type"
    content: "<!i><gray>| <dark_gray>类型: <white>{type:name}"
  - key: "sertraline:tier"
    content: "<!i><gray>| <dark_gray>品质: {tier:format}"
  - key: "sertraline:level"
    content: "<!i><gray>| <dark_gray>等级: <white>{level:name}"
```

行内容中可用的占位符：

| Tier | Level | Type |
|---|---|---|
| `{tier}` 显示名 | `{level}` 显示名 | `{type}` 显示名 |
| `{tier:format}` 颜色 + 显示名 | `{level:name}` 显示名 | `{type:name}` 显示名 |
| `{tier:color}` 颜色 | `{level:color}` 颜色 | `{type:id}` ID |
| `{tier:id}` ID | `{level:id}` ID | `{type:parent}` 父类型 ID |
| `{tier:description}` 描述 | `{level:description}` 描述 | `{type:description}` 描述 |
| `{tier:weight}` 权重 | `{level:weight}` 权重 | |

> 注意：品质**没有** `{tier:name}` / `{tier:desc}` 写法——显示名请用 `{tier}`，要带品质颜色用 `{tier:format}`。部分旧示例注释中写的 `{tier:name}` / `{tier:desc}` 是无效的。

默认示例格式（`lore-formats/loreGenerator.yml`）中已包含这些行，可直接参考。

---

## 五、在脚本中使用

```js
// 读取品质对象与元数据
var tier = TierUtil.getTier("S")
var canReforge = TierUtil.getMetaAsBoolean("S", "allow-reforge")

// 判断手持物品是否属于武器大类（含继承：剑、匕首都算武器）
var isWeapon = TypeUtil.isItemIsExtendType(bItem, "weapon")

// 读取等级（等级没有专门的工具类，直接访问注册表）
var level = Sertraline.levels.get("advanced")
if (level != null) {
    PlayerUtil.sendMessage(player, mmUtil.deserialize("等级: " + level.name))
}
```

完整 API 见 [TierUtil](TierUtil-zh.md) 与 [TypeUtil](TypeUtil-zh.md) 文档。

---

## 六、完整示例

`tiers/rarity.yml`：

```yaml
rare:
  name: "<blue>稀有"
  color: "<blue>"
epic:
  name: "<light_purple>史诗"
  color: "<light_purple>"
legendary:
  name: "<gold>传说"
  color: "<gold>"
```

`types/weapons.yml`：

```yaml
WEAPON:
  name: "武器"
SWORD:
  name: "剑"
  parent: WEAPON
BOW:
  name: "弓"
  parent: WEAPON
```

`workspace/` 下的物品：

```yaml
flame_blade:
  sertraline:
    tier: legendary
    type: SWORD
  xbuilder:
    name: "<!i><gradient:red:gold>烈焰之刃"
    lore: |
      <!i><gray>一把灼热的剑。
    material: NETHERITE_SWORD
```

配合包含 `sertraline:type` / `sertraline:tier` 行的 Lore 格式，成品 Lore 会自动出现：

```
| 类型: 剑
| 品质: 传说
```

---

## 七、常见问题

**修改了 tiers/levels/types 文件但没有生效？**
执行 `/sertraline reload`。

**控制台出现 `Tier 'xxx' not found` 警告？**
物品引用的 ID 未定义，或大小写不一致（tier / level 区分大小写，type 不区分）。检查拼写与定义是否一致。

**设置了 tier / level 后物品属性没有变化？**
这是正常的。三者只是标签，不参与数值计算。想"按品质给属性"有两种做法：
1. 直接在物品配置里写不同数值（不同 ID 的物品本来就是不同配置）；
2. 在脚本中通过 `TierUtil.getMeta(品质ID, "某数值")` 读取元数据，按品质执行不同逻辑。

**weight 字段有什么用？**
预留字段，当前版本暂无实际作用，写了也不影响运行。

**Lore 里没有显示品质行？**
检查物品实际使用的 Lore 格式是否包含 `sertraline:tier` 行（自定义格式需自行添加，写法见第四节），以及物品是否真的设置了 `tier`。
