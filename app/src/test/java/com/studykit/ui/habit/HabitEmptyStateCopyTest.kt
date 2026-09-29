package com.studykit.ui.habit

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 习惯页空状态那句的**事实守卫**（v2.5 §2.1 / §4.3）。
 *
 * 这一句原本写着「21 天养成一个习惯」。那个说法不是"没被证实"，是被原研究团队
 * 公开辟谣过的民间神话（见 spec §2.1），而它是这屏对新用户说的第一句话 ——
 * 所以它是唯一一条"改回去也没人报错"的文案，只能靠测试钉住。
 *
 * 守卫不止「21 天」那三个字：**任何具体天数都不许出现在这一句里**。
 * 换成 66（Lally 2010 的中位数，范围 18–254）同样会被当成 KPI，
 * 而那一屏真正要传达的是"漏一次不毁掉养成"。
 */
class HabitEmptyStateCopyTest {

    /** 直接钉界面用的那一串字（[HABIT_EMPTY_STATE_CAPTION] 就是 `EmptyState(caption = ...)` 的实参） */
    private val caption = HABIT_EMPTY_STATE_CAPTION

    @Test
    fun `the empty state does not repeat the 21-day myth`() {
        assertFalse("「$caption」又写回了 21 天", caption.contains("21"))
        assertFalse("「$caption」又写回了 21 天", caption.contains("21 天"))
        assertFalse("「$caption」不许换成本研究的中位数 66", caption.contains("66"))
    }

    /** 阿拉伯数字 + 天 = 一个会被当成 KPI 的承诺（"三天""一周"这类不含数字的说法不在其列） */
    @Test
    fun `the empty state promises no concrete number of days`() {
        assertFalse("「$caption」里出现了具体天数", Regex("\\d+\\s*天").containsMatchIn(caption))
        listOf("天养成", "养成习惯", "形成一个习惯").forEach {
            assertFalse("「$caption」里不该出现「$it」", caption.contains(it))
        }
    }

    /** 该说的那句还得在：漏一次不算断，这是这一屏对早期流失唯一有用的话 */
    @Test
    fun `the empty state still says a missed day is not a broken streak`() {
        assertTrue("「$caption」丢了「漏一天不算断」这层意思", caption.contains("漏一天"))
        assertTrue("「$caption」丢了「每天坚持一小步」这层意思", caption.contains("每天坚持一小步"))
    }
}
