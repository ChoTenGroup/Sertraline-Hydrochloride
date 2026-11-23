# Sertraline 处理器结构用法

## 处理器体系概述

Sertraline 拥有两套独立的处理器系统，在物品构建流程的不同阶段工作：

| 处理器类型 | 作用阶段 | 功能 |
|---|---|---|
| **物品处理器（ItemProcessor）** | 物品构建时 | 将 `ModernSItem` 配置数据转换为 `ItemStack` 的 Bukkit/物品属性（名称、附魔、属性、NBT 等） |
| **标签处理器（TagProcessor）** | 物品序列化时 | 在物品的 JSON 表示中执行 `${...}$` 占位符替换（如 `${var:name}$`、`${papi:player_name}$`） |

---

## 一、物品处理器（适配器）

### 1.1 核心机制

**注册顺序决定执行顺序**，每个处理器检查物品配置中是否包含其命名空间键名，若存在则执行。

处理器可通过 `config.yml` 的 `modules.<name>.feature` 开关控制。

```yaml
modules:
  common:    { feature: true }
  sertraline: { feature: true }
  xbuilder:   { feature: true }
  minecraft:  { feature: true }
  mmo:        { feature: true }
```

### 1.2 xbuilder 处理器

通过 XItemStack 序列化/反序列化构建物品的基础属性，支持所有 XItemStack 字段。

**配置结构：**

```yaml
xbuilder:
  material: DIAMOND_SWORD    # 物品材质
  name: "<!i><gold>My Sword" # 显示名称，支持 MiniMessage
  lore: |                    # 描述文本，支持 MiniMessage
    <gray>A legendary sword.
    <yellow>+10 Attack Damage
  damage: 0                  # 耐久损伤值
  unbreakable: true          # 是否不可破坏
  glow: true                 # 附魔光泽（自动添加耐久 I）
  item-model: "minecraft:item/iron_ingot"  # 物品模型路径
  flags: [HIDE_ENCHANTS]     # 物品标志
  attributes:                # 属性
    ATTACK_DAMAGE:
      amount: 10
      operation: ADD_NUMBER
      slot: HAND
  enchantments:              # 附魔
    SHARPNESS: 5
```

**示例：**

```yaml
sword_of_skyline:
  xbuilder:
    name: "<!i>&7Sword Of &eSkyline"
    lore: |
      <!i><#ff66ff>AkaCandyKAngel &floves <#66ccff><i>Mr. BlueIce</i><white>.
    material: DIAMOND_SWORD
    glow: true
    attributes:
      ATTACK_DAMAGE:
        amount: 2025
        operation: ADD_NUMBER
        slot: HAND
```

### 1.3 sertraline 处理器

Sertraline 核心处理器，处理品质、类型、变量、动作、Lore 格式等逻辑。

**配置结构：**

```yaml
sertraline:
  tier: C                    # 品质 ID
  type: weapon               # 类型 ID
  lore-format: loreformat  # Lore 格式 ID
  vals:                      # 静态值（编译时确定）
    some_constant: 100
  vars:                      # 变量数据（可持久化到 NBT）
    count: 0
    player_name: "%player_name%"
  dynamics:                  # 动态数据（运行时重新求值）
    random_damage:
      - "random 10 to 20"
  context:                   # 运行时上下文（不持久化）
    some_var: "runtime_value"
  keep-context: [key1, key2] # 应持久化为变量的上下文键
  actions:                   # 事件动作
    onAttack:
      - async: false
        js: |
          event.damage = 100
  revision-id: 1             # 手动修订 ID
  no-sertraline-id: false    # 禁止在 NBT 写入 sertraline_id
```

**vars 占位符使用（配合标签处理器，见下文）：**

```yaml
sertraline:
  vars:
    pills: 14
    total: 14
xbuilder:
  lore: |
    <!i><gray>Left pills: <green>${var:pillsAsIn}$</green><gray> /${var:totalAsIn}$
```

**示例：**

```yaml
sword_of_counter:
  sertraline:
    tier: C
    vars:
      count: 0
    actions:
      onAttack:
        - js: |
            var tag = ItemStackUtil.getItemTag(bItem);
            var added = count + event.damage;
            tag.putDeep("sertraline_data.count", added);
            ItemStackUtil.setItemTagDirect(bItem, tag);
            PlayerUtil.sendMessage(player, mmUtil.deserialize("Damage: " + added))
            ItemStackUtil.rebuildLore(bItem, player);
  xbuilder:
    name: "<!i>&7Sword Of &6Counter"
    lore: |
      <!i><white>Total Damage: <yellow>${var:countAsIn}$
    material: IRON_SWORD
```

