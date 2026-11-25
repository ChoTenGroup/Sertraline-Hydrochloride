# Default Combat Pack

The default combat pack is included with Sertraline. It uses the existing `AttributeAPI` to calculate combat stats, and synchronous global listeners apply the damage rules.

## Install and reload

A fresh installation extracts the bundled files. On an existing server, copy the files below into the matching directories under `plugins/Sertraline/`. For the attributes file, merge the `sertraline:*` entries under `attributes` into your existing definitions. Keep each attribute ID unique.

- [`attributes/default.yml`](../project/common-files/src/main/resources/attributes/default.yml)
- [`global-listeners/default-combat.yml`](../project/common-files/src/main/resources/global-listeners/default-combat.yml)
- [`workspace/default/default_combat_weapons.yml`](../project/common-files/src/main/resources/workspace/default/default_combat_weapons.yml)
- [`workspace/default/default_combat_armor.yml`](../project/common-files/src/main/resources/workspace/default/default_combat_armor.yml)

In `config.yml`, set `attribute.enable: true` and `script.graaljs.allow-java-type: true`. The listeners use GraalJS and need `Java.type` to look up Java, Bukkit, and plugin classes. Config synchronization adds the Java type setting with a default of `true` when it is missing. Changing the setting and reloading rebuilds the cached context the next time a script runs. Injected globals such as `Bukkit`, `AttributeAPI`, `bukkitPlugin`, and `scheduler` are available directly.

Run `/sertraline reload` and check the console for attribute or script errors. Use `/sertralineitem give combat_guard_sword` to try the Guard Sword, or substitute another ID from the table below.

Setting `attribute.enable` to `false` disables both combat listeners, including processing for arrows with a stored snapshot. To disable only the pack's damage rules, move `default-combat.yml` out of the global listeners directory and reload. If another plugin handles damage, choose one set of rules to avoid applying damage modifiers twice.

## Attributes and damage calculation

All pack attributes use the `sertraline:` namespace. They are separate from the legacy built-in attributes `crit_chance`, `crit_damage`, and `magic_damage`.

| Attribute ID without the namespace | Default | Effect |
| --- | --- | --- |
| `physical_defense`, `magic_defense`, `fire_defense`, `poison_defense`, `fall_defense` | 0 | Defense against the matching damage type, from 0 to 10,000 |
| `physical_damage`, `magic_damage`, `fire_damage`, `poison_damage` | 0 | Bonus attack damage of the matching type, from 0 to 10,000 |
| `critical_chance` | 0 | Critical hit chance from 0 to 1. A value of 0.25 means 25% |
| `critical_bonus` | 0.5 | Extra critical hit multiplier, from 0 to 10. A value of 0.5 means a critical hit deals 150% damage |

For each component, damage after defense is `component damage × 100 / (100 + matching defense)`. A defense of 100 cuts that component by 50%, and 300 cuts it by 75%. The same calculation halves vanilla base fall damage when fall defense is 100.

The pack applies defense separately to the original attack damage and each bonus component, then adds the results. A pack critical hit multiplies the total by `1 + critical_bonus`. For example, 10 points of base physical damage plus 6 points of bonus fire damage against a target with 100 physical defense and 50 fire defense deals `10 / 2 + 6 / 1.5 = 9` base damage. With a critical bonus of 0.5, a critical hit raises it to 13.5.

The rules update the Bukkit event's base damage. Vanilla armor, resistance, and enchantments still affect how much health the target loses. Vanilla falling melee critical hits can stack with the pack's critical hits.

## Damage types

| Defense type | Bukkit `DamageCause` values |
| --- | --- |
| Physical | `ENTITY_ATTACK`, `ENTITY_SWEEP_ATTACK`, `PROJECTILE`, `CONTACT`, `ENTITY_EXPLOSION`, `BLOCK_EXPLOSION`, `FALLING_BLOCK`, `FLY_INTO_WALL`, `THORNS` |
| Magic | `MAGIC`, `DRAGON_BREATH`, `SONIC_BOOM`, `WITHER` |
| Fire | `FIRE`, `FIRE_TICK`, `LAVA`, `HOT_FLOOR` |
| Poison | `POISON` |
| Fall | `FALL` |

