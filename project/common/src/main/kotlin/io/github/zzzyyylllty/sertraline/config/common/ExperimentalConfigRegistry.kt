package io.github.zzzyyylllty.sertraline.config.common

/**
 * experimental.yml 节点注册表。
 */
object SertralineExperimentalRegistry {

    /** experimental.yml schema 版本（由 SConfigSpec.upgrade 驱动迁移）。 */
    const val VERSION = 1

    /** 版本迁移表：key = 迁移到的版本号（2..VERSION）。 */
    val migrations: Map<Int, ConfigMigration> = emptyMap()

    val nodes: List<SConfigNode> = listOf(
        SConfigNode(
            "fast-minimessage", false,
            "使用 Sparrow MiniMessage 快速解析引擎替代原版 MiniMessage（adventure），\n"
                + "对物品名 / Lore 等高频解析场景有显著性能提升。\n"
                + "Use the Sparrow MiniMessage engine to replace the standard MiniMessage (adventure)\n"
                + "for significant performance gains in hot paths (item names / lore, etc.).\n#\n"
                + "注意：Sparrow 需要服务端 Java 21+ 且服务端自带 Adventure 4.18+（Paper/Leaf 1.21.5 及以上）。\n"
                + "服务端 Adventure 过旧时（如 Paper/Leaf 1.21.4 及更早），该选项会被自动禁用并输出警告，\n"
                + "不影响正常使用。Legacy(1.12.2) 平台不支持，此选项会被强制忽略。\n"
                + "Note: Sparrow requires Java 21+ and server-side Adventure 4.18+ (Paper/Leaf 1.21.5+).\n"
                + "On servers with an older Adventure (e.g. Paper/Leaf 1.21.4 or earlier), this option is\n"
                + "automatically disabled with a warning; the plugin keeps working normally.\n"
                + "The Legacy(1.12.2) platform is not supported and this option is forcibly ignored."
        ),
        SConfigNode(
            "fast-minimessage-cache-size", 2048,
            "解析结果缓存条数（仅 fast-minimessage 开启时生效；0 = 禁用缓存）。\n"
                + "Parse-result cache size (only effective when fast-minimessage is enabled; 0 = disabled).\n"
                + "物品模板字符串会被反复解析，命中缓存可进一步降低 CPU 开销。\n"
                + "Item template strings are parsed repeatedly; cache hits further reduce CPU overhead."
        ),
    )
}
