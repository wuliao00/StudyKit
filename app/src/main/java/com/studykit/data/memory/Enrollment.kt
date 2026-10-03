package com.studykit.data.memory

/**
 * 书摘「纳入复习队列」的初始排期（v2.7.0.1 修复 / spec §7.2）。
 *
 * v2.7 发版如实记下的缺陷：新摘录落库 `next_review_at=0`（未启用哨兵），而复习队列那条 SQL 要
 * `next_review_at > 0`，生产里又没有录入侧的纳入路径——于是新摘录永远进不了队列，复习页只剩空态。
 * 这条把 spec §7.2 的原口径补回来：**新摘录默认 `next_review_at = now + 1d`**（建摘即入队，明天第一次
 * 到期），每摘仍可 opt-out（`enabled=false` → 落回 0 哨兵，正是复习页「把这条移出复习队列」那条）。
 * 无 schema 变更，`Excerpt.nextReviewAt` 那列本来就在。
 *
 * 抽成注入 `now` 的纯函数，是为了让「默认入队 = now+1d 且为正」这条语义在 JVM 直接可测
 * （`ExcerptEnrollTest`）——不落库、不碰 Android；而「`addExcerpt` 真的调了它、插进去的行确为正数」
 * 这条写路径由 Robolectric 集成测 `ExcerptEnrollQueueTest` 钉住。两处各守一段，不重复。
 */
object Enrollment {

    /**
     * 一天的毫秒数。与 [ExcerptReview] 内部那个同量纲，但语义不同（这里是「建摘后的初始延迟」，
     * 那里是「复习间隔的折算基数」），故各自独立定义，不为省一个常量把两个概念绑在一起。
     */
    private const val ONE_DAY_MS = 24L * 3_600_000L

    /**
     * 新建摘录时 `next_review_at` 的初始值。
     *
     * 默认（`enabled=true`）= `now + 1d`，为正数即代表「已入队、明天到期第一次检索」；
     * `enabled=false` = `0L`（未启用哨兵，等价「这条不复习」）。
     */
    fun initialNextReviewAt(now: Long, enabled: Boolean = true): Long =
        if (enabled) now + ONE_DAY_MS else 0L
}
