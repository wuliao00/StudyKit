package com.studykit.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.studykit.data.entity.MockSession
import kotlinx.coroutines.flow.Flow

/**
 * 套卷会话持久化（v3.0-B）。整份读写的 JSON 列（`question_ids`/`answers`/`result`）
 * 不在 SQL 里做半结构化查询——理由见 [MockSession] 的类注释。
 */
@Dao
interface MockSessionDao {

    @Insert
    suspend fun insert(session: MockSession): Long

    @Update
    suspend fun update(session: MockSession)

    @Query("SELECT * FROM mock_sessions WHERE id = :id")
    suspend fun getById(id: Long): MockSession?

    @Query("SELECT * FROM mock_sessions WHERE id = :id")
    fun observeById(id: Long): Flow<MockSession?>

    /** 没交卷的会话（新的一条在前）：进模考页时拿它问一句「继续上次那卷？」 */
    @Query("SELECT * FROM mock_sessions WHERE state != ${MockSession.STATE_DONE} ORDER BY created_at DESC LIMIT 20")
    suspend fun observeActive(): List<MockSession>

    @Query("SELECT * FROM mock_sessions ORDER BY created_at DESC LIMIT 50")
    fun observeRecent(): Flow<List<MockSession>>

    @Query("DELETE FROM mock_sessions WHERE id = :id")
    suspend fun deleteById(id: Long)
}
