package com.studykit.ui.study

import com.studykit.data.AppSettings
import com.studykit.data.memory.MemoryScheduler
import com.studykit.data.memory.ReviewStrictness
import com.studykit.data.memory.Scheduling
import java.time.LocalDate

/**
 * 某个学科这一轮生效的调度参数（v3.0-B）。
 *
 * **全仓唯一**把「严格度（[ReviewStrictness]）+ 考试清单 + 学科 + 今天」拼成 [Scheduling] 的地方：
 * 错题重做评分走它，涌量预测走它，将来任何要按学科排期的新调用点也必须走它。理由与 `KernelHub` 一样 ——
 * 组合口径一旦有两份，「设置里存的」和「实际排的」就会分家，而分家在排期这件事上是静默的：
 * 没有任何报错，只有用户的复习间隔悄悄不是他调的那个。
 *
 * `subject` 传 null = 这张卡没有学科归属（词卡、书摘）。此时只有关联学科为空的考试会影响它，
 * 见 `ExamPolicy.linksSubject`。
 *
 * `settings` 传 null = 还没读到设置（首帧、或 Room 读失败）。退回「无考试」的默认口径，
 * 与 `KernelHub.forId(id, null)` 同一条保守纪律：宁可按默认排一次，不在冷启动时抛。
 *
 * 学科取的是**错题/题目那一列的原值**（`Mistake.subject`），不在这里做归一化：
 * 大小写与首尾空白的容忍住在 `ExamPolicy.linksSubject`，两处各掐一次只会让它们不一致。
 */
internal fun schedulingForSubject(
    settings: AppSettings?,
    subject: String?,
    today: LocalDate,
): Scheduling = MemoryScheduler.forSubject(
    strictness = settings?.reviewStrictness ?: AppSettings().reviewStrictness,
    exams = settings?.examList ?: emptyList(),
    subject = subject,
    today = today,
)
