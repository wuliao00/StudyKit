package com.studykit.ui.mistake

import com.studykit.data.entity.Mistake
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 错题详情页面相判定（[renderMistakeDetail]）纯逻辑单元测试。
 *
 * 钉的是终审 C1 那条数据正确性：route 上的 `mistakeId` 是唯一准绳，VM 交出的行必须与它
 * 比对过才允许渲染，否则「在 B 页渲染 A」会把标记掌握 / 删除 / 覆盖排期写到 A 行上。
 * 每条用例都各挡一种塌法：只判空、只判行 id、或者把「还没答完」当成「库里没有」。
 *
 * 后半组是 v2.7 B14（计划 Step 3）加进来的文案守卫：错题排期已经交给内核，
 * v2.5 §2.4 那句「复习时间由你自己定，这里没有算法排期。」必须跟着退役——
 * 把它钉成「不再出现」而不是删掉了事，手法与 `ContractDeleteCopyTest` 一致
 * （文案收在纯函数里，所以守卫不需要 Robolectric）。上面那组页相判定一条未改。
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
            MistakeDetailLoading,
            renderMistakeDetail(state = stale, mistakeId = 2L),
        )
    }

    @Test fun `状态没带上请求 id 时即使行号撞上也不渲染`() {
        // 只把行往下传、忘了带请求 id 的写法会从这里漏出去：判定看的是 state.id，不是 mistake.id
        val rowOnly = MistakeDetailState(id = null, mistake = mistake(7L), answered = true)
        assertEquals(
            MistakeDetailLoading,
            renderMistakeDetail(state = rowOnly, mistakeId = 7L),
        )
    }

    @Test fun `VM 初始 seed 态是加载态而非不存在`() {
        assertEquals(
            MistakeDetailLoading,
            renderMistakeDetail(state = MistakeDetailState(), mistakeId = 3L),
        )
    }

    // ── 「还没答完」与「答完但没有这一行」必须分得开 ──────────────────────

    @Test fun `id 对上但数据库尚未回话时是加载态`() {
        val inFlight = MistakeDetailState(id = 3L, mistake = null, answered = false)
        assertEquals(
            MistakeDetailLoading,
            renderMistakeDetail(state = inFlight, mistakeId = 3L),
        )
    }

    @Test fun `id 对上且确认库里没有这一行才是不存在`() {
        val gone = MistakeDetailState(id = 3L, mistake = null, answered = true)
        assertEquals(
            MistakeDetailMissing,
            renderMistakeDetail(state = gone, mistakeId = 3L),
        )
    }

    @Test fun `未答完时绝不判成不存在`() {
        // 反过来说：把 answered 当成可省掉的标志，首帧就会闪「错题不存在或已删除」
        val notYet = MistakeDetailState(id = 3L, mistake = null, answered = false)
        assertNotEquals(MistakeDetailMissing, renderMistakeDetail(state = notYet, mistakeId = 3L))
    }

    // ── Ready：渲染与动作同一把尺子 ──────────────────────────────────────

    @Test fun `应答的行就是 route 那道题`() {
        val hit = MistakeDetailState(id = 5L, mistake = mistake(5L), answered = true)
        val render = renderMistakeDetail(state = hit, mistakeId = 5L)
        assertTrue(render is MistakeDetailReady)
        // 页面把 mistakeId 直接喂给写动作，靠的就是这条等式成立
        assertEquals(5L, (render as MistakeDetailReady).mistake.id)
    }

    @Test fun `行被别处更新后依旧按 id 命中`() {
        val updated = MistakeDetailState(
            id = 5L,
            mistake = mistake(5L, title = "改过名的题"),
            answered = true,
        )
        val render = renderMistakeDetail(state = updated, mistakeId = 5L)
        assertEquals("改过名的题", (render as MistakeDetailReady).mistake.title)
    }

    // ── 排期文案守卫（v2.7 B14：算法接管，旧承诺退役）─────────────────────

    /** 页面上与排期有关的全部说法，守卫逐条过一遍 */
    private fun allSchedulingCopy(): List<String> = listOf(
        reviewScheduleTitle(),
        systemSchedulingNote(),
        scheduleOverrideLabel(),
        nextReviewLine(null),
        nextReviewLine("10月05日"),
    ) + manualOverrideOptions().map { it.first }

    @Test fun `旧承诺「复习时间由你自己定」已从所有排期文案退役`() {
        for (copy in allSchedulingCopy()) {
            assertFalse("排期文案里不该再出现旧承诺：$copy", copy.contains("复习时间由你自己定"))
            assertFalse("排期文案里不该再宣称没有算法：$copy", copy.contains("这里没有算法排期"))
        }
    }

    @Test fun `主位说的是系统排期、人可以覆盖`() {
        assertEquals("复习排期", reviewScheduleTitle())
        assertEquals("系统按遗忘曲线排期，你可以覆盖", systemSchedulingNote())
        assertEquals("覆盖排期", scheduleOverrideLabel())
    }

    @Test fun `三档手选保留但只作为覆盖选项`() {
        // 降级不是删掉：用户真的在用这三档；顺序也是页面上的显示顺序
        assertEquals(listOf("明天" to 1, "三天后" to 3, "一周后" to 7), manualOverrideOptions())
    }

    @Test fun `未排期时不假装已经定好时间`() {
        val unset = nextReviewLine(null)
        assertTrue(unset.contains("还没排上"))
        assertTrue(unset.contains("由系统安排"))
        assertEquals("下次复习：10月05日", nextReviewLine("10月05日"))
    }
}