### 1.4 minecraft 处理器

直接操作 Minecraft 数据组件（Data Component），**仅限 1.20.5+**。

**用法：** minecraft 下的每个键都是 Minecraft 数据组件名称，值遵循数据组件结构。

**添加数据组件：**

```yaml
model_item:
  xbuilder:
    material: DIAMOND
    name: "Test Item"
  minecraft:
    item-model: "minecraft:item/iron_ingot"
    max_stack_size: 1
```

**移除以 null 值的数据组件：**

```yaml
Unequippable:
  xbuilder:
    material: DIAMOND_CHESTPLATE
    name: "Unequippable Chestplate"
  minecraft:
    equippable: null
```

**支持的组件列表（部分）：**

| 组件 | 说明 |
|---|---|
| `custom_name` | 自定义名称 |
| `item_name` | 物品名称 |
| `lore` | 描述文本 |
| `item_model` | 物品模型 |
| `max_stack_size` | 最大堆叠数 |
| `damage` | 耐久损伤 |
| `food` | 食物属性 |
| `attribute_modifiers` | 属性修饰符 |
| `enchantments` | 附魔 |
| `unbreakable` | 不可破坏 |
| `equippable` | 可装备 |
| `consumable` | 可消耗 |
| `tool` | 工具属性 |
| `rarity` | 稀有度 |
| `custom_data` | 自定义数据 |
| `repair_cost` | 修复花费 |
| `trim` | 盔甲纹饰 |

完整列表见 `internal/description/minecraft.yml`。

### 1.5 custom_data 处理器

将自定义键值对写入物品 NBT 标签。

```yaml
custom_data:
  myplugin:custom_value: hello
  some_number: 42
```

### 1.6 chotenatb 处理器

Choten 属性系统，自动从材质后缀推断允许的装备槽位。

```yaml
chotenatb:
  allowed: [mainhand]        # 允许的槽位，自动推断：boots/chestplate/leggings/helmet
  attack_damage: 30          # 攻击伤害，支持百分比如 "10%"
  max_health: 50
  movement_speed: 0.1
```

### 1.7 chotenatb_complex 处理器

复杂 Choten 属性定义列表。

```yaml
chotenatb_complex:
  - condition: "mainhand"
    attack_damage: 20
    movement_speed: 0.05
  - condition: "offhand"
    attack_damage: 10
```

### 1.8 mmo 处理器

MMOItems/MMOCore 兼容处理。

```yaml
mmo:
  fix-attack-speed: true     # 修复 MMOItems 攻击速度
  attack-damage: 2020        # 攻击伤害，支持百分比如 "5%"
  max-health: 100
  allowed: [mainhand]
```

### 1.9 common 处理器

空操作处理器，通过事件监听器处理游戏玩法限制（禁止合成、锻造、村民交易等）。

```yaml
common:
  disable-crafting: true
  disable-smelting: true
  disable-enchanting: true
  disable-anvil: true
  disable-brewing: true
```

---

## 二、标签处理器

### 2.1 核心机制

标签处理器在物品 JSON 序列化阶段工作，匹配 `${...}$` 格式的占位符并替换为实际值。

**占位符语法：**

```
${<处理器别名>:<键名>}$
```

**类型转换：**

```
${<别名>:<键名>As<类型码>}$
```

| 类型码 | 含义 | 示例 |
|---|---|---|
| `AsSt` | 字符串 | `${var:nameAsSt}$` |
| `AsIn` | 整数 | `${var:countAsIn}$` |
| `AsDo` | 双精度浮点数 | `${var:valueAsDo}$` |
| `AsFl` | 浮点数 | `${var:valueAsFl}$` |
| `AsBy` | 字节 | `${var:flagAsBy}$` |
| `AsLo` | 长整型 | `${var:bigAsLo}$` |
| `AsBo` | 布尔值 | `${var:enabledAsBo}$` |
| `AsBd` | BigDecimal | `${var:preciseAsBd}$` |
| `AsLi` | 列表（用分隔符连接） | `${var:itemsAsLiSplitBy,}$` |
| `AsMa` | 映射（用分隔符连接） | `${var:propsAsMaSplitBy=:` |

**非空标记：** 键名以 `!!` 结尾表示不可为空，值为 null 时替换为空字符串而非 "null"。

```
${var:name!!}$
```

**默认值：** 使用 `?:` 分隔键名和默认值。

```
${var:name?:default_value}$
```

### 2.2 sertraline 标签处理器

处理物品自身的变量数据，别名为 `val`、`var`、`dynamic`、`kether`、`context`。

