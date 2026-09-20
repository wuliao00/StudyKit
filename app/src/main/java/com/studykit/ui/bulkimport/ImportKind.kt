package com.studykit.ui.bulkimport

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Star
import androidx.compose.ui.graphics.vector.ImageVector
import com.studykit.util.importer.ImportPlan
import com.studykit.util.importer.QuestionLineParser
import com.studykit.util.importer.RejectReason
import com.studykit.util.importer.WordLineParser

/**
 * 这一批导成什么：决定标题、占位文案、用哪个解析器、走哪条入库路。
 * 粘贴、SAF 文件、在线词库、OCR 四条来源共用它，所以解析入口只在这里出现一次。
 *
 * 只有 WORD 与 QUESTION 两个。摘录（`NoteLineParser` 已能解析并单测覆盖）在库里落不下：
 * `excerpts` 要求一个已存在的 `book_id`（外键级联），且没有承载「感想」的列 ——
 * 批量导摘录得先回答"导进哪本书、感想存哪儿"，那是独立一轮设计（见计划 Task 7b），
 * 不是在这里摆一个只会提示"暂不支持"的按钮。
 */
enum class ImportKind(
    val title: String,
    val placeholder: String,
    val hint: String,
    val icon: ImageVector,
) {
    WORD(
        title = "批量录入单词",
        placeholder = "abandon\tv. 放弃\nbenefit\tn. 利益",
        hint = "Tab、逗号、两个空格都能分列；第三列可选作例句",
        icon = Icons.Outlined.Star,
    ),
    QUESTION(
        title = "批量录入题目",
        placeholder = "1/2 + 1/3 = ?|5/6|1/6|1|2/5|A",
        hint = "竖线分列：题干 | 选项… | 答案（字母或选项原文）",
        icon = Icons.Outlined.CheckCircle,
    ),
    ;

    fun parse(text: String): ImportPlan = when (this) {
        WORD -> WordLineParser.parseAll(text)
        QUESTION -> QuestionLineParser.parseAll(text)
    }

    companion object {
        /** 路由串认不出来时返回 null，由调用方决定退到哪一种，而不是让 `valueOf` 抛在导航层 */
        fun fromRoute(raw: String?): ImportKind? = ImportKind.entries.firstOrNull { it.name == raw }
    }
}

/**
 * 失败原因的人类可读说法。「待修正」区必须把原因说清，否则用户不知道要改哪一列。
 * 新增 `RejectReason` 分支时编译器会强制这里补齐。
 */
internal fun RejectReason.label(): String = when (this) {
    RejectReason.EMPTY_FIELDS -> "内容为空"
    RejectReason.TOO_FEW_COLUMNS -> "列数不足"
    RejectReason.ANSWER_NOT_FOUND -> "答案无法识别（暂不支持多选题）"
}
