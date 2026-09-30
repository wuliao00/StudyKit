package com.studykit.ui.mistake

/**
 * 错题复习的科学规则。
 *
 * 证据落点：
 * - 检索练习优于精读解析（Roediger & Karpicke 2006；Karpicke & Blunt 2011, Science）
 *   → 默认进入「重做」而不是「看解析」。
 * - 超纠正效应（Metcalfe 2011）与错误回弹（Butler 2011）
 *   → 高置信答错的条目要排得更紧，反馈后必须安排重测。
 * - 掌握判定不能只看一次答对 → 需跨间隔连续答对。
 * - 建设性挣扎（productive struggle）→ 提示分档揭示，最后一步留给用户。
 */
object MistakeMastery {

    /** 掌握门槛：跨间隔连续答对次数 */
    const val REQUIRED_CONSECUTIVE_CORRECT = 2

    /** 挤牙膏提示的档数：线索 → 第一步 → 方法 */
    const val HINT_LEVELS = 3

    /** 高置信答错的目标保留率（更紧的重测间隔） */
    const val RETENTION_HIGH_CONFIDENCE = 0.95

    /** 常规目标保留率 */
    const val RETENTION_NORMAL = 0.90

    enum class ReviewMode { REDO, SOLUTION }

    enum class HintKind { CUE, FIRST_STEP, METHOD }

    /** 反馈分层：任务级（对不对）→ 过程级（哪步错）→ 自我调节级（下次怎么防） */
    enum class FeedbackLayer { TASK, PROCESS, ROOT_CAUSE, SELF_REGULATION }

    /** 复习流程状态 */
    data class ReviewFlow(val canRevealSolution: Boolean, val promptsRecall: Boolean)

    /** 一档提示 */
    data class Hint(val level: Int, val kind: HintKind, val label: String, val givesAwayAnswer: Boolean)

    /** 错因标签，自带自我解释提示 */
    data class Cause(val label: String, val selfExplainPrompt: String, val layer: FeedbackLayer)

    /** 变体题标记：考点不变，外壳换掉（语境变异性，促进迁移） */
    data class Variant(val conceptTag: String, val surface: String?, val isVariant: Boolean)

    /** 默认重做模式：先自己算一遍，再对照解析 */
    val defaultMode = ReviewMode.REDO

    val FEEDBACK_LAYERS = listOf(FeedbackLayer.TASK, FeedbackLayer.PROCESS, FeedbackLayer.SELF_REGULATION)

    val CAUSES = listOf(
        Cause("概念不清", "这条考点的准确定义是什么？我把它和哪个概念混了？", FeedbackLayer.ROOT_CAUSE),
        Cause("计算失误", "我是哪一步算错的？当时为什么没检查出来？", FeedbackLayer.PROCESS),
        Cause("审题偏差", "题目给的关键条件我漏看了哪一个？它限制了什么？", FeedbackLayer.PROCESS),
        Cause("记忆模糊", "我要靠什么线索把这个答案提出来，而不是靠再看一遍？", FeedbackLayer.TASK),
    )

    /** 跨间隔连续答对才算掌握：同一天连对两次只是短期表现 */
    fun isMastered(correctStreak: Int, lastGapDays: Int): Boolean =
        correctStreak >= REQUIRED_CONSECUTIVE_CORRECT && lastGapDays > 0

    /** 答对累加、答错归零 */
    fun nextStreak(previous: Int, correct: Boolean): Int = if (correct) previous + 1 else 0

    /** 未作答前不给完整解析 */
    fun reviewFlow(attempted: Boolean): ReviewFlow = ReviewFlow(
        canRevealSolution = attempted,
        promptsRecall = true,
    )

    /** 按档位取下一条提示，超出档位后停在最后一档（仍然不给最终答案） */
    fun nextHint(level: Int): Hint {
        val idx = level.coerceIn(0, HINT_LEVELS - 1)
        return when (idx) {
            0 -> Hint(1, HintKind.CUE, "线索：先确认这道题在考哪个点", givesAwayAnswer = false)
            1 -> Hint(2, HintKind.FIRST_STEP, "第一步：把已知条件写成式子或关键词", givesAwayAnswer = false)
            else -> Hint(3, HintKind.METHOD, "方法方向：该用哪条定理/思路收尾，剩下的你自己走", givesAwayAnswer = false)
        }
    }

    /** 错因标签决定反馈重点 */
    fun feedbackLayerFor(label: String): FeedbackLayer =
        CAUSES.firstOrNull { it.label == label }?.layer ?: FeedbackLayer.TASK

    /** 自动排期优先，用户手动钉过的日期不再被算法覆盖 */
    fun shouldAutoSchedule(pinnedByUser: Boolean): Boolean = !pinnedByUser

    /** 高置信错题排得更紧 */
    fun retentionFor(confident: Boolean): Double = if (confident) RETENTION_HIGH_CONFIDENCE else RETENTION_NORMAL

    /** 变体题：保留考点标签，外壳换成新情境 */
    fun variantOf(sourceTag: String, surface: String?): Variant =
        Variant(conceptTag = sourceTag, surface = surface, isVariant = !surface.isNullOrBlank())
}
