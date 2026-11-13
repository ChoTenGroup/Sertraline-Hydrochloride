package io.github.zzzyyylllty.sertraline.attribute

import io.github.zzzyyylllty.sertraline.debugMode.devLog
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeInstance
import org.bukkit.entity.LivingEntity
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves vanilla `Attribute` instances by name.
 *
 * Reflection only at resolve time: the `legacy12` variant compiles against the 1.12.2 API, where
 * `Attribute` is an enum and `Registry.ATTRIBUTE` does not exist. `getAttribute` / `setBaseValue`
 * exist on every version, so those are called directly.
 */
object VanillaAttributes {

    private val cache = ConcurrentHashMap<String, Any>()
    private val NONE = Any()

    private val registryHandle: Any? by lazy {
        runCatching { Class.forName("org.bukkit.Registry").getField("ATTRIBUTE").get(null) }.getOrNull()
    }

    private val registryGet by lazy {
        registryHandle?.javaClass?.methods?.firstOrNull { it.name == "get" && it.parameterTypes.size == 1 }
    }

    /** `Registry` lookup (1.21+), then enum constants `GENERIC_<NAME>` / `<NAME>` (1.8-1.20). */
    fun resolve(rawName: String): Attribute? {
        val vanillaName = rawName.lowercase().removePrefix("minecraft:").removePrefix("generic.")
        cache[vanillaName]?.let { return if (it === NONE) null else it as Attribute }

        val resolved = tryRegistry(vanillaName) ?: tryEnum(vanillaName)
        cache[vanillaName] = resolved ?: NONE
        devLog("VanillaAttributes: '$vanillaName' -> ${resolved ?: "unavailable"}")
        return resolved
    }

    private fun tryRegistry(name: String): Attribute? {
        val registry = registryHandle ?: return null
        val get = registryGet ?: return null
        val key = namespacedKey(name) ?: return null
        return runCatching { get.invoke(registry, key) as? Attribute }.getOrNull()
            // Pre-1.21 registry names carry the generic. prefix.
            ?: if (name.contains('.')) null else namespacedKey("generic.$name")?.let {
                runCatching { get.invoke(registry, it) as? Attribute }.getOrNull()
            }
    }

    private fun namespacedKey(name: String): Any? = runCatching {
        val keyClass = Class.forName("org.bukkit.NamespacedKey")
        val factory = keyClass.methods.firstOrNull {
            it.name == "minecraft" && it.parameterTypes.firstOrNull() == String::class.java
        }
        factory?.invoke(null, name)
            ?: keyClass.getConstructor(String::class.java, String::class.java)
                .newInstance("minecraft", name)
    }.getOrNull()

    private fun tryEnum(name: String): Attribute? {
        val constants = try {
            @Suppress("UNCHECKED_CAST")
            val enumClass = Class.forName("org.bukkit.attribute.Attribute") as? Class<out Enum<*>>
            enumClass?.enumConstants
        } catch (_: Throwable) {
            null
        } ?: return null

        val wanted = name.uppercase().replace('.', '_')
        return (constants.firstOrNull { it.name == "GENERIC_$wanted" }
            ?: constants.firstOrNull { it.name == wanted }) as? Attribute
    }

    fun instanceOf(entity: LivingEntity, attribute: Attribute): AttributeInstance? =
        runCatching { entity.getAttribute(attribute) }.getOrNull()

    fun all(): List<Attribute> = (registryHandle as? Iterable<*>)?.filterIsInstance<Attribute>()
        ?: Attribute::class.java.enumConstants?.toList()
        ?: emptyList()

    fun typeDefault(entity: LivingEntity, attribute: Attribute): Double? = runCatching {
        val defaults = entity.type.javaClass.getMethod("getDefaultAttributes").invoke(entity.type)
            as org.bukkit.attribute.Attributable
        defaults.getAttribute(attribute)?.baseValue
    }.getOrNull()
}
