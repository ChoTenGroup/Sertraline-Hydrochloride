package io.github.zzzyyylllty.sertraline.attribute

import org.bukkit.attribute.AttributeInstance
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.LivingEntity
import java.util.UUID

/** Owned modifiers only. */
object AttributeVanillaSync {
    data class Entry(val target: String, val operation: String, val amount: Double)
    private data class Applied(val instance: AttributeInstance, val modifier: AttributeModifier, val entry: Entry)
    private val applied = hashMapOf<UUID, MutableMap<String, Applied>>()
    private val initialized = hashSetOf<UUID>()
    private val keyedConstructor by lazy {
        AttributeModifier::class.java.constructors.firstOrNull {
            it.parameterTypes.firstOrNull()?.name == "org.bukkit.NamespacedKey" && it.parameterCount in 3..4
        }
    }

    internal fun createModifier(key: String, amount: Double, operation: AttributeModifier.Operation): AttributeModifier {
        val uuid = modifierId(key)
        val constructor = keyedConstructor
        if (constructor != null) {
            val keyClass = constructor.parameterTypes[0]
            val namespacedKey = keyClass.getConstructor(String::class.java, String::class.java)
                .newInstance("sertraline", "attribute/$uuid")
            return if (constructor.parameterCount == 3) constructor.newInstance(namespacedKey, amount, operation) as AttributeModifier
            else constructor.newInstance(namespacedKey, amount, operation, constructor.parameterTypes[3].getField("ANY").get(null)) as AttributeModifier
        }
        @Suppress("DEPRECATION")
        return AttributeModifier(uuid, "sertraline-attribute", amount, operation)
    }

    internal fun isOwned(modifier: AttributeModifier): Boolean = runCatching {
        val key = modifier.javaClass.getMethod("getKey").invoke(modifier).toString()
        key.startsWith("sertraline:attribute/")
    }.getOrDefault(false) || modifier.name == "sertraline-attribute"

    private fun modifierId(key: String): UUID = UUID.nameUUIDFromBytes("sertraline:attribute:$key".toByteArray(Charsets.UTF_8))

    fun apply(entity: LivingEntity, entries: Map<String, Entry>) {
        apply(entity, entries, { target -> VanillaAttributes.resolve(target)?.let { VanillaAttributes.instanceOf(entity, it) } },
            { VanillaAttributes.all().mapNotNull { VanillaAttributes.instanceOf(entity, it) } })
    }

    internal fun apply(entity: LivingEntity, entries: Map<String, Entry>, lookup: (String) -> AttributeInstance?, all: () -> List<AttributeInstance>) {
        val health = lookup("max_health")
        val before = health?.value ?: 0.0
        val ratio = if (before > 0) entity.health / before else 0.0
        if (initialized.add(entity.uniqueId)) {
            val legacyIds = entries.keys.flatMap { listOf(modifierId(it), modifierId(it.removePrefix("sync/"))) }.toSet()
            for (instance in all()) {
                instance.modifiers.filter { isOwned(it) || it.uniqueId in legacyIds }.forEach { instance.removeModifier(it) }
            }
        }
        val owned = applied.getOrPut(entity.uniqueId) { linkedMapOf() }
        for ((key, old) in owned.toMap()) {
            if (entries[key] != old.entry || !old.instance.modifiers.contains(old.modifier)) {
                old.instance.removeModifier(old.modifier)
                owned.remove(key)
            }
        }
        for ((key, entry) in entries) {
            if (key in owned || !entry.amount.isFinite() || entry.amount == 0.0) continue
            val instance = lookup(entry.target) ?: continue
            val operation = when (entry.operation.substringAfter(':')) {
                "add_value" -> AttributeModifier.Operation.ADD_NUMBER
                "add_multiplied_base" -> AttributeModifier.Operation.ADD_SCALAR
                "add_multiplied_total" -> AttributeModifier.Operation.MULTIPLY_SCALAR_1
                else -> continue
            }
            val modifier = createModifier(key, entry.amount, operation)
            instance.modifiers.filter { it.uniqueId == modifier.uniqueId }.forEach { instance.removeModifier(it) }
            instance.addModifier(modifier)
            owned[key] = Applied(instance, modifier, entry)
        }
        if (owned.isEmpty()) applied.remove(entity.uniqueId)
        val after = health?.value ?: before
        if (before != after && !entity.isDead && after > 0) {
            entity.health = (after * ratio).coerceIn(0.0, after)
        }
    }

    fun clear(entity: LivingEntity) {
        apply(entity, emptyMap())
        initialized.remove(entity.uniqueId)
    }
}
