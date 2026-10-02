package com.studykit.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.studykit.data.entity.Mistake
import kotlinx.coroutines.flow.Flow

@Dao
interface MistakeDao {

    @Query("SELECT * FROM mistakes ORDER BY created_at DESC")
    fun observeAll(): Flow<List<Mistake>>

    @Query("SELECT * FROM mistakes WHERE source = :source ORDER BY created_at DESC")
    fun observeBySource(source: String): Flow<List<Mistake>>

    @Query("SELECT * FROM mistakes WHERE subject = :subject ORDER BY created_at DESC")
    fun observeBySubject(subject: String): Flow<List<Mistake>>

    /**
     * 未掌握错题（错题本默认列表）。
     *
     * v2.7 计划 B Task 12：先按 [Mistake.priority] 降序，把「高置信答错」的超纠正机会顶到最前，
     * 同优先级内再按 `created_at DESC`（新→旧）。下游 MistakeViewModel 只做筛选/groupBy、不再本地重排，
     * SQL 这一序会原样透到列表；[observeMastered] 一侧不置顶（已掌握无需再抢注意力），保持 `created_at DESC`。
     */
    @Query("SELECT * FROM mistakes WHERE mastered = 0 ORDER BY priority DESC, created_at DESC")
    fun observeUnmastered(): Flow<List<Mistake>>

    /** 已掌握一侧（列表页「已掌握」chip 用），与 [observeUnmastered] 合成全量 */
    @Query("SELECT * FROM mistakes WHERE mastered = 1 ORDER BY created_at DESC")
    fun observeMastered(): Flow<List<Mistake>>

    @Query("SELECT * FROM mistakes WHERE id = :id")
    fun observeById(id: Long): Flow<Mistake?>

    /** 按 id 单次查询（删除等操作前取最新记录用，不依赖 UI 缓存） */
    @Query("SELECT * FROM mistakes WHERE id = :id")
    suspend fun getById(id: Long): Mistake?

    /** 到期且未掌握的错题（复习提醒用） */
    @Query("SELECT * FROM mistakes WHERE mastered = 0 AND review_at IS NOT NULL AND review_at <= :now")
    suspend fun getDueForReview(now: Long): List<Mistake>

    @Query("SELECT COUNT(*) FROM mistakes WHERE mastered = 0")
    fun observeUnmasteredCount(): Flow<Int>

    @Query("DELETE FROM mistakes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Insert
    suspend fun insert(mistake: Mistake): Long

    @Update
    suspend fun update(mistake: Mistake)

    @Query("UPDATE mistakes SET mastered = 1 WHERE id = :id")
    suspend fun markMastered(id: Long)

    /**
     * 清除学习数据用（设置页「数据管理」）。
     *
     * 只删行、**不删图片文件**：磁盘上 `mistake_images` 目录里的那些 jpg 必须由调用方先读出
     * `image_path` 再逐张删（见 `SettingsViewModel.clearBusinessData`），
     * 顺序反了就再也找不到那些文件了。
     */
    @Query("DELETE FROM mistakes")
    suspend fun deleteAll()
}
