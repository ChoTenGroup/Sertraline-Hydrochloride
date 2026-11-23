package io.github.zzzyyylllty.sertraline.util

object ItemReflections {

    private val clazz_Holder_Reference by lazy {
        Class.forName("net.minecraft.core.Holder\$Reference")
    }

    private val clazz_TagKey by lazy {
        Class.forName("net.minecraft.tags.TagKey")
    }

    val clazz_ResourceLocation by lazy {
        getClazzCompat(
            "net.minecraft.resources.Identifier",
            "net.minecraft.resources.ResourceLocation",
            "net.minecraft.resources.MinecraftKey"
        )
    }

    val clazz_ResourceKey by lazy {
        Class.forName("net.minecraft.resources.ResourceKey")
    }

    // reflection fields
    val field_Holder_Reference_tags by lazy {
        clazz_Holder_Reference.getDeclaredField("tags").apply {
            isAccessible = true
        }
    }

    val field_TagKey_location by lazy {
        clazz_TagKey.getDeclaredField("location").apply {
            isAccessible = true
        }
    }

    // reflection methods
    val method_TagKey_create by lazy {
        clazz_TagKey.getDeclaredMethod(
            "create",
            clazz_ResourceKey,
            clazz_ResourceLocation
        )
    }

    val method_ResourceLocation_fromNamespaceAndPath by lazy {
        clazz_ResourceLocation.getDeclaredMethod(
            "fromNamespaceAndPath",
            String::class.java,
            String::class.java
        )
    }
}