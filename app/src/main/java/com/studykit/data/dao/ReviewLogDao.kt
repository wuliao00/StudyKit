package com.studykit.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.studykit.data.entity.ReviewLog

/**
 * `review_log` 事件流的 DAO（v2.9 计划 Task 4）。
 *
 * **刻意只有两条方法**：一条追加写、一条只读有序查。这里没有、也不该有 `@Update`/`@Delete`/`@Upsert`
 * ——复习历史是端侧优化器的校准本钱，只能 append，不许原地改写。这条约束由 `ReviewLogDaoTest`
 * 反射钉死：任何往本接口塞改历史方法的做法都会让那条测试变红。
 *
 * 清理学习数据也不删这张表：优化器要靠完整轨迹才能重训，删表等于把人背过的东西一笔勾销。
 */
@Dao
interface ReviewLogDao {

    /**
     * 追加一条评分事件；返回自增 id。调用点见 Task 6 那四处（背词/练习/错题重做/书摘自评），
     * 加上 v3.0-B T12 的第五处（套卷交卷逐题），一律在既有落库之后。
     */
    @Insert
    suspend fun insert(log: ReviewLog): Long

    /**
     * 全量按 `(card_kind, card_id, ts)` 升序读回 —— 优化器（Task 7 LogPacker）要的正是
     * "同一张卡的历史按时间升序"，这样按卡分组后组内天然有序，可直接切 80/20 时序 train/val。
     * 名字里的 "Since" 指"从头到尾的完整事件流"（当前不带时间下界）：扫描键已建索引，一次有序读即可。
     */
    @Query("SELECT * FROM review_log ORDER BY card_kind, card_id, ts ASC")
    suspend fun getAllSince(): List<ReviewLog>
}
