package com.studykit.data.repository

import com.studykit.data.dao.PracticeDao
import com.studykit.data.dao.QuestionDao
import com.studykit.data.dao.SubjectStageFormatCount
import com.studykit.data.dao.SubjectTypeCount
import com.studykit.data.entity.PracticeRecord
import com.studykit.data.entity.Question
import com.studykit.data.question.ExamStage
import com.studykit.data.question.GradeResult
import com.studykit.data.question.QuestionGrader
import com.studykit.data.question.QuestionType
import com.studykit.data.question.QuizResponse
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import java.util.UUID

class QuestionRepository(
    private val questionDao: QuestionDao,
    private val practiceDao: PracticeDao,
) {

    fun observeAll(): Flow<List<Question>> = questionDao.observeAll()

    fun observeSubjects(): Flow<List<String>> = questionDao.observeSubjects()

    suspend fun getById(id: Long): Question? = questionDao.getById(id)

    suspend fun getBySubject(subject: String, limit: Int, offset: Int): List<Question> =
        questionDao.getBySubject(subject, limit, offset)

    /**
     * 按学科 + 题型取题；[type] 传 null = 不限题型（等价于旧 [getBySubject]）。
     * 旧签名保留，老 UI 不用改。
     */
    suspend fun getBySubject(
        subject: String,
        type: QuestionType?,
        limit: Int,
        offset: Int,
    ): List<Question> = getBySubject(subject, null, type, limit, offset)

    /** v3.3：按学科 + 阶段 + 题型取题；stage/type 传 null = 不限。 */
    suspend fun getBySubject(
        subject: String,
        stage: ExamStage?,
        type: QuestionType?,
        limit: Int,
        offset: Int,
    ): List<Question> = questionDao.getBySubjectFiltered(
        subject = subject,
        stage = stage?.storageValue,
        type = type?.storageValue,
        limit = limit,
        offset = offset,
    )

    /** 模考用：只取单值可编码的客观题型（SINGLE/TRUE_FALSE），阶段可选 */
    suspend fun getBySubjectAndTypes(
        subject: String,
        stage: ExamStage?,
        types: Set<QuestionType>,
        limit: Int,
        offset: Int,
    ): List<Question> = questionDao.getBySubjectAndTypes(
        subject = subject,
        stage = stage?.storageValue,
        types = types.map { it.storageValue },
        limit = limit,
        offset = offset,
    )

    suspend fun countBySubject(subject: String): Int = questionDao.countBySubject(subject)

    /** 按学科 + 题型计数；[type] 传 null = 该学科全部题型 */
    suspend fun countBySubject(subject: String, type: QuestionType?): Int = if (type == null) {
        questionDao.countBySubject(subject)
    } else {
        questionDao.countBySubjectAndType(subject, type.storageValue)
    }

    /** 学科 × 题型题量（题库页分组用） */
    fun observeSubjectTypeCounts(): Flow<List<SubjectTypeCount>> =
        questionDao.observeSubjectTypeCounts()

    /** v3.3：学科 × 阶段 × 题型题量 */
    fun observeSubjectStageFormatCounts(): Flow<List<SubjectStageFormatCount>> =
        questionDao.observeSubjectStageFormatCounts()

    /**
     * 同考点候选池（v2.7 计划 B Task 15）。[conceptTag] 为空串（未标注）时直接给空列表：
     * 空标签不构成考点，下去会把所有未标注的题当成变式。非空时返回含目标自身的同标签题，
     * 排除自身与选随机那两步交给纯函数 `VariantPicker`。
     */
    suspend fun getByConceptTag(conceptTag: String): List<Question> =
        if (conceptTag.isBlank()) emptyList() else questionDao.getByConceptTag(conceptTag)

    suspend fun add(
        subject: String,
        stem: String,
        options: List<String>,
        answerIndex: Int,
        explanation: String,
    ): Long = add(
        subject = subject,
        stage = ExamStage.UNSPECIFIED,
        stem = stem,
        options = options,
        type = QuestionType.SINGLE,
        answerIndices = listOf(answerIndex),
        answerTexts = emptyList(),
        explanation = explanation,
    )

    /** v3.3：按题型入库（未标注阶段）。单选/多选用 [answerIndices]；填空/计算用 [acceptedAnswers]。 */
    suspend fun add(
        subject: String,
        stem: String,
        options: List<String>,
        type: QuestionType,
        answerIndices: List<Int>,
        acceptedAnswers: List<String>,
        explanation: String,
    ): Long = add(
        subject = subject,
        stage = ExamStage.UNSPECIFIED,
        stem = stem,
        options = options,
        type = type,
        answerIndices = answerIndices,
        answerTexts = acceptedAnswers,
        explanation = explanation,
    )

    /**
     * v3.3：按阶段 + 题型入库。
     * [answerTexts]：填空/计算是可接受答案；简答/主观/材料是参考答案（首元素）。
     */
    suspend fun add(
        subject: String,
        stage: ExamStage,
        stem: String,
        options: List<String>,
        type: QuestionType,
        answerIndices: List<Int>,
        answerTexts: List<String>,
        explanation: String,
    ): Long {
        val optionsJson = JSONArray(options).toString()
        val indices = answerIndices.filter { it >= 0 }.distinct().sorted()
        val answerIndex = indices.firstOrNull() ?: 0
        val indicesJson = if (type == QuestionType.MULTIPLE) {
            JSONArray(indices).toString()
        } else {
            "[]"
        }
        val answersJson = JSONArray(
            answerTexts.map { it.trim() }.filter { it.isNotEmpty() },
        ).toString()
        return questionDao.insert(
            Question(
                uuid = UUID.randomUUID().toString(),
                subject = subject,
                stem = stem,
                optionsJson = optionsJson,
                answerIndex = answerIndex,
                explanation = explanation,
                questionType = type.storageValue,
                answerIndicesJson = indicesJson,
                answerTextJson = answersJson,
                examStage = stage.storageValue,
            ),
        )
    }

    /** 批量入库，返回实际写入条数；判重与 `uuid` 填充由调用方负责 */
    suspend fun addAll(questions: List<Question>): Int = questionDao.insertAll(questions).size

    /**
     * 提交一次练习作答并写入练习记录，返回本次作答是否正确。
     *
     * [confidence] 为作答前自评信心的落库整数值（编码唯一归口 [com.studykit.data.memory.toStorageInt]，不在本处重抄字面量；列口径见 [com.studykit.data.entity.PracticeRecord.confidence]）；
     * null = 用户没选或关掉信心条，落 NULL，不冒充「瞎猜」（口径同 [com.studykit.ui.study.StudyViewModel.gradeCard]）。
     *
     * 旧签名保留：内部按 [QuizResponse.Single] 处理，旧 UI/旧测试零改动。
     */
    suspend fun submitAnswer(questionId: Long, selected: Int, confidence: Int? = null): Boolean =
        submitAnswer(questionId, QuizResponse.Single(selected), confidence).correct

    /**
     * 新判分入口：读题 → [QuestionGrader] 判分 → 落一条练习记录。
     *
     * `practice_records.selected` 是旧 schema 的单个 INTEGER，不改表：
     * 单选/多选存 [QuizResponse.firstIndex]（多选取最小选中下标），填空存 -1。
     * 多选/填空的完整作答文本不落库（不扩 practice_records），只存在于返回的 [GradeResult] 里。
     *
     * 填空全不命中时 [GradeResult.needsSelfJudge] 为 true，默认仍是错；
     * 若调用方带回 [selfJudgedCorrect]，只在需要自评时用它覆盖 `correct`（不覆盖 `needsSelfJudge`，
     * 保留「这题曾需要人工复核」的事实）。多选的部分正确不覆盖 `correct`，只通过 partial* 展示。
     */
    suspend fun submitAnswer(
        questionId: Long,
        response: QuizResponse,
        confidence: Int? = null,
        selfJudgedCorrect: Boolean? = null,
    ): GradeResult {
        val question = questionDao.getById(questionId)
        val graded = if (question == null) {
            GradeResult(
                correct = false,
                optionStates = emptyMap(),
                userAnswerText = "",
                correctAnswerText = "",
            )
        } else {
            QuestionGrader.grade(question, response)
        }
        val finalCorrect = if (graded.needsSelfJudge && selfJudgedCorrect != null) {
            selfJudgedCorrect
        } else {
            graded.correct
        }
        practiceDao.insert(
            PracticeRecord(
                uuid = UUID.randomUUID().toString(),
                questionId = questionId,
                selected = response.firstIndex,
                correct = finalCorrect,
                confidence = confidence,
            ),
        )
        return graded.copy(correct = finalCorrect, selfJudged = selfJudgedCorrect != null)
    }

    fun observeRecordsByQuestion(questionId: Long): Flow<List<PracticeRecord>> =
        practiceDao.observeByQuestion(questionId)

    fun observeTotalRecords(): Flow<Int> = practiceDao.observeTotal()

    /** 全部作答时间戳（连续学习天数/今日完成数用） */
    fun observePracticeTimestamps(): Flow<List<Long>> = practiceDao.observeActivityTimestamps()
}
