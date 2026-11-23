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
        fun configure(allowed: Boolean?) {
            val snapshot = SettingsSnapshot.ofDefault()
            SettingsSnapshot::class.java.getDeclaredField("dynamic").also { it.isAccessible = true }
                .set(snapshot, if (allowed == null) emptyMap<String, Any?>() else mapOf("script.graaljs.allow-java-type" to allowed))
            current.set(SertralineSettings, snapshot)
        }
        try {
            configure(null) // Existing configurations without the key default to enabled.
            val first = GraalJsUtil.withBindings(emptyMap()) { context ->
                assertEquals("sertraline:test", context.eval("js",
                    "new (Java.type('org.bukkit.NamespacedKey'))('sertraline', 'test').toString()").asString())
                assertEquals(0, context.eval("js", "new (Java.type('java.util.ArrayList'))().size()").asInt())
                context
            }
            configure(false)
            GraalJsUtil.withBindings(emptyMap()) { context ->
                assertNotSame(first, context)
                assertThrows(PolyglotException::class.java) {
                    context.eval("js", "Java.type('org.bukkit.NamespacedKey')")
                }
                assertEquals(2, context.eval("js", "1 + 1").asInt())
            }
            configure(true)
            GraalJsUtil.withBindings(emptyMap()) { context ->
                assertEquals("sertraline:test", context.eval("js",
                    "new (Java.type('org.bukkit.NamespacedKey'))('sertraline', 'test').toString()").asString())
            }
        } finally {
            current.set(SertralineSettings, previous)
        }
    }
}
