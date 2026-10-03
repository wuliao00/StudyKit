package com.studykit.data.memory

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 新摘录「默认入复习队列」的初始排期语义（v2.7.0.1 修复 / spec §7.2）。
 *
 * 纯函数级钉 [Enrollment.initialNextReviewAt] —— 它就是 `addExcerpt` 组实体、给 `nextReviewAt` 赋值那一步
 * 的口径。锁三件事：
 *  1. 默认（不传 enabled）= `now + 1d` 且为正（正数即「已入队」，区别于 0 未启用哨兵）；
 *  2. 显式 `enabled=true` 与默认完全一致（「默认入队」不是特殊分支，就是启用态本身）；
 *  3. opt-out（`enabled=false`）落回 0——与复习页「把这条移出复习队列」同一语义。
 *
 * 「插入后真的 >0、并约一天后进到期队列」这条写路径不在这里测，由 Robolectric 集成测
 * [com.studykit.data.dao.ExcerptEnrollQueueTest] 覆盖，两边各守一段不重复。
 */
class ExcerptEnrollTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `默认入队时初始到期是_now_加一天且为正`() {
        val at = Enrollment.initialNextReviewAt(now)
        assertEquals(now + TimeUnit.DAYS.toMillis(1), at)
        assertTrue("正数代表已入队（区别于 0 未启用哨兵）", at > 0L)
    }

    @Test
    fun `显式启用与默认口径一致`() {
        assertEquals(
            Enrollment.initialNextReviewAt(now, enabled = true),
            Enrollment.initialNextReviewAt(now),
        )
    }

    @Test
    fun `opt_out_初始排期落回0未启用哨兵`() {
        assertEquals(0L, Enrollment.initialNextReviewAt(now, enabled = false))
    }
}
