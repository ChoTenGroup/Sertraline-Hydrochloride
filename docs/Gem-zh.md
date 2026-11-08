# 宝石系统 Gem System

宝石系统给 Sertraline 物品加上「插槽」，宝石物品镶嵌进去后通过**数据修改（Alteration）**改变宿主物品的数据（变量、名称、Lore、属性……）。整套机制建立在 `sertraline:alteration` 之上，因此宝石效果会随 `rebuild` 一直保留。

相关代码：

| 位置 | 作用 |
| --- | --- |
| `data/GemData.kt` | 解析 `sertraline:gem` / `sertraline:gem-slots`，把 `modify-data` 编译成 alteration |
| `item/gem/gemManager.kt` | 镶嵌 / 拆卸 / 读取槽位状态 |
| `listener/gem/GemSocketListener.kt` | 手持宝石点击物品镶嵌 |
| `command/gemSubCommand.kt` | `/sertralinegem`（别名 `/sgem`） |
| `item/process/gemSlotTagProcessor.kt` | Lore 占位符 `${gem-slot:<slotId>}$` |
| `workspace/default/default_gem.yml` | 可直接使用的示例物品 |

---

## 一、在物品上声明插槽

插槽写在宿主物品的 `sertraline:gem-slots` 下，键名就是槽位 ID：

```yaml
gem_example_sword:
  sertraline:
    vars:
      atk: 100
    gem-slots:
      slot_red:
        name: "<red>Red Socket"        # 槽位名，用于提示与 Lore
        desc: "<gray>Can apply a red gem."  # 空槽位时显示的说明
        gem-color: "red"               # 正则；省略或留空 = 接受任意颜色
      slot_any:
        name: "<light_purple>Prismatic Socket"
        desc: "<gray>Accepts any gem color."
  xbuilder:
    name: "<!i>&cGem Example Sword"
    material: DIAMOND_SWORD
    lore: |
      <!i><gray>Attack: <red>${var:atkAsIn}$
      <!i>${gem-slot:slot_red}$
      <!i>${gem-slot:slot_any}$
```

- `gem-color` 是**正则**（全匹配），`red|blue` 这类写法也支持；不写则任意颜色都能镶。
- 槽位是「按 ID 一对一」的，同一个 ID 只能嵌一颗宝石。

## 二、在宝石物品上声明行为

```yaml
gem_example_ruby:
  sertraline:
    gem:
      color: red
      meta:                    # 可选，自由元数据
        note: "A red gem."
      modify-data:             # 镶嵌后对宿主物品施加的修改
        sertraline:
          vars:
            atk: "+15"
  xbuilder:
    name: "<!i><red>Ruby Gem"
    material: RED_DYE
```

`modify-data` 的两种等价写法：

```yaml
modify-data:
  sertraline:                  # 嵌套写法，会被拼接成 sertraline:vars.atk
    vars:
      atk: "+15"

modify-data:
  "sertraline:vars.atk": "+15" # 扁平写法，直接就是数据键
```

叶子值的语义：

| 写法 | 含义 |
| --- | --- |
| `"+15"` / `"-5"` / `"+2.5"` | 在现有数值上加减（目标不存在时按 0 起算） |
| `"set:value"` | 直接设置 |
| `"remove"` | 删除该键 |
| `"list_add:v"` / `"list_add_last:v"` / `"list_remove:i"` / `"list_remove_value:v"` / `"list_remove_all:v"` / `"list_clear"` | 列表操作 |
| `"map_set:k=v"` / `"map_remove:k"` / `"map_clear"` | 映射操作 |
| 其他（`100`、`"hello"`、`true`…） | 直接设置（SET） |

操作名大小写不敏感，`-` 与 `_` 等价（`list-add:v` = `list_add:v`）。

> 注意：键必须是「命名空间:路径」形式。`sertraline` / `xbuilder` / `minecraft` 之外的命名空间不会生效；没有命名空间的路径会被当成顶层数据键写入，物品构建时被忽略。

## 三、镶嵌与拆卸

### 交互方式（可配置）

点击镶嵌由 `config.yml` 的 `gem.interaction.click` 控制：

