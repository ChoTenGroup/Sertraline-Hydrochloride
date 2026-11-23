package io.github.zzzyyylllty.sertraline.config

import taboolib.module.configuration.Config
import taboolib.module.configuration.Configuration

/**
 * Custom event registration config (custom-events.yml)
 *
 * It is a standalone config file separate from the custom-events section in config.yml;
 * keys are trigger names and values are the event class / priority / accept-cancelled / item-source.
 * Reference the loading approach of [io.github.zzzyyylllty.sertraline.manager.ManagerConfig].
 */
object CustomEventsConfig {

    @Config("custom-events.yml", migrate = true)
    lateinit var config: Configuration
        private set

    internal fun publish(candidate: Configuration) {
        config = candidate
    }
}
