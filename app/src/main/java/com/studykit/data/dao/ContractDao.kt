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
}
