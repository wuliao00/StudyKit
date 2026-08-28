package com.studykit.data.repository

import com.studykit.data.dao.WordDao
import com.studykit.data.entity.Word
import com.studykit.data.entity.WordReview
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class WordRepository(private val wordDao: WordDao) {

    fun observeAll(): Flow<List<Word>> = wordDao.observeAll()

    suspend fun getAll(): List<Word> = wordDao.getAll()

    /** 到期待复习且未掌握的单词（复习提醒用，SQL 下推过滤） */
    suspend fun getDueForReview(now: Long): List<Word> = wordDao.getDueForReview(now)

    fun observeByStatus(status: String): Flow<List<Word>> = wordDao.observeByStatus(status)

    fun observeCount(): Flow<Int> = wordDao.observeCount()

    suspend fun add(word: String, meaning: String, example: String): Long =
        wordDao.insert(Word(uuid = UUID.randomUUID().toString(), word = word, meaning = meaning, example = example))

    suspend fun update(word: Word) = wordDao.update(word)

    suspend fun remove(word: Word) = wordDao.delete(word)

    suspend fun updateStatus(id: Long, status: String, nextReviewAt: Long = 0L) =
        wordDao.updateStatus(id, status, nextReviewAt)

    /** 写入一次学习记录 */
    suspend fun recordReview(wordId: Long, correct: Boolean): Long =
        wordDao.insertReview(
            WordReview(uuid = UUID.randomUUID().toString(), wordId = wordId, correct = correct),
        )
}
