package com.studykit.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 在线词库的导入来源：一行 = 一次「整本导入」。
 * `words.source_list_id` 指回这里，因此支持「删掉这本词库带进来的所有单词」——
 * 用户最怕的就是「导入 3000 个词之后没法反悔」。
 */
@Entity(
    tableName = "word_lists",
    indices = [Index(value = ["source_id"], unique = true)],
)
data class WordList(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** 词库在数据源里的标识（如 kajweb/dict 的 `CET4luan_1`），用于去重与整表删除 */
    @ColumnInfo(name = "source_id") val sourceId: String,
    val title: String,
    /** 数据源声称的词数，仅用于展示「已导入 x / 3000」 */
    @ColumnInfo(name = "word_num") val wordNum: Int = 0,
    /** 实际入库条数（去重后可能少于 wordNum） */
    @ColumnInfo(name = "imported_count") val importedCount: Int = 0,
    @ColumnInfo(name = "imported_at") val importedAt: Long = System.currentTimeMillis(),
)
