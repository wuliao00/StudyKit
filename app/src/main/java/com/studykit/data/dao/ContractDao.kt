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

    @Update
    suspend fun update(contract: Contract)

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
