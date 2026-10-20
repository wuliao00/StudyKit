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
