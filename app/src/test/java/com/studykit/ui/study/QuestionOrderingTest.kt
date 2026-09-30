package com.studykit.ui.study

import com.studykit.data.entity.Question
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 交错练习排序（[interleaveBySubject]）纯逻辑单测（v2.5 缺口补齐）。
 *
 * 钉的是五件事，每件都各挡一种坏法：
 *  - **可关**：`enabled = false` 原样退回取数顺序。这一条红了就是「关了开关还在打乱」。
 *  - **单科不硬打散**：只有一个学科时保持原序 —— 交错对单一材料无意义，硬打乱只会平白添噪。
 *  - **不增不减**：条目数与内容完全一致，一个 id 不多一个 id 不少（防手滑写成过滤/复制）。
 *  - **确定性**：纯函数、不吃随机，同样输入两次调用给出同样的输出，顺序可复现。
 *  - **打散目标**：多学科时把连续同科压到最短（均衡分布时不出现连续两题同科）。
 *
 * 与 [RecallGateTest] / [QuizOptionStateTest] 同一个理由：这套规则留在 Composable 里
 * 就只能靠真机一题题点数，而它一旦写反**不报错**，只能靠单测钉住。
 */
class QuestionOrderingTest {

    private fun q(id: Long, subject: String) = Question(
        id = id,
        uuid = "u$id",
        subject = subject,
        stem = "题干 $id",
        optionsJson = "[\"甲\",\"乙\"]",
        answerIndex = 0,
        explanation = "",
    )

    private fun subjectsOf(list: List<Question>): List<String> = list.map { it.subject }

    /** 最长的「连续同科」游程：全同科 = n，完美交替 = 1 */
    private fun maxSameSubjectRun(list: List<Question>): Int {
        if (list.isEmpty()) return 0
        var best = 1
        var run = 1
        for (i in 1 until list.size) {
            run = if (list[i].subject == list[i - 1].subject) run + 1 else 1
            if (run > best) best = run
        }
        return best
    }

    // ── 可关（开关真的有消费点）───────────────────────────────────────────

    @Test fun `关交错时原样返回同一顺序`() {
        val input = listOf(q(1, "数学"), q(2, "数学"), q(3, "物理"), q(4, "化学"))
        assertEquals(subjectsOf(input), subjectsOf(interleaveBySubject(input, enabled = false)))
        assertEquals(input.map { it.id }, interleaveBySubject(input, enabled = false).map { it.id })
    }

    @Test fun `关交错时直接返回同一实例`() {
        // 关着就该一个字节都不动：退回原引用，不重新构造列表
        val input = listOf(q(1, "数学"), q(2, "物理"))
        assertSame(input, interleaveBySubject(input, enabled = false))
    }

    // ── 单科不硬打散 ────────────────────────────────────────────────────────

    @Test fun `单一学科即使开交错也保持原序`() {
        val input = (1L..8L).map { q(it, "数学") }
        // 交错对单一材料无意义：这一条要求原样返回，顺序与实例都不动
        assertSame(input, interleaveBySubject(input, enabled = true))
    }

    @Test fun `空列表与单题都原样返回`() {
        val empty = emptyList<Question>()
        assertSame(empty, interleaveBySubject(empty, enabled = true))
        val single = listOf(q(1, "数学"))
        assertSame(single, interleaveBySubject(single, enabled = true))
    }

    // ── 不增不减 ────────────────────────────────────────────────────────────

    @Test fun `打散后条目不增不减`() {
        val input = listOf(
            q(1, "数学"), q(2, "数学"), q(3, "数学"),
            q(4, "物理"), q(5, "物理"),
            q(6, "化学"),
        )
        val out = interleaveBySubject(input, enabled = true)
        assertEquals(input.size, out.size)
        // 集合完全一致：每个 Question 实例都在，且不多任何
        assertEquals(input.toSet(), out.toSet())
        assertEquals(input.map { it.id }.sorted(), out.map { it.id }.sorted())
    }

    // ── 确定性 ──────────────────────────────────────────────────────────────

    @Test fun `同样输入两次调用结果一致`() {
        val input = listOf(
            q(1, "数学"), q(2, "物理"), q(3, "数学"), q(4, "化学"),
            q(5, "数学"), q(6, "物理"), q(7, "化学"), q(8, "数学"),
        )
        val first = interleaveBySubject(input, enabled = true)
        val second = interleaveBySubject(input, enabled = true)
        assertEquals(first.map { it.id }, second.map { it.id })
    }

    @Test fun `打散确实改变了多学科的顺序`() {
        // 输入是按学科分组的（同科连排），开交错后不该维持原分组顺序
        val input = listOf(
            q(1, "数学"), q(2, "数学"), q(3, "数学"),
            q(4, "物理"), q(5, "物理"), q(6, "物理"),
        )
        val out = interleaveBySubject(input, enabled = true)
        assertNotEquals(subjectsOf(input), subjectsOf(out))
    }

    // ── 打散目标 ────────────────────────────────────────────────────────────

    @Test fun `均衡多学科时不出现连续两题同科`() {
        val input = listOf(
            q(1, "数学"), q(2, "数学"), q(3, "数学"),
            q(4, "物理"), q(5, "物理"), q(6, "物理"),
            q(7, "化学"), q(8, "化学"), q(9, "化学"),
        )
        val out = interleaveBySubject(input, enabled = true)
        assertEquals(
            "均衡分布时最长同科游程应为 1，实际序列：${subjectsOf(out)}",
            1,
            maxSameSubjectRun(out),
        )
    }

    @Test fun `两科均衡时逐题交替`() {
        val input = listOf(
            q(1, "语文"), q(2, "语文"), q(3, "语文"), q(4, "语文"),
            q(5, "英语"), q(6, "英语"), q(7, "英语"), q(8, "英语"),
        )
        val out = interleaveBySubject(input, enabled = true)
        assertEquals(1, maxSameSubjectRun(out))
        // 相邻两题永远不同科
        for (i in 1 until out.size) {
            assertTrue(
                "第 $i 题与前一题同科：${subjectsOf(out)}",
                out[i].subject != out[i - 1].subject,
            )
        }
    }

    @Test fun `一科独大时尽力打散不抛异常`() {
        // 数学 6 题、物理 1 题：数学必然连排，但算法仍要把物理插进去、且不增不减、不死循环
        val input = (1L..6L).map { q(it, "数学") } + listOf(q(7, "物理"))
        val out = interleaveBySubject(input, enabled = true)
        assertEquals(input.size, out.size)
        assertEquals(input.toSet(), out.toSet())
        // 物理被插进了中段而非孤零零吊在队尾（能拆就拆）
        val physicsIndex = out.indexOfFirst { it.subject == "物理" }
        assertTrue("物理题应被插入分布，实际序列：${subjectsOf(out)}", physicsIndex in 1 until out.size)
    }

    @Test fun `同科内保持录入相对顺序`() {
        // 打散只改学科之间的先后，同一学科内仍按原相对次序（1→2→3 不被反接）
        val input = listOf(
            q(1, "数学"), q(2, "数学"), q(3, "数学"),
            q(4, "物理"), q(5, "物理"), q(6, "物理"),
        )
        val out = interleaveBySubject(input, enabled = true)
        val mathIds = out.filter { it.subject == "数学" }.map { it.id }
        assertEquals(listOf(1L, 2L, 3L), mathIds)
    }
}
