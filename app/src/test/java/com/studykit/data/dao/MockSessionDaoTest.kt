package com.studykit.data.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.studykit.data.AppDatabase
import com.studykit.data.entity.MockSession
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 套卷会话持久化（v3.0-B Task 8）：`mock_sessions` 表上的 DAO 读写。
 *
 * ## 它钉住什么
 *  - `insert` 后 `getById` 读回**全字段**相等——`uuid`/`created_at` 这类构造期生成的值
 *    必须原样往返，不能被列默认值悄悄替换；
 *  - `observeActive` 滤掉已交卷（state=4，字面量钉死，不引用被测常量）的那条；
 *  - `update` 改 `answers` 与 `state` 后真的落库；
 *  - `deleteById` 删干净；
 *  - `deadlineTs` 为 null（不限时）时读写保持 null——不能退化成 0 冒充"1970 年截止"。
 *
 * 走 in-memory Room 而非假 DAO，是因为这些性质长在真实 SQL 与真实列默认值上；
 * 量具照 [RetrievalCountTest]（Robolectric + `Room.inMemoryDatabaseBuilder`），
 * 本仓 CI 唯一执行器是 `testDebugUnitTest`，能这么跑就不引模拟器。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MockSessionDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 一条正在作答的会话；state=1（run）按字面量写，与被测常量无关 */
    private fun session(state: Int = 1, deadlineTs: Long? = null, pausedAt: Long? = null) = MockSession(
        subject = "数学",
        examName = "期末考",
        questionIds = "[7,8,9]",
        state = state,
        deadlineTs = deadlineTs,
        pausedAt = pausedAt,
    )

    @Test
    fun `插入后按主键读回全字段相等`() = runBlocking {
        val s = session()
        val id = db.mockSessionDao().insert(s)
        val back = db.mockSessionDao().getById(id)
        // 数据类逐字段比较：uuid/created_at/三段 JSON 都必须原样往返
        assertEquals(s.copy(id = id), back)
    }

    @Test
    fun `活跃列表不返回已交卷的那条`() = runBlocking {
        val active = session()
        val done = session(state = 4) // ⛔ 字面量：4=done，与 SQL 里的过滤值同一口径
        val activeId = db.mockSessionDao().insert(active)
        val doneId = db.mockSessionDao().insert(done)
        val list = db.mockSessionDao().observeActive()
        assertEquals("已交卷的那条不该出现在活跃列表", listOf(activeId), list.map { it.id })
    }

    @Test
    fun `更新后作答与状态真的落库`() = runBlocking {
        val id = db.mockSessionDao().insert(session())
        val before = db.mockSessionDao().getById(id)!!
        val after = before.copy(answers = "{\"7\":\"A\"}", state = 4)
        db.mockSessionDao().update(after)
        val back = db.mockSessionDao().getById(id)
        assertEquals(after, back)
        assertEquals("{\"7\":\"A\"}", back?.answers)
    }

    @Test
    fun `按主键删除后读不到`() = runBlocking {
        val id = db.mockSessionDao().insert(session())
        db.mockSessionDao().deleteById(id)
        assertNull(db.mockSessionDao().getById(id))
    }

    @Test
    fun `不限时会话的截止时刻读写保持为空`() = runBlocking {
        val id = db.mockSessionDao().insert(session(deadlineTs = null))
        val back = db.mockSessionDao().getById(id)
        assertNull("deadline_ts 不能退化成 0", back?.deadlineTs)
        // 对照组：填了值就要原样读回，证明上一条的 null 是列真允许空、不是写丢了
        val id2 = db.mockSessionDao().insert(session(deadlineTs = 123456L))
        assertEquals(123456L, db.mockSessionDao().getById(id2)?.deadlineTs)
    }

    /**
     * 评审 F1 —— 暂停起点是**库里的一列**，不是内存里的一个数。
     *
     * 没有它，跨进程恢复只能把暂停当作"就在恢复这一刻发生"，于是「继续作答」的下一 tick 直接
     * 自动交卷，整段停表被悄悄吃掉（默认 `examPauseCountsTime = false`，暂停本该停表）。
     * 这一条钉的是那列能存能读、且没暂停时确实是 NULL —— NULL 必须留着，因为 0 会被读成
     * 「1970 年就暂停了」，那是凭空造出 55 年停表。
     */
    @Test
    fun `暂停起点随会话行往返，没暂停时保持为空`() = runBlocking {
        val pausedId = db.mockSessionDao().insert(session(state = 2, pausedAt = 777_000L))
        assertEquals("暂停起点要原样读回", 777_000L, db.mockSessionDao().getById(pausedId)?.pausedAt)
        val plainId = db.mockSessionDao().insert(session())
        assertNull("没在暂停中的行不该有一个凭空造出来的暂停起点", db.mockSessionDao().getById(plainId)?.pausedAt)
    }
}
