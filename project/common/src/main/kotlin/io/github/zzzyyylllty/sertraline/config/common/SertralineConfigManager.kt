package io.github.zzzyyylllty.sertraline.config.common

import io.github.zzzyyylllty.sertraline.logger.warningL
import taboolib.module.configuration.Configuration

/**
 * 普通配置文件统一入口。
 *
 * - 释放/加载 config.yml、experimental.yml
 * - 按注册表自动补写缺失节点（配置自动更新）
 * - 每轮构建不可变 [SettingsSnapshot] 并整体发布到 [SertralineSettings]
 *
 * 在插件 ENABLE 周期最前调用 [bind]；reload 时调用 [reload]。
 */
object SertralineConfigManager {

    val main = SConfigSpec(
        "config.yml",
        SertralineMainConfig.nodes,
        version = SertralineMainConfig.VERSION,
        migrations = SertralineMainConfig.migrations,
    )
    val experimental = SConfigSpec(
        "experimental.yml",
        SertralineExperimentalRegistry.nodes,
        version = SertralineExperimentalRegistry.VERSION,
        migrations = SertralineExperimentalRegistry.migrations,
    )

    @Volatile
    private var bound = false

    /** 启动期捕获的 restart-only 数据库快照（连接/mapper 为 lazy 单例，重建需重启）。 */
    @Volatile
    private var startupDatabaseSnapshot: DatabaseStartupValues? = null

    /** 数据库 restart-only 值集合（与 DatabaseManager lazy dbConfig 实际消费的路径一致）。 */
    private class DatabaseStartupValues(
        val enable: Boolean,
        val filename: String,
    )

    /** 绑定全部配置文件（幂等：LOAD 周期 PacketEventsUtil 早绑定后，ENABLE 周期再次调用直接跳过）。 */
    fun bind() {
        if (bound) return
        synchronized(this) {
            if (bound) return
            main.bind()
            experimental.bind()
            SertralineSettings.load(main, experimental)
            startupDatabaseSnapshot = DatabaseStartupValues(
                enable = SertralineSettings.databaseEnable,
                filename = SertralineSettings.databaseFilename,
            )
            bound = true
        }
    }

    fun reload() {
        // reloadSafely: TabooLib reload 是「先 clear 再 parse」，文件损坏时内存配置会被清空；
        // 这里先快照旧内容，失败时恢复 last-good，保证 SertralineSettings 不会静默降级为默认值
        ConfigReloadGuard.reloadSafely(main.config, main.fileName)
        // 运行期 reload 同样执行版本迁移（服务端热升级插件后生效），再补写缺失节点
        main.upgrade()
        // Re-run sync on every reload so newly-registered nodes from a plugin upgrade
        // get appended to the on-disk file (preserving existing user values/comments).
        main.sync()
        ConfigReloadGuard.reloadSafely(experimental.config, experimental.fileName)
        experimental.upgrade()
        experimental.sync()
        SertralineSettings.load(main, experimental)
        checkRestartOnlyChanges()
    }

    /**
     * restart-only 检测：packet.register（PacketEvents 生命周期在启动期决定）与
     * database.*（mapper 连接为 lazy 单例）在 reload 中不会重建。检测到启动值
     * 与当前文件不一致时输出提示，不热切换。
     */
    private fun checkRestartOnlyChanges() {
        val current = SertralineSettings.currentSnapshot()
        val startup = startupDatabaseSnapshot
        if (startup != null) {
            val changedDb = startup.enable != current.databaseEnable || startup.filename != current.databaseFilename
            if (changedDb) {
                warningL("Config_Restart_Required", "database")
            }
        }
        // packet.register=false 时 PacketEvents 在启动期未初始化；运行期改回 true 不会生效
        //（反向：启动 true 运行期改 false 只是不再被 packet handler 检查，无生命周期问题，无需提示）
    }

    /** 兼容旧调用点：主配置对象。 */
    @Deprecated("Use SertralineSettings (snapshot) or SertralineConfigManager.main instead")
    val config: Configuration get() = main.config
}
