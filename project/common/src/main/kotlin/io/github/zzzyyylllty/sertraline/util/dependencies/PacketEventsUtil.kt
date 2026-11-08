package io.github.zzzyyylllty.sertraline.util.dependencies

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerPriority
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder
import io.github.zzzyyylllty.sertraline.config.common.SertralineConfigManager
import io.github.zzzyyylllty.sertraline.config.common.SertralineSettings
import io.github.zzzyyylllty.sertraline.debugMode.devLog
import io.github.zzzyyylllty.sertraline.util.DependencyHelper
import taboolib.common.LifeCycle
import taboolib.common.platform.Awake
import taboolib.platform.util.bukkitPlugin

object PacketEventsUtil {
    @Awake(LifeCycle.LOAD)
    fun onInitPacketEvents() {
        // PacketEvents initializes before ENABLE, where SertralineConfigManager.bind() normally runs —
        // bind early so packet.register is readable from SertralineSettings
        SertralineConfigManager.bind()
        if (!DependencyHelper.pe || !SertralineSettings.packetRegister) return
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(bukkitPlugin))
        PacketEvents.getAPI().load()

        devLog("Registering packet listeners...")
        PacketEvents.getAPI().eventManager.registerListener(
            PacketEventsSendListener(), PacketListenerPriority.HIGHEST
        )
        PacketEvents.getAPI().eventManager.registerListener(
            PacketEventsReceiveListener(), PacketListenerPriority.HIGHEST
        )


    }

    @Awake(LifeCycle.ENABLE)
    fun onEnablePacketEvents() {
        if (!DependencyHelper.pe || !SertralineSettings.packetRegister) return
        //Initialize!
        PacketEvents.getAPI().init()
    }

    @Awake(LifeCycle.DISABLE)
    fun onDisablePacketEvents() {
        if (!DependencyHelper.pe || !SertralineSettings.packetRegister) return
        //Terminate the instance (clean up process)
        PacketEvents.getAPI().terminate()
    }
}