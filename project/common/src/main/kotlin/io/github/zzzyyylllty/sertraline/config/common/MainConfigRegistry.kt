package io.github.zzzyyylllty.sertraline.config.common

/**
 * config.yml 节点注册表（单一事实来源）。
 * 代码一律经 [SertralineSettings] 读取；文件结构由此生成。
 */
object SertralineMainConfig {

    /** config.yml schema 版本（由 SConfigSpec.upgrade 驱动迁移）。 */
    const val VERSION = 2

    /**
     * Sertraline 在 FastStats 上的项目 token（32 位小写字母/数字），作为 `metrics.token` 的默认值分发。
     *
     * 与 bStats 的 plugin id 同性质：它只是「上报到哪个项目」的公开标识，不是账号凭证，
     * 因此可以随插件一起分发。真正的退出开关是 `metrics.enabled` 与
     * `plugins/faststats/config.properties` 里的 `enabled` / `submitMetrics`。
     *
     * Sertraline's FastStats project token, shipped as the default for `metrics.token`.
     * Same nature as a bStats plugin id — a public "which project" identifier, not a credential.
     * The real opt-outs are `metrics.enabled` and `plugins/faststats/config.properties`.
     */
    const val FASTSTATS_TOKEN = "48b10c752e7ff60620bc630506d81223"

    /** 版本迁移表：key = 迁移到的版本号（2..VERSION），value = 从 v(key-1) 升到 v(key) 的迁移。 */
    val migrations: Map<Int, ConfigMigration> = mapOf(
        // v2: 宝石系统上线。旧默认值只保留 sertraline_data / sertraline_revision，
        //     缺少 sertraline_gem 会让每次 rebuild 丢掉已镶嵌的宝石。
        //     这里补进 keep-data（列表节点不会被 sync 补写，只能靠迁移）。
        2 to ConfigMigration { config ->
            val keep = config.getStringList("rebuild.keep-data").toMutableList()
            if (keep.isEmpty()) {
                config.set("rebuild.keep-data", listOf("sertraline_data", "sertraline_revision", "sertraline_gem"))
            } else if ("sertraline_gem" !in keep) {
                keep += "sertraline_gem"
                config.set("rebuild.keep-data", keep)
            }
        },
    )

