package io.github.zzzyyylllty.sertraline.logger

import io.github.zzzyyylllty.sertraline.Sertraline.allowAsyncLog
import io.github.zzzyyylllty.sertraline.Sertraline.console
import io.github.zzzyyylllty.sertraline.Sertraline.consoleSender
import io.github.zzzyyylllty.sertraline.Sertraline.isEnabled
import io.github.zzzyyylllty.sertraline.logger.infoS
import io.github.zzzyyylllty.sertraline.logger.severeS
import io.github.zzzyyylllty.sertraline.logger.warningS
import io.github.zzzyyylllty.sertraline.compat.PlatformCompat
import io.github.zzzyyylllty.sertraline.util.minimessage.legacyToMiniMessage
import io.github.zzzyyylllty.sertraline.util.minimessage.mmUtil
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import taboolib.common.platform.function.submit
import taboolib.module.lang.asLangText

val prefix = "[<gradient:#66ffff:#99ccff:#aa99cc>Sertraline</gradient>]"


fun infoL(node: String, vararg args: Any) {
    val message = console.asLangText(node, *args)
    if (ReloadCollector.current != null) consoleSender.infoSSync(message)
    else consoleSender.infoS(message)
}

fun infoLSync(node: String, vararg args: Any) {
    consoleSender.infoSSync(console.asLangText(node, *args))
}

fun severeL(node: String, vararg args: Any) {
    val message = console.asLangText(node, *args)
    if (ReloadCollector.current != null) ReloadCollector.addError(message)
    else consoleSender.severeS(message)
}

fun warningL(node: String, vararg args: Any) {
    val message = console.asLangText(node, *args)
    if (ReloadCollector.current != null) ReloadCollector.addWarning(message)
    else consoleSender.warningS(message)
}

fun CommandSender?.fineS(message: String, bothSendConsole: Boolean = false) {
    val sender = this
    submit(async = allowAsyncLog) {
        (sender ?:consoleSender).sendStringAsComponent("<gray>$prefix [<#66ffcc>FINE</#66ffcc>]</gray> <reset>$message")
        if (sender != null && bothSendConsole) consoleSender.sendStringAsComponent("<gray>$prefix [<#66ffcc>FINES</#66ffcc>]</gray> <reset>$message")
    }
}

fun CommandSender?.fineSSync(message: String, bothSendConsole: Boolean = false) {
    val sender = this
        (sender ?:consoleSender).sendStringAsComponent("<gray>$prefix [<#66ffcc>FINE</#66ffcc>]</gray> <reset>$message")
        if (sender != null && bothSendConsole) consoleSender.sendStringAsComponent("<gray>$prefix [<#66ffcc>FINES</#66ffcc>]</gray> <reset>$message")
}

fun CommandSender?.debugS(message: String, bothSendConsole: Boolean = false) {
    val sender = this
    submit(async = allowAsyncLog) {
    (sender ?:consoleSender).sendStringAsComponent("<gray>$prefix [<#ddaa77>DEBUG</#ddaa77>]</gray> <#aaaaaa>$message")
    if (sender != null && bothSendConsole) consoleSender.sendStringAsComponent("<gray>$prefix [<#ddaa77>DEBUG</#ddaa77>]</gray> <#aaaaaa>$message")
    }
}

fun CommandSender?.infoS(message: String, bothSendConsole: Boolean = false) {
    val sender = this
    submit(async = allowAsyncLog) {
        (sender ?:consoleSender).sendStringAsComponent("<gray>$prefix [<#66ccff>INFO</#66ccff>]</gray> <reset>$message")
        if (sender != null && bothSendConsole) consoleSender.sendStringAsComponent("<gray>$prefix [<#66ccff>INFOS</#66ccff>]</gray> <reset>$message")
    }
}
fun CommandSender?.infoSSync(message: String, bothSendConsole: Boolean = false) {
    val sender = this
    (sender ?:consoleSender).sendStringAsComponent("<gray>$prefix [<#66ccff>INFO</#66ccff>]</gray> <reset>$message")
    if (sender != null && bothSendConsole) consoleSender.sendStringAsComponent("<gray>$prefix [<#66ccff>INFOS</#66ccff>]</gray> <reset>$message")
}

fun CommandSender?.warningS(message: String, bothSendConsole: Boolean = false) {
    val sender = this
    submit(async = allowAsyncLog) {
    (sender ?:consoleSender).sendStringAsComponent("<gray>$prefix [<#ffee66>WARN</#ffee66>]</gray> <#eeeeaa>$message")
    if (sender != null && bothSendConsole) consoleSender.sendStringAsComponent("<gray>$prefix [<#ffee66>WARNI</#ffee66>]</gray> <#eeeeaa>$message")
    }
}

fun CommandSender?.severeS(message: String, bothSendConsole: Boolean = false) {
    val sender = this
    submit(async = allowAsyncLog) {
        (sender ?: consoleSender).sendStringAsComponent("<gray>$prefix [<#ff6600>ERROR</#ff6600>]</gray> <#ffccbb>$message")
        if (sender != null && bothSendConsole) consoleSender.sendStringAsComponent("<gray>$prefix [<#ff6600>ERROR</#ff6600>]</gray> <#ffccbb>$message")
    }
}

fun fineS(message: String) {
    submit(async = allowAsyncLog) {
        consoleSender.sendStringAsComponent("<gray>$prefix [<#66ffcc>FINE</#66ffcc>]</gray> <reset>$message")
    }
}

fun debugS(message: String) {
    submit(async = allowAsyncLog) {
        consoleSender.sendStringAsComponent("<gray>$prefix [<#ddaa77>DEBUG</#ddaa77>]</gray> <#aaaaaa>$message")
    }
}

fun infoS(message: String) {
    submit(async = allowAsyncLog) {
        consoleSender.sendStringAsComponent("<gray>$prefix [<#66ccff>INFO</#66ccff>]</gray> <reset>$message")
    }
}

fun warningS(message: String) {
    submit(async = allowAsyncLog) {
        consoleSender.sendStringAsComponent("<gray>$prefix [<#ffee66>WARN</#ffee66>]</gray> <#eeeeaa>$message")
    }
}

fun severeS(message: String) {
    submit(async = allowAsyncLog) {
        consoleSender.sendStringAsComponent("<gray>$prefix [<#ff6600>ERROR</#ff6600>]</gray> <#ffccbb>$message")
    }
}
fun debugSSync(message: String) {
    submit(async = allowAsyncLog) {
        consoleSender.sendStringAsComponent("<gray>$prefix [<#ddaa77>DEBUG</#ddaa77>]</gray> <#aaaaaa>$message")
    }
}

fun infoSSync(message: String) {
    submit(async = allowAsyncLog) {
        consoleSender.sendStringAsComponent("<gray>$prefix [<#66ccff>INFO</#66ccff>]</gray> <reset>$message")
    }
}

fun warningSSync(message: String) {
    submit(async = allowAsyncLog) {
        consoleSender.sendStringAsComponent("<gray>$prefix [<#ffee66>WARN</#ffee66>]</gray> <#eeeeaa>$message")
    }
}

fun severeSSync(message: String) {
    submit(async = allowAsyncLog) {
        consoleSender.sendStringAsComponent("<gray>$prefix [<#ff6600>ERROR</#ff6600>]</gray> <#ffccbb>$message")
    }
}

fun CommandSender.sendStringAsComponent(message: String) {
    PlatformCompat.sendComponent(this, mmUtil.deserialize(message.legacyToMiniMessage()))
}
