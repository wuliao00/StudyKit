package com.studykit.data.repository

import com.studykit.data.dao.WordDao
import com.studykit.data.dao.WordListDao
import com.studykit.data.entity.WordList
import kotlinx.coroutines.flow.Flow

/** 在线词库来源表；单词侧的按库删除经 [wordDao] 转发，避免调用方同时摸两个 DAO */
class WordListRepository(
    private val wordListDao: WordListDao,
    private val wordDao: WordDao,
) {

    fun observeAll(): Flow<List<WordList>> = wordListDao.observeAll()

    suspend fun add(wordList: WordList): Long = wordListDao.insert(wordList)

    suspend fun getBySourceId(sourceId: String): WordList? = wordListDao.getBySourceId(sourceId)

    suspend fun markImportedCount(id: Long, count: Int) = wordListDao.updateImportedCount(id, count)

    /** 返回被一并删除的单词条数，供结果卡展示「已撤销 N 个单词」 */
    suspend fun deleteAlongWithWords(wordList: WordList): Int {
        val removed = wordDao.deleteByList(wordList.id)
        wordListDao.delete(wordList)
        return removed
    }
}
