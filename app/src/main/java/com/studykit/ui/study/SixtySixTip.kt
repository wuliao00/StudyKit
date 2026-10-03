package com.studykit.ui.study

import com.studykit.tips.StudyTips

/**
 * SIXTY_SIX 贴士（"21 天不是终点，66 天才是中位数"）的**点亮 / 存活**判定。
 *
 * 纯函数、零 Compose、零时钟 —— 计划 B Task 17 Step 2b 钉的三条口径（第 20/22 天不弹、
 * 第 21 天弹且只弹一次）以前只写在 `StudyHomeScreen` 的 `LaunchedEffect` 里，一行测试都没有：
 * 把 `==` 改成 `>=`（第 22 天还在念 21 天）或把"只弹一次"丢掉，全仓不会红。抽到这里之后由
 * `SixtySixTipTest` 钉住。
 *
 * 两个函数各管一件事，别混成一个：
 *  - [shouldLatch]：**要不要点亮**。只在天数恰好走到 [StudyTips.STREAK_MYTH_DAY]、
 *    而且这台安装还没看过时点亮一次；点亮那一次就把 `settings.sixtySixTipSeen` 落库。
 *  - [shouldShow]：**还在不在**。点亮之后只在天数仍是 21 的那段挂着 —— 明天再学一次就是 22 天，
 *    那句"21 天弹且只弹一次"里的"只"字落到这里就是**状态复位**：不把已经过去的 21 天
 *    一直留在首页 hero 下面发霉。跨会话不再出现由 `sixtySixTipSeen` 负责。
 */
internal object SixtySixTip {

    /** 点亮：连续天数**恰好**走到 21（不是"到了 21 之后"），且这台安装没看过。 */
    fun shouldLatch(streakDays: Int, tipSeen: Boolean): Boolean =
        streakDays == StudyTips.STREAK_MYTH_DAY && !tipSeen

    /** 存活：点亮过（[latched]）并且天数还停在 21；走过那一天就自动复位。 */
    fun shouldShow(streakDays: Int, latched: Boolean): Boolean =
        latched && streakDays == StudyTips.STREAK_MYTH_DAY
}
