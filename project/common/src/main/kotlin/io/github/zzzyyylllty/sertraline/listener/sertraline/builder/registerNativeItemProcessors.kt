package io.github.zzzyyylllty.sertraline.listener.sertraline.builder

import io.github.zzzyyylllty.sertraline.Sertraline.itemManager
import io.github.zzzyyylllty.sertraline.debugMode.devLog
import io.github.zzzyyylllty.sertraline.item.adapter.chotenComplexAdapter
import io.github.zzzyyylllty.sertraline.item.adapter.chotenDataAdapter
import io.github.zzzyyylllty.sertraline.item.adapter.commonAdapter
import io.github.zzzyyylllty.sertraline.item.adapter.customDataAdapter
import io.github.zzzyyylllty.sertraline.item.adapter.minecraftAdapter
import io.github.zzzyyylllty.sertraline.item.adapter.mmoAdapter
import io.github.zzzyyylllty.sertraline.item.adapter.sertralineAdapter
import io.github.zzzyyylllty.sertraline.item.adapter.xbuilderAdapter
import io.github.zzzyyylllty.sertraline.util.VersionHelper

fun registerNativeAdapter() {

    itemManager.registerProcessor("xbuilder") { sItem, item, player ->
        devLog("Adapting xbuilder")
        xbuilderAdapter(item, sItem, player)
    }
    itemManager.registerProcessor("sertraline") { sItem, item, player ->
        devLog("Adapting sertraline")
        sertralineAdapter(item, sItem, player)
    }
    itemManager.registerProcessor("custom_data") { sItem, item, player ->
        devLog("Adapting custom_data")
        customDataAdapter(item, sItem, player)
    }
    itemManager.registerProcessor("chotenatb") { sItem, item, player ->
        devLog("Adapting chotenatb")
        chotenDataAdapter(item, sItem, player)
    }
    itemManager.registerProcessor("chotenatb_complex") { sItem, item, player ->
        devLog("Adapting chotenatb complex")
        chotenComplexAdapter(item, sItem, player)
    }
    itemManager.registerProcessor("mmo") { sItem, item, player ->
        devLog("Adapting MMO")
        mmoAdapter(item, sItem, player)
    }
    if (VersionHelper.isOrAbove12005()) itemManager.registerProcessor("minecraft") { sItem, item, player ->
        devLog("Adapting minecraft")
        minecraftAdapter(item, sItem, player)
    } else {
        devLog("Version < 12005, skipping minecraft registering")
    }

    itemManager.registerProcessor("common") { sItem, item, player ->
        devLog("Adapting common")
        commonAdapter(item, sItem, player)
    }

    // 外部插件注册的段落必须在每次重载后重新挂载，否则 /sreload 之后它们会静默失效。
    ExternalItemProcessors.applyExternalProcessors()

}