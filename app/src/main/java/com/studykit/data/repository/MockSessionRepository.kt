package com.studykit.data.repository

import com.studykit.data.dao.MockSessionDao
import com.studykit.data.entity.MockSession

/**
 * 套卷会话仓储（v3.0-B）。
 *
 * 这层不夹逻辑，只为把 Room 挡在 ViewModel 外——与 [MistakeRepository] 同一手法，
 * 方法逐一对应 [MockSessionDao] 的同名查询，不做任何业务判断。
 *
 * 与 DAO 同步收掉两枚无人调用的订阅查询（`observeById` / `observeRecent`），理由见
 * [MockSessionDao] 的类注释：恢复卷只问 [observeActive] 那一条，历史清单这一屏还不存在。
 */
class MockSessionRepository(private val dao: MockSessionDao) {

    suspend fun insert(session: MockSession): Long = dao.insert(session)

    suspend fun update(session: MockSession) = dao.update(session)

    suspend fun getById(id: Long): MockSession? = dao.getById(id)

    /** 没交卷的会话（新的一条在前），供模考页问「继续上次那卷？」 */
    suspend fun observeActive(): List<MockSession> = dao.observeActive()

    suspend fun deleteById(id: Long) = dao.deleteById(id)
}
