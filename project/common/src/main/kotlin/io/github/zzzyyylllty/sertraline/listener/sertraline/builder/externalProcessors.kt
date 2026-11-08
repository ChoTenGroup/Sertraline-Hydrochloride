package io.github.zzzyyylllty.sertraline.listener.sertraline.builder

import io.github.zzzyyylllty.sertraline.Sertraline.itemManager
import io.github.zzzyyylllty.sertraline.debugMode.devLog

/**
 * 外部插件（例如 ChoTenTech）注册的物品段落处理器登记表。
 *
 * ### 为什么需要这张表
 * `Sertraline` 每次重载配置都会执行 `itemManager.unregisterAllProcessor()`，然后只重新注册
 * **内置**处理器（[registerNativeAdapter]）。如果外部插件只用 `itemManager().registerProcessor(...)`，
 * 那么任何一次 `/sreload` 之后它们的段落（`itemdef` / `model` / `mag` / `ammo` / `addon` / `armor`）
 * 都会静默失效 —— 表现为物品没有名称、描述、模型，弹匣也不再有运行时字段。
 *
 * 因此外部注册必须走 [SertralineAPI.registerItemProcessor]，登记到这里；
 * [registerNativeAdapter] 在每次重载后调用 [applyExternalProcessors] 把它们重新挂回去。
 */
object ExternalItemProcessors {

    /** 段落名 → 处理器（保持注册顺序）。 */
    private val processors = LinkedHashMap<String, ItemProcessor>()

    /** 登记（并立即挂到当前 live 的 processor 表上）。 */
    fun register(name: String, processor: ItemProcessor) {
        processors[name] = processor
        itemManager.forceRegisterProcessor(name, processor)
    }

    /** 重载后重新挂载全部外部处理器；[registerNativeAdapter] 调用。 */
    fun applyExternalProcessors() {
        if (processors.isEmpty()) return
        for ((name, processor) in processors) {
            itemManager.forceRegisterProcessor(name, processor)
        }
        devLog("Re-registered ${processors.size} external item processors: ${processors.keys}")
    }

    /** 当前登记的全部段落名（调试用）。 */
    fun names(): List<String> = processors.keys.toList()
}
