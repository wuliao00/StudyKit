package com.studykit.modelpack

/**
 * 一个包在"可选包总预算"里的占位。
 *
 * 口径只有一处：[ModelPackStore.usages] 从已安装包的 manifest.sizeBytes 汇总而来。
 * 界面上的"已装可选包 X MB"与预算判定必须吃同一份数字 —— 计划 §3.4 明确要求
 * installedBytes 与 BudgetGuard 的输入是同一个函数，避免两套算法各说各话。
 */
data class PackUsage(
    val id: String,
    val displayName: String,
    val bytes: Long,
)

/**
 * 一次预算判定的完整结论。
 *
 * 界面只有两件事要做：能不能装（[allowed]）；不能装时，正文里写"先卸掉哪个"
 * （[suggestRemove]）或"卸哪个都不够、还差多少"（[needFreeBytes]）。
 * 真正的删除必须由用户确认 —— 本类不负责删、也不缓存任何状态。
 */
data class BudgetDecision(
    val allowed: Boolean,
    val installedBytes: Long,
    val newBytes: Long,
    val budgetBytes: Long,
    val needFreeBytes: Long,
    val suggestRemove: PackUsage?,
)

/**
 * 可选模型包的体积预算守卫（文档 L2220 的 ⛔ 项，计划 §3.5）。
 *
 * ## 为什么必须有它
 * 计划 §2.6 复核实测源体积：LLM 433.4 + TTS 30.1 + ASR 70.6 + StructureV3 ≈90 + 去手写 ≈45
 * ≈ 669MB（解压后更多），再加 v3.0 欠账的语义包 ≈30MB ≈ 699MB —— 文档 L2024 的
 * "597MB < 600" 用的是修正前体积，**五包全装时 600MB 预算必然被触发**。
 * 所以预算不是锦上添花，是包管理必须先在代码里守住的拒装逻辑。
 *
 * ## 判定口径（逐条被单测钉住）
 * - 已装合计 + 新包 **恰好等于**预算 → 允许（文档的动词是"超过 600MB 拒装"，= 不超）；
 * - 超 1 字节 → 拒；
 * - **重装同一个 id = 替换**：该 id 已装的旧占用不重复计入（否则升级一次就会被自己的旧包挤出预算）；
 * - 移除建议只在"能腾够"的已装包里挑**最小**的那一个（少删一点）；
 * - 没有单个已装包能腾够 → [BudgetDecision.suggestRemove] 为 null，界面走"卸哪个都不够"分支。
 */
object BudgetGuard {

    /** 600MB 上限，出自文档 L2220；单位按 1024 进（与 [ModelPackStore] 的字节统计同口径）。 */
    const val DEFAULT_BUDGET_BYTES: Long = 600L * 1024 * 1024

    private const val MB = 1024L * 1024L

    /**
     * 判定"再装 [incoming] 会不会超预算"。
     *
     * 纯函数：不读文件、不碰 Android，所有输入都由调用方摊平传入 —— 这是它能在无 runner 的
     * JVM 单测里把边界（== / +1 字节）与五包全装的真实体积账都钉住的前提。
     */
    fun check(
        installed: List<PackUsage>,
        incoming: PackUsage,
        budgetBytes: Long = DEFAULT_BUDGET_BYTES,
    ): BudgetDecision {
        require(budgetBytes > 0) { "预算必须是正数，读到 $budgetBytes" }
        require(incoming.bytes > 0) { "要装的包体积必须 > 0，读到 ${incoming.id}=${incoming.bytes}" }

        // 重装同 id：旧占用不重复算（升级是替换，不是再装一份）
        val others = installed.filter { it.id != incoming.id }
        val installedBytes = others.sumOf { it.bytes }
        val totalBytes = installedBytes + incoming.bytes
        val allowed = totalBytes <= budgetBytes
        val needFreeBytes = (totalBytes - budgetBytes).coerceAtLeast(0L)

        val suggestRemove = if (!allowed) {
            // 挑「最小且够腾」的那个：删一个包就够的话，没必要让用户多删
            others
                .filter { it.bytes >= needFreeBytes }
                .minWithOrNull(compareBy<PackUsage> { it.bytes }.thenBy { it.id })
        } else {
            null
        }

        return BudgetDecision(
            allowed = allowed,
            installedBytes = installedBytes,
            newBytes = incoming.bytes,
            budgetBytes = budgetBytes,
            needFreeBytes = needFreeBytes,
            suggestRemove = suggestRemove,
        )
    }

    /** 字节 → MB（向下取整）；预算文案统一走 [ModelPackCopy]，这里只给测试与拷贝层一个口径。 */
    fun toMb(bytes: Long): Long = bytes / MB
}