| 别名 | 数据来源 | 说明 |
|---|---|---|
| `val` | `sertraline:vals` | 静态编译时值 |
| `var` | `sertraline:vars` | 可变数据，可持久化到 NBT |
| `context` | `sertraline:context` | 运行时对象（Player、Event 等），不持久化 |
| `kether` | 内联 Kether 脚本 | 执行 Kether 表达式并返回结果 |
| `dynamic` | `sertraline:dynamics` | 动态数据，每次求值时重新执行 Kether |

**示例：**

```yaml
sertraline:
  vars:
    count: 0
    chance: "%sertraline_random:40to80%"
xbuilder:
  lore: |
    <!i><white>Current Count: <yellow>${var:countAsIn}$
    <!i><white>Skill Chance: <yellow>${var:chanceAsIn}$% ${kether: if check get chance >= 85 then *<red>[Maximum]!!}$
```

### 2.3 papi 标签处理器

处理 PlaceHolderAPI 占位符，**需要安装 PlaceholderAPI**。

| 别名 | 数据来源 | 示例 |
|---|---|---|
| `papi` | PlaceholderAPI | `${papi:player_name}$` |

**示例：**

```yaml
sertraline:
  vars:
    bound: "%player_name%"
xbuilder:
  lore: |
    <!i><white>Bound to: <yellow>${var:bound}$ ${papi:player_name}$
```

### 2.4 nbt 标签处理器

从物品的现有 NBT 标签中读取值。

| 别名 | 数据来源 | 示例 |
|---|---|---|
| `nbt` | 物品 NBT 标签 | `${nbt:sertraline_data.count}$` |

**示例：**

```yaml
xbuilder:
  lore: |
    <!i><white>NBT Data: <yellow>${nbt:myplugin:custom_value}$
```

---

## 三、处理器执行流水线

### 3.1 物品加载流水线

从 YAML 配置加载到 `itemMap` 的过程：

```
loadItemFile: 读取 YAML 文件
  → 触发 ItemLoadEvent(key, arguments, itemData)
    ├── [LOWEST] itemModuleSertraline: 提取 sertraline 功能（tier, type, vars, actions 等）
    ├── [LOW]    itemModuleXBuilder: 提取 xbuilder 功能（material, name, lore）
    ├── [LOW]    itemModuleMinecraft: 提取 minecraft 数据组件
    ├── [LOW]    itemModuleVisual: 提取 visual 数据组件
    └── [MONITOR] itemModuleSave: 构建 ModernSItem 并存入 itemMap
```

### 3.2 物品构建流水线

从 `sertralineItemBuilder` 调用到生成最终 `ItemStack` 的过程：

```
sertralineItemBuilder(template, player)
  │
  ├── 1. 从 itemMap 查找 ModernSItem 模板
  ├── 2. 应用 overrideData / vars / context
  ├── 3. itemSerializer(ModernSItem, player)
  │      ├── 3a. 序列化：ModernSItem → JSON 字符串
  │      ├── 3b. 标签处理：tagManager.processItem(json, item, stack, player)
  │      │      ├── 提取 ${val:...}$ ${var:...}$ ${papi:...}$ 等占位符
  │      │      ├── 按处理器分组（sertraline → val/var/context/kether/dynamic, papi, nbt）
  │      │      ├── 按顺序执行每个 TagProcessor
  │      │      └── 替换结果写回 JSON
  │      └── 3c. 反序列化：JSON → ModernSItem
  │
  ├── 4. 解析物品源材质（xbuilder:material 或 minecraft:material）
  ├── 5. itemManager.processItem(processedTemplate, baseItem, player)
  │      ├── xbuilder 处理器    （检查 config 中是否有 "xbuilder" 键）
  │      ├── sertraline 处理器  （检查 config 中是否有 "sertraline" 键）
  │      ├── custom_data 处理器
  │      ├── chotenatb 处理器
  │      ├── chotenatb_complex 处理器
  │      ├── mmo 处理器
  │      ├── minecraft 处理器   （仅 1.20.5+）
  │      └── common 处理器
  │
  └── 6. 写入 sertraline_id、修订版、类型到 NBT 标签
      → 返回最终 ItemStack
```

### 3.3 处理器执行顺序

物品处理器按注册顺序依次执行，**仅当物品配置中存在对应键名时才会调用**：

