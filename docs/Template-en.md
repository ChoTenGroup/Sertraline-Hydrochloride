# Template Syntax

Store template definitions in the `templates/` directory. Each top-level key is a template name, and its value is the template mapping. You can add a namespace with a colon, as in `combat:weapon`. Use the full name when referencing the template. The loader reads files in relative path order. If a name appears in more than one file, the loader uses the first definition and reports both files. YAML, TOML, and JSON are supported.

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

Use `$template: combat:weapon` as shorthand. A placeholder such as `%{key}` reads an argument, and `%{key?:fallback}` supplies a default. `_ID` is the ID of the current top-level entry. Arguments, templates, and sibling fields can reference values in the current expansion scope. The loader logs unused arguments and unresolved placeholders. Inside a placeholder, use `\}`, `\{`, or `\?:` to write those characters literally.

Arguments accept text, numeric, and Boolean scalars. Numbers and Booleans are substituted as text. Plain arguments can reference arguments declared later. The loader evaluates `$t` and `$c` in declaration order, and they can read previously declared arguments and `_ORIGIN`. A conversion uses the first available value from the same argument's `$t` result, `$c.value`, or an argument of the same name in an outer scope. If the conversion fails, the loader logs the failure and uses `$c.fallback` or the template's default.

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

`self_increase` assigns one value per expansion during a configuration load. Every reference in that expansion uses the same value. Counters are shared by template name, argument name, and range. Reloading starts a new sequence. Integer conversions support `ceil`, `floor`, and `round`.

The loader merges template and entry mappings recursively. The entry's own fields take precedence. Lists append by default. Set `$list-mode: replace` alongside `$template` to replace overlapping lists in full.

Public template lookups read the published snapshot. To expand a template from the current candidate configuration during a load event, pass the event's `LoadContext` to `TemplateManager.resolveInMap`.
