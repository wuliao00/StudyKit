package com.studykit.data

import android.content.Context
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Room
import androidx.room.Update
import androidx.room.Upsert
import androidx.test.core.app.ApplicationProvider
import com.studykit.data.dao.ReviewLogDao
import com.studykit.data.entity.ReviewLog
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
 * `review_log` DAO 的两条硬性质（v2.9 计划 Task 4）—— 只有真 Room + Robolectric 才复现得到：
 *
 * 1) **有序读回**：优化器（Task 7 LogPacker）要"按卡分组、组内按时间升序"的事件流，DAO 的
 *    `getAllSince()` 必须把 `card_kind → card_id → ts` 三级升序原样吐出，将来才能直接切 train/val。
 *    这里故意乱序写入再读回，钉住排序而不是"插入顺序恰好对"。
 * 2) **append-only**：复习历史是校准的本钱，只能追加、不许原地改写或删除。这条不是靠约定，
 *    而是反射检查 [ReviewLogDao] 的接口：没有 `@Update`/`@Delete`/`@Upsert`，且每个 `@Query`
 *    都以 `SELECT` 开头（只读）—— 一旦有人加了改历史的方法，本测试立刻红。
 *
 * 走 in-memory 库口径同 [RedoHistoryTest] / `ContractBatchSettleTest`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewLogDaoTest {

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

    private fun log(
        kind: String,
        cardId: Long,
        ts: Long,
        rating: Int = 3,
    ) = ReviewLog(
        cardKind = kind,
        cardId = cardId,
        ts = ts,
        rating = rating,
        elapsedDays = 1.0,
        scheduledDays = 2.0,
        state = 2,
        durationMs = 500L,
        origin = kind,
        ocrConf = null,
        selfConf = null,
        sessionId = "s-$kind-$cardId",
        isMakeup = 0,
        logSchemaVer = 1,
    )

    @Test
    fun `插入后按 card_kind,card_id,ts 升序读回`() = runBlocking {
        val dao = db.reviewLogDao()
        // 乱序写入：(word,2,30) 最先，(practice,1,10) 第二……
        dao.insert(log("word", 2, 30))
        dao.insert(log("word", 1, 20))
        dao.insert(log("practice", 1, 10))
        dao.insert(log("word", 1, 10))
        dao.insert(log("word", 1, 30))

        val rows = dao.getAllSince()
        assertEquals(5, rows.size)

        // 全局有序：(card_kind, card_id, ts) 逐级非降
        assertTrue(
            "必须按 (card_kind, card_id, ts) 升序",
            rows.zipWithNext().all { (a, b) -> tupleCompare(a, b) <= 0 },
        )
        // 'practice' 字母序在 'word' 前，先占首位
        assertEquals("practice", rows.first().cardKind)
        // 其余四行全是 word，且 card_id 升序、同卡内 ts 升序
        assertEquals(listOf("word", "word", "word", "word"), rows.drop(1).map { it.cardKind })
        assertEquals(listOf(1L, 1L, 1L, 2L), rows.drop(1).map { it.cardId })
        assertEquals(listOf(10L, 20L, 30L, 30L), rows.drop(1).map { it.ts })
    }

    @Test
    fun `review_log 是 append-only：DAO 只有插入与只读查询`() {
        val methods = ReviewLogDao::class.java.declaredMethods
        assertTrue(
            "append-only 表不应有 @Update 方法",
            methods.none { it.isAnnotationPresent(Update::class.java) },
        )
        assertTrue(
            "append-only 表不应有 @Delete 方法",
            methods.none { it.isAnnotationPresent(Delete::class.java) },
        )
        assertTrue(
            "append-only 表不应有 @Upsert 方法",
            methods.none { it.isAnnotationPresent(Upsert::class.java) },
        )
        // 所有 @Query 必须只读：改历史不许借 @Query 的壳混进来（mapNotNull 顺带把可空的注解取值收敛成非空）
        methods.mapNotNull { it.getAnnotation(Query::class.java) }.forEach { q ->
            // @Query.value 是单个 String（可能多行 SQL），直接规整大小写后断言只读
            val sql = q.value.trim().uppercase()
            assertTrue("DAO 的 @Query 必须只读 SELECT，实际：$sql", sql.startsWith("SELECT"))
        }
    }

    private fun tupleCompare(a: ReviewLog, b: ReviewLog): Int {
        val k = a.cardKind.compareTo(b.cardKind)
        if (k != 0) return k
        val id = a.cardId.compareTo(b.cardId)
        if (id != 0) return id
        return a.ts.compareTo(b.ts)
    }
}
