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

    /** 提交一次练习作答并写入练习记录，返回本次作答是否正确 */
    suspend fun submitAnswer(questionId: Long, selected: Int): Boolean {
        val correct = questionDao.getById(questionId)?.answerIndex == selected
        practiceDao.insert(
            PracticeRecord(
                uuid = UUID.randomUUID().toString(),
                questionId = questionId,
                selected = selected,
                correct = correct,
            ),
        )
        return correct
    }

    fun observeRecordsByQuestion(questionId: Long): Flow<List<PracticeRecord>> =
        practiceDao.observeByQuestion(questionId)

    fun observeTotalRecords(): Flow<Int> = practiceDao.observeTotal()
}
