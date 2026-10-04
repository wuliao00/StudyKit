package com.studykit.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.studykit.optimize.ParamSnapshot

/**
 * `param_snapshots` 快照表的 DAO（v2.9 计划 Task 5）。
 *
 * 三条：追加一份、按时间倒序读回、以及"只留最近 N 份"的淘汰。这里没有 `@Update`——
 * 快照是不可变的历史留痕（改了它，"退回到那一刻的参数"这句话就失去意义），
 * 只有插入、只读查询、以及按 ts 裁尾的删除。
 *
 * 与 append-only 的 [ReviewLogDao] 不同，这张表**允许删最旧**：它的价值是"回滚几步"，
 * 不是"完整训练数据集"（完整数据在 review_log）。留 5 份够回滚又不至于无限膨胀，
 * 淘汰口径见 [pruneToMostRecent]。
 */
@Dao
interface ParamSnapshotDao {

    /** 采纳新参数前写一份快照；返回自增 id。调用点在 Task 11 的 adopt()。 */
    @Insert
    suspend fun insert(snapshot: ParamSnapshot): Long

    /** 全部按 ts 降序读回——校准卡取"最近一份"、回滚列表从新到旧都靠它。 */
    @Query("SELECT * FROM param_snapshots ORDER BY ts DESC")
    suspend fun getAll(): List<ParamSnapshot>

    /**
     * 裁到只剩最近 [keep] 份：删掉所有"不在最近 keep 个 ts 里"的行。
     * `LIMIT :keep` 的子查询取的是按 ts 降序的前 keep 个 id，其余一律删——插入第 6 份后
     * 调用它，最旧的那份就被淘汰（Task 11 护栏：写快照 → prune(5)，可回滚 5 步又不膨胀）。
     */
    @Query(
        "DELETE FROM param_snapshots WHERE id NOT IN " +
            "(SELECT id FROM param_snapshots ORDER BY ts DESC LIMIT :keep)",
    )
    suspend fun pruneToMostRecent(keep: Int)
}
