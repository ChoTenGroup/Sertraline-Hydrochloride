package io.github.zzzyyylllty.sertraline.database

import io.github.zzzyyylllty.sertraline.config.common.SertralineSettings
import taboolib.common.io.newFile
import taboolib.common.platform.function.getDataFolder
import taboolib.expansion.mapper

object DatabaseManager {

    private val dbConfig: Any by lazy {
        if (SertralineSettings.databaseEnable) {
            // MySQL 连接参数由 database 配置段承载，作为 mapper source
            SertralineSettings.databaseSection!!
        } else {
            // SQLite 文件必须落在插件数据目录（plugins/Sertraline/）下，并用 newFile 预先创建；
            // 之前直接 File("...").db 使用相对路径，SQLite 驱动会把它建到服务器工作目录甚至因父目录不存在而无法创建
            newFile(getDataFolder(), "${SertralineSettings.databaseFilename}.db")
        }
    }

    val propertyMapper by mapper<PlayerProperty>(dbConfig)
    val cooldownMapper by mapper<PlayerCooldown>(dbConfig)
    val sessionMapper by mapper<CraftingSession>(dbConfig)
    val privateItemMapper by mapper<PrivateItem>(dbConfig)

    fun init() {
        propertyMapper.count()
        cooldownMapper.count()
        sessionMapper.count()
        privateItemMapper.count()
    }

    /**
     * Closes every mapper's underlying connection/datasource.
     *
     * Nothing closed them before: `onDisable` ended without touching the database, so any write that
     * was still in flight (private-item save, player property, crafting session) was simply dropped
     * when the JVM exited — the mapper calls are blocking JDBC, not queued work.
     *
     * Each mapper is closed independently: one broken connection must not prevent the others from
     * flushing.
     */
    fun close() {
        closeQuietly("PlayerProperty") { propertyMapper.close() }
        closeQuietly("PlayerCooldown") { cooldownMapper.close() }
        closeQuietly("CraftingSession") { sessionMapper.close() }
        closeQuietly("PrivateItem") { privateItemMapper.close() }
    }

    private inline fun closeQuietly(what: String, body: () -> Unit) {
        try {
            body()
        } catch (e: Throwable) {
            io.github.zzzyyylllty.sertraline.logger.warningSSync(
                "[DatabaseManager] Failed to close $what mapper: ${e.message}"
            )
        }
    }
}
