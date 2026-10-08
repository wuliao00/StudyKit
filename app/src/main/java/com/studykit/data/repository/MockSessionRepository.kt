package com.studykit.data.repository

import com.studykit.data.dao.MockSessionDao
import com.studykit.data.entity.MockSession

/**
 * 套卷会话仓储（v3.0-B）。
 *
 * 这层不夹逻辑，只为把 Room 挡在 ViewModel 外——与 [MistakeRepository] 同一手法，
 * 方法逐一对应 [MockSessionDao] 的同名查询，不做任何业务判断。
 *
 * 与 DAO 同步收掉三枚无人调用的成员（`observeById` / `observeRecent` / 这层的 `getById`），理由见
 * [MockSessionDao] 的类注释：恢复卷只问 [observeActive] 那一条，历史清单这一屏还不存在。
 * DAO 那个 `getById` **留着** —— 它是用例的读回原语（`MockSessionDaoTest` /
 * `MockSessionResumeWriterTest` 直接按 id 核落库那一行），不是生产路径上的死查询。
 */
class MockSessionRepository(private val dao: MockSessionDao) {

    suspend fun insert(session: MockSession): Long = dao.insert(session)

    suspend fun update(session: MockSession) = dao.update(session)

    /** 没交卷的会话（新的一条在前），供模考页问「继续上次那卷？」 */
    suspend fun observeActive(): List<MockSession> = dao.observeActive()

    suspend fun deleteById(id: Long) = dao.deleteById(id)
}
