package io.github.zzzyyylllty.sertraline.config

import io.github.zzzyyylllty.sertraline.util.serialize.parseToMap
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TemplateEngineProbeTest {
    @get:Rule
    val temp = TemporaryFolder(File(".").absoluteFile.normalize())

    private val resources = File(System.getProperty("sertraline.root"), "project/common-files/src/main/resources")

    @After
    fun clearTemplates() = TemplateManager.clearTemplates()

    @Test
    fun relativeTemplateDirectoryLoadsOnConsecutiveReloads() {
        val root = temp.newFolder("plugins", "Sertraline", "templates")
        val relative = root.relativeTo(File(".").absoluteFile.normalize())
        val nested = File(root, "gear").apply { mkdir() }
        File(nested, "basic.yml").writeText("sword: {xbuilder: {material: IRON_SWORD}}")
        val item = yaml("test_sword: {§template: sword}")

        repeat(2) {
            val candidate = LoadContext()
            assertEquals(1, TemplateManager.loadTemplates(relative, candidate))
            val resolved = section(TemplateManager.resolveInMap(item, candidate)["test_sword"])
            assertEquals("IRON_SWORD", section(resolved["xbuilder"])["material"])
            TemplateManager.publish(candidate)
        }
    }

    @Test
    fun shippedTemplatesResolveWithTheProductionYamlParser() {
        val templates = File(resources, "templates").listFiles()!!.sortedBy(File::getName)
            .flatMap { parseToMap(it.readText(), "yaml").orEmpty().entries }
            .associate { (key, value) -> key to section(value) }
        TemplateManager.publishTemplates(templates)
        assertEquals(templates.size, TemplateManager.templateCount())
        for ((name, material) in mapOf(
            "wooden_melee" to "WOODEN_SWORD", "stone_melee" to "STONE_SWORD",
            "iron_melee" to "IRON_SWORD", "diamond_melee" to "DIAMOND_SWORD",
            "netherite_melee" to "NETHERITE_SWORD",
        )) {
            assertEquals(name, material, section(TemplateManager.resolveTemplate(name, emptyMap())!!["xbuilder"])["material"])
        }

        val examples = parseToMap(File(resources, "workspace/default/example_templated_items.yml").readText(), "yaml")!!
        val resolved = TemplateManager.resolveInMap(examples)
        for ((id, material) in mapOf(
            "magic_staff" to "STICK", "knight_longsword" to "DIAMOND_SWORD",
            "flame_sword" to "NETHERITE_SWORD", "forest_guardian_sword" to "WOODEN_SWORD",
            "clean_blade" to "GOLDEN_SWORD",
        )) {
            assertEquals(id, material, section(section(resolved[id])["xbuilder"])["material"])
        }
        assertEquals("ECHO HELMET", section(section(resolved["echo_helmet"])["xbuilder"])["name"])
        assertEquals("1.55", attribute(resolved, "light_dagger", "ATTACK_SPEED"))
        assertEquals("13", attribute(resolved, "precision_blade", "ATTACK_DAMAGE"))
        assertEquals(listOf("<gold>No template lore here."), section(section(resolved["clean_blade"])["xbuilder"])["lore"])
        val consumable = resolve("potion: {§template: consumable_item, sertraline: {tier: S}}")
        assertNotNull(section(section(consumable["potion"])["sertraline"])["actions"])
        assertEquals("Token #1", section(section(resolved["numbered_token"])["xbuilder"])["name"])
        assertEquals("Token #2", section(section(TemplateManager.resolveInMap(examples)["numbered_token"])["xbuilder"])["name"])
    }

    @Test
    fun nestedOverridesUseArgumentsAndCountersAreSampledOnce() {
        publish("""
            base:
              xbuilder:
                material: IRON_SWORD
                name: "Token #%{n}"
                lore: ["first", "serial %{n}"]
            outer:
              §template:
                use: base
                arguments:
                  n:
                    §t: {type: self_increase, from: "%{start}", step: 2}
              sertraline: {tier: "%{tier}"}
              xbuilder:
                lore: ["second"]
        """)
        val resolved = resolve("""
            item1:
              §template:
                use: outer
                arguments: {start: 4, tier: S}
            item2:
              §template:
                use: outer
                arguments: {start: 4, tier: A}
              §list-mode: replace
              xbuilder: {lore: ["replacement"]}
        """)
        val first = section(resolved["item1"])
        assertEquals("S", section(first["sertraline"])["tier"])
        assertEquals("Token #4", section(first["xbuilder"])["name"])
        assertEquals(listOf("first", "serial 4", "second"), section(first["xbuilder"])["lore"])
        val second = section(section(resolved["item2"])["xbuilder"])
        assertEquals("Token #6", second["name"])
        assertEquals(listOf("replacement"), second["lore"])
        assertEquals("IRON_SWORD", second["material"])
    }

    @Test
    fun converterInputsFallbacksAndPlaceholderEscapes() {
        publish("""
            base:
              input: "%{value?:10}"
              literal: '\{literal\}\?:'
              fallback: '%{missing?:a \} b}'
            inherited:
              §template:
                use: base
                arguments:
                  value:
                    §c: {type: "%{kind}", round: "%{round}"}
        """)
        val resolved = resolve("""
            converted:
              §template:
                use: inherited
                arguments: {value: 3.2, kind: int, round: ceil}
            invalid:
              §template:
                use: base
                arguments:
                  value:
                    §c: {type: int, value: invalid}
            explicit:
              §template:
                use: base
                arguments:
                  value:
                    §c: {type: string, case: uppercase, value: echo}
            untyped:
              §template:
                use: base
                arguments:
                  value: [1, 2]
        """)
        assertEquals("4", section(resolved["converted"])["input"])
        assertEquals("10", section(resolved["invalid"])["input"])
        assertEquals("ECHO", section(resolved["explicit"])["input"])
        assertEquals("10", section(resolved["untyped"])["input"])
        assertEquals("""\{literal\}\?:""", section(resolved["invalid"])["literal"])
        assertEquals("a } b", section(resolved["invalid"])["fallback"])
    }

    @Test
    fun discardedCandidateDoesNotLeakTemplatesAndCycleTerminates() {
        publish("""
            one: {§template: two}
            two: {§template: one}
            stable: {value: old}
        """)
        val candidate = LoadContext()
        candidate.templates["stable"] = mapOf("value" to "new")
        val raw = yaml("item: {§template: stable}")
        assertEquals("new", section(TemplateManager.resolveInMap(raw, candidate)["item"])["value"])
        assertEquals("old", section(TemplateManager.resolveInMap(raw)["item"])["value"])
        assertTrue(TemplateManager.resolveTemplate("one", emptyMap()).orEmpty().isEmpty())
    }

    private fun attribute(items: Map<String, Any?>, item: String, name: String): Any? =
        section(section(section(section(items[item])["xbuilder"])["attributes"])[name])["amount"]

    private fun publish(input: String) = TemplateManager.publishTemplates(yaml(input).mapValues { section(it.value) })
    private fun resolve(input: String) = TemplateManager.resolveInMap(yaml(input))
    private fun yaml(input: String) = parseToMap(input.trimIndent().replace('§', '$'), "yaml")!!

    @Suppress("UNCHECKED_CAST")
    private fun section(value: Any?): Map<String, Any?> = value as Map<String, Any?>
}
