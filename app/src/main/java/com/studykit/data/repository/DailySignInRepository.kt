package com.studykit.data.repository

import com.studykit.data.dao.DailySignInDao
import com.studykit.data.entity.DailySignIn
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/** 每日签到的薄仓储：只负责落库/订阅，连击与日历由纯函数现算。 */
class DailySignInRepository(private val dao: DailySignInDao) {

    fun observeDays(): Flow<List<String>> = dao.observeDays()

    /** @return true 表示今天这次写入成功；false 表示今天已经签过（幂等）。 */
    suspend fun signIn(today: LocalDate = LocalDate.now()): Boolean =
        dao.insert(DailySignIn(day = today.toString(), createdAt = System.currentTimeMillis())) > 0L

    suspend fun days(): List<String> = dao.allDays()
}
