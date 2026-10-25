package com.studykit.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.studykit.data.entity.DesignPlan
import kotlinx.coroutines.flow.Flow

/**
 * 「学习设计」方案的读写口（v12）。
 *
 * 整份读写的 `fields_json` / `content` 不在 SQL 里做半结构化查询 —— 没有人会按
 * 「概念=机会成本」去筛方案，理由同 [MockSessionDao] 对 JSON 列的处理。
 *
 * 读侧只有两条：列表（`updated_at` 倒序，走索引）与单条订阅（编辑页要跟着保存刷新）。
 * `getById` 是保存用例的读回原语（见 `DesignPlanRepository.save`），不是给 UI 用的。
 */
@Dao
interface DesignPlanDao {

    @Query("SELECT * FROM design_plans ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<DesignPlan>>

    @Query("SELECT * FROM design_plans WHERE id = :id")
    fun observeById(id: Long): Flow<DesignPlan?>

    @Query("SELECT * FROM design_plans WHERE id = :id")
    suspend fun getById(id: Long): DesignPlan?

    @Insert
    suspend fun insert(plan: DesignPlan): Long

    @Update
    suspend fun update(plan: DesignPlan)

    @Query("DELETE FROM design_plans WHERE id = :id")
    suspend fun deleteById(id: Long)
}
