package com.studykit.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 自我契约（v2.4 批次五）：监督计划的**离线替身**。
 *
 * 小计划的"监督计划"需要账号与服务器，StudyKit 没有后端 —— 这里的替代是
 * Ariely & Wertenbroch 2002 的 precommitment：把目标、期限、违约后果**写下来并签名**，
 * 到期由数据自动对账。约束力弱于真人监督，所以创建页第一句话就说清这一点，不吹。
 *
 * 目标必须是**机器能判定的结构化字段**（到截止日累计打卡 ≥ [goalCount] 次），
 * [promiseText] 只是展示用的承诺原文 —— 自由文本不当判据，否则对账就是玄学。
 */
@Entity(tableName = "contracts")
data class Contract(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    @ColumnInfo(name = "syncStatus", defaultValue = "0") val syncStatus: Int = 0,
    @ColumnInfo(name = "habit_id") val habitId: Long,
    /** 截止日（LocalDate.toEpochDay()） */
    @ColumnInfo(name = "deadline_epoch_day") val deadlineEpochDay: Long,
    /** 到截止日累计打卡 ≥ 这个次数算达成 */
    @ColumnInfo(name = "goal_count") val goalCount: Int,
    /** 承诺原文（展示用；给 if-then 模板填空，依据 Gollwitzer 1999） */
    @ColumnInfo(name = "promise_text", defaultValue = "''") val promiseText: String = "",
    /** 违约后果（自己写的，到期原样摆出来） */
    @ColumnInfo(name = "consequence_text", defaultValue = "''") val consequenceText: String = "",
    /** 签名 = 设置里的昵称；空则显示"未署名" */
    @ColumnInfo(name = "signed_by", defaultValue = "''") val signedBy: String = "",
    @ColumnInfo(name = "signed_at_epoch_day") val signedAtEpochDay: Long,
    /** ACTIVE / ACHIEVED / FAILED */
    @ColumnInfo(name = "status", defaultValue = "'ACTIVE'") val status: String = STATUS_ACTIVE,
    /** 对账时刻；未对账为 null */
    @ColumnInfo(name = "settled_at") val settledAt: Long? = null,
) {
    companion object {
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_ACHIEVED = "ACHIEVED"
        const val STATUS_FAILED = "FAILED"
    }
}