    val nodes: List<SConfigNode> = listOf(
        SConfigNode("debug", false, "调试模式 debug\nDebug mode"),
        SConfigNode("lang", "en_US", "语言 language\nLanguage"),
        SConfigNode("async-logging", true, "异步日志输出\nAsync log output"),

        // ==================== 数据统计 ====================
        SConfigNode(
            "metrics.enabled", true,
            "FastStats 数据统计（类似 bStats 的匿名使用统计，https://docs.faststats.dev/java）\n"
                + "默认开启，不采集 IP / 玩家数据 / 个人信息；首次启动只提示不上报，重启后才开始提交\n"
                + "退出方式：metrics.enabled=false，或 plugins/faststats/config.properties 的 enabled=false\n"
                + "FastStats anonymous usage metrics, bStats-like (https://docs.faststats.dev/java)\n"
                + "Enabled by default; collects no IP addresses, player data or personal information. The first\n"
                + "start only prints a notice; opt out via metrics.enabled=false or the FastStats config file."
        ),
        SConfigNode(
            "metrics.token", FASTSTATS_TOKEN,
            "Sertraline 的 FastStats 项目 token（32 位小写字母/数字），随插件分发，与 bStats 的 plugin id 同性质\n"
                + "仅在要把数据上报到自己的项目时才需修改；清空则不再上报\n"
                + "Sertraline's FastStats project token, shipped with the plugin (not a secret, like a bStats plugin id)\n"
                + "Only change it to report into your own project; clearing it disables submission"
        ),
        SConfigNode(
            "metrics.error-tracking", false,
            "上报未捕获异常（需在 FastStats 项目中启用错误追踪）\n"
                + "Report uncaught exceptions (error tracking must be enabled in the project)"
        ),
        SConfigNode(
            "metrics.custom-metrics", true,
            "上报插件自定义指标（数据源需先在 FastStats 项目中按同名同类型创建）\n"
                + "Submit plugin custom metrics (data sources must exist in the project with a matching id and type)"
        ),

        // ==================== 物品更新系统 ====================
        SConfigNode(
            "item-update.enabled", true,
            "物品更新系统\nItem Update System — automatically rebuilds items when their definition changes\n"
                + "Uses sertraline_revision NBT tag to compare expected vs actual revision.\n"
                + "On mismatch, the item is fully rebuilt via itemSerializer and atomically replaced."
        ),
        SConfigNode("item-update.auto-track", true, "自动追踪物品文件变更（基于文件内容哈希）\nAuto-track item file changes based on content hash"),
        SConfigNode("item-update.auto-track-dir", "revision", "自动追踪数据存储目录 (相对于插件目录)\nAuto-track data directory (relative to plugin folder)"),
        SConfigNode("item-update.triggers.on-login", true, "玩家登录时检查全部背包（含护甲/副手/末影箱）\nCheck the whole inventory on player login (armor/offhand/ender chest included)"),
        SConfigNode("item-update.triggers.on-hold", true, "玩家切换手持物品时检查 Check when the player switches held item"),
        SConfigNode("item-update.triggers.on-interact", true, "玩家交互时检查手持物品 Check held item on player interaction"),
        SConfigNode("item-update.triggers.on-swap-hand", true, "玩家 F 键交换主副手后检查两个槽位\nCheck both hands after swapping them (F key)"),
        SConfigNode("item-update.triggers.on-pickup", true, "玩家拾取掉落物时检查（1.13+；1.12 不适用）\nCheck a dropped item when a player picks it up (1.13+; not available on 1.12)"),
        SConfigNode("item-update.triggers.on-container-open", true, "打开方块容器（箱子/木桶/潜影盒等）时检查容器内物品\nCheck container contents when a block container is opened"),
        SConfigNode("item-update.triggers.on-armor-change", true, "护甲槽变化时检查护甲物品\nCheck armor items when an armor slot changes"),
        SConfigNode("item-update.triggers.on-reload", true, "重载配置后立即刷新在线玩家\nRefresh online players right after a config reload"),
        SConfigNode(
            "item-update.triggers.async-interval", 0,
            "定时检查间隔 (单位: tick, 0=禁用)，在主线程扫描在线玩家背包\n"
                + "Periodic check (in ticks, 0=disabled); scans online players on the main thread.\n"
                + "背包越大/玩家越多开销越高，建议使用 6000 (5 分钟) 级别而不是很小的值。\n"
                + "Costs scale with players x inventory size - prefer something like 6000 (5 min)."
        ),
        SConfigNode(
            "item-update.notify", true,
            "更新时是否通知玩家（同一批检查合并为一条消息）\n"
                + "Whether to notify the player of updates (one merged message per check batch)"
        ),

        // ==================== 文件加载 ====================
        SConfigNode(
            "file-load.item", "^(?![#!]).*\\.(?i)(yaml|yml|toml|tml|json|conf)$",
            "如果你想使用 \\ 这样的转义字符（如 \\.），必须改用 \\\\ 代替。\n"
                + "If you want to use \\ like \\. , You must use \\\\. instead."
        ),
        SConfigNode("file-load.lore-format", "^(?![#!]).*\\.(?i)(yaml|yml|toml|tml|json|conf)$", ""),
        SConfigNode("file-load.mapping", "^(?![#!]).*\\.(?i)(yaml|yml|toml|tml|json|conf)$", ""),
        SConfigNode("file-load.crafting-station", "^(?![#!]).*\\.(?i)(yaml|yml|toml|tml|json|conf)$", ""),
        SConfigNode("file-load.recipe", "^(?![#!]).*\\.(?i)(yaml|yml|toml|tml|json|conf)$", ""),
        SConfigNode("file-load.tier", "^(?![#!]).*\\.(?i)(yaml|yml|toml|tml|json|conf)$", ""),
        SConfigNode("file-load.type", "^(?![#!]).*\\.(?i)(yaml|yml|toml|tml|json|conf)$", ""),
        SConfigNode("file-load.level", "^(?![#!]).*\\.(?i)(yaml|yml|toml|tml|json|conf)$", ""),

        // ==================== 预加载 ====================
        SConfigNode("preload.script.graaljs", true, "预编译脚本\nPre-compile scripts"),
        SConfigNode("preload.script.javascript", true, ""),
        SConfigNode("preload.script.jexl", true, ""),

        // ==================== 模块 ====================
        SConfigNode("modules.common.feature", true, "模块 Modules"),
        SConfigNode("modules.common.editor", false, ""),
        SConfigNode("modules.sertraline.feature", true, ""),
        SConfigNode("modules.sertraline.editor", true, ""),
        SConfigNode("modules.xbuilder.feature", true, ""),
        SConfigNode("modules.xbuilder.editor", true, ""),
        SConfigNode("modules.itemdef.feature", true, "Gun/Weapon system"),
        SConfigNode("modules.model.feature", true, "Gun/Weapon system"),
        SConfigNode("modules.mag.feature", true, "Gun/Weapon system"),
        SConfigNode("modules.ammo.feature", true, "Gun/Weapon system"),
        SConfigNode("modules.minecraft.feature", true, ""),
        SConfigNode("modules.minecraft.editor", false, ""),
        SConfigNode("modules.visual.feature", true, ""),
        SConfigNode("modules.visual.editor", false, ""),
        SConfigNode("modules.mmo.feature", true, ""),
        SConfigNode("modules.mmo.editor", true, ""),
        SConfigNode("modules.eco.feature", true, ""),
        SConfigNode("modules.eco.editor", true, ""),

        // ==================== 占位符 ====================
        SConfigNode("placeholders.plus", "+", "正负号占位符\nSign placeholders ({plus} / {minus})"),
        SConfigNode("placeholders.minus", "-", ""),

        // ==================== 物品重建保留项 ====================
        SConfigNode("rebuild.keep-data", listOf("sertraline_data", "sertraline_revision", "sertraline_gem"), "重建时保留的数据键\nNBT keys preserved on rebuild"),
        SConfigNode(
            "rebuild.keep-component",
            listOf("minecraft:damage", "minecraft:max_damage", "minecraft:repair_cost", "minecraft:enchantments"),
            "重建时保留的组件\nComponents preserved on rebuild"
        ),

        // ==================== 宝石系统 ====================
        SConfigNode(
            "gem.interaction.click.enable", true,
            "点击镶嵌总开关（手持宝石点击带槽位物品）\n"
                + "Master switch for click-to-socket (hold a gem, click a socketable item)"
        ),
        SConfigNode(
            "gem.interaction.click.types", listOf("RIGHT"),
            "允许触发镶嵌的点击方式：LEFT / RIGHT / SHIFT_LEFT / SHIFT_RIGHT / DOUBLE_CLICK\n"
                + "Click gestures that socket a gem\n"
                + "物品/槽位/宝石也可以在自身配置里用 click: false 单独禁用点击镶嵌\n"
                + "Items, sockets and gems can also opt out with click: false"
        ),
        SConfigNode(
            "gem.interaction.click.permission", "",
            "点击镶嵌所需权限，留空则不检查（命令 /sertralinegem 不受此限制）\n"
                + "Permission required for click-to-socket; blank = none (the /sertralinegem command is not affected)"
        ),

        // ==================== 发包 ====================
        SConfigNode(
            "packet.register", true,
            "是否注册包处理器\n不启用或不安装packetevents 2.10+ 所有发包功能均不会生效\n"
                + "Whether to register the packet handler\n"
                + "If not enabled or packetevents 2.10+ is not installed, all packet sending functions will not take effect"
        ),
        SConfigNode("packet.packet-lore", true, "是否启用发包Lore\nWhether to enable packet Lore"),
        SConfigNode("packet.packet-component", true, "是否启用发包组件\nWhether to enable packet component"),

        // ==================== 标签解析 ====================
        SConfigNode("tags.sertraline", true, "标签解析\nTag parsing"),
        SConfigNode("tags.papi", true, ""),
        SConfigNode("tags.val", true, ""),
        SConfigNode("tags.var", true, ""),
        SConfigNode("tags.dynamic", true, ""),
        SConfigNode("tags.kether", true, ""),

        // ==================== 属性 ====================
        SConfigNode(
            "attribute.debounce-time", 1000,
            "默认防抖时间\nDefault debounce time\n"
                + "Example: debounce-time: 250\n"
                + "updateAttribute() // Start timer\n"
                + "updateAttribute() // Reset timer\n"
                + "updateAttribute() // Reset timer from 0ms\n#\n"
                + "// after 250 ms\n"
                + "// updateAttribute execute."
        ),
        SConfigNode(
            "attribute.armor-change-polling", false,
            "Spigot 专用：模拟 Paper 的装备更换事件（属性刷新）。Paper 上自动忽略。\n"
                + "Spigot-only: simulate Paper's armor change event (attribute refresh). Ignored on Paper."
        ),

        // ==================== 动作 ====================
        SConfigNode(
            "action.throttle-time", 500,
            "默认节流时间\nDefault throttle time\n"
                + "Example: throttle-time: 500\n"
                + "runAction() // Execute ✅\n"
                + "runAction() // Ignore ❌\n"
                + "runAction() // Ignore ❌\n#\n"
                + "// after 500 ms\n"
                + "runAction() // Execute ✅"
        ),
        SConfigNode("action.throttle.onConsume", 0, "为每个动作设置不同的节流时间\n0 = 禁用节流\nSet different throttling times for each action\n0 to disable"),

        SConfigNode(
            "database.enable", false,
            "数据库类型（true: MySQL；false: SQLite）\nDatabase type (true: MySQL; false: SQLite)"
        ),
        SConfigNode("database.filename", "data", "data.db，SQLite 数据库文件名\ndata.db for SQLite Database File."),
        SConfigNode("database.host", "localhost", ""),
        SConfigNode("database.port", 3306, ""),
        SConfigNode("database.user", "", "MySQL 用户名（默认留空，启用 database 时填写）\nMySQL username (leave empty by default; fill in when database enabled)"),
        SConfigNode("database.password", "", "MySQL 密码（默认留空，启用 database 时填写）\nMySQL password (leave empty by default; fill in when database enabled)"),
        SConfigNode("database.database", "sertraline", ""),
        SConfigNode("database.table", "sertraline_data", ""),

        // ==================== 消息 ====================
        SConfigNode("messages.condition-not-met", "<red>Condition not met: {condition_name}</red>", "合成条件不满足时显示\nShown when crafting conditions are not met"),
        SConfigNode("messages.crafting-already-active", "<yellow>You already have an active crafting process!</yellow>", "已有合成进行中\nA crafting process is already in progress"),
        SConfigNode("messages.missing-ingredients", "<red>Missing ingredients: {display_name}</red>", "缺少材料\nMissing ingredients"),
        SConfigNode("messages.crafting-started", "<yellow>Crafting started... ({time} s)</yellow>", "合成开始\nCrafting started"),
        SConfigNode("messages.crafting-complete", "<green>Crafting complete!</green>", "合成完成\nCrafting complete"),
        SConfigNode("messages.common.crafting-blocked", "<red>This item cannot be used in crafting!</red>", "通用限制模块消息\nCommon restriction module messages\n合成被阻止时显示（留空或移除以禁用消息）\nMessage displayed when crafting is blocked (set empty to disable)"),
        SConfigNode("messages.common.smithing-blocked", "<red>This item cannot be used in smithing!</red>", "锻造被阻止时显示\nShown when smithing is blocked"),
        SConfigNode("messages.common.village-trade-blocked", "<red>This item cannot be traded!</red>", "与村民交易被阻止时显示\nShown when trading with villagers is blocked"),

        // ==================== 性能 ====================
        SConfigNode("performance.incremental-loading.enable", true, "增量加载配置，只加载修改过的文件\nIncremental loading, only load modified files"),
        SConfigNode(
            "performance.incremental-loading.monitor",
            listOf("item", "mapping", "lore-format", "crafting-station"),
            "监控的文件类型\nFile types to monitor"
        ),
        SConfigNode(
            "performance.adventure.use-split-replace-list-serialize", false,
            "使用将列表替换为带分割符号的单行文本以节省性能开销\n这可能带来兼容性问题，谨慎使用\n"
                + "Replace the list with a single-line text containing delimiters to reduce performance overhead\n"
                + "This may cause compatibility issues, so use it with caution"
        ),

        // ==================== 修复 ====================
        SConfigNode(
            "fixes.xbuilder.reapply-item-model", true,
            "XItemStack 相关模块未更新 ItemModel 部分，重新添加 ItemModel\n"
                + "Re-apply ItemModel since XItemStack-related modules do not update the ItemModel part"
        ),
    )
}
