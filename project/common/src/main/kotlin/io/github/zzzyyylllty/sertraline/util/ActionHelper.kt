package io.github.zzzyyylllty.sertraline.util

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.github.zzzyyylllty.sertraline.config.common.SertralineSettings
import io.github.zzzyyylllty.sertraline.debugMode.devLog
import io.github.zzzyyylllty.sertraline.event.SertralineReloadEvent
import io.github.zzzyyylllty.sertraline.function.action.applyActions
import io.github.zzzyyylllty.sertraline.listener.action.ThrottleActionLink
import io.github.zzzyyylllty.sertraline.listener.action.ThrottleActionParam
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import taboolib.common.function.ThrottleFunction
import taboolib.common.function.throttle
import taboolib.common.platform.event.SubscribeEvent
import taboolib.platform.util.isAir
import java.util.concurrent.TimeUnit


object ActionHelper {

    // Caffeine is a runtime-injected, relocated dependency that only exists from LifeCycle.INIT, while an
    // object's <clinit> runs earlier at LifeCycle.CONST. A `by lazy` lambda allocated in <clinit> makes the
    // verifier resolve the lambda's return type, so a Caffeine-typed property would throw NoClassDefFoundError
    // while the class loads. The Caffeine type therefore lives in a separate holder class that is only loaded
    // on first use (after INIT). The value type is nullable, which cannot round-trip through ConcurrentMap
    // because Kotlin snaps build()'s V to non-null — hence Cache here instead of asMap().
    private class InstanceCacheHolder {
        val cache: Cache<String, ThrottleFunction.Parameterized<ThrottleActionLink, ThrottleActionParam>?> =
            Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterAccess(1, TimeUnit.HOURS)
                .build()
    }

    private val instanceCacheHolder by lazy { InstanceCacheHolder() }

    fun throttleAction(link: ThrottleActionLink, data: ThrottleActionParam) {
        val instance = instanceCacheHolder.cache.get(link.str) {
            val time = SertralineSettings.rawLong("action.throttle.${link.str}", SertralineSettings.actionThrottleTime)
            if (time < 1) null else
            throttle<ThrottleActionLink, ThrottleActionParam>(time) { link, data ->
                if (data.bItem.isNotExist()) {
                    devLog("ItemStack is null or air or amount == 0,Skipping actions.")
                } else {
                    data.p.applyActions(link.str, data.e, data.ce, data.bItem!!, sItemId = data.sItemId)
                    if (link.subStr != null) data.p.applyActions(link.str + "@" + link.subStr, data.e, data.ce, data.bItem, sItemId = data.sItemId)
                }
            }
        }
        if (instance != null) {
            instance(link, data)
        } else {
            if (data.bItem.isNotExist()) {
                devLog("ItemStack is null or air or amount == 0,Skipping actions.")
            } else {
                data.p.applyActions(link.str, data.e, data.ce, data.bItem!!, sItemId = data.sItemId)
                if (link.subStr != null) data.p.applyActions(link.str + "@" + link.subStr, data.e, data.ce, data.bItem, sItemId = data.sItemId)
            }
        }
    }

    @SubscribeEvent
    fun resetInstances(e: SertralineReloadEvent) {
        instanceCacheHolder.cache.invalidateAll()
    }

}

fun ItemStack?.isNotExist(): Boolean {
    return this == null || this.isAir || this.amount <= 0
}