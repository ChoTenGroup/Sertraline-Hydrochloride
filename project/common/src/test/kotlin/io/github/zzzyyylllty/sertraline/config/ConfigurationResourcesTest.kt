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
