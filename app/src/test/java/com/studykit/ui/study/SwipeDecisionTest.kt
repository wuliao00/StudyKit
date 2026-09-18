package com.studykit.ui.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滑动评价判定（[decideSwipe] / [swipeThresholdPx]）纯逻辑单元测试：只喂位移 + 甩速 + 卡宽，
 * 不依赖 Android / Compose，也不碰 `CardStudyScreen` 的任何快照。
 *
 * 这些数字就是「手感」的全部契约，改动等于改交互语义，所以逐条钉死：
 * 位移过阈按位移方向判定、位移不足时同向甩速补判、异号或零位移一律不判定。
 * 基准宽度取 1000px，阈值即 330px，测试值都离阈值足够远，不依赖浮点舍入方向。
 */
class SwipeDecisionTest {

    // ── 位移过阈（brief 例 1、2）─────────────────────────────────────────

    @Test fun `位移越过右阈值判为认识`() {
        assertEquals(
            SwipeDecision.KNOWN,
            decideSwipe(offsetX = 400f, velocityX = 0f, widthPx = 1000),
        )
    }

    @Test fun `位移越过左阈值判为不认识`() {
        assertEquals(
            SwipeDecision.UNKNOWN,
            decideSwipe(offsetX = -400f, velocityX = 0f, widthPx = 1000),
        )
    }

    // ── 位移不足但甩速够：补判（brief 例 3、4）────────────────────────────

    @Test fun `位移未过阈但右向甩速够仍判为认识`() {
        assertEquals(
            SwipeDecision.KNOWN,
            decideSwipe(offsetX = 50f, velocityX = 1200f, widthPx = 1000),
        )
    }

    @Test fun `位移未过阈但左向甩速够仍判为不认识`() {
        assertEquals(
            SwipeDecision.UNKNOWN,
            decideSwipe(offsetX = -50f, velocityX = -1200f, widthPx = 1000),
        )
    }

    // ── 都不够（brief 例 5）──────────────────────────────────────────────

    @Test fun `位移与速度都不够时不判定`() {
        assertEquals(
            SwipeDecision.NONE,
            decideSwipe(offsetX = 50f, velocityX = 200f, widthPx = 1000),
        )
        assertEquals(
            SwipeDecision.NONE,
            decideSwipe(offsetX = 0f, velocityX = 0f, widthPx = 1000),
        )
    }

    // ── 反向甩速不得误判（brief 例 6）────────────────────────────────────

    @Test fun `位移已过阈时反向甩速不推翻位移方向`() {
        // 拖过阈值后往回猛甩再松手：结论仍按位移走，绝不翻转成反方向的判定
        assertEquals(
            SwipeDecision.KNOWN,
            decideSwipe(offsetX = 400f, velocityX = -1500f, widthPx = 1000),
        )
        assertEquals(
            SwipeDecision.UNKNOWN,
            decideSwipe(offsetX = -400f, velocityX = 1500f, widthPx = 1000),
        )
    }

    @Test fun `甩速与位移异号时不判定`() {
        // 回弹途中松手：速度方向与偏移相反，此时不该结算（速判必须与位移同号）
        assertEquals(
            SwipeDecision.NONE,
            decideSwipe(offsetX = 50f, velocityX = -1500f, widthPx = 1000),
        )
        assertEquals(
            SwipeDecision.NONE,
            decideSwipe(offsetX = -50f, velocityX = 1500f, widthPx = 1000),
        )
    }

    @Test fun `零位移时再快的甩速也不判定`() {
        // 同号判定用的是严格同号：offsetX 恰为 0 时没有方向可依
        assertEquals(
            SwipeDecision.NONE,
            decideSwipe(offsetX = 0f, velocityX = 5000f, widthPx = 1000),
        )
        assertEquals(
            SwipeDecision.NONE,
            decideSwipe(offsetX = 0f, velocityX = -5000f, widthPx = 1000),
        )
    }

    // ── 阈值与边界（brief 例 7 + 补充）───────────────────────────────────

    @Test fun `阈值按宽度比例计算且夹到至少一像素`() {
        assertEquals(330f, swipeThresholdPx(widthPx = 1000), 0.01f)
        assertEquals(1f, swipeThresholdPx(widthPx = 0), 0f)
        assertEquals(1f, swipeThresholdPx(widthPx = 1), 0f)
    }

    @Test fun `宽度未测量时不崩不误判`() {
        // 首帧 onSizeChanged 还没给宽度，历史 bug 面：阈值 0 会得到「碰一下就判定」+ 除零
        for (w in listOf(0, 1)) {
            val threshold = swipeThresholdPx(widthPx = w)
            assertTrue(threshold.isFinite() && threshold >= 1f)
            assertEquals(
                SwipeDecision.NONE,
                decideSwipe(offsetX = 0f, velocityX = 0f, widthPx = w),
            )
            assertEquals(
                SwipeDecision.KNOWN,
                decideSwipe(offsetX = 20f, velocityX = 0f, widthPx = w),
            )
            assertEquals(
                SwipeDecision.UNKNOWN,
                decideSwipe(offsetX = -20f, velocityX = 0f, widthPx = w),
            )
        }
    }

    @Test fun `阈值边界按严格越过判定`() {
        val threshold = swipeThresholdPx(widthPx = 1000)
        assertEquals(
            SwipeDecision.NONE,
            decideSwipe(offsetX = threshold, velocityX = 0f, widthPx = 1000),
        )
        assertEquals(
            SwipeDecision.KNOWN,
            decideSwipe(offsetX = threshold + 1f, velocityX = 0f, widthPx = 1000),
        )
        assertEquals(
            SwipeDecision.UNKNOWN,
            decideSwipe(offsetX = -(threshold + 1f), velocityX = 0f, widthPx = 1000),
        )
    }
}
