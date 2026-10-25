package com.studykit.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 一份「学习设计」方案（v12）。
 *
 * 8 种模板共用这一张表，而不是一种模板一张表：模板之间的差别只在字段清单与拼装顺序，
 * 差在**数据形状**上只有一份 —— 一份 `mode` + 一组键值字段 + 一段正文。分表只会让
 * 「我的方案」那条列表变成 8 次 union，而模板日后增删又要跟着搬家。
 *
 * 各列的口径：
 *  - [mode] 存 `DesignMode.name`（枚举名，不是 `label`）。中文名改字不该让旧方案变成
 *    「未知模板」；[com.studykit.domain.design.DesignMode.fromStorage] 按名字读回，
 *    认不出就返回 null，UI 显示「未知模板」而不是崩。
 *  - [fieldsJson] 是用户填的**原始字段键值** JSON（`DesignPlanCodec` 编码，键排序稳定）。
 *    留着它是为了「继续编辑」时把表单原样填回去 —— 正文是可以重拼的，但用户敲进去的
 *    内容不能反解析。改过模板（加字段、改提示语）后旧方案照样能打开，新字段留空即可。
 *  - [content] 是**保存那一刻**拼装出来的 Markdown 快照。导出、复制、分享都只读它：
 *    模板以后怎么改（哪怕某个字段被删掉），旧方案在用户眼里仍然是当初那一份，
 *    不会今天打开一个样、明年打开另一个样。代价是正文不会自动跟着模板升级 ——
 *    这是有意的，用户要新格式就重新生成、再存一次。
 *
 * `uuid` 跟着 `mistakes`/`mock_sessions` 的既有形状走，为将来同步留位；
 * `created_at` / `updated_at` 都是毫秒时间戳。索引只加在 `updated_at` 上：
 * 列表的唯一读法是「按最近修改倒序」。
 */
@Entity(tableName = "design_plans", indices = [Index(value = ["updated_at"])])
data class DesignPlan(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    val mode: String,
    val title: String,
    @ColumnInfo(name = "fields_json") val fieldsJson: String = "{}",
    val content: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
)