Only the listed causes use the pack's defense calculation. Potion hits use the damage cause reported by Bukkit. Fire and poison bonuses deal immediate damage. They apply neither burning nor a potion effect, and a poison bonus can be lethal.

Bonus damage and critical hits apply to direct melee attacks from living entities, sweep attacks, and arrows or tridents that deal `PROJECTILE` damage. Defense also applies to thorns, ongoing fire and poison damage, and explosions. Snowballs, eggs, and potions receive no weapon bonuses.

When an arrow or trident is launched, the listener stores the shooter's six offensive attributes on the projectile. The projectile keeps that snapshot after the shooter swaps weapons, logs out, or despawns. A thrown trident uses the shooter's equipped stats at launch. A projectile without a snapshot can receive defense against its base damage.

The listener updates the current event once. It skips cancelled events, events with zero base damage, and attacks fully blocked by a shield. When absorption alone reduces final damage to zero, the listener still adds any bonus damage and Bukkit recalculates absorption. Bonus damage is flat by default and uses the same amount at different melee or bow charge levels.

## Bundled equipment

| Item ID | English name | Bonuses |
| --- | --- | --- |
| `combat_guard_sword` | Guard Sword | +5 physical damage, +10 physical defense |
| `combat_assassin_dagger` | Assassin's Dagger | +3 physical damage, +25 percentage points of critical chance, +75% critical damage bonus |
| `combat_arcane_blade` | Arcane Blade | +8 magic damage, +15 magic defense |
| `combat_ember_sword` | Ember Sword | +7 fire damage, +20 fire defense |
| `combat_venom_blade` | Venom Blade | +6 poison damage, +10 percentage points of critical chance |
| `combat_ranger_bow` | Ranger's Bow | +4 physical damage, +15 percentage points of critical chance, +25% critical damage bonus |
| `combat_sentinel_helmet` | Sentinel Helmet | +20 physical defense, +10 magic defense |
| `combat_runic_chestplate` | Runic Chestplate | +25 physical defense, +35 magic defense |
| `combat_ember_leggings` | Ember Leggings | +15 physical defense, +50 fire defense |
| `combat_feather_boots` | Feather Boots | +100 fall defense, +10 physical defense |
| `combat_antidote_helmet` | Antidote Hood | +75 poison defense, +15 magic defense |
| `combat_ward_shield` | Ward Shield | +20 each to physical, magic, fire, and poison defense |

Weapons grant their bonuses in the main hand, armor in its matching slot, and the Ward Shield in the off hand. The Sentinel Helmet and Antidote Hood are separate equipment choices.

The equipment uses `minecraft:add_value` modifiers with stable IDs. The lore lists the bonuses contributed by the item. Critical damage bonuses add to the base 0.5, so the Assassin's Dagger gives a total critical hit multiplier of `1 + 0.5 + 0.75 = 2.25`.

## Test the pack

Run `python docs/tests/default_combat_test.py` from the repository root. This requires Python, PyYAML, and Node.js. The test reads the real YAML and runs its listener scripts against simulated Bukkit objects to check damage types, mixed damage, defense, critical hits, cancelled and zero-damage events, and projectile snapshots.

For checks using GraalJS and the Bukkit API, set `COMBAT_TEST_CLASSPATH` to the GraalJS, Polyglot, Truffle, Bukkit or Paper API, Guava, and required Adventure JARs, joined with your system's classpath separator. With a JDK installed, run `python docs/tests/default_combat_test.py --runtime`. This checks class lookup, script initialization, damage method overloads, and writes to the projectile's PDC using the `DOUBLE` type. Verify actual damage, equipment attributes, and compatibility with other plugins on your target server.
