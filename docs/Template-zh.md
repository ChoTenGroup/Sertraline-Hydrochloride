# 模板语法

模板定义存放在 templates 目录。每个配置文件根节点的键是模板名，值是模板映射。可用冒号给名称加命名空间，例如 combat:weapon，调用时使用完整名称。目录按相对路径排序加载，遇到重名时采用第一份定义并报告两个文件。加载器识别 YAML、TOML 和 JSON。

```yaml
# templates/weapons.yml
combat:weapon:
  xbuilder:
    material: "%{material?:IRON_SWORD}"
    name: "%{name?:Weapon}"
    lore:
      - "<gray>Tier %{tier}"
  sertraline:
    tier: "%{tier}"

# workspace/default/example.yml
steel_sword:
  $template:
    use: combat:weapon
    arguments:
      name: Steel Sword
      tier: A
  xbuilder:
    lore:
      - "<gray>Forged in the city."
```

模板可用 `$template: combat:weapon` 简写。形如 `%{key}` 的占位符读取参数，`%{key?:fallback}` 提供默认值。`_ID` 是当前文件根条目的 ID。参数、模板和同级字段可以引用当前展开的作用域。未使用的参数和未解析的占位符会报告日志。占位符内部可以用 `\}`、`\{` 和 `\?:` 引用字面字符。

参数接受文本、数字和布尔标量。数字和布尔值作为文本参与替换。普通参数可引用后续声明的参数。`$t` 和 `$c` 按声明顺序求值，可以读取已声明参数与 `_ORIGIN`。类型转换的值依次来自同条参数的 `$t`、`$c.value` 或外层同名参数。转换失败会报告日志，随后采用 `$c.fallback` 或模板默认值。

```yaml
arguments:
  damage:
    $c:
      type: int
      value: "10.2"
      round: ceil
  serial:
    $t:
      type: self_increase
      from: 1
      to: 100
  name: "Token #%{serial}"
```

`self_increase` 在一次配置加载中的每次展开只分配一个值，该次展开里的所有引用使用同一个值。计数器按模板名、参数名和范围共享，重载后开启新序列。整数支持 `ceil`、`floor` 和 `round`。

模板映射和条目映射递归合并，同级条目字段优先。两边的列表默认追加。在 `$template` 同一级配置 `$list-mode: replace` 可整体替换重叠的列表。

公开模板查询读取已发布的快照。加载事件中若需展开当前候选模板，将事件提供的 `LoadContext` 传给 `TemplateManager.resolveInMap`。
