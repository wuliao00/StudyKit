package com.studykit.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.studykit.data.entity.Question
import kotlinx.coroutines.flow.Flow

/** 学科 × 题型的题量计数（题库页分组用）。`type` 是数据库里的小写列值 */
data class SubjectTypeCount(
    val subject: String,
    val type: String,
    val count: Int,
)

/** v3.3：学科 × 阶段 × 题型的题量计数（双行筛选条与学科卡计数用） */
data class SubjectStageFormatCount(
    val subject: String,
    val stage: String,
    val type: String,
    val count: Int,
)

@Dao
interface QuestionDao {

    @Query("SELECT * FROM questions ORDER BY id")
    fun observeAll(): Flow<List<Question>>

    @Query("SELECT * FROM questions WHERE id = :id")
    suspend fun getById(id: Long): Question?

    @Query("SELECT * FROM questions WHERE subject = :subject ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun getBySubject(subject: String, limit: Int, offset: Int): List<Question>

    /** 按学科 + 题型取题；[type] 用 [com.studykit.data.question.QuestionType.storageValue] 的小写值 */
    @Query(
        "SELECT * FROM questions WHERE subject = :subject AND question_type = :type " +
            "ORDER BY id LIMIT :limit OFFSET :offset",
    )
    suspend fun getBySubjectAndType(subject: String, type: String, limit: Int, offset: Int): List<Question>

    /**
     * v3.3：按学科 + 阶段 + 题型取题；[stage]/[type] 传 null = 不限（阶段空串是“未标注”，不是 null）。
     */
    @Query(
        "SELECT * FROM questions WHERE subject = :subject " +
            "AND (:stage IS NULL OR exam_stage = :stage) " +
            "AND (:type IS NULL OR question_type = :type) " +
            "ORDER BY id LIMIT :limit OFFSET :offset",
    )
    suspend fun getBySubjectFiltered(
        subject: String,
        stage: String?,
        type: String?,
        limit: Int,
        offset: Int,
    ): List<Question>

    /** 模考只收单值可编码的客观题（SINGLE/TRUE_FALSE），阶段可选 */
    @Query(
        "SELECT * FROM questions WHERE subject = :subject " +
            "AND (:stage IS NULL OR exam_stage = :stage) " +
            "AND question_type IN (:types) " +
            "ORDER BY id LIMIT :limit OFFSET :offset",
    )
    suspend fun getBySubjectAndTypes(
        subject: String,
        stage: String?,
        types: List<String>,
        limit: Int,
        offset: Int,
    ): List<Question>

    @Query("SELECT DISTINCT subject FROM questions ORDER BY subject")
    fun observeSubjects(): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM questions WHERE subject = :subject")
    suspend fun countBySubject(subject: String): Int

    @Query("SELECT COUNT(*) FROM questions WHERE subject = :subject AND question_type = :type")
    suspend fun countBySubjectAndType(subject: String, type: String): Int

    /**
     * 学科 × 题型题量。用 SQL 聚合而不是把全表捞进内存再 groupBy：
     * 题库可能上千题，而且这个 Flow 会在题库页常驻订阅。
     */
    @Query(
        "SELECT subject, question_type AS type, COUNT(*) AS count FROM questions " +
            "GROUP BY subject, question_type ORDER BY subject",
    )
    fun observeSubjectTypeCounts(): Flow<List<SubjectTypeCount>>

    /** v3.3：学科 × 阶段 × 题型，双行筛选的计数来源 */
    @Query(
        "SELECT subject, exam_stage AS stage, question_type AS type, COUNT(*) AS count FROM questions " +
            "GROUP BY subject, exam_stage, question_type ORDER BY subject",
    )
    fun observeSubjectStageFormatCounts(): Flow<List<SubjectStageFormatCount>>

    /**
     * 同考点候选池（v2.7 计划 B Task 15「换一道同考点的」）。
     *
     * 按 `concept_tag` **完全相等**取同标签的题（含目标自身，好让 `VariantPicker` 从池里读到目标那道
     * 的标签再排除自身；空标签的题不参与匹配 —— `''` 是「未标注」，`WHERE concept_tag = :tag` 传空串
     * 会把所有未标注的题捞进来当变式，是错的，故调用方（Repository）先挡掉空标签）。
     * `concept_tag` 列自 v7 就在，这里只加一条只读查询，没动过表结构（整库现在是 v9，
     * 抬上去的是 v3.0-B 的 `mock_sessions` 与 `mistakes` 两列，与本题无关）。
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
