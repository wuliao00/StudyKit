package com.studykit.ui.habit

/**
 * 一场专注计时（v2.4 批次三）：纯 JVM 数据 + 纯函数，零 Compose、零 Android ——
 * 与 [SnakeGame] 同一条架构红线。倒计时的算术错了是**静默**的（早停一秒、少记一分钟，
 * 用户和账面都看不出来），所以规则收敛在这个可单测的小类里，屏幕只负责"喂 now、画剩余"。
 *
 * 时间基准是 `SystemClock.elapsedRealtime()`（开机起的单调毫秒），不是墙上钟：
 * 系统改时间/时区跳变不会把一场 25 分钟变成 5 分钟。起点由调用方在创建时存入，
 * 本类只做减法 —— 后台挂起、回前台补算都天然正确，不信任何 UI 倒计时。
 */
data class FocusSession(
    val habitId: Long,
    val durationMinutes: Int,
    val startElapsedMs: Long,
) {

    /**
     * 非法时长（0/负）在这里**钳到 1 分钟**而不是抛异常。理由：正常路径的构造入口只有
     * [PRESETS] 与 [DEFAULT_MINUTES]，到不了非法值；这道防线防的是脏路由/脏配置 ——
     * 纯逻辑类在构造时崩掉整个应用，比把一场坏数据当 1 分钟处理代价大得多，
     * 与本仓 `isWithinCheckInWindow` 对越界窗口的 coerce 兜底是同一取舍。
     */
    private val safeMinutes: Int = durationMinutes.coerceAtLeast(1)

    private val durationSeconds: Long = safeMinutes * 60L

    /** 剩余秒数：到点后恒为 0，永不出现负数（UI 直接拿去画，不需要再判）；早于起点的 now 视为还没开始 */
    fun remainingSeconds(nowElapsedMs: Long): Int {
        val elapsedMs = (nowElapsedMs - startElapsedMs).coerceAtLeast(0L)
        return (durationSeconds - elapsedMs / 1000L).coerceAtLeast(0L).toInt()
    }

    /** 是否到点（剩余为 0 即完成，含已超时的情形） */
    fun isComplete(nowElapsedMs: Long): Boolean = remainingSeconds(nowElapsedMs) <= 0

    /**
     * 已完整进行的分钟数：向下取整，封顶在时长上（超时之后不涨）。
     * 设计文档原稿写的是无参 `val`，但纯类里的属性拿不到"现在" —— 一读 `System` 就把
     * 零 Android 的红线撕了；与 [remainingSeconds] 同形收成带 now 的函数才保得住可单测性。
     */
    fun elapsedMinutes(nowElapsedMs: Long): Int {
        val elapsedMs = (nowElapsedMs - startElapsedMs).coerceAtLeast(0L)
        return (elapsedMs / 60_000L).toInt().coerceAtMost(safeMinutes)
    }

    companion object {
        /** 预设时长（分钟）：设计文档口径 15/25/45，25 默认 */
        val PRESETS = listOf(15, 25, 45)
        const val DEFAULT_MINUTES = 25
    }
}
