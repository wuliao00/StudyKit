package com.studykit.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一条用户本地设置。整张表是「键 → 值」的扁平形态，而不是单行多列，理由有二：
 *
 * 1. 加一项设置不用再动 schema（v4 之后新键只是新行），少一次迁移就少一次真机升级风险；
 * 2. 值全部走字符串，坏值可以在 [com.studykit.data.AppSettings.fromMap] 里逐项退回默认，
 *    而列类型的表在恢复了一份手改过的备份之后，是整行读不出来。
 *
 * 类型化入口只有一个：[com.studykit.data.repository.SettingsRepository]，页面不得直接摸这张表。
 */
@Entity(tableName = "app_settings")
data class AppSetting(
    @PrimaryKey
    @ColumnInfo(name = "key")
    val key: String,

    @ColumnInfo(name = "value")
    val value: String,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
)
