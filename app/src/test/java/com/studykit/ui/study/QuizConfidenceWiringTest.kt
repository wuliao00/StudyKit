package com.studykit.ui.study

import com.studykit.data.memory.Confidence
import com.studykit.ui.mistake.MistakeIntake
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 题库信心 → 错题优先级接线（v2.7 计划 B Task 12）。
 *
 * 只钉**映射**：答错入库时 `mistakes.priority` 是不是「超纠正命中」那一档。
 * 口径不在这里重算公式——那正是本仓「两处各抄一遍、改一漏一」的老毛病，
 * 唯一判定归 [com.studykit.data.memory.Hypercorrection.retestDelayMinutes]，
 * [MistakeIntake.priorityFor] 只是把它翻译成 priority，测试再把这层翻译钉住。
 *
 * 为什么不测 `selectOption` / Compose 本身：那两段要 Room + Robolectric 才跑得动，
 * 而这一段的错全在「哪种信心×对错组合该置顶」上；渲染侧的信心条由
 * ConfidencePillRenderTest 钉、交互序由现有 QuizScreen 状态机保证（口径同 KernelWiringTest）。
 */
class QuizConfidenceWiringTest {

    @Test fun `SURE x wrong is the only high-priority intake`() {
        // 唯一置顶档：非常确定却答错 = 超纠正机会
        assertEquals(1, MistakeIntake.priorityFor(Confidence.SURE, correct = false))
    }

    @Test fun `SURE x right never gets priority`() {
        assertEquals(0, MistakeIntake.priorityFor(Confidence.SURE, correct = true))
    }

    @Test fun `low and unknown confidence x wrong stays ordinary`() {
        assertEquals(0, MistakeIntake.priorityFor(Confidence.FAIR, correct = false))
        assertEquals(0, MistakeIntake.priorityFor(Confidence.GUESS, correct = false))
        // null = 跳过信心采集（关掉开关或没选），落普通档，不冒充「非常确定」
        assertEquals(0, MistakeIntake.priorityFor(null, correct = false))
    }

    @Test fun `priority maps exactly one-to-one with hypercorrection trigger`() {
        // 钉「映射而非再抄公式」：凡 Hypercorrection 会提前重测的组合，priority 必为 1，其余为 0。
        Confidence.entries.forEach { conf ->
            listOf(true, false).forEach { correct ->
                val expected = if (com.studykit.data.memory.Hypercorrection
                        .retestDelayMinutes(conf, recalled = correct) != null
                ) 1 else 0
                assertEquals(expected, MistakeIntake.priorityFor(conf, correct))
            }
        }
        val expectedNull = if (com.studykit.data.memory.Hypercorrection
                .retestDelayMinutes(null, recalled = false) != null
        ) 1 else 0
        assertEquals(expectedNull, MistakeIntake.priorityFor(null, correct = false))
    }
}
