package com.studykit.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.studykit.data.entity.MockSession

/**
 * 套卷会话持久化（v3.0-B）。整份读写的 JSON 列（`question_ids`/`answers`/`result`）
 * 不在 SQL 里做半结构化查询——理由见 [MockSession] 的类注释。
 *
 * v3.0-B T11 收掉两枚没人调的订阅查询（`observeById` / `observeRecent`）：整个 v3.0-B 计划里
 * 恢复卷只走 [observeActive] 那一问（进页面时手上没有 id 可订阅，而"最近的历史清单"这一屏
 * 根本不存在），留着就是让发布包里带两枚永远不会被点亮的 Flow —— Room 会为它们生成实现，
 * 而没人能为没被调用过的查询担保它以后是对的形状。要历史清单时按那时的读法重新加。
 */
@Dao
interface MockSessionDao {

    @Insert
    suspend fun insert(session: MockSession): Long

    @Update
    suspend fun update(session: MockSession)

    @Query("SELECT * FROM mock_sessions WHERE id = :id")
    suspend fun getById(id: Long): MockSession?

    /** 没交卷的会话（新的一条在前）：进模考页时拿它问一句「继续上次那卷？」 */
    @Query("SELECT * FROM mock_sessions WHERE state != ${MockSession.STATE_DONE} ORDER BY created_at DESC LIMIT 20")
    suspend fun observeActive(): List<MockSession>

    @Query("DELETE FROM mock_sessions WHERE id = :id")
    suspend fun deleteById(id: Long)
}
