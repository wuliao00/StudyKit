package com.studykit.data.repository

import com.studykit.data.dao.MockSessionDao
import com.studykit.data.entity.MockSession
import kotlinx.coroutines.flow.Flow

/**
 * 套卷会话仓储（v3.0-B）。
 *
 * 这层不夹逻辑，只为把 Room 挡在 ViewModel 外——与 [MistakeRepository] 同一手法，
 * 方法逐一对应 [MockSessionDao] 的同名查询，不做任何业务判断。
 */
class MockSessionRepository(private val dao: MockSessionDao) {

    suspend fun insert(session: MockSession): Long = dao.insert(session)

    suspend fun update(session: MockSession) = dao.update(session)

    suspend fun getById(id: Long): MockSession? = dao.getById(id)

    fun observeById(id: Long): Flow<MockSession?> = dao.observeById(id)

    /** 没交卷的会话（新的一条在前），供模考页问「继续上次那卷？」 */
    suspend fun observeActive(): List<MockSession> = dao.observeActive()

    /** 最近会话（含已交卷），供历史清单 */
    fun observeRecent(): Flow<List<MockSession>> = dao.observeRecent()

    suspend fun deleteById(id: Long) = dao.deleteById(id)
}
