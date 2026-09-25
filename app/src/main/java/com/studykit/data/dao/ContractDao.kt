package com.studykit.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.studykit.data.entity.Contract
import kotlinx.coroutines.flow.Flow

/** 自我契约（批次五）。逻辑对账在仓库层做，这里只管存取 */
@Dao
interface ContractDao {

    @Insert
    suspend fun insert(contract: Contract): Long

    /**
     * 单张更新。
     *
     * **一次要写多张不要在这里套循环**：每张各开一次隐式事务，`contracts` 表就失效几次，
     * 观察者会收到"半新半旧"的快照 —— 批量对账走 [updateAll]，理由见那里的注释。
     */
    @Update
    suspend fun update(contract: Contract)

    /**
     * 一批契约一次写完（到期对账走这条路，见 `ContractRepository.updateAll`）。
     *
     * 为什么要有它：`@Update` 逐张提交时每张各是一次事务，Room 每提交一张就失效一次
     * `contracts` 表，于是 `observeAll()` 的观察者可能收到**部分更新**的中间快照。
     * 结算逻辑是按"这一张的状态变了没有"判定"本次结算了哪几张"的，中间快照里那些
     * 还写着 ACTIVE 且已到期的契约会被再判一次、再报一次 —— 真机上表现为达成仪式
     * 队列为三张真达成占了五个槽、同一张放两遍。整批一次写，配合仓储层的事务，
     * 失效只在全部写完之后送到观察者手里一次。
     */
    @Update
    suspend fun updateAll(contracts: List<Contract>)

    @Query("SELECT * FROM contracts ORDER BY deadline_epoch_day ASC")
    fun observeAll(): Flow<List<Contract>>

    @Query("SELECT * FROM contracts WHERE id = :id")
    suspend fun byId(id: Long): Contract?

    /**
     * 整表清空（设置页「清除学习数据」用）。
     *
     * 契约**必须**跟着习惯一起清：`Contract.habitId` 是裸的 Long，没有声明外键，
     * 所以删除习惯不会级联删除契约，只会留下一张指向不存在习惯的契约 ——
     * 进度恒为 0，到期被判成"未达成"，用户看到的是一条与自己历史无关的失败记录。
     */
    @Query("DELETE FROM contracts")
    suspend fun deleteAll()

    /**
     * 撤销单份契约（契约页「撤销」/「删除」入口）。
     *
     * **物理删除，不是软删/归档**（计划 R3）：契约本来就是一张凭据，留着一条"被撤销的契约"
     * 没有任何消费方 —— 卡面不再展示它，对账也不再碰它，统计里没有它的位置；
     * 而 `archived` 这个语义已经被习惯占用了，借来用会让两张表的"归档"含义分叉。
     * 已结算的（ACHIEVED / FAILED）同样直接删：历史的所有权属于用户，
     * app 没有权利替人留着一条他自己要抹掉的记录。
     *
     * 只删 `contracts` 这一行：`check_ins` 与契约无关（打卡记录归习惯），
     * 撤销契约不会让已经打过的卡消失。
     */
    @Query("DELETE FROM contracts WHERE id = :id")
    suspend fun deleteById(id: Long)
}
