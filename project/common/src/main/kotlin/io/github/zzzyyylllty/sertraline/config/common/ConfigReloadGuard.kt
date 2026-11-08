package io.github.zzzyyylllty.sertraline.config.common

import io.github.zzzyyylllty.sertraline.logger.severeL
import taboolib.library.configuration.ConfigurationSection
import taboolib.module.configuration.Configuration

/**
 * TabooLib [Configuration.reload] 是「先 clear 再 parse」：文件损坏时内存配置已被清空且抛异常，
 * 运行时随后读到的全是默认值（静默降级）。此扩展把「磁盘 → 内存」做成最小事务：
 *
 * 1. reload 前把内存配置深拷贝为快照；
 * 2. reload 成功 → 直接用新内容；
 * 3. reload 失败 → 用快照恢复内存配置，保持 last-good，并输出 severe。
 *
 * 快照使用 [Configuration.loadFromString] 重建，不依赖底层实现细节。
 */
object ConfigReloadGuard {

    fun reloadSafely(config: Configuration, fileName: String) {
        val snapshot = try {
            config.saveToString()
        } catch (_: Exception) {
            null
        }
        try {
            config.reload()
        } catch (e: Exception) {
            severeL("Config_Load_Error_Parse", fileName, e.message ?: "Unknown error")
            if (snapshot != null) {
                try {
                    config.loadFromString(snapshot)
                    severeL("Config_Reload_Recovered", fileName)
                } catch (e2: Exception) {
                    severeL("Config_Reload_Recover_Failed", fileName, e2.message ?: "Unknown error")
                }
            }
        }
    }
}
