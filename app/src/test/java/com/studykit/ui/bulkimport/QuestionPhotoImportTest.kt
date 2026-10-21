package com.studykit.ui.bulkimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拍照录题的三岔口（`decidePhotoImport`）：识别文本过得了四选一解析就照旧进预览，
 * 过不了但确实有字就转错题本（图片与文本原样带走），连字都没有才是真正的空结果。
 *
 * 这些断言全用字面量：真实书页那一条刻意长得像数学课本习题（编号行 + 小题行），
 * 它永远满足不了「题干 | 选项… | 答案」的竖线列数，正是本 bug 的现场输入。
 */
class QuestionPhotoImportTest {

    @Test
    fun `合法四选一文本进预览且条目非空`() {
        val outcome = decidePhotoImport("1/2 + 1/3 = ?|5/6|1/6|1|2/5|A")
        assertTrue(outcome is PhotoImportOutcome.Preview)
        val plan = (outcome as PhotoImportOutcome.Preview).plan
        assertEquals(1, plan.items.size)
        assertTrue(plan.items.isNotEmpty())
    }

    @Test
    fun `教科书书页文本转错题本且原文原样带回`() {
        val page = """
            8. 求下列函数的导数：
            (1) y=(arcsin x/2)^2
            (2) y=ln(x+sqrt(1+x^2))
            (3) y=e^(2x)cos 3x
        """.trimIndent()
        val outcome = decidePhotoImport(page)
        assertTrue(outcome is PhotoImportOutcome.Fallback)
        assertEquals(page, (outcome as PhotoImportOutcome.Fallback).text)
    }

    @Test
    fun `数学课本极限习题页转错题本且原文原样带回`() {
        // 用户 2026-10-21 实拍的那页（习题 1-5）：编号行 + 小题行 + 数列判断题，没有竖线列。
        val page = """
            习题 1-5
            1. 计算下列极限：
            (1) lim x→2 (x²+5)/(x-3)
            (2) lim x→1 (x²-3)/(√(3x²+1))
            (4) lim x→0 (4x³-2x²+x)/(3x²+2x)
            4. 设 {aₙ}，{bₙ}，{cₙ} 均为非负数列，且 lim aₙ=0，lim bₙ=1，lim cₙ=∞。
            下列陈述中哪些是对的，哪些是错的？
            (1) aₙ < bₙ, n∈N+
            (3) lim aₙcₙ 不存在
        """.trimIndent()
        val outcome = decidePhotoImport(page)
        assertTrue(outcome is PhotoImportOutcome.Fallback)
        assertEquals(page, (outcome as PhotoImportOutcome.Fallback).text)
    }

    @Test
    fun `高考试卷截图有选择题但非竖线格式也转错题本`() {
        // 用户 2026-10-21 发的第二张（2026 高考数学卷一）：有 A/B/C/D 选项，但没有「|」分列，
        // 题库解析器照样不收；这条钉住"有选择题"不等于"能进题库"，照片仍要落到错题本。
        val page = """
            2026年普通高等学校招生全国统一考试
            数学
            一、选择题：本题共8小题，每小题5分，共40分。
            1. 样本数据6，8，4，5，12的中位数为
            A. 5    B. 6    C. 8    D. 9
            2. 已知平面向量a，b不共线，且2a+b=...
            A. y=x    B. y=2x    C. y=3x    D. y=4x
        """.trimIndent()
        val outcome = decidePhotoImport(page)
        assertTrue(outcome is PhotoImportOutcome.Fallback)
        assertEquals(page, (outcome as PhotoImportOutcome.Fallback).text)
    }

    @Test
    fun `空白文本是空结果`() {
        assertEquals(PhotoImportOutcome.Empty, decidePhotoImport(""))
        assertEquals(PhotoImportOutcome.Empty, decidePhotoImport("   \n\t  "))
    }

    @Test
    fun `只有竖线但列数不够仍是转错题本`() {
        val outcome = decidePhotoImport("a|b|c")
        assertTrue(outcome is PhotoImportOutcome.Fallback)
        assertEquals("a|b|c", (outcome as PhotoImportOutcome.Fallback).text)
    }
}
