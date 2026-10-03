package com.studykit.ui.nav

import com.studykit.ui.bulkimport.ImportUiState
import com.studykit.util.importer.ImportOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.7.0.1 两处真机回归的根因测试。刻意走纯 JVM：被钉的两条逻辑（结果页显示源、退出导入流的落点）
 * 都不该依赖 NavController / Room，能把它们写成纯函数就绝不引模拟器（本仓 CI 唯一执行器是 testDebugUnitTest）。
 *
 * 三条返回栈形态取自真机取证 —— 用户那 1162 词的四级库 `source_id='CET4luan_1'` 是在线词库那条路导入的：
 *  - 在线词库 words→store→preview→result（无 paste）
 *  - 截图取词 words→preview→result（无 paste）
 *  - 批量粘贴 words→paste→preview→result（有 paste，是能正常工作的对照路径）
 */
class ImportExitTest {

    // ── Bug A：显示源绝不为 null 伪造 0 ─────────────────────────────
    @Test
    fun `有真实 outcome 时原样透出，不被清零`() {
        val state = ImportUiState(outcome = ImportOutcome(inserted = 1162, skippedDuplicates = emptyList(), rejected = emptyList()))
        assertEquals(1162, importResultOutcomeFor(state)?.inserted)
    }

    @Test
    fun `outcome 被清空时返回 null，而不是伪造一个 0 的成功导入`() {
        // reset() 之后（或任何 outcome 缺失的异常态）：宁可 null 让调用方显示占位，
        // 也绝不能显示「成功导入 0」——那正是真机上「录入了单词显示 0 个」的显示源。
        assertNull(importResultOutcomeFor(ImportUiState(outcome = null)))
    }

    // ── Bug B：退出导入流的落点 ────────────────────────────────────
    @Test
    fun `在线词库栈没有粘贴页时，退出落到商店页而不是卡死`() {
        val dictStack = listOf("study", "study/words", "study/dict/store", "import/preview", "import/result")
        // 原写法死盯 PASTE：popBackStack 找不到不在栈里的目标 ⇒ no-op ⇒ 留在结果页（Bug B）。
        // 正解：paste 不在栈里就落在预览页下面那一屏 = 商店页。
        assertEquals("study/dict/store", importExitLanding(dictStack, inclusive = true))
    }

    @Test
    fun `截图取词栈没有粘贴页时，退出落到单词列表`() {
        val ocrStack = listOf("study", "study/words", "import/preview", "import/result")
        assertEquals("study/words", importExitLanding(ocrStack, inclusive = true))
    }

    @Test
    fun `任何一条路点完成都一定离开结果页`() {
        // 把三条真机入口都跑一遍：落点绝不能还停在 import/result（那就是「留在原地」）。
        val stacks = listOf(
            listOf("study", "study/words", "study/dict/store", "import/preview", "import/result"),
            listOf("study", "study/words", "import/preview", "import/result"),
            listOf("study", "study/words", ImportRoutes.PASTE, "import/preview", "import/result"),
        )
        for (stack in stacks) {
            val landing = importExitLanding(stack, inclusive = true)
            assertTrue("落点不能还停在结果页：$landing", landing != "import/result")
            assertTrue("落点必须是栈里真实存在的一屏：$landing", stack.contains(landing))
        }
    }

    @Test
    fun `粘贴栈点完成时原样弹过粘贴页回列表，语义一字不改`() {
        val pasteStack = listOf("study", "study/words", ImportRoutes.PASTE, "import/preview", "import/result")
        assertEquals("study/words", importExitLanding(pasteStack, inclusive = true))
    }

    @Test
    fun `粘贴栈点回去修那几行时停在粘贴页`() {
        val pasteStack = listOf("study", "study/words", ImportRoutes.PASTE, "import/preview", "import/result")
        assertEquals(ImportRoutes.PASTE, importExitLanding(pasteStack, inclusive = false))
    }

    @Test
    fun `题目粘贴栈点完成也落回发起导入那一屏`() {
        val qPasteStack = listOf("study", ImportRoutes.PASTE, "import/preview", "import/result")
        assertEquals("study", importExitLanding(qPasteStack, inclusive = true))
    }
}
