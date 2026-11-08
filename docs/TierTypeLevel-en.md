# Tier, Level and Type

Sertraline offers three independent, individually optional classification dimensions for items — "quality", "grade" and "category" tags used in lore display and script checks:

| Dimension | Typical use | Example |
|---|---|---|
| **Tier** | Rarity / quality display | E / D / C / B / A / S |
| **Level** | Grade classification | Normal / Advanced / Super |
| **Type** | Item category (with inheritance) | Sword → Weapon, Pickaxe → Tool |

> Note: these are **tags only** — they do not change item stats by themselves. Stats are still determined by `xbuilder.attributes` etc. To vary stats by tier, handle it in your config or scripts (see the FAQ below).

---

## 1. Defining tiers / levels / types

The three dimensions live in the `tiers/`, `levels/` and `types/` folders of the plugin data folder:

```
plugins/Sertraline/
├── tiers/      # tiers
├── levels/     # levels
└── types/      # types
```

- If a folder is missing, a sample `default.yml` is generated on first start — edit it directly.
- Supported extensions: `.yml` / `.yaml` / `.toml` (as `.tml`) / `.json` / `.conf`; subfolders are fine for organization.
- One file can define multiple entries, or split them across files.
- Run `/sertraline reload` after changes.
- Optional: add a `file-load.tier` / `file-load.level` / `file-load.type` regex in `config.yml` to filter which files load (all files load by default).

The entry ID is the YAML key (e.g. `S`, `advanced`, `SWORD` below) — items reference this ID. For duplicate IDs, later-loaded files overwrite earlier ones.

### Tier fields

```yaml
S:
  name: "<gold>S</gold>"         # display name, MiniMessage supported; falls back to the ID
  description: "<gold>Legendary" # description, empty by default
  color: "<gold>"                # color prefix, used by the {tier:format} placeholder
  weight: 100                    # weight (reserved field, no effect in the current version)
  extra:                         # extra data (optional, free-form)
    sort: 6
  meta:                          # metadata (optional), read in scripts via TierUtil.getMeta
    allow-reforge: true
```

### Level fields

Same fields as Tier (`name` / `description` / `color` / `weight` / `extra`) but **no `meta`**.

```yaml
advanced:
  name: "<blue>Advanced"
  description: "<gray>Advanced item"
  color: "<blue>"
  weight: 50
```

### Type fields

```yaml
WEAPON:
  name: "Weapons"
  description: "For combat"
SWORD:
  name: "Sword"
  parent: WEAPON       # inherits WEAPON
DAGGER:
  name: "Dagger"
  parent: SWORD        # inheritance chains: Dagger → Sword → Weapon
```

| Field | Description |
|---|---|
| `name` | Display name; falls back to the ID |
| `parent` | Parent type ID (optional), forming an inheritance chain |
| `description` | Description |
| `extra` | Extra data |
| `meta` | Metadata, read in scripts via `TypeUtil.getMeta` |

Type has no color or weight fields.

---

## 2. Using on items

Reference them under the item's `sertraline` node — all three fields optional:

```yaml
my_sword:
  sertraline:
    tier: S
    level: advanced
    type: SWORD
  xbuilder:
    name: "<!i><gold>Holy Sword"
    material: DIAMOND_SWORD
```

- Values are entry IDs; omitting a field disables that dimension.
- Referencing an undefined ID logs a console warning (`Tier 'X' not found for item ...`); the item still loads, the field just has no effect.
- **Casing**: `tier` / `level` IDs must match the definition exactly (case-sensitive); `type` is case-insensitive, but keep a consistent style.

---

## 3. Type inheritance

With `parent`, types form a tree — e.g. `SWORD → WEAPON`, `DAGGER → SWORD`. Inheritance matters for **script checks**: "is this item a weapon?" matches swords and daggers alike. See the [TypeUtil doc](TypeUtil-en.md) (`isItemIsExtendType` / `isAssignableFrom`).

---

