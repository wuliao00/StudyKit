package com.studykit.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "questions",
    indices = [Index("subject")],
)
data class Question(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    @ColumnInfo(name = "syncStatus", defaultValue = "0") val syncStatus: Int = 0,
    val subject: String,
    val stem: String,
    @ColumnInfo(name = "options_json") val optionsJson: String,
    @ColumnInfo(name = "answer_index") val answerIndex: Int,
    val explanation: String,
    /** v2.7：考点标签（spec §3.2/G7）。'' = 未标注；变式候选按它匹配，不做知识点树 */
    @ColumnInfo(name = "concept_tag", defaultValue = "''") val conceptTag: String = "",
)
