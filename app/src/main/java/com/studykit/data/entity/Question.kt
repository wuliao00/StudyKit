package com.studykit.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.studykit.data.question.ExamStage
import com.studykit.data.question.QuestionType
import org.json.JSONArray

@Entity(
    tableName = "questions",
    indices = [
        Index("subject"),
        Index(value = ["exam_stage", "question_type", "subject"]),
    ],
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
    /**
     * v10：题型（`single` / `multiple` / `fill`）。旧行由迁移/默认值落成 `single`，
     * 因此所有只认识 `answerIndex` 的老代码无需改动就能继续工作。
     */
    @ColumnInfo(name = "question_type", defaultValue = "'single'")
    val questionType: String = QuestionType.SINGLE.storageValue,
    /** v10：多选正确下标，JSON 整型数组；单选/填空为 `[]`（单选仍以 [answerIndex] 为准） */
    @ColumnInfo(name = "answer_indices_json", defaultValue = "'[]'")
    val answerIndicesJson: String = "[]",
    /** v10：填空可接受答案，JSON 字符串数组；其它题型为 `[]` */
    @ColumnInfo(name = "answer_text_json", defaultValue = "'[]'")
    val answerTextJson: String = "[]",
    /**
     * v11：考试阶段（`''` = 未标注，与 concept_tag 的先例一致）。
     * 阶段与学科正交：数学可以出现在初中/高中/大学/考研；不能把“考公”做成学科。
     */
    @ColumnInfo(name = "exam_stage", defaultValue = "''")
    val examStage: String = ExamStage.UNSPECIFIED.storageValue,
) {

    /** 列值 → 枚举。脏值/空串退回 [QuestionType.SINGLE]，与数据库 DEFAULT 一致 */
    val typeEnum: QuestionType get() = QuestionType.fromStorage(questionType)

    /** 列值 → 阶段。脏值/空串退回 [ExamStage.UNSPECIFIED] */
    val stageEnum: ExamStage get() = ExamStage.fromStorage(examStage)

    /**
     * 正确选项下标集合。
     * - 单选/判断：沿用 [answerIndex]；
     * - 多选：读 [answerIndicesJson]；JSON 为空/损坏时退回 [answerIndex]；
     * - 填空/计算/简答/主观/材料：没有选项，返回空集合。
     */
    fun correctIndices(): Set<Int> = when (typeEnum) {
        QuestionType.SINGLE, QuestionType.TRUE_FALSE -> setOf(answerIndex)
        QuestionType.MULTIPLE -> parseIntArray(answerIndicesJson)
            .ifEmpty { listOf(answerIndex) }
            .filter { it >= 0 }
            .toSet()
        QuestionType.FILL,
        QuestionType.CALCULATION,
        QuestionType.SHORT_ANSWER,
        QuestionType.ESSAY,
        QuestionType.CASE_ANALYSIS,
        -> emptySet()
    }

    /**
     * 可接受答案：只有填空/计算参与自动规范化比对。
     * 简答/主观/材料的 [answerTextJson] 存的是**参考答案**，绝不能拿来逐字匹配。
     */
    fun acceptedAnswers(): List<String> = when (typeEnum) {
        QuestionType.FILL, QuestionType.CALCULATION -> parseStringArray(answerTextJson)
        else -> emptyList()
    }

    /** 简答/主观/材料的参考答案（首元素）；其它题型为空串 */
    fun referenceAnswer(): String = when (typeEnum) {
        QuestionType.SHORT_ANSWER, QuestionType.ESSAY, QuestionType.CASE_ANALYSIS ->
            parseStringArray(answerTextJson).firstOrNull().orEmpty()
        else -> ""
    }

    private companion object {
        fun parseIntArray(json: String): List<Int> = try {
            val array = JSONArray(json)
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val value = array.optInt(i, Int.MIN_VALUE)
                    if (value != Int.MIN_VALUE) add(value)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }

        fun parseStringArray(json: String): List<String> = try {
            val array = JSONArray(json)
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    array.optString(i, "").takeIf { it.isNotEmpty() }?.let(::add)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
