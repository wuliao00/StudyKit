package com.studykit.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.studykit.optimize.ParamSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `param_snapshots` 快照表的 DAO 行为（v2.9 计划 Task 5，并入 Room v8）。
 *
 * 钉两条优化器采纳流程（Task 11）依赖的性质：
 * 1) **读回按 ts 降序**——校准卡片要显示"最近一次快照 X 天前优化"，且回滚列表从新到旧；
 * 2) **只保留最近 5 份**——采纳新参数前写一份快照、并淘汰第 6 份起的最旧的，保证可回滚 5 步
 *    又不无限膨胀（Task 11 护栏：插入 7 → 只剩最新 5）。
 *
 * 走 in-memory 库，口径同 [ReviewLogDaoTest]。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParamSnapshotDaoTest {

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

    private fun snap(ts: Long) = ParamSnapshot(
        params = "0.212,1.2931,2.3065,8.2956,6.4133,0.8334,3.0194,0.001,1.8722,0.1666,0.796," +
            "1.4835,0.0614,0.2629,1.6483,0.6014,1.8729,0.5425,0.0912,0.0658,0.1542",
        ts = ts,
        nTerms = 1200,
        logloss = 0.42,
        engineVer = "v6.21",
    )

    @Test
    fun `getAll 按 ts 降序读回`() = runBlocking {
        val dao = db.paramSnapshotDao()
        dao.insert(snap(100))
        dao.insert(snap(300))
        dao.insert(snap(200))
        val rows = dao.getAll()
        assertEquals(3, rows.size)
        assertEquals(listOf(300L, 200L, 100L), rows.map { it.ts })
    }

    @Test
    fun `params 逗号串能原样存回`() = runBlocking {
        val dao = db.paramSnapshotDao()
        dao.insert(snap(1))
        val got = dao.getAll().single()
        assertEquals(21, got.params.split(",").size)
        assertEquals(0.42, got.logloss, 1e-9)
        assertEquals("v6.21", got.engineVer)
    }

    @Test
    fun `保留最近5份_插入7份只留最新5`() = runBlocking {
        val dao = db.paramSnapshotDao()
        // ts 递增写 7 份：1000..7000
        for (i in 1..7) dao.insert(snap(i * 1000L))
        dao.pruneToMostRecent(keep = 5)
        val rows = dao.getAll()
        assertEquals("只应保留最近 5 份", 5, rows.size)
        assertTrue("留下的必须是最旧的 1000/2000 已被淘汰", rows.none { it.ts == 1000L || it.ts == 2000L })
        assertEquals(listOf(7000L, 6000L, 5000L, 4000L, 3000L), rows.map { it.ts })
    }
}
