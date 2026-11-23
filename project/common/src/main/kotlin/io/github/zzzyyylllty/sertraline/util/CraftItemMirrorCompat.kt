package io.github.zzzyyylllty.sertraline.util

import org.bukkit.Bukkit
import org.bukkit.inventory.ItemStack
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import taboolib.common.BinaryCache
import taboolib.module.nms.MinecraftVersion
import taboolib.module.nms.NMSItemTag
import taboolib.module.nms.NMSTranslate
import taboolib.module.nms.remap.RemapTranslation

/** Adapts the two pinned TabooLib item bridges on servers that removed the old CraftItemStack mirror. */
internal object CraftItemMirrorCompat {
    private const val OWNER = "org/bukkit/craftbukkit/inventory/CraftItemStack"
    private const val PARAMETER = "(Lnet/minecraft/world/item/ItemStack;)"
    private const val OLD_DESCRIPTOR = "$PARAMETER" + "L$OWNER;"
    private const val NEW_DESCRIPTOR = "$PARAMETER" + "Lorg/bukkit/inventory/ItemStack;"

    private val bridgeNames = setOf(NMSItemTag::class.java.name + "Impl26", NMSTranslate::class.java.name + "Impl")

    fun install() {
        if (!MinecraftVersion.isUnobfuscated) return
        val loader = Bukkit.getServer().javaClass.classLoader
        val craft = Class.forName(OWNER.replace('/', '.'), false, loader)
        val nmsStack = Class.forName("net.minecraft.world.item.ItemStack", false, loader)
        val legacy = craft.methods.any {
            it.name == "asCraftMirror" && it.returnType == craft && it.parameterTypes.contentEquals(arrayOf(nmsStack))
        }
        if (legacy) return
        val modern = runCatching { craft.getMethod("asBukkitMirror", nmsStack) }.getOrNull()
        if (modern?.returnType != ItemStack::class.java) return

        RemapTranslation.extraTransformers.add { name, bytes ->
            if (name in bridgeNames) rewrite(bytes) else null
        }

        // TabooLib loads cached classes before invoking its transformer pipeline.
        for (name in bridgeNames) {
            val entry = "remap/$name"
            val cache = BinaryCache.getCacheFile().resolve("binary/$entry.cache")
            if (cache.isFile && runCatching { rewrite(cache.readBytes()) != null }.getOrDefault(true)) {
                BinaryCache.drop(entry)
            }
        }
    }

    internal fun rewrite(bytes: ByteArray): ByteArray? {
        val reader = ClassReader(bytes)
        val writer = ClassWriter(reader, 0)
        var changed = false
        reader.accept(object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitMethod(
                access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?,
            ): MethodVisitor {
                val visitor = super.visitMethod(access, name, descriptor, signature, exceptions)
                if (name != "getBukkitCopy") return visitor
                return object : MethodVisitor(Opcodes.ASM9, visitor) {
                    override fun visitMethodInsn(opcode: Int, owner: String, method: String, desc: String, isInterface: Boolean) {
                        if (opcode == Opcodes.INVOKESTATIC && owner == OWNER && method == "asCraftMirror" && desc == OLD_DESCRIPTOR) {
                            changed = true
                            super.visitMethodInsn(opcode, owner, "asBukkitMirror", NEW_DESCRIPTOR, isInterface)
                        } else {
                            super.visitMethodInsn(opcode, owner, method, desc, isInterface)
                        }
                    }
                }
            }
        }, 0)
        return if (changed) writer.toByteArray() else null
    }
}
