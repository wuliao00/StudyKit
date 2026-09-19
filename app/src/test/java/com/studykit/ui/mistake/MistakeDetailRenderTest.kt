package com.studykit.ui.mistake

import com.studykit.data.entity.Mistake
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 错题详情页面相判定（[renderMistakeDetail]）纯逻辑单元测试。
 *
 * 钉的是终审 C1 那条数据正确性：route 上的 `mistakeId` 是唯一准绳，VM 交出的行必须与它
 * 比对过才允许渲染，否则「在 B 页渲染 A」会把标记掌握 / 删除 / 设复习时间写到 A 行上。
 * 每条用例都各挡一种塌法：只判空、只判行 id、或者把「还没答完」当成「库里没有」。
 */
class MistakeDetailRenderTest {

    private fun mistake(id: Long, title: String = "题$id") = Mistake(
        id = id,
        uuid = "u$id",
        source = Mistake.SOURCE_PHOTO,
        subject = "数学",
        title = title,
        content = "题干",
    )

    // ── 跨题串台（C1 本体）───────────────────────────────────────────────

    @Test fun `上一道错题的行在新 route 上仍是加载态`() {
        val stale = MistakeDetailState(id = 1L, mistake = mistake(1L), answered = true)
        assertEquals(
            MistakeDetailRender.Loading,
            renderMistakeDetail(state = stale, mistakeId = 2L),
        )
    }

    @Test fun `状态没带上请求 id 时即使行号撞上也不渲染`() {
        // 只把行往下传、忘了带请求 id 的写法会从这里漏出去：判定看的是 state.id，不是 mistake.id
        val rowOnly = MistakeDetailState(id = null, mistake = mistake(7L), answered = true)
        assertEquals(
            MistakeDetailRender.Loading,
            renderMistakeDetail(state = rowOnly, mistakeId = 7L),
        )
    }

    @Test fun `VM 初始 seed 态是加载态而非不存在`() {
        assertEquals(
            MistakeDetailRender.Loading,
            renderMistakeDetail(state = MistakeDetailState(), mistakeId = 3L),
        )
    }

    // ── 「还没答完」与「答完但没有这一行」必须分得开 ──────────────────────

    @Test fun `id 对上但数据库尚未回话时是加载态`() {
        val inFlight = MistakeDetailState(id = 3L, mistake = null, answered = false)
        assertEquals(
            MistakeDetailRender.Loading,
            renderMistakeDetail(state = inFlight, mistakeId = 3L),
        )
    }

    @Test fun `id 对上且确认库里没有这一行才是不存在`() {
        val gone = MistakeDetailState(id = 3L, mistake = null, answered = true)
        assertEquals(
            MistakeDetailRender.Missing,
            renderMistakeDetail(state = gone, mistakeId = 3L),
        )
    }

    @Test fun `未答完时绝不判成不存在`() {
        // 反过来说：把 answered 当成可省掉的标志，首帧就会闪「错题不存在或已删除」
        val notYet = MistakeDetailState(id = 3L, mistake = null, answered = false)
        assertTrue(renderMistakeDetail(state = notYet, mistakeId = 3L) != MistakeDetailRender.Missing)
    }

    // ── Ready：渲染与动作同一把尺子 ──────────────────────────────────────

    @Test fun `应答的行就是 route 那道题`() {
        val hit = MistakeDetailState(id = 5L, mistake = mistake(5L), answered = true)
        val render = renderMistakeDetail(state = hit, mistakeId = 5L)
        assertTrue(render is MistakeDetailRender.Ready)
        // 页面把 mistakeId 直接喂给写动作，靠的就是这条等式成立
        assertEquals(5L, (render as MistakeDetailRender.Ready).mistake.id)
    }

    @Test fun `行被别处更新后依旧按 id 命中`() {
        val updated = MistakeDetailState(
            id = 5L,
            mistake = mistake(5L, title = "改过名的题"),
            answered = true,
        )
        val render = renderMistakeDetail(state = updated, mistakeId = 5L)
        assertEquals("改过名的题", (render as MistakeDetailRender.Ready).mistake.title)
    }
}
