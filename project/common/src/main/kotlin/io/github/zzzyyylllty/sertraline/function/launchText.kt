package io.github.zzzyyylllty.sertraline.function

import io.github.zzzyyylllty.sertraline.Sertraline.console
import io.github.zzzyyylllty.sertraline.Sertraline.consoleSender
import io.github.zzzyyylllty.sertraline.logger.sendStringAsComponent
import io.github.zzzyyylllty.sertraline.util.VersionHelper
import org.bukkit.command.CommandSender
import taboolib.common.LifeCycle
import taboolib.common.platform.Awake
import taboolib.common.platform.function.pluginVersion
import taboolib.common.platform.function.runningPlatform
import taboolib.module.lang.asLangText
import taboolib.module.nms.MinecraftVersion.versionId
import taboolib.platform.util.asLangText

// ⬛🟦🟥🟫🟧🟨🟩🟪⬜
private val characterDrat = """
    ⬜⬜⬜⬜
    ⬜⬜�?
    ⬜⬜
""".trimIndent()

@Awake(LifeCycle.ENABLE)
fun launchText() {

    val premiumDisplayName = if (VersionHelper.isSertralinePremium) {
        "<gradient:yellow:gold>" + console.asLangText("PremiumVersion")
    } else {
        "<gradient:green:aqua>" + console.asLangText("FreeVersion")
    }

    val specialThanks = listOf("MAORI","NK_XingChen","Jesuzi","Blue_ruins(BlueIce)","Zero","TheAchu","CedricHunsen")

    consoleSender.sendStringAsComponent(" ")
    consoleSender.sendStringAsComponent("<gradient:#bbeeff:#99ccff:#aa99cc:#9966aa>|  $$$$$$\\                        $$\\                         $$\\ $$\\                      |")
    consoleSender.sendStringAsComponent("<gradient:#bbeeff:#99ccff:#aa99cc:#9966aa>| $$  __$$\\                       $$ |                        $$ |\\__|                     |")
    consoleSender.sendStringAsComponent("<gradient:#bbeeff:#99ccff:#aa99cc:#9966aa>| $$ /  \\__| $$$$$$\\   $$$$$$\\  $$$$$$\\    $$$$$$\\   $$$$$$\\  $$ |$$\\ $$$$$$$\\   $$$$$$\\   |")
    consoleSender.sendStringAsComponent("<gradient:#bbeeff:#99ccff:#aa99cc:#9966aa>| \\$$$$$$\\  $$  __$$\\ $$  __$$\\ \\_$$  _|  $$  __$$\\  \\____$$\\ $$ |$$ |$$  __$$\\ $$  __$$\\  |")
    consoleSender.sendStringAsComponent("<gradient:#bbeeff:#99ccff:#aa99cc:#9966aa>|  \\____$$\\ $$$$$$$$ |$$ |  \\__|  $$ |    $$ |  \\__| $$$$$$$ |$$ |$$ |$$ |  $$ |$$$$$$$$ | |")
    consoleSender.sendStringAsComponent("<gradient:#bbeeff:#99ccff:#aa99cc:#9966aa>| $$\\   $$ |$$   ____|$$ |        $$ |$$\\ $$ |      $$  __$$ |$$ |$$ |$$ |  $$ |$$   ____| |")
    consoleSender.sendStringAsComponent("<gradient:#bbeeff:#99ccff:#aa99cc:#9966aa>| \\$$$$$$  |\\$$$$$$$\\ $$ |        \\$$$$  |$$ |      \\$$$$$$$ |$$ |$$ |$$ |  $$ |\\$$$$$$$\\  |")
    consoleSender.sendStringAsComponent("<gradient:#bbeeff:#99ccff:#aa99cc:#9966aa>|  \\______/  \\_______|\\__|         \\____/ \\__|       \\_______|\\__|\\__|\\__|  \\__| \\_______| |")
    consoleSender.sendStringAsComponent(" ")
    consoleSender.sendStringWithPrefix("<dark_aqua>",consoleSender.asLangText("WelcomeSeries"))
    consoleSender.sendStringWithPrefix("<dark_aqua>",consoleSender.asLangText("DesignBy", "<#ff66cc>AkaCandyKAngel</#ff66cc>"))
    consoleSender.sendStringWithPrefix("<dark_aqua>",consoleSender.asLangText("SpecialThanks","<aqua>[<dark_aqua>${specialThanks.joinToString("<dark_gray>, </dark_gray>")}<aqua>]"))
    consoleSender.sendStringWithPrefix("<dark_aqua>",consoleSender.asLangText("PoweredBy", "<#66ccff>TabooLib <gold>6.2"))
    consoleSender.sendStringAsComponent(" ")
    consoleSender.sendStringWithPrefix("<#88ccff>", console.asLangText("Welcome1"))
    consoleSender.sendStringWithPrefix("<#88ccff>", console.asLangText("Welcome2", premiumDisplayName, "$pluginVersion<reset>", "${runningPlatform.name} - $versionId"))
    consoleSender.sendStringAsComponent(" ")
    consoleSender.sendStringWithPrefix("<#66bbff>", console.asLangText("Welcome3", "https://github.com/zzzyyylllty"))
    consoleSender.sendStringWithPrefix("<#66bbff>", console.asLangText("Welcome4", "https://github.com/zzzyyylllty/Sertraline-Hydrochloride"))
    consoleSender.sendStringWithPrefix("<#66bbff>", console.asLangText("Welcome5", "https://chotengroup.gitbook.io/sertraline"))
    consoleSender.sendStringAsComponent(" ")
    if (VersionHelper.isSertralinePremium) consoleSender.sendStringWithPrefix("<gradient:red:yellow:green:aqua:light_purple>", console.asLangText("PremiumVersionWelcome", premiumDisplayName))
    consoleSender.sendStringAsComponent(" ")

}

private fun CommandSender.sendStringWithPrefix(prefix: String, message: String) {
    this.sendStringAsComponent(prefix + message)
}