```yaml
gem:
  interaction:
    click:
      enable: true        # 总开关
      types:              # 允许触发镶嵌的点击方式
        - RIGHT           # LEFT / RIGHT / SHIFT_LEFT / SHIFT_RIGHT / DOUBLE_CLICK
      permission: ""      # 所需权限，留空不检查
```

除全局配置外，还可以在**物品 / 槽位 / 宝石**三级单独禁用点击镶嵌（命令与 API 不受影响）：

```yaml
# ① 整件物品禁止点击镶嵌
some_item:
  sertraline:
    gem-click: false
    gem-slots:
      # ② 单个槽位禁止点击镶嵌
      slot_locked:
        name: "<gray>Locked Socket"
        click: false
      slot_red:
        name: "<red>Red Socket"
        gem-color: "red"

# ③ 宝石自身禁止点击镶嵌
gem_example_plain:
  sertraline:
    gem:
      color: white
      click: false
```

`click` 接受 `true/false`、`yes/no`、`on/off`、`1/0`、`enable/disable`、`allow/deny`（别名 `click-socket`；物品级为 `gem-click` / `gem-click-socket`）。任一为 `false` 即拒绝点击镶嵌，并提示 `gem-socket-denied`。

> 新交互方式（GUI、合成台、kether 函数……）通过 `GemSocketMethod` 增加一项，再在 `GemSocketPolicy.denialOf` 里给出该路由的规则即可；`socketGem(..., method)` 会自动按路由套用物品级开关。

### 命令

| 命令 | 说明 |
| --- | --- |
| `/sertralinegem list` | 列出手持物品的槽位与已镶嵌的宝石 |
| `/sertralinegem socket <slotId> [gemId]` | 把 `gemId`（省略时取副手）镶进指定槽位 |
| `/sertralinegem unsocket <slotId>` | 取下指定槽位的宝石并归还玩家 |

别名 `/sgem`，权限 `sertraline.command.gem`（默认 OP）。命令走 `GemSocketMethod.COMMAND`，**忽略**上面的点击开关与 `click: false`，方便管理员与剧情脚本强制镶嵌。

### 效果的去向

- 指向 `sertraline:vars.*` 的修改会**折叠进** `sertraline_data`（永久生效），拆卸时按记录的原值/相反数还原。
- 其他修改（`xbuilder:name`、Lore、属性等）每次 rebuild 都重放，拆卸时不再重放即恢复原状。

## 四、NBT 结构

```yaml
sertraline_gem:
  used: ["slot_red"]        # 已占用的槽位（唯一事实来源）
  slots:
    slot_red:
      gem: "gem_example_ruby"  # 宝石的 Sertraline ID
      timestamp: 1791764589    # 镶嵌时间（毫秒）
```

`sertraline_gem` 会在每次 rebuild 时被构建管线显式带过去，**不需要**写进 `rebuild.keep-data`；但如果你的 `keep-data` 里有它也没影响（v2 配置迁移已自动补齐）。

## 五、Lore 占位符

`${gem-slot:<slotId>}$` 在构建时渲染：

- 空槽位 → `◇ <槽位名> — <desc>`
- 已镶嵌 → `◆ <槽位名>: <宝石ID>`

请把它放在**带其他文字的行内**（例如 `<!i>${gem-slot:slot_red}$`），不要让它独占整行——标签管线是把值直接替换进物品 JSON 的，独占整行会得到没有引号的裸字符串。

## 六、常见问题

**宝石镶上去没效果？** 检查 `modify-data` 的键是否是 `sertraline:vars.xxx` 这种带命名空间的路径；嵌套写法现在会自动拼接成正确形式。

**`+15` 没生效？** 目标变量不存在时按 0 起算（已修复），若仍无效请确认键名拼写。

**自定义物品（private）能镶吗？** 可以，槽位与宝石定义都通过 `itemTemplateForStack` 解析，支持私有模板。

**同一槽位能反复镶吗？** 不能，槽位被占用后 `socket` 返回 `SLOT_OCCUPIED`；换宝石需先 `unsocket`。
