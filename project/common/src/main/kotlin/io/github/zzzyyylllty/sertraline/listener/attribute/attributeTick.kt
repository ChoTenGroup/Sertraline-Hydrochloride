package io.github.zzzyyylllty.sertraline.listener.attribute

import io.github.zzzyyylllty.sertraline.attribute.SertralineAttributeEngine
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerRespawnEvent
import taboolib.common.LifeCycle
import taboolib.common.platform.Awake
import taboolib.common.platform.event.SubscribeEvent
import taboolib.common.platform.function.submit
import taboolib.common.platform.service.PlatformExecutor.PlatformTask

private var attributeTask: PlatformTask? = null

@Awake(LifeCycle.ACTIVE)
fun startAttributeTick() {
    stopAttributeTick()
    attributeTask = submit(period = 1) { SertralineAttributeEngine.pulse() }
}

internal fun stopAttributeTick() {
    attributeTask?.cancel()
    attributeTask = null
}

@SubscribeEvent
fun joinAttributes(event: PlayerJoinEvent) = SertralineAttributeEngine.requestRefresh(event.player)

@SubscribeEvent
fun respawnAttributes(event: PlayerRespawnEvent) {
    SertralineAttributeEngine.clear(event.player)
    SertralineAttributeEngine.requestRefresh(event.player)
}
