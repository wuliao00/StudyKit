package com.studykit.srs

import com.studykit.util.Time
import java.time.LocalDate

/**
 * 记忆看板：今天/明天要复习多少、当前整体预测保留率。
 *
 * 取代原先「明天没有排期」这类无信息文案——把调度器的状态直接讲给用户看。
 */
data class MemoryBoard(
    val dueToday: Int,
    val dueTomorrow: Int,
    val total: Int,
    val predictedRetention: Double,
)

/**
 * 在 FSRS 之上做复习调度决策：排序优先级、目标保留率、交错编排、看板聚合。
 *
 * 两条来自证据的设计：
 * - 超纠正效应（Metcalfe 2011/2017）：高置信答错的项目最难纠正也最容易被纠正，
 *   因此给更高的目标保留率（排得更紧）与更大的队列优先级。
 * - 交错练习（Brunmair & Richter 2019 / Kornell & Bjork 2008）：相似材料混排提升辨别，
 *   所以 [interleave] 会把同学科/同题型的条目打散。
 */
object ReviewPlanner {

    /** 高置信错题的目标保留率（比正常更紧，尽快重测以阻断错误回弹） */
    const val RETENTION_HIGH_CONFIDENCE_ERROR = 0.95

    /** 正常目标保留率 */
    const val RETENTION_DEFAULT = Fsrs.DEFAULT_RETENTION

    /** 错题队列的优先级权重：欠账（1-R）与置信度风险的权重 */
    private const val OVERDUE_WEIGHT = 2.0

    /** 自评信心与答错组合出的附加优先级（瞎猜答错不需要额外催促） */
    private fun confidenceRisk(confidence: Confidence, correct: Boolean): Double {
        if (correct) return 0.0
        return when (confidence) {
            Confidence.SURE -> 0.30
            Confidence.VAGUE -> 0.15
            Confidence.GUESS -> 0.0
        }
    }

    /**
     * 队列优先级，数值越大越该先做。
     * 欠账（预测保留率跌破 90%）优先，其次是被高置信答错标记的项目。
     */
    fun priority(
        state: MemoryState,
        confidence: Confidence,
        correct: Boolean,
        now: Long,
    ): Double {
        val r = Fsrs.retrievability(state, now)
        return (1.0 - r) * OVERDUE_WEIGHT + confidenceRisk(confidence, correct)
    }

    /**
     * 本次复习后的目标保留率：答错且越有把握，目标越高（间隔越短、重测越早）。
     */
    fun nextRetention(confidence: Confidence, correct: Boolean): Double = if (correct) {
        when (confidence) {
            Confidence.SURE -> 0.90
            Confidence.VAGUE -> 0.89
            Confidence.GUESS -> 0.88
        }
    } else {
        when (confidence) {
            Confidence.SURE -> RETENTION_HIGH_CONFIDENCE_ERROR
            Confidence.VAGUE -> 0.935
            Confidence.GUESS -> 0.92
        }
    }

    /** 按自评信心调整目标保留率后，交给 [Fsrs.review] 计算状态与到期时间 */
    fun review(
        state: MemoryState,
        rating: Rating,
        confidence: Confidence,
        correct: Boolean,
        now: Long,
    ): SchedulingResult = Fsrs.review(
        state = state,
        rating = rating,
        now = now,
        desiredRetention = nextRetention(confidence, correct),
    )

    /** 今日/明日到期数量与整体预测保留率 */
    fun board(
        states: List<MemoryState>,
        now: Long,
        desiredRetention: Double = RETENTION_DEFAULT,
    ): MemoryBoard {
        val today = Time.localDate(now)
        var dueToday = 0
        var dueTomorrow = 0
        var retentionSum = 0.0
        states.forEach { state ->
            retentionSum += Fsrs.retrievability(state, now)
            val due = Time.localDate(Fsrs.dueAt(state, desiredRetention))
            when {
                !due.isAfter(today) -> dueToday++          // 今天与更早的欠账
                due == today.plusDays(1) -> dueTomorrow++
            }
        }
        return MemoryBoard(
            dueToday = dueToday,
            dueTomorrow = dueTomorrow,
            total = states.size,
            predictedRetention = if (states.isEmpty()) 0.0 else retentionSum / states.size,
        )
    }

    /** 一个到期项的「欠账天数」，用于卡片上的「已经拖了 N 天」文案 */
    fun overdueDays(state: MemoryState, now: Long, desiredRetention: Double = RETENTION_DEFAULT): Int {
        val due = Fsrs.dueAt(state, desiredRetention)
        return ((now - due) / Fsrs.DAY_MS).toInt().coerceAtLeast(0)
    }

    /**
     * 交错编排：把同组（学科/题型）的条目打散，保持条目不增不减。
     *
     * 只有一个组时不做打散，返回原始顺序——交错对单一材料无意义，
     * 硬打散只会制造无谓的跳转。
     */
    fun interleave(items: List<Pair<Int, String>>): List<Pair<Int, String>> {
        if (items.size < 2) return items
        val order = ArrayList<String>()                      // 组的首次出现顺序
        val buckets = LinkedHashMap<String, ArrayDeque<Pair<Int, String>>>()
        items.forEach { (index, group) ->
            if (!buckets.containsKey(group)) {
                buckets[group] = ArrayDeque()
                order.add(group)
            }
            buckets.getValue(group).addLast(index to group)
        }
        if (buckets.size < 2) return items

        val result = ArrayList<Pair<Int, String>>(items.size)
        var lastGroup: String? = null
        repeat(items.size) {
            // 优先取剩余最多的组；避开与上一题同组的选项
            val candidate = order
                .filter { group -> buckets.getValue(group).isNotEmpty() }
                .sortedWith(
                    compareByDescending<String> { buckets.getValue(it).size }
                        .thenBy { group -> if (group == lastGroup) 1 else 0 }
                        .thenBy { order.indexOf(it) },
                )
                .firstOrNull { group -> group != lastGroup }
                ?: order.first { group -> buckets.getValue(group).isNotEmpty() }
            result.add(buckets.getValue(candidate).removeFirst())
            lastGroup = candidate
        }
        return result
    }
}
