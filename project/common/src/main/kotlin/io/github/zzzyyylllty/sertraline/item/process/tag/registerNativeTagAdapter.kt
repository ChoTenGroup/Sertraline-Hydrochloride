package io.github.zzzyyylllty.sertraline.item.process.tag

import io.github.zzzyyylllty.sertraline.Sertraline.tagManager
import io.github.zzzyyylllty.sertraline.debugMode.devLog
import io.github.zzzyyylllty.sertraline.item.process.gemSlotTagProcessor
import io.github.zzzyyylllty.sertraline.item.process.attributeTagProcessor
import io.github.zzzyyylllty.sertraline.item.process.nbtTagProcessor
import io.github.zzzyyylllty.sertraline.item.process.papiTagProcessor
import io.github.zzzyyylllty.sertraline.item.process.sertralineTagProcessor
import io.github.zzzyyylllty.sertraline.util.DependencyHelper


fun registerNativeTagAdapter() {

    if (DependencyHelper.papi) tagManager.registerProcessor("papi", listOf("papi")) { data, player, repl, target ->
        devLog("Tag adapting papi")
        papiTagProcessor(data, player, repl, target)
    }
    tagManager.registerProcessor("sertraline", listOf("val","var","dynamic","kether","context")) { data, player, repl, target ->
        devLog("Tag adapting sertraline")
        sertralineTagProcessor(data, player, repl, target)
    }
    tagManager.registerProcessor("nbt", listOf("nbt")) { data, player, repl, target ->
        devLog("Tag adapting nbt")
        nbtTagProcessor(data, player, repl, target)
    }
    tagManager.registerProcessor("gem-slot", listOf("gem-slot")) { data, player, repl, target ->
        devLog("Tag adapting gem-slot")
        gemSlotTagProcessor(data, player, repl, target)
    }
    tagManager.registerProcessor("attribute", listOf("attribute")) { data, player, repl, target ->
        devLog("Tag adapting attribute")
        attributeTagProcessor(data, player, repl, target)
    }

}
