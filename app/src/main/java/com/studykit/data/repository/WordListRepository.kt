package com.studykit.data.repository

import androidx.room.withTransaction
import com.studykit.data.AppDatabase
import com.studykit.data.dao.WordDao
import com.studykit.data.dao.WordListDao
import com.studykit.data.entity.WordList
import kotlinx.coroutines.flow.Flow

/**
 * 在线词库来源表。单词侧的按库删除经 [wordDao] 转发，避免调用方同时摸两个 DAO。
 *
 * 需要 [AppDatabase] 而非单个 DAO，只为「删词库 + 删单词」这一步的事务：两张表必须同生同死。
 */
class WordListRepository(
    private val db: AppDatabase,
) {

    private val wordListDao: WordListDao = db.wordListDao()

    private val wordDao: WordDao = db.wordDao()

    fun observeAll(): Flow<List<WordList>> = wordListDao.observeAll()

    /** [WordListDao.insert] 在 source_id 重复时抛异常（见 DAO 注释），调用方须先 [getBySourceId] 判重 */
    suspend fun add(wordList: WordList): Long = wordListDao.insert(wordList)

    suspend fun getBySourceId(sourceId: String): WordList? = wordListDao.getBySourceId(sourceId)

    suspend fun markImportedCount(id: Long, count: Int) = wordListDao.updateImportedCount(id, count)

    /**
     * 返回被一并删除的单词条数，供结果卡展示「已撤销 N 个单词」。
     * 删除跑在一个事务里：中途失败必须整体回滚，否则留下"词库行还在、单词已空"的幽灵条目，
     * 而它的 source_id 会挡住重新导入。
     */
    suspend fun deleteAlongWithWords(wordList: WordList): Int = db.withTransaction {
        val removed = wordDao.deleteByList(wordList.id)
        wordListDao.delete(wordList)
        removed
    }
}
