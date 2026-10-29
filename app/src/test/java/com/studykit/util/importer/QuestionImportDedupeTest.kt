package com.studykit.util.importer

import com.studykit.data.dao.PracticeDao
import com.studykit.data.dao.QuestionDao
import com.studykit.data.dao.StageQuestionCount
import com.studykit.data.dao.SubjectStageFormatCount
import com.studykit.data.dao.SubjectTypeCount
import com.studykit.data.entity.PracticeRecord
import com.studykit.data.entity.Question
import com.studykit.data.question.ExamStage
import com.studykit.data.question.QuestionType
import com.studykit.data.repository.QuestionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 题库批量导入的**跨批**去重。
 *
 * 背景：`submitQuestions()` 原来只做批内去重，题库商店的「再预览一次」让同一个 60 题包
 * 很容易被导两遍，题库翻倍。现在先读库内题目的判重键再合并去重；
 * 这里用假 DAO 构造 [QuestionRepository]，证明「库里的题」与「再次导入的等价条目」拼出同一个键。
 */
class QuestionImportDedupeTest {

    // ── 键同构：库内实体 ↔ 导入项 ─────────────────────────────────────────────

    @Test
    fun `库内题与等价导入项拼出同一个键_空学科归一为未分类`() = runBlocking {
        val stored = question(
            stem = "鸡兔同笼",
            subject = UNGROUPED_SUBJECT,
            stage = ExamStage.JUNIOR,
        )
        val repository = repositoryOf(stored)

        val incoming = item(
            stem = "鸡兔同笼",
            stage = ExamStage.JUNIOR,
            subject = "", // 导入项没填学科，入库时会落成「未分类」
        )

        assertEquals(setOf(incoming.importKey()), repository.questionKeys())
        // 段序锁死：题干 → 阶段 → 学科（已归一）→ 题型 name → 选项 0..n
        assertEquals(
            listOf("鸡兔同笼", "junior", UNGROUPED_SUBJECT, "SINGLE", "甲", "乙")
                .joinToString(QUESTION_KEY_SEP),
            incoming.importKey(),
        )
    }

    @Test
    fun `库内 storageValue 与导入项枚举统一到 name 口径`() = runBlocking {
        val stored = question(stem = "多选一道", type = QuestionType.MULTIPLE)
        val repository = repositoryOf(stored)

        val incoming = item(
            stem = "多选一道",
            type = QuestionType.MULTIPLE,
            answerIndices = listOf(0, 1),
        )

        assertTrue(
            "库里存的是 multiple，导入项是枚举 name，键必须同构",
            incoming.importKey() in repository.questionKeys(),
        )
    }

    @Test
    fun `选项或阶段不同不会被误判为同一题`() = runBlocking {
        val stored = question(stem = "同题干", stage = ExamStage.JUNIOR)
        val repository = repositoryOf(stored)
        val keys = repository.questionKeys()

        val differentOptions = item(stem = "同题干", stage = ExamStage.JUNIOR)
            .copy(options = listOf("甲", "丙"))
        val differentStage = item(stem = "同题干", stage = ExamStage.SENIOR)

        assertNotEquals(item(stem = "同题干", stage = ExamStage.JUNIOR).importKey(), differentOptions.importKey())
        assertTrue(differentOptions.importKey() !in keys)
        assertTrue(differentStage.importKey() !in keys)
    }

    @Test
    fun `坏选项JSON当空列表且不抛异常`() {
        val stored = question(stem = "脏数据").copy(optionsJson = "not-json")

        assertEquals(
            listOf("脏数据", "", UNGROUPED_SUBJECT, "SINGLE").joinToString(QUESTION_KEY_SEP),
            stored.importKey(),
        )
    }

    // ── 跨批去重：同一题第二次导入被跳过，不同题保留 ──────────────────────────

