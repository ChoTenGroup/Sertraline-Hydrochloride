package io.github.zzzyyylllty.sertraline.logger

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 单次 reload 的独立报告上下文。
 *
 * errors/warnings/stats 只属于这一轮 reload；由 [ReloadCollector] 委托写入，
 * 由 [ReloadCoordinator] 在一轮开始时创建并绑定、结束时解绑。
 *
 * 使用 CopyOnWriteArrayList 保证多线程写入安全（loader 的 submitAsync 路径
 * 可能并发调用 addError/addWarning）。
 */
class ReloadContext(val id: Long) {

    val errors = CopyOnWriteArrayList<String>()
    val warnings = CopyOnWriteArrayList<String>()
    val stats = CopyOnWriteArrayList<String>()

    @Volatile
    var summaryPrinted: Boolean = false
        private set

    /** 标记本轮 summary 已输出，保证 printSummary 幂等（每轮只打印一次）。 */
    fun markSummaryPrinted() {
        summaryPrinted = true
    }
}

/**
 * reload 串行化协调器。
 *
 * gate 保证同一时间最多一轮 reload 在执行；并发请求直接被拒绝（返回 null），
 * 不会启动第二个并发「清空 + 重建」周期，也不会触碰正在运行轮次的报告数据。
 */
object ReloadCoordinator {

    private val gate = AtomicBoolean(false)
    private val counter = AtomicLong(0)

    /** 是否有 reload 正在执行。 */
    val isActive: Boolean get() = gate.get()

    /**
     * 尝试开始一轮 reload。
     *
     * @return 新建的 [ReloadContext]（已绑定到 [ReloadCollector]）；已有 reload 进行中时返回 null。
     */
    fun tryBegin(): ReloadContext? {
        if (!gate.compareAndSet(false, true)) return null
        return ReloadContext(counter.incrementAndGet()).also { ReloadCollector.bind(it) }
    }

    /** 结束当前轮并释放 gate（仅由持有 gate 的 reload 流程调用）。 */
    fun end() {
        ReloadCollector.unbind()
        gate.set(false)
    }
}
