package com.studykit.data.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.studykit.data.AppDatabase
import com.studykit.data.entity.PracticeRecord
import com.studykit.data.entity.Question
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 一轮练习的取数顺序（v3.5）。
 *
 * 背景：题包从每包 8~10 题涨到 44~60 题，而取数原来是 `ORDER BY id LIMIT 10` —— **每轮都是同 10 道**，
 * 用户装了 60 道题却只见到 10 道。现在排序改成「没练过的优先，练过的按最近一次作答时间升序」，
 * 靠的是 `practice_records` 里每题 `MAX(at)`。这条 SQL 的正确性（LEFT JOIN 不漏没练过的题、
 * 已练的按时间而不是按 id 排、`MAX` 而不是 `MIN`）只有在真 SQL 上才测得出来，所以走 in-memory Room。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuestionRotationOrderTest {

    private lateinit var db: AppDatabase
    private val subject = "数学"
    private val ids = mutableListOf<Long>()

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // 用 insert() 的返回值收 id，别在断言里写死 1..12：题库是自增主键，将来加种子数据就全错位。
        repeat(12) { index ->
            ids += db.questionDao().insert(
                Question(
                    uuid = "q-$index",
                    subject = subject,
                    stem = "第 $index 题",
                    optionsJson = "[\"A\",\"B\"]",
                    answerIndex = 0,
                    explanation = "解析 $index",
                ),
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** `index` 是第几道题（0 基），对应 [ids] 里的真实主键 */
    private fun idOf(index: Int): Long = ids[index]

    private fun practice(questionId: Long, at: Long) = runBlocking {
        db.practiceDao().insert(
            PracticeRecord(
                uuid = "p-$questionId-$at",
                questionId = questionId,
                selected = 0,
                correct = true,
                at = at,
            ),
        )
    }

    private fun take(limit: Int, offset: Int = 0, type: String? = null) = runBlocking {
        db.questionDao()
            .getBySubjectFiltered(subject = subject, stage = null, type = type, limit = limit, offset = offset)
            .map { it.id }
    }

    @Test
    fun 没练过的题排在最前面_按id升序() {
        practice(idOf(0), 1_000L)
        practice(idOf(1), 2_000L)

        assertEquals(listOf(idOf(2), idOf(3), idOf(4)), take(limit = 3))
    }

    @Test
    fun 练过的题按最近一次作答时间升序排在后面() {
        practice(idOf(4), 5_000L)
        practice(idOf(3), 3_000L)
        practice(idOf(5), 9_000L)

        // 前 9 条是没练过的（0,1,2,6,7,8,9,10,11），最后三条按 last_at：3(3000) → 4(5000) → 5(9000)
        assertEquals(
            listOf(0, 1, 2, 6, 7, 8, 9, 10, 11, 3, 4, 5).map(::idOf),
            take(limit = 12),
        )
    }

    /**
     * 取的是**最近一次**（MAX）而不是第一次（MIN）。
     *
     * 两道都练过的题错开时间：q0 在 1000 与 9000 各练一次，q1 只在 5000 练过一次。
     * 按 MAX 排：q1(5000) 在 q0(9000) 前面；按 MIN 排会反过来 —— 这条就是钉死这个方向的。
     */
    @Test
    fun 同一道题练多次时以最后一次作答时间为准() {
        practice(idOf(0), 9_000L)
        practice(idOf(0), 1_000L)
        practice(idOf(1), 5_000L)

        val order = take(limit = 12)
        assertEquals("q1 只有一次 5000，应该排在最后一次 9000 的 q0 前面", idOf(1), order[10])
        assertEquals(idOf(0), order[11])
    }

    @Test
    fun 练完一轮之后下一轮自然轮到后面的题() {
        val first = take(limit = 5)
        assertEquals("第一轮应取最前面 5 道没练过的", listOf(0, 1, 2, 3, 4).map(::idOf), first)
        first.forEachIndexed { index, id -> practice(id, 10_000L + index) }

        val second = take(limit = 5)
        assertEquals("第二轮必须换一批题，而不是重复第一轮", listOf(5, 6, 7, 8, 9).map(::idOf), second)
    }

    /** 生产里的语义层缓冲池就靠 `offset = QUIZ_SIZE` 取「第二梯队」，offset 走不通会直接重复出题。 */
    @Test
    fun offset与limit一起用时不重不漏() {
        val firstHalf = take(limit = 5, offset = 0)
        val secondHalf = take(limit = 5, offset = 5)

        assertEquals(5, firstHalf.size)
        assertEquals(5, secondHalf.size)
        assertEquals("两页不能有交集", emptySet<Long>(), (firstHalf intersect secondHalf.toSet()))
        assertEquals(listOf(5, 6, 7, 8, 9).map(::idOf), secondHalf)
    }

    @Test
    fun 题型筛选不受排序影响() {
        runBlocking {
            db.questionDao().insert(
                Question(
                    uuid = "q-multi",
                    subject = subject,
                    stem = "多选题",
                    optionsJson = "[\"A\",\"B\",\"C\"]",
                    answerIndex = 0,
                    explanation = "解析",
                    questionType = "multiple",
                    answerIndicesJson = "[0,1]",
                ),
            )
        }

        val singles = runBlocking {
            db.questionDao().getBySubjectFiltered(subject, stage = null, type = "single", limit = 20, offset = 0)
        }
        assertEquals("单选筛选不该把多选捞进来", 12, singles.size)
        assertEquals(listOf("q-multi"), runBlocking {
            db.questionDao().getBySubjectFiltered(subject, stage = null, type = "multiple", limit = 20, offset = 0)
        }.map { it.uuid })
    }

    @Test
    fun 阶段筛选不受排序影响() {
        runBlocking {
            db.questionDao().insert(
                Question(
                    uuid = "q-junior",
                    subject = subject,
                    stem = "初中题",
                    optionsJson = "[\"A\",\"B\"]",
                    answerIndex = 1,
                    explanation = "解析",
                    examStage = "junior",
                ),
            )
        }

        val junior = runBlocking {
            db.questionDao().getBySubjectFiltered(subject, stage = "junior", type = null, limit = 10, offset = 0)
        }
        assertEquals(1, junior.size)
        assertEquals("q-junior", junior.single().uuid)
    }

    /** 其它学科的作答记录不该影响本科目的顺序（子查询是按 question_id 分组的，不是按学科）。 */
    @Test
    fun 别的学科的练习记录不影响本科目顺序() {
        val otherId = runBlocking {
            db.questionDao().insert(
                Question(
                    uuid = "q-english",
                    subject = "英语",
                    stem = "English",
                    optionsJson = "[\"A\",\"B\"]",
                    answerIndex = 0,
                    explanation = "解析",
                ),
            )
        }
        practice(otherId, 99_000L)

        assertEquals(listOf(0, 1, 2).map(::idOf), take(limit = 3))
    }
}
