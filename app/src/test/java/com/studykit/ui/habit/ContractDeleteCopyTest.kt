package com.studykit.ui.habit

import com.studykit.data.entity.Contract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 撤销/删除入口的文案（纯函数）。
 *
 * 这里钉的是「文案随契约状态分叉」这一条：一颗会删数据的按钮，标签和确认框必须说准
 * 现在发生的到底是"撤掉一份还没判的约定"还是"抹掉一条已经判完的历史"。
 * 分叉留在 Composable 里的话测试碰不到它 —— 抽成纯函数就是为了能被钉住。
 *
 * 三种状态各钉四件事：入口标签、确认框标题、正文、确认按钮标签。
 * 删除是物理删除且不可恢复（计划 R3），所以文案不许出现"可以恢复""还能找回"那类
 * 过度承诺；进行中的那句还必须点明"打卡不受影响"—— 那是用户真正担心的连带损失。
 */
class ContractDeleteCopyTest {

    // ── 入口标签 ─────────────────────────────────────────────

    /** 进行中：动词是「撤销」—— 撤的是自己刚签下的承诺 */
    @Test
    fun `active entry is labelled revoke`() {
        assertEquals("撤销", contractDeleteLabel(Contract.STATUS_ACTIVE))
    }

    /** 已达成 / 未达成：动词是「删除」—— 抹的是一条已经判完的历史 */
    @Test
    fun `settled entry is labelled delete`() {
        assertEquals("删除", contractDeleteLabel(Contract.STATUS_ACHIEVED))
        assertEquals("删除", contractDeleteLabel(Contract.STATUS_FAILED))
    }

    /** 兜底：状态串认不出来就按"已结算"处理 —— 只有 STATUS_ACTIVE 常量本身才走撤销分支 */
    @Test
    fun `unknown status falls back to delete`() {
        assertEquals("删除", contractDeleteLabel(""))
        assertEquals("删除", contractDeleteLabel("ACTIVE "))
    }

    // ── ACTIVE 的确认框 ──────────────────────────────────────

    @Test
    fun `active confirm title`() {
        val (title, body) = contractDeleteConfirmText(Contract.STATUS_ACTIVE)
        assertEquals("撤销这份契约？", title)
        assertEquals("撤销后这条契约连同它的判定一起消失，不会留下记录；已打的打卡不受影响。", body)
    }

    /** 正文必须同时说清"什么会没"和"什么留下" */
    @Test
    fun `active confirm body says what goes and what stays`() {
        val (_, body) = contractDeleteConfirmText(Contract.STATUS_ACTIVE)
        assertTrue(body.contains("不会留下记录"))
        assertTrue(body.contains("打卡不受影响"))
    }

    @Test
    fun `active confirm button`() {
        assertEquals("撤销契约", contractDeleteConfirmLabel(Contract.STATUS_ACTIVE))
    }

    // ── 已结算的确认框（ACHIEVED 与 FAILED 同一套措辞）─────────

    @Test
    fun `achieved confirm text`() {
        val (title, body) = contractDeleteConfirmText(Contract.STATUS_ACHIEVED)
        assertEquals("删除这条记录？", title)
        assertEquals("这是已经判完的历史，删了就找不回来。", body)
        assertEquals("删除记录", contractDeleteConfirmLabel(Contract.STATUS_ACHIEVED))
    }

    /** 达成与未达成都是"判完的历史"，措辞不该分叉 —— 分叉了就是多写了一份要维护的文案 */
    @Test
    fun `failed confirm text is the same as achieved`() {
        assertEquals(
            contractDeleteConfirmText(Contract.STATUS_ACHIEVED),
            contractDeleteConfirmText(Contract.STATUS_FAILED),
        )
        assertEquals("删除记录", contractDeleteConfirmLabel(Contract.STATUS_FAILED))
    }

    // ── 文案纪律 ─────────────────────────────────────────────

    /** 两种措辞不许串台：进行中的框不把契约说成"记录"，已结算的框不暗示还能"撤" */
    @Test
    fun `copy does not cross the two statuses`() {
        val active = contractDeleteConfirmText(Contract.STATUS_ACTIVE)
        assertFalse(active.second.contains("已经判完"))
        assertFalse(active.first.contains("删除"))

        val settled = contractDeleteConfirmText(Contract.STATUS_FAILED)
        assertFalse(settled.first.contains("撤销"))
        assertFalse(settled.second.contains("撤销"))
    }

    /** 物理删除没有回收站：任何一条文案都不许留"能找回来"的口子 */
    @Test
    fun `copy never promises recovery`() {
        val forbidden = listOf("可恢复", "可以恢复", "还能找回", "随时找回", "回收站", "撤销后可恢复")
        val statuses = listOf(
            Contract.STATUS_ACTIVE,
            Contract.STATUS_ACHIEVED,
            Contract.STATUS_FAILED,
            "UNKNOWN",
        )
        statuses.forEach { status ->
            val (title, body) = contractDeleteConfirmText(status)
            listOf(title, body, contractDeleteLabel(status), contractDeleteConfirmLabel(status))
                .forEach { text ->
                    forbidden.forEach { phrase ->
                        assertFalse("「$text」里不该出现「$phrase」", text.contains(phrase))
                    }
                }
        }
    }
}
