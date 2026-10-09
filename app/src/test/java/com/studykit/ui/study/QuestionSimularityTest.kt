package com.studykit.ui.study

import com.studykit.data.entity.Question
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 题面相似度提供方（v3.0-C Task 2）。
 *
 * 两条主轴：`sim()` 的**语义**（自反、对称、不认识的 id 给 0f）与**记忆化的可证上界**
 * （`lookups`/`computations` 两个计数器）。后者不是性能洁癖 —— Task 3 的修复算法会反复问同一对，
 * Task 8 的 500 题闸门直接断言 `lookups`，口径必须在这一层就钉住。
 *
 * 夹具按 id 登记的是**构造那一刻**的题目快照，之后往原列表追加题目不影响提供方。
 */
class QuestionSimularityTest {

    private fun q(id: Long, subject: String = "语文", stem: String = "甲乙丙丁戊己", tag: String = "") =
        Question(
            id = id,
            uuid = "u$id",
            subject = subject,
            stem = stem,
            optionsJson = "[]",
            answerIndex = 0,
            explanation = "",
            conceptTag = tag,
        )

    @Test fun `同一题与自身相似度为一`() {
        val s = QuestionSimularity(listOf(q(1), q(2)))
        assertEquals(1f, s.sim(1L, 1L), 1e-6f)
    }

    @Test fun `对称性`() {
        val a = q(1, stem = "下列句子中没有语病的一项是")
        val b = q(2, stem = "下列句子中有语病的一项是")
        val s = QuestionSimularity(listOf(a, b))
        assertEquals(s.sim(1L, 2L), s.sim(2L, 1L), 1e-6f)
    }

    @Test fun `不认识的 id 两侧不同时给零`() {
        val s = QuestionSimularity(listOf(q(1)))
        assertEquals(0f, s.sim(1L, 99L), 1e-6f)
        assertEquals(0f, s.sim(98L, 99L), 1e-6f)
    }

    @Test fun `陌生 id 的自反给一不给零`() {
        // 两条约定的先后：自反排在「不认识的 id 给零」之前（见 Simularity 的 KDoc）
        val s = QuestionSimularity(listOf(q(1)))
        assertEquals(1f, s.sim(99L, 99L), 1e-6f)
        assertEquals(0, s.computations)   // 短路在查表之前，不认识也一样不消耗计算
    }

    @Test fun `同一对只真正计算一次`() {
        val s = QuestionSimularity(listOf(q(1), q(2)))
        repeat(5) { s.sim(1L, 2L) }
        repeat(5) { s.sim(2L, 1L) }
        assertEquals(10, s.lookups)
        assertEquals(1, s.computations)   // 反向查命中同一份缓存 —— 偏差 D6 第 4 条的确定性前提
    }

    @Test fun `自反查不计入计算`() {
        val s = QuestionSimularity(listOf(q(1), q(2)))
        s.sim(1L, 1L); s.sim(2L, 2L)
        assertEquals(0, s.computations)
        assertEquals(2, s.lookups)
    }

    @Test fun `每对不同题至多真实计算一次`() {
        // 500 题压力测试的成本前提（Task 8）：记忆化的粒度是「对」—— 一对题算一次，此后两侧都走缓存。
        val many = (1L..6L).map { q(it, stem = "甲乙丙丁戊己庚辛") }
        val s = QuestionSimularity(many)
        for (i in 1L..6) for (j in 1L..6) s.sim(i, j)
        assertEquals(36, s.lookups)
        // 6 题两两（含同题）不同无序对 = 15；反向与自身不重复 ⇒ 至多 15 次真实计算
        assertTrue("computations=${s.computations} 超过 15，说明同一对题算了不止一次", s.computations <= 15)
    }

    @Test fun `空题列表给全零不抛`() {
        val s = QuestionSimularity(emptyList())
        assertEquals(0f, s.sim(1L, 2L), 1e-6f)
    }

    @Test fun `重复 id 以最后一次出现的题面为准`() {
        val dup = QuestionSimularity(listOf(
            q(1, stem = "甲乙丙丁戊己"),
            q(1, stem = "戊己庚辛壬癸"),
            q(2, stem = "戊己庚辛壬癸"),
        ))
        assertEquals(1f, dup.sim(1L, 2L), 1e-6f)   // id 1 最终就是 q(2) 那份题面：同文 ⇒ 1.0
        assertEquals(0f, dup.sim(1L, 3L), 1e-6f)   // id 3 从没登记过
    }

    @Test fun `构造后再往列表加题不影响提供方`() {
        val src = mutableListOf(q(1), q(2))
        val s = QuestionSimularity(src)
        src.add(q(3))
        assertEquals(0f, s.sim(1L, 3L), 1e-6f)          // 后加的题不在缓存的 id 集合里
    }

    @Test fun `同考点标签的两题相似且不同考点的不相似`() {
        val a = q(1, stem = "甲乙丙丁戊己", tag = "病句辨析")
        val b = q(2, stem = "戊己庚辛壬癸", tag = "病句辨析")
        val c = q(3, stem = "子丑寅卯辰巳", tag = "成语")
        val s = QuestionSimularity(listOf(a, b, c))
        assertEquals(0.65f, s.sim(1L, 2L), 1e-6f)
        assertTrue(s.sim(1L, 3L) < 0.1f)
    }
}
