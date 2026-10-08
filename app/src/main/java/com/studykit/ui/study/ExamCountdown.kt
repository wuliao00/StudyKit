package com.studykit.ui.study

import com.studykit.data.memory.ExamEntry
import com.studykit.data.memory.ExamPolicy
import com.studykit.data.memory.MemoryScheduler
import com.studykit.data.memory.ReviewStrictness
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * 学习首页的考试倒计时那一行（v3.0-B，文档 L1894：`%1$s · 还剩 %2$d 天 · 冲刺保留 %3$.0f%%`）。
 *
 * 文案收在纯函数里才钉得进测试 —— 与 `MistakeScheduling` 尾部那一组 `xxxLine()` 同一手法。
 *
 * 口径决定（评审 1 之后重写；上一版让调用方传 `schedulingForSubject(...).targetRecall` 进来，
 * 那是排期**已 ramp** 的输出，这里再 ramp 一次 = ramp 的 ramp：RELAXED + 全学科考试 10 天后，
 * 调度器用 0.90，屏幕印 93%）：
 *  - **base 与调度器同一个实现**：本函数自己取 [MemoryScheduler.baseTargetRecall]
 *    （`forSubject` 第 2 段抽出来的公共件），再对它做**一次** [ExamPolicy.effectiveRetention]。
 *    horizon（days）按 `subject = null` 从**整份清单**取最近一场，与排期路径同一口径 ——
 *    打印的那个百分数就是调度器会用的那个数，一次算完，不经他人转手。
 *  - **「冲刺保留」那一截只在 ramp 真的抬过它时出现**：`left in 1 until windowDays` **且**
 *    `effective > base`（严格大于）。窗口外 ramp 恒等于 base；而 exam 绑了别的学科时
 *    `subject = null` 根本不 ramp 这一行，把 base 原样印成"冲刺"数字是在说谎。
 *    AUTO/STRICT 在窗内 base 本就等于默认冲刺值 0.95、抬不动，同样落进"不追加"这一支。
 *  - **天数只经 [ExamPolicy.daysLeft]**（评审 3）：不再自己 `ChronoUnit` 减 epochDay，
 *    日期算术与调度侧同一处。
 *  - **考试当天保留旧那句「今天考试」**：这五个字从 v2.7 起就在屏幕上，一字未动；
 *    已过那支自 v3.0-B 起带考试名与直角引号——「name」 已过 N 天（评审 4 订正：旧注释说
 *    这句也"一字不改"与代码不符，实际 v2.7 的「考试已过 N 天」到这里多了名字和引号）。
 *
 * `subject` 在这里传 null 是刻意的：这一行讲的是**那场考试整体**的冲刺口径（页面上没有"这一科"
 * 的概念），与首页用 `AppSettings.examForCountdown` 选场时同一条读法。真按学科分科的冲刺值差异，
 * 用户看的是统计页那条涌量预警（v3.0-B Task 7），不是这一行。
 */
internal fun countdownLine(
    exam: ExamEntry?,
    today: LocalDate,
    strictness: ReviewStrictness,
    exams: List<ExamEntry>,
): String? {
    if (exam == null) return null
    val left = ExamPolicy.daysLeft(exam, today)
    if (left == 0L) return "今天考试"
    if (left < 0L) return "「${exam.name}」 已过 ${-left} 天"
    val head = "「${exam.name}」 · 还剩 $left 天"
    // 与 forSubject 同一条缝：horizon 从整份清单按 subject=null 取，base 用同一个实现算，ramp 一次
    val days = ExamPolicy.nearestExamDaysLeft(null, today, exams)?.coerceAtLeast(1)
    val base = MemoryScheduler.baseTargetRecall(strictness, days)
    val effective = ExamPolicy.effectiveRetention(null, today, listOf(exam), base)
    if (left !in 1 until exam.windowDays || effective <= base) return head
    return "$head · 冲刺保留 ${(effective * 100).roundToInt()}%"
}
