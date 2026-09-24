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
}
