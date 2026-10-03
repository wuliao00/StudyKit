package com.studykit.ui.study

import com.studykit.tips.StudyTips
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SIXTY_SIX 贴士的**点亮 / 存活**判定（计划 B Task 17 Step 2b；B17 复审 ⚠4 补的钉测）。
 *
 * Step 2b 原话钉的是"第 20/22 天不弹、第 21 天弹且只弹一次"。以前这三条口径只写在
 * [StudyHomeScreen] 的 `LaunchedEffect` 里，没有一行测试覆盖 —— 也就是说把 `==` 改成 `>=`
 * （第 22 天还在弹）或把"只弹一次"丢掉，全仓不会红。判定抽成 [SixtySixTip] 这两个纯函数后：
 *  - `shouldLatch` 管**点亮**：只在天数恰好走到 21、且这台安装没看过时点亮一次；
 *  - `shouldShow` 管**还在不在**：点亮之后只在天数仍是 21 的那段挂着，
 *    天数走过头（明天再学一次就是 22）状态自动复位，不把"21 天"那句留在页面上发霉。
 *    —— 这就是"21 天弹且只弹一次"里"只"字的复位语义；跨会话不再出现由
 *    `settings.sixtySixTipSeen` 负责（点亮当次即落库）。
 *
 * 纯 Kotlin，不碰 Compose、不碰时钟。
 */
class SixtySixTipTest {

    @Test fun `第 20 天既不点亮也不显示`() {
        assertFalse(SixtySixTip.shouldLatch(streakDays = 20, tipSeen = false))
        // 就算页面状态里留着某种"已点亮"，20 天也不该画（严格 == STREAK_MYTH_DAY）
        assertFalse(SixtySixTip.shouldShow(streakDays = 20, latched = true))
    }

    @Test fun `第 21 天点亮并显示`() {
        assertTrue(SixtySixTip.shouldLatch(streakDays = StudyTips.STREAK_MYTH_DAY, tipSeen = false))
        assertTrue(SixtySixTip.shouldShow(streakDays = 21, latched = true))
    }

    @Test fun `第 21 天只弹一次_看过之后不再点亮`() {
        assertTrue(SixtySixTip.shouldLatch(streakDays = 21, tipSeen = false))
        // 点亮那一次就把 sixtySixTipSeen 落库 ⇒ 同一天再怎么重组都不二次点亮
        assertFalse(SixtySixTip.shouldLatch(streakDays = 21, tipSeen = true))
    }

    @Test fun `第 22 天不点亮且显示状态复位`() {
        assertFalse(SixtySixTip.shouldLatch(streakDays = 22, tipSeen = false))
        assertFalse(
            "天数走过 21 → 昨天点亮的那枚状态要复位，不能把 SIXTY_SIX 一直挂在首页",
            SixtySixTip.shouldShow(streakDays = 22, latched = true),
        )
    }

    @Test fun `没点亮时任何天数都不显示`() {
        assertFalse(SixtySixTip.shouldShow(streakDays = StudyTips.STREAK_MYTH_DAY, latched = false))
        assertFalse(SixtySixTip.shouldShow(streakDays = 0, latched = false))
    }
}
