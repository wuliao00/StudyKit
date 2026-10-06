package com.studykit.data.memory

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 一场考试（v3.0-B）。住在设置里（`app_settings.exams_json`），不是表 —— 理由见计划 D2：
 * 「考试日」全仓已有 8 个读点在 `AppSettings` 上，另开一张表就是两处真值。
 *
 * [subjects] 为空或全是空白串 = **全学科**。这不是偷懒的默认值，是 v2.9 那个全局考试日的原语义
 * （它当年影响所有卡的排期），旧数据合成进来时必须保持这个口径。
 */
data class ExamEntry(
    val name: String,
    val epochDay: Long,
    val subjects: List<String> = emptyList(),
    val rSprint: Double = DEFAULT_R_SPRINT,
    val windowDays: Int = DEFAULT_WINDOW_DAYS,
) {
    companion object {
        const val DEFAULT_R_SPRINT = 0.95
        const val DEFAULT_WINDOW_DAYS = 30
        const val DEFAULT_NAME = "考试"
    }
}

/**
 * 考试临窗的目标保留率 ramp（文档 §3.3 / L1868-1879）：**政策层**，FSRS 参数一个都不动。
 *
 * `R(t) = R_base + (R_sprint − R_base) · clamp((D0 − t)/D0, 0, 1)`，其中 D0 = [ExamEntry.windowDays]、
 * `D0 − t` 就是剩余天数 [daysLeft]。四个边界是 ⛔ 一票否决（`ExamPolicyTest` 钉着）：
 * left=window → base；left=0 → sprint；left=window+1 → base；非关联学科恒 base。
 *
 * 上限 [MAX_EFFECTIVE_RETENTION] 是护栏：目标保留率往 1 贴，反解出的间隔会坍缩到 0 附近，
 * 复习量随之爆炸（文档 L1877「防间隔坍缩+复习量爆炸」）。
 *
 * 零 Android、零时钟（`today` 由调用方注入），与 [MemoryScheduler] 同量纲（天 + Double）。
 */
object ExamPolicy {

    /** 提升后的目标保留率天花板（⛔ 测试要求 r_sprint=0.99 时结果为 0.97） */
    const val MAX_EFFECTIVE_RETENTION = 0.97

    /**
     * 这场考试管不管这张卡。
     *
     * - `subjects` 为空**或全是空白串** = 全学科，连没有学科归属的卡（词卡、书摘）也算。
     *   空白兜底不是洁癖：下一任务要从旧存储的字符串合成 `ExamEntry`，切出 `listOf("")`
     *   是迟早会发生的事；若把它当真实学科过滤，症状是「全局考试悄悄不再影响任何卡」——
     *   不报错、不崩溃，肉眼查不出来，所以口径必须在这里兜住而不是指望上游干净。
     * - `subject` 传 `null` = 这张卡没有学科，于是**只**被全学科那场关联（见 [MemoryScheduler.forSubject]）；
     * - 匹配掐首尾空白、忽略大小写：库里 `subject` 是 `SELECT DISTINCT` 来的，用户手输多个空格很常见。
     */
    fun linksSubject(exam: ExamEntry, subject: String?): Boolean {
        if (exam.subjects.none { it.isNotBlank() }) return true
        if (subject == null) return false
        val needle = subject.trim()
        if (needle.isEmpty()) return false
        return exam.subjects.any { it.trim().equals(needle, ignoreCase = true) }
    }

    /** 距考试还有几天（已过为负数，正是「今天考试」= 0 的那个口径） */
    fun daysLeft(exam: ExamEntry, today: LocalDate): Long =
        ChronoUnit.DAYS.between(today, LocalDate.ofEpochDay(exam.epochDay))

    /**
     * 把 base 目标保留率按临窗进度抬到冲刺值，封顶 [MAX_EFFECTIVE_RETENTION]。
     *
     * 多场考试同时命中时取 `max`（= 最紧的那一场），且**只抬不降**：`maxOf` 保证用户已经调严
     * （`STRICT` = 0.95）时，一场冲刺值更低的考试不会反过来把他的复习放松。
     */
    fun effectiveRetention(
        subject: String?,
        today: LocalDate,
        exams: List<ExamEntry>,
        rBase: Double,
    ): Double {
        var r = rBase
        for (exam in exams) {
            if (!linksSubject(exam, subject)) continue
            val left = daysLeft(exam, today)
            if (left < 0L || left > exam.windowDays) continue
            // windowDays<=0 的退化配置：只在当天抬（left 已被上面筛成 0），不做除零
            val progress = if (exam.windowDays <= 0) 1.0 else 1.0 - left.toDouble() / exam.windowDays
            r = maxOf(r, rBase + (exam.rSprint - rBase) * progress)
        }
        return r.coerceAtMost(MAX_EFFECTIVE_RETENTION)
    }

    /**
     * 关联学科里最近一场考试（**含今天**）的剩余天数；没有当天或未来的关联考试 = null（调用方退回默认保持期）。
     *
     * 两个下游必须知道的事实：
     * - **返回值可能是 0**。0 = 今天就是考试日，正是最紧急的一天：[effectiveRetention] 在这一天把 ramp 打满
     *   （progress=1.0），这边若把当天判掉，整条 ramp 在唯一最需要它的日子里反而什么都不做，
     *   计划里「left=0 → sprint」那条 ⛔ 就成了空话——两边口径必须对齐，不对称本身就是 bug。
     * - **换算成保持期档前先 `coerceAtLeast(1)`**：[MemoryModel.maxIntervalDaysFor] 与 [MemoryModel.targetRecallFor]
     *   拿到 0 都走「无信息」分支（当作没有考试），0 天直接喂过去会把最紧急的一天放松成最宽松的一档。
     */
    fun nearestExamDaysLeft(subject: String?, today: LocalDate, exams: List<ExamEntry>): Int? {
        var best: Long = Long.MAX_VALUE
        for (exam in exams) {
            if (!linksSubject(exam, subject)) continue
            val left = daysLeft(exam, today)
            if (left >= 0L && left < best) best = left
        }
        return if (best == Long.MAX_VALUE) null else best.toInt()
    }
}
