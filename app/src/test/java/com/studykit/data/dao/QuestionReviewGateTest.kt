package com.studykit.data.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.studykit.data.AppDatabase
import com.studykit.data.entity.Question
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v3.12：待校对题不得进入题库练习/模考/变式候选/筛选计数。
 *
 * 之前只做了首页「待校对」入口，若用户直接点「题库练习」，needs_review=1 的题会被当成正常题
 * 抽进复习；这里用 Room 真查询钉住过滤条件，确认后才放行。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuestionReviewGateTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: QuestionDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.questionDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun question(uuid: String, subject: String, pending: Boolean, tag: String = "") = Question(
        uuid = uuid,
        subject = subject,
        stem = uuid,
        optionsJson = "[]",
        answerIndex = 0,
        explanation = "",
        conceptTag = tag,
        needsReview = pending,
    )

    @Test
    fun pendingQuestionsAreExcludedFromPracticeQueries() = runBlocking {
        val ids = dao.insertAll(
            listOf(
                question("u-ok", "数学", pending = false, tag = "tag"),
                question("u-pending", "数学", pending = true, tag = "tag"),
                question("u-chain", "语文", pending = false),
            ),
        )
        val pendingId = ids[1]

        assertEquals(listOf("u-ok"), dao.getBySubject("数学", 10, 0).map { it.uuid })
        assertEquals(listOf("u-ok"), dao.getBySubjectAndType("数学", "single", 10, 0).map { it.uuid })
        assertEquals(listOf("u-ok"), dao.getBySubjectFiltered("数学", null, null, 10, 0).map { it.uuid })
        assertEquals(
            listOf("u-ok"),
            dao.getBySubjectAndTypes("数学", null, listOf("single"), 10, 0).map { it.uuid },
        )
        assertEquals(1, dao.countBySubject("数学"))
        assertEquals(1, dao.countBySubjectAndType("数学", "single"))
        assertEquals(listOf("u-ok"), dao.getByConceptTag("tag").map { it.uuid })

        assertEquals(listOf("数学", "语文"), dao.observeSubjects().first())
        assertEquals(1, dao.observeSubjectTypeCounts().first().first { it.subject == "数学" }.count)
        assertEquals(2, dao.observeSubjectStageFormatCounts().first().sumOf { it.count })
        assertEquals(2, dao.observeCountsByStage().first().sumOf { it.count })
        assertEquals(listOf("u-pending"), dao.observePendingReview().first().map { it.uuid })

        // 确认后放行
        dao.markReviewed(pendingId)
        assertEquals(2, dao.countBySubject("数学"))
        assertEquals(2, dao.getBySubject("数学", 10, 0).size)
    }

    @Test
    fun getByIdStillSeesPendingQuestionForReviewScreen() = runBlocking {
        val ids = dao.insertAll(listOf(question("u-pending", "数学", pending = true)))
        assertEquals("u-pending", dao.getById(ids.single())?.uuid)
    }
}
