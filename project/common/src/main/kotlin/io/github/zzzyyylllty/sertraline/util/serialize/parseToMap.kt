package io.github.zzzyyylllty.sertraline.util.serialize

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.toml.TomlFactory
import com.fasterxml.jackson.module.kotlin.*
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.type.TypeReference
import java.util.concurrent.ConcurrentHashMap

private val mapperCache = ConcurrentHashMap<String, ObjectMapper>()

private val SUPPORTED_FORMATS = setOf("yaml", "toml", "json")

fun isSupportedFormat(format: String): Boolean = format in SUPPORTED_FORMATS

fun parseToMap(data: String, format: String): Map<String, Any?>? {
    require(isSupportedFormat(format)) { "Unsupported configuration format: $format" }
    val mapper = mapperCache.computeIfAbsent(format) { fmt ->
        val factory: JsonFactory = when (fmt) {
            "yaml" -> YAMLFactory()
            "toml" -> TomlFactory()
            else -> JsonFactory()
        }
        ObjectMapper(factory).registerKotlinModule().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    }
    return mapper.readValue(data, object : TypeReference<Map<String, Any?>>() {})
}