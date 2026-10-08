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

    // ── v3.0-B T14：照片这一段的门（页相判定不看 kind + 占位那句不许说谎）───
    // 渲染层的"照片到底在不在树上"由 `MistakePhotoGateRenderTest` 钉（Robolectric）。
    // 本文件是纯 JVM 的页相/文案守卫，能钉的是这两件事，都不需要真图片文件。

    @Test fun `遮挡卡的行照常渲染，页相判定只看 id 不看 kind`() {
        // T16/T17 会开始写 kind=1 的行；那只是一张**多画了几个块**的错题照片，
        // 不是另一种卡 —— 若页相判定哪天去读 kind，这些行会在详情页凭空变成"错题不存在"。
        val occlusion = Mistake(
            id = 9L, uuid = "u9", source = Mistake.SOURCE_PHOTO, subject = "数学",
            title = "遮挡卡", content = "题干", imagePath = "x.jpg",
            kind = Mistake.KIND_OCCLUSION, extra = "{\"shapes\":[]}",
        )
        val render = renderMistakeDetail(
            state = MistakeDetailState(id = 9L, mistake = occlusion, answered = true),
            mistakeId = 9L,
        )
        assertTrue(render is MistakeDetailReady)
        assertEquals(9L, (render as MistakeDetailReady).mistake.id)
    }

    @Test fun `照片被扣住时那句占位提示不许撒谎`() {
        val covered = buildRedoGate("题干", "", RedoPhase.REDO, hasImage = true)
        // 占位卡出现的唯一条件：这一行确实有照片，且此刻不许露出
        assertTrue(covered.hasImage && !covered.imageVisible)
        val hint = imageCoverHint(covered.shapeCount)
        // 不许写成"照片不见了"：用户会以为错题的图片丢了
        for (phrase in listOf("不存在", "已删除", "丢失", "失败")) {
            assertFalse("占位提示不许说照片没了：$hint", hint.contains(phrase))
        }
        // 一块都没画的时候不许说"已遮 N 处" —— 那是画了块之后才成立的话，说反了等于骗人
        assertFalse(hint.contains("已遮"))
        // 核对阶段同一行：照片回到树上，这句提示也就该退场（imageVisible 为真）
        assertTrue(buildRedoGate("题干", "", RedoPhase.CHECK, hasImage = true).imageVisible)
    }

    // ── v3.0-B T17：详情页补画遮挡 ────────────────────────────────────────
    // 入口文案 + VM 那一步真正要写的那一对列。`MistakeViewModel.setOcclusion` 的函数体只有
    // 「读行 → copy(这两列) → update」，VM 本身在 JVM 侧起不来（本仓无 VM 测试先例），
    // 所以能钉的全在这里：给定编辑器那份形状列表，落库的 `kind`/`extra` 必须是哪一对。

    @Test fun `遮挡入口的文案跟着块数换_零块说的是补画`() {
        assertEquals("在照片上画遮挡", occlusionEntryLabel(0))
        assertEquals("编辑遮挡（已 2 处）", occlusionEntryLabel(2))
        // 零块不许写「已 0 处」：那读起来像编辑器坏了，而此刻确实一块都没盖
        assertFalse(occlusionEntryLabel(0).contains("0"))
        // 脏输入（负数）退回补画那句，不许拼出「已 -1 处」
        assertEquals(occlusionEntryLabel(0), occlusionEntryLabel(-1))
    }

    @Test fun `补画遮挡写的那一对列由形状列表直接决定`() {
        val drawn = listOf(
            OcclusionShape.Rect(0.1, 0.2, 0.5, 0.6),
            OcclusionShape.Ellipse(0.7, 0.8, 0.1, 0.05),
        )
        val columns = occlusionColumns(shapesToJson(drawn))
        assertEquals(Mistake.KIND_OCCLUSION, columns.kind)
        // 重新编码之后读回来还是画的那两块、还是那个顺序（草稿到库之间不许丢块、不许换位）
        assertEquals(drawn, shapesFromJson(columns.extra))

        val cleared = occlusionColumns(shapesToJson(emptyList()))
        assertEquals(Mistake.KIND_BASIC, cleared.kind)
        assertEquals("{}", cleared.extra)

        // 撤干净之后这一行必须读成「没有遮挡」：`kind` 是唯一开关（T13），
        // 留一个 kind=1 + 空 extra 的行就是最坏那一档 —— 页面承认遮挡却读到零块。
        val row = Mistake(
            id = 11L, uuid = "u11", source = Mistake.SOURCE_PHOTO, subject = "数学",
            title = "撤掉遮挡", content = "题干", imagePath = "x.jpg",
            kind = cleared.kind, extra = cleared.extra,
        )
        assertTrue(occlusionShapes(row).isEmpty())
        // 退回普通错题的重做阶段：整张扣住（安全侧），而不是"遮了但什么都没盖"地把照片摊开
        val gate = buildRedoGate(
            content = row.content,
            note = row.note,
            phase = RedoPhase.REDO,
            hasImage = true,
            shapeCount = occlusionShapes(row).size,
        )
        assertFalse(gate.imageVisible)
    }

    @Test fun `画着两处时那一行读回来仍是两处且照片可见`() {
        // 与上一条成对：补画成功之后同一行在重做阶段的可信状态
        val drawn = listOf(
            OcclusionShape.Rect(0.0, 0.4, 0.9, 0.55),
            OcclusionShape.Polygon(
                listOf(NormalizedPoint(0.1, 0.1), NormalizedPoint(0.4, 0.2), NormalizedPoint(0.2, 0.6)),
            ),
        )
        val columns = occlusionColumns(shapesToJson(drawn))
        val row = Mistake(
            id = 12L, uuid = "u12", source = Mistake.SOURCE_PHOTO, subject = "数学",
            title = "画好遮挡", content = "题干", imagePath = "x.jpg",
            kind = columns.kind, extra = columns.extra,
        )
        assertEquals(2, occlusionShapes(row).size)
        val gate = buildRedoGate(
            content = row.content,
            note = row.note,
            phase = RedoPhase.REDO,
            hasImage = true,
            shapeCount = occlusionShapes(row).size,
        )
        // 用户已指明答案写在哪几块 ⇒ 重做阶段也敢看（这正是遮挡这块功能的用处）
        assertTrue(gate.imageVisible)
        assertTrue(gate.partiallyCovered)
    }
}
