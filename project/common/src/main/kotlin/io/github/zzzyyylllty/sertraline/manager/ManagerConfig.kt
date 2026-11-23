package io.github.zzzyyylllty.sertraline.manager

import taboolib.module.configuration.Config
import taboolib.module.configuration.Configuration

object ManagerConfig {

    @Config("manager.yml", migrate = true)
    lateinit var config: Configuration
        private set

    internal fun publish(candidate: Configuration) {
        config = candidate
    }

    val defaultScope: String get() = config.getString("defaults.scope", "public") ?: "public"
    val defaultSub: String get() = config.getString("defaults.sub", "persistent") ?: "persistent"
    val allowDeletePublicPersistent: Boolean get() = config.getBoolean("allow-delete-public-persistent", false)
    val deleteFile: Boolean get() = config.getBoolean("delete-file", false)
    val autoUuid: String get() = config.getString("private.auto-uuid", "") ?: ""
}
