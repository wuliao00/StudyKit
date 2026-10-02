package com.studykit.data.repository

import com.studykit.data.dao.MistakeDao
import com.studykit.data.entity.Mistake
import com.studykit.data.entity.MistakeRedo
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class MistakeRepository(private val mistakeDao: MistakeDao) {

    fun observeAll(): Flow<List<Mistake>> = mistakeDao.observeAll()

    fun observeBySource(source: String): Flow<List<Mistake>> = mistakeDao.observeBySource(source)

    fun observeBySubject(subject: String): Flow<List<Mistake>> = mistakeDao.observeBySubject(subject)

    fun observeUnmastered(): Flow<List<Mistake>> = mistakeDao.observeUnmastered()

    /** 已掌握一侧（列表页「已掌握」chip） */
    fun observeMastered(): Flow<List<Mistake>> = mistakeDao.observeMastered()

    fun observeById(id: Long): Flow<Mistake?> = mistakeDao.observeById(id)

    /** 按 id 单次查询（删除等操作前取最新记录用，不依赖 UI 缓存） */
    suspend fun getById(id: Long): Mistake? = mistakeDao.getById(id)

    /** 到期且未掌握的错题（复习提醒用） */
    suspend fun getDueForReview(now: Long): List<Mistake> = mistakeDao.getDueForReview(now)

    fun observeUnmasteredCount(): Flow<Int> = mistakeDao.observeUnmasteredCount()

    /**
     * 入本。
     *
     * **不预排复习时间**（v2.7 B14）：`reviewAt` 留 null、`fsrsStability` 留 null、`fsrsState` 取列
     * 默认 1=LEARNING。于是这道题要么由用户手动「覆盖排期」写进 `review_at`，要么等第一次重做评分
     * 由内核首评（`stability == null` 即 firstTime 支）写出第一次到期 —— 两条路写的是同一列。
     * 提醒侧 `getDueForReview` 带 `review_at IS NOT NULL`，所以刚入本的题不会被当成"到期"轰炸用户。
     */
    suspend fun add(
        source: String,
        subject: String,
        title: String,
        content: String,
        imagePath: String? = null,
        note: String = "",
        priority: Int = 0,
    ): Long =
        mistakeDao.insert(
            Mistake(
                uuid = UUID.randomUUID().toString(),
                source = source,
                subject = subject,
                title = title,
                imagePath = imagePath,
                content = content,
                note = note,
                priority = priority,
            ),
        )

    suspend fun update(mistake: Mistake) = mistakeDao.update(mistake)

    suspend fun delete(id: Long) = mistakeDao.deleteById(id)

    /** 标记错题为已掌握 */
    suspend fun markMastered(id: Long) = mistakeDao.markMastered(id)

    /**
     * 写一次逐次重做历史（v2.7 计划 B Task 15；`mistake_redos` 表自 v7 就在，RedoFlow 自认缺的就是这张）。
     *
     * **调用顺序必须是“先排期、再写这条历史”**，与 [com.studykit.ui.mistake.MistakeViewModel.gradeRedo] 里
     * `MistakeScheduling.grade → update` 那一段紧接着发生，理由与 `WordRepository.applyReview → recordGradedReview`
     * 完全同一个：万一中间被杀进程，“这次重做没进历史”下次重做照常发生，用户无感；
     * 反过来先写历史就会出现“历史里有一次重做、排期却没动”的行，
     * 那条记录以后会被当成一次真实评分去拟合，属于**污染校准样本**。
     *
     * @param hadNoteRebuild 拍照题重述门是否真的过了（非拍照题恒 false，见 RedoFlow 的 [com.studykit.ui.mistake.restateGateRequired]）。
     */
    suspend fun insertRedo(
        mistakeId: Long,
        correct: Boolean,
        hintsUsed: Int,
        hadNoteRebuild: Boolean,
    ): Long = mistakeDao.insertRedo(
        MistakeRedo(
            uuid = UUID.randomUUID().toString(),
            mistakeId = mistakeId,
            correct = correct,
            hintsUsed = hintsUsed,
            hadNoteRebuild = hadNoteRebuild,
        ),
    )

    /** 某道错题的逐次重做历史（按发生时刻升序）。 */
    fun observeRedosByMistake(mistakeId: Long): Flow<List<MistakeRedo>> =
        mistakeDao.observeRedosByMistake(mistakeId)

    /** 某道错题一共重做过几次。 */
    suspend fun countRedosByMistake(mistakeId: Long): Int = mistakeDao.countRedosByMistake(mistakeId)
}
