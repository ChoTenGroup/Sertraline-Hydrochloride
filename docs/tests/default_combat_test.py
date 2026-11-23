"""Validate the shipped YAML and execute its actual scripts against Bukkit mocks.

Run: python docs/tests/default_combat_test.py (requires PyYAML and Node.js).
"""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

import yaml

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / 'project/common-files/src/main/resources'


class UniqueLoader(yaml.SafeLoader):
    pass


def unique_mapping(loader, node):
    result = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node)
        assert key not in result, f'Duplicate YAML key: {key}'
        result[key] = loader.construct_object(value_node)
    return result


UniqueLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, unique_mapping)


def read(relative):
    return yaml.load((RES / relative).read_text(encoding='utf-8'), Loader=UniqueLoader)


definitions = read('attributes/default.yml')['attributes']
combat = {k: v for k, v in definitions.items() if k.startswith('sertraline:')}
assert len(combat) == 11
for definition in combat.values():
    assert definition['constraint']['min'] <= definition['base'] <= definition['constraint']['max']

items = {}
modifier_ids = set()
for filename in ['default_combat_weapons.yml', 'default_combat_armor.yml']:
    for key, item in read('workspace/default/' + filename).items():
        assert key not in items
        items[key] = item
        for modifier in item['attribute-modifiers']:
            assert modifier['type'] in combat
            assert modifier['id'] not in modifier_ids
            modifier_ids.add(modifier['id'])
            assert modifier['operation'] == 'minecraft:add_value'
            assert modifier['slot'] in {'mainhand', 'offhand', 'head', 'chest', 'legs', 'feet'}
assert len(items) == 12

listeners = read('global-listeners/default-combat.yml')
for listener in listeners.values():
    assert listener['async'] is False
    assert listener['accept-cancelled'] is False
    assert listener['priority'] == 'HIGH'
    assert set(listener['eval']) == {'graaljs'}
assert read('config.yml')['script']['graaljs']['allow-java-type'] is True

payload = {
    'defaults': {k: v['base'] for k, v in combat.items()},
    'snapshot': listeners['default_combat_projectile_snapshot']['eval']['graaljs'],
    'damage': listeners['default_combat_damage']['eval']['graaljs'],
}
subprocess.run(['node', str(Path(__file__).with_suffix('.cjs'))],
               input=json.dumps(payload), text=True, check=True)
print('PASS YAML: 11 definitions, 12 items, unique modifiers, synchronous listeners')

if '--runtime' in sys.argv:
    # Supply the project's GraalJS + Bukkit/Paper API + Guava dependencies.
    # The shim models only the enabled flag; Bukkit types are the real API classes.
    classpath = os.environ['COMBAT_TEST_CLASSPATH']
    java_home = os.environ.get('JAVA_HOME')
    java = str(Path(java_home) / 'bin/java') if java_home else 'java'
    javac = str(Path(java_home) / 'bin/javac') if java_home else 'javac'
    with tempfile.TemporaryDirectory(prefix='combat-runtime-') as temp:
        temp = Path(temp)
        shim = temp / 'SertralineAttributeEngine.java'
        shim.write_text('''package io.github.zzzyyylllty.sertraline.attribute;
public class SertralineAttributeEngine {
  public static final SertralineAttributeEngine INSTANCE = new SertralineAttributeEngine();
  public boolean getEnabled() { return true; }
}''', encoding='utf-8')
        scripts = []
        for name in ['snapshot', 'damage']:
            script = temp / (name + '.js')
            script.write_text(payload[name], encoding='utf-8')
            scripts.append(str(script))
        subprocess.run([javac, '-J-Xmx96m', '-cp', classpath, '-d', str(temp), str(shim),
                        str(Path(__file__).with_name('CombatRuntimeProbe.java'))], check=True)
        subprocess.run([java, '-Xms16m', '-Xmx96m', '-XX:+UseSerialGC', '-cp',
                        str(temp) + os.pathsep + classpath, 'CombatRuntimeProbe', *scripts], check=True)
