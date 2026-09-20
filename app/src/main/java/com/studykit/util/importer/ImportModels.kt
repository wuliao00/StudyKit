package com.studykit.util.importer

/**
 * 一行文本解析出来的待入库条目。四种目标类型共用一套「行号 + 原文」外壳，
 * 预览页与结果页因此只需要认这一个类型。
 */
sealed interface ImportItem {
    /** 原文行号（1 基），逐行定位与「待修正」回显都靠它 */
    val sourceLine: Int
    /** 原始文本；入库成功后不再使用，仅用于展示与二次修正 */
    val raw: String

    data class Word(
        override val sourceLine: Int,
        override val raw: String,
        val word: String,
        val meaning: String,
        val example: String = "",
    ) : ImportItem

    data class Question(
        override val sourceLine: Int,
        override val raw: String,
        val stem: String,
        val options: List<String>,
        val answerIndex: Int,
        val explanation: String = "",
    ) : ImportItem

    data class Excerpt(
        override val sourceLine: Int,
        override val raw: String,
        val book: String,
        val excerpt: String,
        val thought: String = "",
    ) : ImportItem

    data class Mistake(
        override val sourceLine: Int,
        override val raw: String,
        val subject: String,
        val title: String,
        val content: String,
    ) : ImportItem
}

/** 解析失败的原因。刻意不含「未知」：新增格式时必须在这里表态 */
enum class RejectReason { EMPTY_FIELDS, TOO_FEW_COLUMNS, ANSWER_NOT_FOUND }

/** 解析失败/信息不全的行 —— 进「待修正」区，绝不静默丢弃（spec §5.1） */
data class RejectedLine(val sourceLine: Int, val raw: String, val reason: RejectReason)

/** 单行解析结果三态：可用 / 待修正 / 空行（空行不计入任何统计） */
sealed interface LineResult {
    data class Ok(val item: ImportItem) : LineResult
    data class Bad(val rejected: RejectedLine) : LineResult
    data object Blank : LineResult
}

/**
 * 一次导入的最终账目：成功条数 / 因重复被跳过的原始键 / 待修正行。
 * 放在模型层是因为预览页与结果页都要读它，而它不属于任何一个来源。
 */
data class ImportOutcome(
    val inserted: Int,
    val skippedDuplicates: List<String>,
    val rejected: List<RejectedLine>,
)

/** 一次解析的产出：可入库项 + 待修正项 + 被跳过的空行数 */
data class ImportPlan(
    val items: List<ImportItem>,
    val rejected: List<RejectedLine>,
    val blankCount: Int = 0,
) {
    val totalMeaningful: Int get() = items.size + rejected.size
}

/** 把三态结果折叠成 ImportPlan，四个解析器共用这段折叠逻辑 */
internal fun List<LineResult>.toPlan(): ImportPlan {
    val items = ArrayList<ImportItem>(size)
    val rejected = ArrayList<RejectedLine>(4)
    var blanks = 0
    forEach { result ->
        when (result) {
            is LineResult.Ok -> items.add(result.item)
            is LineResult.Bad -> rejected.add(result.rejected)
            LineResult.Blank -> blanks++
        }
    }
    return ImportPlan(items = items, rejected = rejected, blankCount = blanks)
}