| 顺序 | 处理器 | 触发条件 | 版本要求 |
|---|---|---|---|
| 1 | `xbuilder` | config 含 `xbuilder` 键 | 全版本 |
| 2 | `sertraline` | config 含 `sertraline` 键 | 全版本 |
| 3 | `custom_data` | config 含 `custom_data` 键 | 全版本 |
| 4 | `chotenatb` | config 含 `chotenatb` 键 | 全版本 |
| 5 | `chotenatb_complex` | config 含 `chotenatb_complex` 键 | 全版本 |
| 6 | `mmo` | config 含 `mmo` 键 | 全版本 |
| 7 | `minecraft` | config 含 `minecraft` 键 | 1.20.5+ |
| 8 | `common` | config 含 `common` 键 | 全版本 |

---

## 四、完整配置示例

### 4.1 基础武器

```yaml
my_sword:
  sertraline:
    tier: C
    type: weapon
    lore-format: loreformat
  xbuilder:
    name: "<!i><gradient:gold:yellow>My Sword"
    lore: |
      <!i><gray>A legendary sword.
      <!i><yellow>+10 Attack Damage
    material: DIAMOND_SWORD
    unbreakable: true
    attributes:
      ATTACK_DAMAGE:
        amount: 10
        operation: ADD_NUMBER
        slot: HAND
```

### 4.2 带变量和动作的物品

```yaml
counter_sword:
  sertraline:
    tier: C
    vars:
      count: 0
    actions:
      onAttack:
        - js: |
            var tag = ItemStackUtil.getItemTag(bItem);
            tag.putDeep("sertraline_data.count", count + 1);
            ItemStackUtil.setItemTagDirect(bItem, tag);
            ItemStackUtil.rebuildLore(bItem, player);
  xbuilder:
    name: "<!i>&7Sword Of &6Counter"
    lore: |
      <!i><white>Hit Count: <yellow>${var:countAsIn}$
    material: IRON_SWORD
```

### 4.3 带数据组件的物品（1.20.5+）

```yaml
model_item:
  xbuilder:
    material: DIAMOND
    name: "Custom Model Item"
  minecraft:
    item-model: "minecraft:item/iron_ingot"
    max_stack_size: 1
    food:
      nutrition: 6
      saturation: 7.2
    consumable: {}
```

### 4.4 完整物品示例

```yaml
sword_of_eternal_frozen:
  sertraline:
    tier: S
    vars:
      chance: "%sertraline_random:40to80%"
    actions:
      onAttack:
        - async: false
          js: |
            var target = event.entity;
            if (target instanceof org.bukkit.entity.LivingEntity && Math.random() * 100 <= chance) {
              target.addPotionEffect(new org.bukkit.potion.PotionEffect(
                org.bukkit.potion.PotionEffectType.SLOWNESS, 300, 1));
              PlayerUtil.sendActionBar(player, mmUtil.deserialize("<#66ccff>Skill Activated!"));
            }
  xbuilder:
    name: "<!i><b><gradient:#66ccff:white:#66ccff><i>Eternal Frozen Sword"
    lore: |
      <!i><#aabbcc>On Attacking, chance to trigger a skill.
      <!i><#ffffaa>Current Trigger Chance: <yellow>${var:chanceAsIn}$% ${kether: if check get chance >= 85 then *<red>[Maximum]!!}$
    material: DIAMOND_SWORD
    glow: true
    unbreakable: true
    attributes:
      ATTACK_DAMAGE:
        amount: 16
        operation: ADD_NUMBER
        slot: HAND
```

---

## 五、管理与调试

### 5.1 查看已注册的处理器

```
/sertraline debug processors    # 查看已注册的物品处理器列表
/sertraline debug tagProcessors # 查看已注册的标签处理器列表
```

### 5.2 处理器开关

在 `config.yml` 中控制：

```yaml
modules:
  common:    { feature: true }
  sertraline: { feature: true }
  xbuilder:   { feature: true }
  minecraft:  { feature: true }
  visual:     { feature: true }
  mmo:        { feature: true }

tags:
  sertraline: true
  papi: true
  nbt: true
```

---

## 六、注意事项

1. **minecraft 处理器** 依赖 Minecraft 数据组件系统，**仅限 1.20.5+**，旧版本会自动跳过。
2. **papi 标签处理器** 需要安装 PlaceHolderAPI 插件，否则自动禁用。
3. **处理器按注册顺序执行**，后执行的处理器可能覆盖前一个处理器的修改。
4. **标签处理器中的 `${key!!}$`** 语法标记键为不可为空，值为 null 时替换为空字符串而非 "null"。
5. **标签处理器中的 `${key?:default}$`** 语法可设置默认值。
6. **vars 中的数据可持久化到 NBT**，玩家手持物品重新登录后变量值依然保留。
7. **context 中的数据不持久化**，仅运行时有效，适合传递 Player、Event 等对象。
8. **keep-context** 可将指定的 context 键持久化为 var，例如将运行时获取的玩家名称写入 NBT。