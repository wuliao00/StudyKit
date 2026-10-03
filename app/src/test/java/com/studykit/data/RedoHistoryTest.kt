package com.studykit.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.studykit.data.entity.Mistake
import com.studykit.data.repository.MistakeRepository
import kotlinx.coroutines.flow.first
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
 * 逐次重做历史（`mistake_redos`）的落库与读回（v2.7 计划 B Task 15；`RedoFlow` 注释自认缺的那张表）。
 *
 * 钉的是四件事，都是纯函数层够不着、只有真 Room 才复现得到的：
 *  - **写入即读回**：`insertRedo` 把 correct / hintsUsed / hadNoteRebuild 原样落进对应列，
 *    取回来字段一字不差（列名映射、默认值都在这一步被验）。
 *  - **按 mistakeId 归堆 + 计数**：同一道错题插两条，历史就是两条、计数就是二；
 *    另一道错题读自己的那份，不会被串进来（外键 + `mistake_id` 索引那条路）。
 *  - **升序**：历史按 `redone_at` 升序回话，将来拿真实「上次评分时刻」反哺排期时顺序不能乱。
 *  - **外键级联**：删掉错题行，它的重做历史一起没了（`ON DELETE CASCADE`）——不留孤儿。
 *
 * ## 关于「先状态后历史」这条顺序
 * 它落在 [com.studykit.ui.mistake.MistakeViewModel.gradeRedo] 里，是一枚 `AndroidViewModel` 上的挂起调用，
 * 要 Room + Robolectric + 整只 DI 容器才跑得到；本仓的既有纪律（见 `MistakeSchedulingTest` 开头那段
 * 「为什么不测 gradeRedo」）一贯不拿 JVM 单测碰 VM，`KernelWiringTest` 也只钉纯映射。
 * 所以这条**没有**在本文件里被做成断言，而是在 `gradeRedo` 里用「同一个协程内 `update` 紧跟 `insertRedo`、
 * 中间不 fork」的结构钉死，并在 `MistakeRepository.insertRedo` 的 KDoc 引用 `WordRepository.applyReview →
 * recordGradedReview` 那段「污染校准样本」的理由说明为什么这个先后不许倒。此处如实说明，不假装测过。
 *
 * 走 Robolectric 而不引模拟器：与 `ContractBatchSettleTest` 同一套 in-memory 库的口径。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RedoHistoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: MistakeRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MistakeRepository(db.mistakeDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 先落一道错题（重做历史有外键指向它，必须先有父行）；返回它的自增 id */
    private suspend fun seedMistake(): Long = repository.add(
        source = Mistake.SOURCE_PHOTO,
        subject = "数学",
        title = "题",
        content = "题干：略",
    )

    @Test fun `一次重做写进去读回来字段一字不差`() {
        runBlocking {
            val mistakeId = seedMistake()

            repository.insertRedo(
                mistakeId = mistakeId,
                correct = true,
                hintsUsed = 2,
                hadNoteRebuild = true,
            )

            val rows = repository.observeRedosByMistake(mistakeId).first()
            assertEquals(1, rows.size)
            val redo = rows.single()
            assertEquals(mistakeId, redo.mistakeId)
            assertTrue("判对落 true", redo.correct)
            assertEquals("用了几档提示原样回来", 2, redo.hintsUsed)
            assertTrue("重述门过了才 true", redo.hadNoteRebuild)
            assertEquals(1, repository.countRedosByMistake(mistakeId))
        }
    }

    @Test fun `同一道错题插两条计数成双且按时刻升序`() {
        runBlocking {
            val mistakeId = seedMistake()

            repository.insertRedo(mistakeId, correct = false, hintsUsed = 3, hadNoteRebuild = false)
            repository.insertRedo(mistakeId, correct = true, hintsUsed = 0, hadNoteRebuild = false)

            val rows = repository.observeRedosByMistake(mistakeId).first()
            assertEquals(2, rows.size)
            // 升序：后写的那条 redone_at 不早于前一条
            assertTrue(
                "历史必须按发生时刻升序回话",
                rows.zipWithNext().all { (a, b) -> a.redoneAt <= b.redoneAt },
            )
            // 判对判错各一条，读回来不能串
            assertEquals(listOf(false, true), rows.map { it.correct })
            assertEquals(2, repository.countRedosByMistake(mistakeId))
        }
    }

    @Test fun `计数只算自己那道题不会串到别处`() {
        runBlocking {
            val a = seedMistake()
            val b = seedMistake()

            repository.insertRedo(a, correct = true, hintsUsed = 0, hadNoteRebuild = false)
            repository.insertRedo(a, correct = true, hintsUsed = 1, hadNoteRebuild = false)

            assertEquals(2, repository.countRedosByMistake(a))
            assertEquals("另一道错题一份历史都没有", 0, repository.countRedosByMistake(b))
            assertTrue(repository.observeRedosByMistake(b).first().isEmpty())
        }
    }

    @Test fun `删掉错题行时它的重做历史一起消失`() {
        runBlocking {
            val mistakeId = seedMistake()
            repository.insertRedo(mistakeId, correct = true, hintsUsed = 0, hadNoteRebuild = false)
            assertEquals(1, repository.countRedosByMistake(mistakeId))

            repository.delete(mistakeId)

            assertEquals(
                "外键 ON DELETE CASCADE 必须把子行一起带走，别在历史表里留孤儿",
                0,
                repository.countRedosByMistake(mistakeId),
            )
        }
    }
}
