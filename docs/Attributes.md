# Attributes

Definitions load recursively from `plugins/Sertraline/attributes/`. Reload with `/sertraline reload`.
All files are validated before publication. Invalid bounds, duplicate IDs, missing dependencies,
unknown operations and derived cycles reject the reload. API registrations survive reloads.
Empty and comment-only files are skipped. Deleting an unused definition removes its synced modifiers at the next refresh.

The format follows [CraftEngine attributes](https://ce-pre.gtemc.cn/zh-Hans/configuration/attribute).
This implements definitions, operations and item modifiers in Sertraline. CraftEngine damage rules,
equipment sets, number providers and placeholder syntax are separate features and are not imported.

## Definitions

```yaml
# Definitions / 属性定义
attributes:
  demo:might:
    name: Might
    base: 10
    constraint: {min: 0, max: 100}
  demo:power:
    derived: "demo:might * 2"
attribute_operations:
  demo:capped:
    expression: "MIN(current + amount, base * 2)"
```

IDs accept namespaces and paths. Legacy unqualified IDs remain valid. Builtin IDs cannot be replaced.
`name`, `description` and legacy `default`, `min`, `max`, `percentage`, `vanilla-name` remain supported.
An omitted constraint bound is unlimited. Derived attributes ignore `base`, `operations` and `sync`;
modifiers cannot change them directly. Namespaced references resolve across files in any order.
Use spaces around operators, including ternary `:`, to distinguish them from namespaced IDs.
Nested API reads use the previous snapshot, or defaults before the first refresh.

`base` accepts a number, a JS expression, or a provider:

```yaml
# Native base / 原版基础值
base:
  type: vanilla
  attribute: minecraft:armor
  fallback: 0
  transform: "value * 0.5"
  update_interval: 20
```

`constant` uses `value`. `vanilla` reads the current vanilla base, excluding modifiers.
`by_entity_type` reads Paper's entity-type defaults. Unsupported attributes or platforms use `fallback`.
`transform` receives `value`. A nonpositive base `update_interval` disables polling until reload or clear.
`entities` accepts entity IDs or Bukkit entity tags; unsupported tags do not match.
Players refresh automatically. Other living entities start tracking after `refresh` or an API modifier request;
unloaded/dead entities are removed. Tracked equipment includes hands and four armor slots.

## Operations

Default order:

1. `minecraft:add_value`: `current + amount`.
2. `minecraft:add_multiplied_base`: `current + base * amount`.
3. `minecraft:add_multiplied_total`: `current * (1 + amount)`.

`base` is the value at the start of each stage. `operations` can reorder or omit stages.
Custom operations receive `base`, `current`, `amount`, `entity` and `player`.
Constraints apply last. Invalid operation results preserve `current`.
New percentages use decimals: `0.2` means 20%. Legacy `20%` and `2x` retain their old units.
Legacy percentage attributes such as `crit_chance` still add percentage points.

## Items

```yaml
# Main-hand bonus / 主手加成
attribute-modifiers:
  - type: demo:might
    id: demo:sword/might
    amount: 8
    operation: minecraft:add_value
    slot: mainhand
```

`settings.attribute_modifiers` is an alias. Stable IDs replace duplicate contributions to the same attribute.
Slots: `mainhand`, `offhand`, `hand`, `head`, `chest`, `legs`, `feet`, `armor`, `any`.
Legacy armor names also work. `scope: entity` is the default.
`scope: weapon` contributes only to `getWeaponValue`, from the current main-hand item, starting at zero.
It does not enter the panel or automatically modify Bukkit damage. For projectiles, capture the API value at launch.

`amount` accepts numbers, JS expressions or script nodes. `condition`/`conditions` accept boolean JS expressions;
all must pass. `update_interval` defaults to 20 ticks. Runtime variables include `entity`, `player`,
Bukkit `item`, Sertraline `sItem`, `AttributeAPI` and existing script utilities.
Periodic refresh also detects equipment changes. `attribute.update-interval` defaults to 20 ticks;
smaller provider/modifier intervals shorten it. Evaluation failures skip the affected modifier and warn once.

```yaml
# Dynamic amount / 动态修饰量
amount:
  type: js
  script: "return 4 + player.getLevel() * 0.2;"
conditions:
  - "player.getLevel() >= 5"
update_interval: 20
```

## Scripts

Expressions default to the existing GraalJS engine; legacy servers use Nashorn.
`type: js` selects Nashorn. `type: graaljs` selects GraalJS.
`expression` is a value expression; `script` is a function body with `return`.
`{type: script, name: my_script}` invokes the existing `scripts/` entry with the same context.
Attribute scripts must return synchronously. Kether entries are rejected for numeric values.
`MIN`, `MAX`, `ABS`, `FLOOR`, `CEIL`, `ROUND`, `SQRT`, `POW` alias JS Math functions in expressions.

The global `AttributeAPI` is available in item/action/lore scripts and `ScriptHelper`.

```javascript
var might = AttributeAPI.getValue(player, "demo:might");
AttributeAPI.setOperationModifier(player, "skill:boost", "demo:might", 0.2, "minecraft:add_multiplied_base");
AttributeAPI.removeModifier(player, "skill:boost");
```

## Vanilla sync

```yaml
# Health bonus / 生命加成
sync:
  - target: minecraft:max_health
    operation: add_value
    value: value
```

Sync accepts the three vanilla operations and an expression with `value` and `base`.
`value: {type: delta}` means `value - base`; `value: {type: ratio}` means `value / base`.
A non-finite result contributes zero. Synced values use owned modifiers; vanilla base values and
other plugins' modifiers remain intact. Maximum-health changes preserve the health ratio.
Unequip, disabled sync, reload, quit and plugin shutdown remove obsolete modifiers.
Modern servers use `sertraline:attribute/` keys so persisted modifiers remain identifiable.
`attribute.vanilla-sync: auto` avoids competing ChoTenTech/MythicLib providers; `true` forces sync.

## API

Class: `io.github.zzzyyylllty.sertraline.attribute.api.SertralineAttributeAPI`.
Methods are Java static methods, Kotlin companion methods, and JS `AttributeAPI` methods.

| Method | Result |
| --- | --- |
| `getValue(entity, id)` | Effective value |
| `getAll(entity)` | Last snapshot |
| `getAllWithDefaults(entity)` | All applicable values |
| `getWeaponValue(entity, id)` | Value with current weapon contribution |
| `getType(id)`, `getRegistered()`, `isRegistered(id)` | Definitions |
| `register(type)`, `registerDefinition(id, map)`, `unregister(id)` | Manage definitions |
| `registerOperation(id, expression)`, `unregisterOperation(id)` | Manage operations |
| `setModifier(entity, key, id, value[, type])` | Legacy units |
| `setOperationModifier(entity, key, id, amount, operation)` | Decimal amounts |
| `removeModifier(entity, key)`, `getModifiers(entity)` | External modifiers |
| `requestRefresh(entity)` | Scheduled refresh |
| `refresh(entity)` | Immediate refresh |
| `clear(entity)` | Remove runtime state and owned sync |

Use the server thread for entity access and immediate refresh/clear. Cached `getAll` reads are immutable.
Modifier changes schedule refresh; call `refresh` on the server thread to read the result immediately.
External modifiers last until removal, quit or shutdown. Definitions are immutable snapshots.

```java
SertralineAttributeAPI.setOperationModifier(player, "skill:might", "demo:might", 5, "minecraft:add_value");
SertralineAttributeAPI.refresh(player);
double value = SertralineAttributeAPI.getValue(player, "demo:might");
```
