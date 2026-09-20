package com.studykit.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.studykit.data.entity.WordList
import kotlinx.coroutines.flow.Flow

@Dao
interface WordListDao {

    @Insert
    suspend fun insert(wordList: WordList): Long

    @Query("SELECT * FROM word_lists ORDER BY imported_at DESC")
    fun observeAll(): Flow<List<WordList>>

    @Query("SELECT * FROM word_lists WHERE source_id = :sourceId LIMIT 1")
    suspend fun getBySourceId(sourceId: String): WordList?

    @Query("UPDATE word_lists SET imported_count = :count WHERE id = :id")
    suspend fun updateImportedCount(id: Long, count: Int)

    @Delete
    suspend fun delete(wordList: WordList)
}
