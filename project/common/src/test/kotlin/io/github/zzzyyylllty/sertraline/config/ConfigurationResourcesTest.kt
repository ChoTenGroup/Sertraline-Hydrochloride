package io.github.zzzyyylllty.sertraline.config

import io.github.zzzyyylllty.sertraline.config.common.ConfigTextUpdater
import io.github.zzzyyylllty.sertraline.config.common.SConfigNode
import io.github.zzzyyylllty.sertraline.config.common.SertralineMainConfig
import io.github.zzzyyylllty.sertraline.util.serialize.parseToMap
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ConfigurationResourcesTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun shippedYamlAndReferenceTablesParse() {
        val root = File(System.getProperty("sertraline.root"))
        val resources = File(root, "project/common-files/src/main/resources")
        val files = listOf(resources, File(root, "docs/reference")).flatMap { directory ->
            directory.walkTopDown().filter { it.isFile && it.extension in setOf("yaml", "yml") }.toList()
        }
        assertTrue(files.size >= 38)
        for (file in files) {
            val text = file.readText()
            if (text.lineSequence().all { it.isBlank() || it.trimStart().startsWith('#') }) continue
            assertNotNull(file.path, parseToMap(text, "yaml"))
        }
        val config = parseToMap(File(resources, "config.yml").readText(), "yaml")!!
        for (node in SertralineMainConfig.nodes) {
            var value: Any? = config
            for (part in node.path.split('.')) value = (value as? Map<*, *>)?.get(part)
            assertNotNull(node.path, value)
            if (node.path.startsWith("file-load.")) Regex(value.toString())
        }
        val en = parseToMap(File(resources, "lang/en_US.yml").readText(), "yaml")!!
        val zh = parseToMap(File(resources, "lang/zh_CN.yml").readText(), "yaml")!!
        assertEquals(en.keys, zh.keys)
    }

    @Test
    fun malformedAndTrailingDocumentsAreReportedByTheParser() {
        for (input in listOf("item: [broken", "- item", "one: 1\n---\ntwo: 2")) {
            assertThrows(com.fasterxml.jackson.core.JsonProcessingException::class.java) {
                parseToMap(input, "yaml")
            }
        }
        assertEquals(1, parseToMap("{\"count\":1}", "json")!!["count"])
        assertEquals(1, parseToMap("count = 1", "toml")!!["count"])
    }

    @Test
    fun configSyncNestsMissingNodesInLookupableSections() {
        // A hand-edited file may mix indentation steps (this one has a 4-space `script:` block and a 2-space
        // `preload:` block). Inferring a single file-wide step from the first deeper line made the inserted node land
        // deeper than the intended parent -- as a child of the previous scalar/section instead of the target section.
        // The node then stayed "missing" on every later pass, so each startup appended another misplaced copy.
        val config = folder.newFile("config.yml")
        config.writeText(
            "script:\n" +
                "    graaljs:\n" +
                "      allow-java-type: true\n" +
                "\n" +
                "preload:\n" +
                "  script:\n" +
                "    graaljs: true\n"
        )
        val nodes = listOf(
            SConfigNode("script.graaljs.allow-java-type", true, ""),
            SConfigNode("preload.script.jexl", true, "Pre-compile scripts"),
        )
        assertEquals(1, ConfigTextUpdater.appendMissingNodes(config, nodes, setOf("script.graaljs.allow-java-type")))

        val text = config.readText()
        assertEquals(1, Regex("^preload:$", RegexOption.MULTILINE).findAll(text).count())
        assertEquals(1, Regex("^  script:$", RegexOption.MULTILINE).findAll(text).count())
        assertEquals(
            mapOf(
                "script" to mapOf("graaljs" to mapOf("allow-java-type" to true)),
                "preload" to mapOf("script" to mapOf("graaljs" to true, "jexl" to true)),
            ),
            parseToMap(text, "yaml"),
        )

        // A second pass must be a no-op: the node is now findable where it was written, so nothing is re-appended.
        assertEquals(0, ConfigTextUpdater.appendMissingNodes(config, nodes, setOf("script.graaljs.allow-java-type", "preload.script.jexl")))
        assertEquals(text, config.readText())
    }

    @Test
    fun configSyncPreservesOriginalInBackupAndUsesOriginalLineEndings() {
        val config = folder.newFile("config.yml")
        val before = "\uFEFF# user comment\r\nexample:\r\n  value: custom\r\n"
        config.writeText(before)
        val nodes = listOf(SConfigNode("example.value", "default", ""), SConfigNode("example.extra", true, "note"))
        assertEquals(1, ConfigTextUpdater.appendMissingNodes(config, nodes, setOf("example.value")))
        assertEquals(before, File(config.parentFile, "config.yml.bak").readText())
        val after = config.readText()
        assertTrue(after.startsWith("\uFEFF# user comment\r\n"))
        assertFalse(after.replace("\r\n", "").contains('\n'))
        assertEquals(mapOf("value" to "custom", "extra" to true), parseToMap(after.removePrefix("\uFEFF"), "yaml")!!["example"])
    }
}
