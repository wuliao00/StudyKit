package com.studykit.data.repository

import com.studykit.data.dao.DailySignInDao
import com.studykit.data.entity.DailySignIn
import com.studykit.util.signin.SignInStats
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/** 每日签到的薄仓储：只负责落库/订阅，连击与日历由纯函数现算。 */
class DailySignInRepository(private val dao: DailySignInDao) {

    fun observeDays(): Flow<List<String>> = dao.observeDays()

    fun observeMakeupDays(): Flow<List<String>> = dao.observeMakeupDays()

    /** @return true 表示今天这次写入成功；false 表示今天已经签过（幂等）。 */
    suspend fun signIn(today: LocalDate = LocalDate.now()): Boolean =
        dao.insert(DailySignIn(day = today.toString(), createdAt = System.currentTimeMillis())) > 0L

    /**
     * 补签：只允许最近 3 天（不含今天/未来），且不与已有签到重复。
     * 补签计入连续/累计，但库里 makeup=true，日历会标出来。
     */
    suspend fun makeUp(date: LocalDate, today: LocalDate = LocalDate.now()): Boolean {
        if (!SignInStats.canMakeUp(date, today)) return false
        return dao.insert(
            DailySignIn(
                day = date.toString(),
                createdAt = System.currentTimeMillis(),
                makeup = true,
            ),
        ) > 0L
    }

    suspend fun days(): List<String> = dao.allDays()

    suspend fun makeupDays(): List<String> = dao.allMakeupDays()
}
