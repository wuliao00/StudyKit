package com.studykit.ui.study

import com.studykit.data.memory.Confidence
import com.studykit.data.memory.Hypercorrection
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
        // 钉「映射而非再抄公式」：expected 是手写的 0/1 真值表，**独立于被测代码**——
        // 不再拿 retestDelayMinutes 反算 expected（那是用被测公式验被测公式，永远绿）。
        // 只有「SURE×答错」才是超纠正命中→置顶 1，其余一律 0。
        val truthTable = mapOf(
            (Confidence.GUESS to true) to 0,
            (Confidence.GUESS to false) to 0,
            (Confidence.FAIR to true) to 0,
            (Confidence.FAIR to false) to 0,
            (Confidence.SURE to true) to 0,
            (Confidence.SURE to false) to 1,
        )
        truthTable.forEach { (key, want) ->
            val (conf, correct) = key
            assertEquals(want, MistakeIntake.priorityFor(conf, correct))
            // 再拿手写的真值表与 Hypercorrection 的触发集交叉核对一次：置顶档 ≡ 超纠正命中
            val triggeredByHyper = Hypercorrection.retestDelayMinutes(conf, recalled = correct) != null
            assertEquals("超纠正触发应与置顶档一致(conf=$conf,correct=$correct)", want, if (triggeredByHyper) 1 else 0)
        }
        // null = 跳过信心采集（关掉开关或没选）：永远落普通档，也不触发超纠正
        assertEquals(0, MistakeIntake.priorityFor(null, correct = false))
        assertEquals(0, MistakeIntake.priorityFor(null, correct = true))
    }
}