## 4. Displaying in lore formats

In a [lore format](LoreFormatUtil-en.md), `sertraline:tier` / `sertraline:level` / `sertraline:type` work as line keys: **the line hides automatically when the item lacks the field**.

```yaml
elements:
  - key: "sertraline:type"
    content: "<!i><gray>| <dark_gray>Type: <white>{type:name}"
  - key: "sertraline:tier"
    content: "<!i><gray>| <dark_gray>Tier: {tier:format}"
  - key: "sertraline:level"
    content: "<!i><gray>| <dark_gray>Level: <white>{level:name}"
```

Placeholders available in line content:

| Tier | Level | Type |
|---|---|---|
| `{tier}` display name | `{level}` display name | `{type}` display name |
| `{tier:format}` color + name | `{level:name}` display name | `{type:name}` display name |
| `{tier:color}` color | `{level:color}` color | `{type:id}` ID |
| `{tier:id}` ID | `{level:id}` ID | `{type:parent}` parent ID |
| `{tier:description}` description | `{level:description}` description | `{type:description}` description |
| `{tier:weight}` weight | `{level:weight}` weight | |

> Note: tiers have **no** `{tier:name}` / `{tier:desc}` — use `{tier}` for the name, `{tier:format}` to include the tier color. Some older example comments mention `{tier:name}` / `{tier:desc}`; those are invalid.

The default sample format (`lore-formats/loreGenerator.yml`) already includes these lines.

---

## 5. Using in scripts

```js
// Read a tier object and its metadata
var tier = TierUtil.getTier("S")
var canReforge = TierUtil.getMetaAsBoolean("S", "allow-reforge")

// Check whether the held item belongs to the weapon category
// (with inheritance: swords and daggers both match)
var isWeapon = TypeUtil.isItemIsExtendType(bItem, "weapon")

// Read a level (levels have no dedicated util; access the registry directly)
var level = Sertraline.levels.get("advanced")
if (level != null) {
    PlayerUtil.sendMessage(player, mmUtil.deserialize("Level: " + level.name))
}
```

Full API: the [TierUtil](TierUtil-en.md) and [TypeUtil](TypeUtil-en.md) docs.

---

## 6. Complete example

`tiers/rarity.yml`:

```yaml
rare:
  name: "<blue>Rare"
  color: "<blue>"
epic:
  name: "<light_purple>Epic"
  color: "<light_purple>"
legendary:
  name: "<gold>Legendary"
  color: "<gold>"
```

`types/weapons.yml`:

```yaml
WEAPON:
  name: "Weapon"
SWORD:
  name: "Sword"
  parent: WEAPON
BOW:
  name: "Bow"
  parent: WEAPON
```

An item in `workspace/`:

```yaml
flame_blade:
  sertraline:
    tier: legendary
    type: SWORD
  xbuilder:
    name: "<!i><gradient:red:gold>Flame Blade"
    lore: |
      <!i><gray>A burning sword.
    material: NETHERITE_SWORD
```

With a lore format containing the `sertraline:type` / `sertraline:tier` lines, the final lore shows:

```
| Type: Sword
| Tier: Legendary
```

---

## 7. FAQ

**Changed tiers/levels/types files but nothing happened?**
Run `/sertraline reload`.

**Console warns `Tier 'xxx' not found`?**
The referenced ID is undefined, or its casing differs (tier / level are case-sensitive, type is not). Check spelling and casing.

**Item stats didn't change after setting tier / level?**
That's expected — the three are tags and don't participate in stat calculation. To vary stats by tier:
1. Simply configure different stats per item (different item IDs are different configs anyway);
2. Or read tier metadata in scripts via `TierUtil.getMeta(tierId, "some_value")` and branch on it.

**What is the `weight` field for?**
Reserved — no effect in the current version.

**The tier line doesn't show in lore?**
Check that the lore format actually in use contains a `sertraline:tier` line (custom formats must add it themselves, see section 4), and that the item really has `tier` set.
