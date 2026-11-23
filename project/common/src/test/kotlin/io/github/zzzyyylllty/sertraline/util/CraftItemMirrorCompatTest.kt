package io.github.zzzyyylllty.sertraline.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*

class CraftItemMirrorCompatTest {
    @Test
    fun changesOnlyTheLegacyCallInsideTheItemBridge() {
        val owner = "org/bukkit/craftbukkit/inventory/CraftItemStack"
        val parameter = "(Lnet/minecraft/world/item/ItemStack;)"
        val old = "${parameter}L$owner;"
        val modern = "${parameter}Lorg/bukkit/inventory/ItemStack;"
        val writer = ClassWriter(0)
        writer.visit(V1_8, ACC_PUBLIC, "ExampleBridge", null, "java/lang/Object", null)
        for (method in listOf("getBukkitCopy", "unrelated")) {
            writer.visitMethod(ACC_PUBLIC or ACC_STATIC, method, modern, null, null).apply {
                visitCode()
                visitVarInsn(ALOAD, 0)
                visitMethodInsn(INVOKESTATIC, owner, "asCraftMirror", old, false)
                visitInsn(ARETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
        }

        val updated = CraftItemMirrorCompat.rewrite(writer.toByteArray())!!
        val calls = linkedMapOf<String, Pair<String, String>>()
        ClassReader(updated).accept(object : ClassVisitor(ASM9) {
            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?) =
                object : MethodVisitor(ASM9) {
                    override fun visitMethodInsn(opcode: Int, owner: String, method: String, desc: String, isInterface: Boolean) {
                        calls[name] = method to desc
                    }
                }
        }, 0)
        assertEquals(mapOf("getBukkitCopy" to ("asBukkitMirror" to modern), "unrelated" to ("asCraftMirror" to old)), calls)
        assertNull(CraftItemMirrorCompat.rewrite(updated))
    }
}
