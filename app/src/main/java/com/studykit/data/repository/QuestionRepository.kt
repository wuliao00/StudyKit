package com.studykit.data.repository

import com.studykit.data.dao.PracticeDao
import com.studykit.data.dao.QuestionDao
import com.studykit.data.entity.PracticeRecord
import com.studykit.data.entity.Question
import com.studykit.srs.Confidence
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import java.util.UUID

class QuestionRepository(
    private val questionDao: QuestionDao,
    private val practiceDao: PracticeDao,
) {

    /** 一轮练习默认题数 */
    companion object {
        const val SESSION_SIZE = 10
    }

    fun observeAll(): Flow<List<Question>> = questionDao.observeAll()

    fun observeSubjects(): Flow<List<String>> = questionDao.observeSubjects()

    suspend fun getById(id: Long): Question? = questionDao.getById(id)

    suspend fun getBySubject(subject: String, limit: Int, offset: Int): List<Question> =
        questionDao.getBySubject(subject, limit, offset)

    suspend fun countBySubject(subject: String): Int = questionDao.countBySubject(subject)

    /**
     * 多学科取题池：按学科分段拼接（同学科内部保持原序），
     * 打散顺序不在这里做——交错属于练习编排决策，交给上层 ReviewPlanner.interleave，
     * 这样「交错开 / 关」两种顺序都能由同一个取题结果推出来。
     */
    suspend fun getPoolBySubjects(subjects: List<String>, limit: Int = SESSION_SIZE): List<Question> {
        if (subjects.isEmpty() || limit <= 0) return emptyList()
        val perSubject = (limit + subjects.size - 1) / subjects.size
        return subjects.flatMap { questionDao.getBySubject(it, perSubject, 0) }
    }

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

    /**
     * 提交一次练习作答并写入练习记录，返回本次作答是否正确。
     *
     * confidence / hintLevel 是学习科学改造新落的两列：作答前的自评信心用于
     * 元认知校准与超纠正排期，提示档数记录「挤牙膏挤到第几档」。
     * 默认值保持旧调用方（单学科旧流程）行为不变：未评级写 -1。
     */
    suspend fun submitAnswer(
        questionId: Long,
        selected: Int,
        confidence: Confidence? = null,
        hintLevel: Int = 0,
    ): Boolean {
        val correct = questionDao.getById(questionId)?.answerIndex == selected
        practiceDao.insert(
            PracticeRecord(
                uuid = UUID.randomUUID().toString(),
                questionId = questionId,
                selected = selected,
                correct = correct,
                confidence = confidence?.level ?: -1,
                hintLevel = hintLevel.coerceAtLeast(0),
            ),
        )
        return correct
    }

    fun observeRecordsByQuestion(questionId: Long): Flow<List<PracticeRecord>> =
        practiceDao.observeByQuestion(questionId)

    fun observeTotalRecords(): Flow<Int> = practiceDao.observeTotal()
}
