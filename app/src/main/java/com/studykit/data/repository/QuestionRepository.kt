package com.studykit.data.repository

import com.studykit.data.dao.PracticeDao
import com.studykit.data.dao.QuestionDao
import com.studykit.data.entity.PracticeRecord
import com.studykit.data.entity.Question
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

    suspend fun countBySubject(subject: String): Int = questionDao.countBySubject(subject)

    /**
     * 同考点候选池（v2.7 计划 B Task 15）。[conceptTag] 为空串（未标注）时直接给空列表：
     * 空标签不构成考点，下去会把所有未标注的题当成变式。非空时返回含目标自身的同标签题，
     * 排除自身与选随机那两步交给纯函数 `VariantPicker`。
     */
    suspend fun getByConceptTag(conceptTag: String): List<Question> =
        if (conceptTag.isBlank()) emptyList() else questionDao.getByConceptTag(conceptTag)

    suspend fun add(subject: String, stem: String, options: List<String>, answerIndex: Int, explanation: String): Long {
        val array = JSONArray()
        options.forEach { array.put(it) }
        val optionsJson = array.toString()
        return questionDao.insert(
            Question(
                uuid = UUID.randomUUID().toString(),
                subject = subject,
                stem = stem,
                optionsJson = optionsJson,
                answerIndex = answerIndex,
                explanation = explanation,
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
     */
    suspend fun submitAnswer(questionId: Long, selected: Int, confidence: Int? = null): Boolean {
        val correct = questionDao.getById(questionId)?.answerIndex == selected
        practiceDao.insert(
            PracticeRecord(
                uuid = UUID.randomUUID().toString(),
                questionId = questionId,
                selected = selected,
                correct = correct,
                confidence = confidence,
            ),
        )
        return correct
    }

    fun observeRecordsByQuestion(questionId: Long): Flow<List<PracticeRecord>> =
        practiceDao.observeByQuestion(questionId)

    fun observeTotalRecords(): Flow<Int> = practiceDao.observeTotal()

    /** 全部作答时间戳（连续学习天数/今日完成数用） */
    fun observePracticeTimestamps(): Flow<List<Long>> = practiceDao.observeActivityTimestamps()
}