    @Test
    fun `同一题第二次导入会被跳过_不同题保留`() = runBlocking {
        val repository = repositoryOf(question(stem = "旧题"))

        val sameAsStored = item(stem = "旧题", sourceLine = 1)
        val brandNew = item(stem = "新题", sourceLine = 2)
        val duplicateInsideBatch = item(stem = "旧题", sourceLine = 3)

        val deduped = dedupeBy(
            items = listOf(sameAsStored, brandNew, duplicateInsideBatch),
            keyOf = { it.importKey() },
            displayOf = { it.stem },
            existingKeys = repository.questionKeys(),
        )

        assertEquals(listOf("新题"), deduped.kept.map { it.stem })
        assertEquals("库内已有的题、批内重复的题都要回显", listOf("旧题", "旧题"), deduped.skipped)
    }

    // ── 测试替身 ─────────────────────────────────────────────────────────────

    private fun question(
        stem: String,
        subject: String = UNGROUPED_SUBJECT,
        stage: ExamStage = ExamStage.UNSPECIFIED,
        type: QuestionType = QuestionType.SINGLE,
        options: List<String> = listOf("甲", "乙"),
    ): Question = Question(
        uuid = "q-$stem",
        subject = subject,
        stem = stem,
        optionsJson = JSONArray(options).toString(),
        answerIndex = 0,
        explanation = "",
        questionType = type.storageValue,
        examStage = stage.storageValue,
    )

    private fun item(
        stem: String,
        sourceLine: Int = 1,
        subject: String = "",
        stage: ExamStage = ExamStage.UNSPECIFIED,
        type: QuestionType = QuestionType.SINGLE,
        options: List<String> = listOf("甲", "乙"),
        answerIndices: List<Int> = listOf(0),
    ): ImportItem.Question = ImportItem.Question(
        sourceLine = sourceLine,
        raw = stem,
        stem = stem,
        options = options,
        answerIndex = 0,
        type = type,
        answerIndices = answerIndices,
        stage = stage,
        subject = subject,
    )

    private fun repositoryOf(vararg questions: Question): QuestionRepository =
        QuestionRepository(FakeQuestionDao(questions.toList()), FakePracticeDao())

    private class FakeQuestionDao(private val questions: List<Question>) : QuestionDao {
        override fun observeAll(): Flow<List<Question>> = flowOf(questions)

        override suspend fun getById(id: Long): Question? = notNeeded()
        override suspend fun getBySubject(subject: String, limit: Int, offset: Int): List<Question> = notNeeded()
        override suspend fun getBySubjectAndType(subject: String, type: String, limit: Int, offset: Int): List<Question> = notNeeded()
        override suspend fun getBySubjectFiltered(subject: String, stage: String?, type: String?, limit: Int, offset: Int): List<Question> = notNeeded()
        override suspend fun getBySubjectAndTypes(subject: String, stage: String?, types: List<String>, limit: Int, offset: Int): List<Question> = notNeeded()
        override fun observeSubjects(): Flow<List<String>> = notNeeded()
        override suspend fun countBySubject(subject: String): Int = notNeeded()
        override suspend fun countBySubjectAndType(subject: String, type: String): Int = notNeeded()
        override fun observeSubjectTypeCounts(): Flow<List<SubjectTypeCount>> = notNeeded()
        override fun observeSubjectStageFormatCounts(): Flow<List<SubjectStageFormatCount>> = notNeeded()
        override fun observeCountsByStage(): Flow<List<StageQuestionCount>> = notNeeded()
        override suspend fun getByConceptTag(tag: String): List<Question> = notNeeded()
        override suspend fun insert(question: Question): Long = notNeeded()
        override suspend fun insertAll(questions: List<Question>): List<Long> = notNeeded()
        override suspend fun deleteAll() { notNeeded<Unit>() }
    }

    private class FakePracticeDao : PracticeDao {
        override suspend fun insert(record: PracticeRecord): Long = notNeeded()
        override fun observeByQuestion(questionId: Long): Flow<List<PracticeRecord>> = notNeeded()
        override suspend fun countByQuestion(questionId: Long): Int = notNeeded()
        override fun observeTotal(): Flow<Int> = notNeeded()
        override fun observeActivityTimestamps(): Flow<List<Long>> = notNeeded()
        override suspend fun deleteAll() { notNeeded<Unit>() }
    }
}

private fun <T> notNeeded(): T = throw UnsupportedOperationException("判重测试只用到 observeAll()")
