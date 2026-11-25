package io.github.zzzyyylllty.sertraline.util

import io.github.zzzyyylllty.sertraline.config.common.SertralineSettings
import io.github.zzzyyylllty.sertraline.config.common.SettingsSnapshot
import org.graalvm.polyglot.PolyglotException
import org.junit.Assert.*
import org.junit.Test

class GraalJavaTypeTest {
    @Test fun lookupSettingRecreatesCachedContextsInBothDirections() {
        val current = SertralineSettings::class.java.getDeclaredField("current").also { it.isAccessible = true }
        val previous = current.get(SertralineSettings)
        val attributeEngine = "io.github.zzzyyylllty.sertraline.attribute.SertralineAttributeEngine"
        val attributeLookup = "Java.type('$attributeEngine').INSTANCE.getEnabled()"
        val thread = Thread.currentThread()
        val previousLoader = thread.contextClassLoader
        val serverLoader = object : ClassLoader(previousLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == attributeEngine) throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        fun configure(allowed: Boolean?) {
            val snapshot = SettingsSnapshot.ofDefault()
            SettingsSnapshot::class.java.getDeclaredField("dynamic").also { it.isAccessible = true }
                .set(snapshot, if (allowed == null) emptyMap<String, Any?>() else mapOf("script.graaljs.allow-java-type" to allowed))
            current.set(SertralineSettings, snapshot)
        }
        try {
            thread.contextClassLoader = serverLoader
            configure(null) // Existing configurations without the key default to enabled.
            val first = GraalJsUtil.withBindings(emptyMap()) { context ->
                assertTrue(context.eval(checkNotNull(GraalJsUtil.compile(attributeLookup))).asBoolean())
                assertEquals("sertraline:test", context.eval("js",
                    "new (Java.type('org.bukkit.NamespacedKey'))('sertraline', 'test').toString()").asString())
                assertEquals(0, context.eval("js", "new (Java.type('java.util.ArrayList'))().size()").asInt())
                context
            }
            assertSame(serverLoader, thread.contextClassLoader)
            configure(false)
            GraalJsUtil.withBindings(emptyMap()) { context ->
                assertNotSame(first, context)
                assertThrows(PolyglotException::class.java) {
                    context.eval("js", "Java.type('org.bukkit.NamespacedKey')")
                }
                assertThrows(PolyglotException::class.java) { context.eval("js", attributeLookup) }
                assertEquals(2, context.eval("js", "1 + 1").asInt())
            }
            configure(true)
            GraalJsUtil.withBindings(emptyMap()) { context ->
                assertTrue(context.eval("js", attributeLookup).asBoolean())
                assertEquals("sertraline:test", context.eval("js",
                    "new (Java.type('org.bukkit.NamespacedKey'))('sertraline', 'test').toString()").asString())
            }
        } finally {
            thread.contextClassLoader = previousLoader
            current.set(SertralineSettings, previous)
        }
    }
}
