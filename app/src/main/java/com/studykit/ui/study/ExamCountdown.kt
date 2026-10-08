package com.studykit.ui.study

import com.studykit.data.memory.ExamEntry
import com.studykit.data.memory.ExamPolicy
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * 学习首页的考试倒计时那一行（v3.0-B，文档 L1894：`%1$s · 还剩 %2$d 天 · 冲刺保留 %3$.0f%%`）。
 *
 * 文案收在纯函数里才钉得进测试 —— 与 `MistakeScheduling` 尾部那一组 `xxxLine()` 同一手法。
 *
 * 三条口径决定：
 *  - **`rBase` 由调用方传**，而且必须是排期路径上那同一个数（`schedulingForSubject(...).targetRecall`）。
 *    函数自己不猜 base：猜出来的数与真正排期用的那个一分都不一样，屏幕上那句"冲刺保留 90%"
 *    就是一句装饰。参数化之后，页面与调度器不可能分家。
 *  - **「冲刺保留」那一截只在 ramp 真的在生效时出现**：`left < window` 才谈得上被抬过；
 *    `left == window` 时 ramp 给出的就是 base（Task 1 钉死的那条边界），此时印冲刺值是在说一件没发生的事。
 *  - **考试当天保留旧那句「今天考试」**：这五个字从 v2.7 起就在屏幕上，当天再挂一串百分数
 *    不如把它留着（文档那条文案量的是"还剩 N 天"的情形，N=0 时它自己就不成立了）。
 *
 * `subject` 在这里传 null 是刻意的：这一行讲的是**那场考试整体**的冲刺口径（页面上没有"这一科"
 * 的概念），与首页用 `AppSettings.examForCountdown` 选场时同一条读法。真按学科分科的冲刺值差异，
 * 用户看的是统计页那条涌量预警（v3.0-B Task 7），不是这一行。
 */
internal fun countdownLine(exam: ExamEntry?, today: LocalDate, rBase: Double): String? {
    if (exam == null) return null
    val left = ChronoUnit.DAYS.between(today, LocalDate.ofEpochDay(exam.epochDay))
    if (left == 0L) return "今天考试"
    if (left < 0L) return "「${exam.name}」 已过 ${-left} 天"
    val head = "「${exam.name}」 · 还剩 $left 天"
    if (left >= exam.windowDays) return head
    val effective = ExamPolicy.effectiveRetention(null, today, listOf(exam), rBase)
    return "$head · 冲刺保留 ${(effective * 100).roundToInt()}%"
}
