package com.studykit.data.repository

import androidx.room.withTransaction
import com.studykit.data.AppDatabase
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
 *
 * 需要 [AppDatabase] 而非单个 DAO，只为 [updateAll] 那一步的事务：写法与
 * `WordListRepository.deleteAlongWithWords` 同源（一张表的多行必须同生同死、一起被看见）。
 */
class ContractRepository(
    private val db: AppDatabase,
) {

    private val contractDao = db.contractDao()

    private val habitDao = db.habitDao()

    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun observeAll(): Flow<List<Contract>> = contractDao.observeAll()

    suspend fun byId(id: Long): Contract? = contractDao.byId(id)

    suspend fun insert(contract: Contract): Long = contractDao.insert(contract)

    /**
     * 单张更新。批量对账**不要**在调用方套循环用它 —— 那正是重复 enqueue 的成因，
     * 一次要写多张就走 [updateAll]（细节见 `ContractDao.update` 的注释）。
     */
    suspend fun update(contract: Contract) = contractDao.update(contract)

    /**
     * 一批契约**在一个事务里**写完：整批的可见性是一次性的，Room 对 `contracts` 表的失效
     * 也就只在提交后送到观察者手里一次。
     *
     * 为什么这是硬要求而不只是"少几次写"：`ContractsViewModel` 订阅着 `observeAll()` 做对账，
     * 而"本次结算了哪几张"判的是"这张的状态变了没有"。逐张提交会送出中间快照 ——
     * 里面那些**还写着 ACTIVE 并且已过期**的契约在下一趟里被重新判一遍、重新报一遍，
     * 于是达成仪式为同一张契约放两遍（2026-09 真机走查撞到的就是这个，
     * 计数器上写着「第 1 / 共 5 张」而真达成的只有三张）。
     *
     * 空批次直接返回：不为此开一桩什么都不写的事务，免得白惊动一次订阅者。
     */
    suspend fun updateAll(contracts: List<Contract>) {
        if (contracts.isEmpty()) return
        db.withTransaction { contractDao.updateAll(contracts) }
    }

    /** 整表清空：与习惯同进同退，理由见 `ContractDao.deleteAll` */
    suspend fun deleteAll() = contractDao.deleteAll()

    /**
     * 撤销/删除单份契约：物理删，理由见 `ContractDao.deleteById`。
     * 删完不用手动刷列表 —— `observeAll()` 的 flow 会随 Room 的表失效自动重放。
     */
    suspend fun deleteById(id: Long) = contractDao.deleteById(id)

    /** 某习惯在 [from, to] 闭区间内的打卡次数（对账判据：到截止日累计打卡 ≥ goalCount） */
    suspend fun countCheckInsBetween(habitId: Long, from: LocalDate, to: LocalDate): Int =
        habitDao.countCheckIns(habitId, from.format(dateFmt), to.format(dateFmt))
}
