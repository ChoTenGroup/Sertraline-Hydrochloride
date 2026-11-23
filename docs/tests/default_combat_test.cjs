// Invoked by default_combat_test.py with scripts extracted from the shipped YAML.
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const pack = JSON.parse(fs.readFileSync(0, 'utf8'));
class LivingEntity {
  constructor(attrs = {}) { this.attrs = attrs; }
}
class PDC extends Map {
  set(key, type, value) { return super.set(String(key), value); }
  get(key, type) { return super.get(String(key)) ?? null; }
}
class AbstractArrow {
  constructor(shooter) { this.shooter = shooter; this.data = new PDC(); }
  getShooter() { return this.shooter; }
  getPersistentDataContainer() { return this.data; }
}
class NamespacedKey {
  constructor(ns, key) { this.key = `${ns}:${key}`; }
  toString() { return this.key; }
}
class DamageEvent {
  constructor(target, cause, damage = 10, cancelled = false, finalDamage = damage) {
    Object.assign(this, { target, cause, damage, cancelled, finalDamage, writes: 0 });
  }
  getEntity() { return this.target; }
  getCause() { return { name: () => this.cause }; }
  getDamage(modifier) { return modifier === 'BLOCKING' ? (this.blocking ?? 0) : this.damage; }
  isApplicable(modifier) { return modifier === 'BLOCKING' && this.blocking !== undefined; }
  getFinalDamage() { return this.finalDamage; }
  isCancelled() { return this.cancelled; }
  setDamage(n) { this.damage = n; this.writes++; }
}
class ByEntity extends DamageEvent {
  constructor(target, damager, cause = 'ENTITY_ATTACK', damage = 10) {
    super(target, cause, damage); this.damager = damager;
  }
  getDamager() { return this.damager; }
}
const types = {
  'org.bukkit.entity.LivingEntity': LivingEntity,
  'org.bukkit.event.entity.EntityDamageByEntityEvent': ByEntity,
  'org.bukkit.entity.AbstractArrow': AbstractArrow,
  'org.bukkit.NamespacedKey': NamespacedKey,
  'org.bukkit.persistence.PersistentDataType': { DOUBLE: 'double' },
  'org.bukkit.event.entity.EntityDamageEvent.DamageModifier': { BLOCKING: 'BLOCKING' },
  'io.github.zzzyyylllty.sertraline.attribute.SertralineAttributeEngine': {
    INSTANCE: { getEnabled: () => enabled },
  },
};
let registered = true;
let enabled = true;
let refreshes = 0;
const api = {
  isRegistered: () => registered,
  refresh: () => { refreshes++; },
  getValue: (entity, key) => entity.attrs[key.replace('sertraline:', '')] ?? pack.defaults[key] ?? 0,
};
function run(script, event, roll = 0.5) {
  const math = Object.create(Math);
  math.random = () => roll;
  vm.runInNewContext(script, {
    event, AttributeAPI: api, Math: math,
    Java: { type: name => { assert.ok(types[name], name); return types[name]; } },
  }, { timeout: 1000 });
  return event;
}
const hit = (e, roll) => run(pack.damage, e, roll);
function close(actual, expected) { assert.ok(Math.abs(actual - expected) < 1e-9, `${actual} != ${expected}`); }
let checks = 0;
function test(name, fn) { fn(); checks++; console.log(`PASS ${name}`); }

