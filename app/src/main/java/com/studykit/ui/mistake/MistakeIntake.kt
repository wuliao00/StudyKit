package com.studykit.ui.mistake

import com.studykit.data.memory.Confidence
import com.studykit.data.memory.Hypercorrection

/**
 * 错题自动入本（答错 → mistakes 表）的优先级判定（v2.7 计划 B Task 12；spec §2.3 / §6）。
 *
 * 纯函数、零 Android / Room 依赖，可像 [MistakeMastery] 一样在 JVM 单测里直取。
 * 唯一置顶档是「超纠正命中」= 非常确定却答错：这类错题要进错题本置顶，
 * 让用户先看见自己最自信、其实错了的题（Butler 2011 回弹证据）。
 *
 * 口径不在这里重算：判定归 [Hypercorrection.retestDelayMinutes]，
 * 本函数只是把「要不要提前重测」翻译成 `mistakes.priority`，
 * 免得背词侧信道与错题置顶两处各抄一遍公式、改一漏一。
 */
object MistakeIntake {

    /**
     * 优先级 = 超纠正命中（SURE × 答错）→ 1，其余（含低信心、跳过采集 null）→ 0。
     *
     * @param confidence 提交前自评信心；null = 用户没选（关掉开关或跳过），不参与超纠正。
     * @param correct 本次作答是否正确；仅在答错（false）时才可能置顶。
     *
     * 注：`recalled` 取 `correct` 本体（与 [com.studykit.data.memory.Hypercorrection] 的「未忆起」定义、
     * 以及 [com.studykit.ui.study.StudyViewModel.gradeCard] 里 `recalled = grade != FORGET` 同一口径）：
     * 答错 = 未忆起 = recalled=false，才能命中 SURE × 未忆起。计划 B Task 12 文本里的 `recalled = !correct`
     * 与同段自定的映射表（「SURE+错→1」）矛盾，以测试钉住的语义为准。
     */
    fun priorityFor(confidence: Confidence?, correct: Boolean): Int =
        if (Hypercorrection.retestDelayMinutes(confidence, recalled = correct) != null) 1 else 0
}
