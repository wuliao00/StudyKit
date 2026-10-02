package com.studykit.ui.mistake

import com.studykit.data.entity.Question
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同考点变式题选取器（v2.7 计划 B Task 15）的纯函数单测。
 *
 * 钉的四件事，每件各挡一种塌法：
 *  - **同 tag、非自身**：只在 `conceptTag` 完全相等且 `id` 不等于目标的候选里挑，绝不把原題选回去。
 *  - **空 tag → null**：未标注考点（`''`）的题没有变式可言，页面据此**不出现**「换一道同考点的」按钮。
 *  - **无候选 → null**：同 tag 只剩自己（或压根没有同 tag 的题）时给不出变式，同样不该出现按钮。
 *  - **可由注入的 Random 复现**：同一颗种子两次跑必须选出同一道，替换重做对象这件事才测得准、
 *    也不会因随机而让 UI 测试变成掷骰子。
 *
 * 纯函数、零 Room / Android，与 `MistakeSchedulingTest`、`RedoFlowTest` 同一条纪律。
 */
class VariantPickerTest {

    private fun q(id: Long, tag: String, subject: String = "数学") = Question(
        id = id,
        uuid = "u$id",
        subject = subject,
        stem = "题干$id",
        optionsJson = "[]",
        answerIndex = 0,
        explanation = "",
        conceptTag = tag,
    )

    @Test fun `同考点非自身里挑一道`() {
        val all = listOf(
            q(1L, "三角函数"),
            q(2L, "三角函数"),
            q(3L, "三角函数"),
            q(4L, "数列"),
        )
        val picked = VariantPicker.pick(questionId = 1L, all = all, random = Random(7))
        assertTrue("必须选出题", picked != null)
        assertEquals("只能同考点", "三角函数", picked!!.conceptTag)
        assertTrue("绝不选回自己", picked.id != 1L)
        assertTrue("候选只有 2/3", setOf(2L, 3L).contains(picked.id))
    }

    @Test fun `空考点没有变式`() {
        val all = listOf(q(1L, ""), q(2L, ""), q(3L, "数列"))
        assertNull(VariantPicker.pick(questionId = 1L, all = all, random = Random(7)))
    }

    @Test fun `同考点只剩自己时没有候选`() {
        val all = listOf(q(1L, "三角函数"), q(2L, "数列"), q(3L, "数列"))
        assertNull(VariantPicker.pick(questionId = 1L, all = all, random = Random(7)))
    }

    @Test fun `目标题不在列表里时无从取标签返回 null`() {
        val all = listOf(q(2L, "数列"), q(3L, "数列"))
        assertNull(VariantPicker.pick(questionId = 99L, all = all, random = Random(7)))
    }

    @Test fun `同一颗种子两次跑出同一道`() {
        val all = listOf(
            q(1L, "三角函数"),
            q(2L, "三角函数"),
            q(3L, "三角函数"),
            q(4L, "三角函数"),
            q(5L, "三角函数"),
        )
        val first = VariantPicker.pick(1L, all, Random(42))
        val second = VariantPicker.pick(1L, all, Random(42))
        assertEquals("随机必须由注入的种子复现", first, second)
    }

    @Test fun `空列表返回 null 不抛`() {
        assertNull(VariantPicker.pick(questionId = 1L, all = emptyList(), random = Random(1)))
    }
}