test('ordinary melee without attributes keeps original damage', () => {
  const e = hit(new ByEntity(new LivingEntity(), new LivingEntity()));
  close(e.damage, 10); assert.equal(e.writes, 0);
});
test('mixed damage uses separate defenses and one event mutation', () => {
  const target = new LivingEntity({ physical_defense: 100, fire_defense: 50 });
  const e = hit(new ByEntity(target, new LivingEntity({ fire_damage: 6 })));
  close(e.damage, 9); assert.equal(e.writes, 1);
});
test('all four offensive components use matching defenses', () => {
  const target = new LivingEntity({ physical_defense: 100, magic_defense: 300,
    fire_defense: 100, poison_defense: 400 });
  const attacker = new LivingEntity({ physical_damage: 2, magic_damage: 8, fire_damage: 6, poison_damage: 10 });
  close(hit(new ByEntity(target, attacker)).damage, 13);
});
test('critical chance and bonus, including upper boundary', () => {
  const attacker = new LivingEntity({ critical_chance: 1 });
  close(hit(new ByEntity(new LivingEntity(), attacker), 0.999).damage, 15);
  attacker.attrs.critical_bonus = 1.25;
  close(hit(new ByEntity(new LivingEntity(), attacker), 0).damage, 22.5);
  attacker.attrs.critical_chance = 0;
  close(hit(new ByEntity(new LivingEntity(), attacker), 0).damage, 10);
});
test('fire, magic, poison, fall and physical environment classification', () => {
  const groups = {
    physical: ['ENTITY_ATTACK', 'ENTITY_SWEEP_ATTACK', 'PROJECTILE', 'CONTACT',
      'ENTITY_EXPLOSION', 'BLOCK_EXPLOSION', 'FALLING_BLOCK', 'FLY_INTO_WALL', 'THORNS'],
    magic: ['MAGIC', 'DRAGON_BREATH', 'SONIC_BOOM', 'WITHER'],
    fire: ['FIRE', 'FIRE_TICK', 'LAVA', 'HOT_FLOOR'], poison: ['POISON'], fall: ['FALL'],
  };
  for (const [type, causes] of Object.entries(groups)) {
    for (const cause of causes) {
      close(hit(new DamageEvent(new LivingEntity({ [type + '_defense']: 100 }), cause)).damage, 5);
    }
  }
});
test('unrelated causes keep vanilla damage', () => {
  for (const cause of ['VOID', 'STARVATION', 'DROWNING', 'CUSTOM', 'FREEZE']) {
    const e = hit(new DamageEvent(new LivingEntity({ physical_defense: 100 }), cause));
    assert.equal(e.writes, 0); close(e.damage, 10);
  }
});
test('cancelled, zero, shield-blocked, nonliving and nonfinite events are untouched', () => {
  const blocked = new ByEntity(new LivingEntity(), new LivingEntity({ fire_damage: 20 }));
  blocked.blocking = -10;
  for (const e of [new DamageEvent(new LivingEntity(), 'FALL', 10, true),
    new DamageEvent(new LivingEntity(), 'FALL', 0),
    blocked,
    new DamageEvent({}, 'FALL'), new DamageEvent(new LivingEntity(), 'FALL', NaN)]) {
    hit(e); assert.equal(e.writes, 0);
  }
});
test('absorption reducing final damage to zero still permits bonus damage', () => {
  const e = new ByEntity(new LivingEntity(), new LivingEntity({ fire_damage: 6 }));
  e.finalDamage = 0;
  close(hit(e).damage, 16);
});
test('secondary damage cannot trigger attacker bonuses or crits', () => {
  const attacker = new LivingEntity({ fire_damage: 100, critical_chance: 1 });
  for (const cause of ['THORNS', 'FIRE_TICK', 'POISON', 'MAGIC', 'ENTITY_EXPLOSION']) {
    close(hit(new ByEntity(new LivingEntity(), attacker, cause)).damage, 10);
  }
});
test('projectile snapshot survives weapon switch and missing shooter', () => {
  const shooter = new LivingEntity({ magic_damage: 8, critical_chance: 1 });
  const arrow = new AbstractArrow(shooter);
  run(pack.snapshot, { getEntity: () => arrow, isCancelled: () => false });
  assert.equal(arrow.data.size, 6);
  shooter.attrs = { physical_damage: 1000 }; arrow.shooter = null;
  const target = new LivingEntity({ physical_defense: 100, magic_defense: 100 });
  close(hit(new ByEntity(target, arrow, 'PROJECTILE')).damage, 13.5);
});
test('unsnapshotted projectiles never borrow current shooter bonuses', () => {
  const arrow = new AbstractArrow(new LivingEntity({ fire_damage: 100 }));
  close(hit(new ByEntity(new LivingEntity(), arrow, 'PROJECTILE')).damage, 10);
});
test('non-arrow projectiles and cancelled launches do not gain snapshots', () => {
  const arrow = new AbstractArrow(new LivingEntity({ magic_damage: 100 }));
  run(pack.snapshot, { getEntity: () => arrow, isCancelled: () => true });
  assert.equal(arrow.data.size, 0);
  run(pack.snapshot, { getEntity: () => ({}), isCancelled: () => false });
  close(hit(new ByEntity(new LivingEntity(), {}, 'PROJECTILE')).damage, 10);
});
test('high defense remains nonnegative; invalid offense is ignored', () => {
  const target = new LivingEntity({ physical_defense: 10000 });
  close(hit(new ByEntity(target, new LivingEntity({ fire_damage: NaN }))).damage, 10 / 101);
});
test('unequipped stats and newly equipped defenses use current values', () => {
  const target = new LivingEntity({ fall_defense: 100 });
  close(hit(new DamageEvent(target, 'FALL')).damage, 5);
  target.attrs = {};
  close(hit(new DamageEvent(target, 'FALL')).damage, 10);
  assert.ok(refreshes > 0);
});
test('missing attribute pack leaves events untouched', () => {
  registered = false;
  assert.equal(hit(new DamageEvent(new LivingEntity(), 'FALL')).writes, 0);
  const arrow = new AbstractArrow(new LivingEntity());
  run(pack.snapshot, { getEntity: () => arrow, isCancelled: () => false });
  assert.equal(arrow.data.size, 0);
  registered = true;
});
test('disabled attribute system ignores already-snapshotted projectiles', () => {
  const arrow = new AbstractArrow(new LivingEntity({ fire_damage: 100 }));
  run(pack.snapshot, { getEntity: () => arrow, isCancelled: () => false });
  enabled = false;
  const e = hit(new ByEntity(new LivingEntity(), arrow, 'PROJECTILE'));
  close(e.damage, 10); assert.equal(e.writes, 0);
  const later = new AbstractArrow(new LivingEntity());
  run(pack.snapshot, { getEntity: () => later, isCancelled: () => false });
  assert.equal(later.data.size, 0);
  enabled = true;
});
console.log(`${checks} script checks passed`);
