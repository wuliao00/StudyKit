package com.studykit.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.studykit.data.entity.Question
import kotlinx.coroutines.flow.Flow

@Dao
interface QuestionDao {

    @Query("SELECT * FROM questions ORDER BY id")
    fun observeAll(): Flow<List<Question>>

    @Query("SELECT * FROM questions WHERE id = :id")
    suspend fun getById(id: Long): Question?

    @Query("SELECT * FROM questions WHERE subject = :subject ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun getBySubject(subject: String, limit: Int, offset: Int): List<Question>

    @Query("SELECT DISTINCT subject FROM questions ORDER BY subject")
    fun observeSubjects(): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM questions WHERE subject = :subject")
    suspend fun countBySubject(subject: String): Int

    /**
     * 同考点候选池（v2.7 计划 B Task 15「换一道同考点的」）。
     *
     * 按 `concept_tag` **完全相等**取同标签的题（含目标自身，好让 `VariantPicker` 从池里读到目标那道
     * 的标签再排除自身；空标签的题不参与匹配 —— `''` 是「未标注」，`WHERE concept_tag = :tag` 传空串
     * 会把所有未标注的题捞进来当变式，是错的，故调用方（Repository）先挡掉空标签）。schema 冻结在 v7，
     * `concept_tag` 列自 v7 就在，这里只加一条只读查询，不动表结构。
     */
    @Query("SELECT * FROM questions WHERE concept_tag = :tag ORDER BY id")
    suspend fun getByConceptTag(tag: String): List<Question>

    @Insert
    suspend fun insert(question: Question): Long

    /** 批量入库；返回自增 id 列表，实践上与入参同序，但 Room 未承诺 —— 勿依赖顺序，只当入库计数用 */
    @Insert
    suspend fun insertAll(questions: List<Question>): List<Long>

    /** 清除学习数据用（设置页「数据管理」）。与 `practice_records` 一起清，见 PracticeDao */
    @Query("DELETE FROM questions")
    suspend fun deleteAll()
}
