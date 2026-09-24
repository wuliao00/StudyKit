package com.studykit.data.repository

import com.studykit.data.dao.ContractDao
import com.studykit.data.dao.HabitDao
import com.studykit.data.entity.Contract
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 自我契约仓储（v2.4 批次五）：ContractDao 的薄包装 + 对账所需的打卡计数。
 *
 * 计数挂在 [HabitDao]（check_ins 的查询全部收在那一个 DAO 里，同表同 DAO），
 * 而把「日期 → 查询串」的格式化收在本类：`check_ins.date` 是 'yyyy-MM-dd' TEXT，
 * 定长零填充，字典序即日期序 —— 这个约定只在格式化这一处兑现，调用方传 LocalDate 即可。
 */
class ContractRepository(
    private val contractDao: ContractDao,
    private val habitDao: HabitDao,
) {

    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun observeAll(): Flow<List<Contract>> = contractDao.observeAll()

    suspend fun byId(id: Long): Contract? = contractDao.byId(id)

    suspend fun insert(contract: Contract): Long = contractDao.insert(contract)

    suspend fun update(contract: Contract) = contractDao.update(contract)

    /** 整表清空：与习惯同进同退，理由见 [ContractDao.deleteAll] */
    suspend fun deleteAll() = contractDao.deleteAll()

    /** 某习惯在 [from, to] 闭区间内的打卡次数（对账判据：到截止日累计打卡 ≥ goalCount） */
    suspend fun countCheckInsBetween(habitId: Long, from: LocalDate, to: LocalDate): Int =
        habitDao.countCheckIns(habitId, from.format(dateFmt), to.format(dateFmt))
}
