package com.studykit.ui.settings

import com.studykit.data.AppSettings
import com.studykit.data.memory.ExamEntry
import java.time.LocalDate

/**
 * 考试清单编辑器的解析与校验（v3.0-B T9）。全部纯函数、零 Compose，`ExamEditorTest` 直接打。
 *
 * 日期不用 `DatePickerDialog`：本仓从来没有过 Material3 日期选择器（grep `DatePicker` 只命中
 * `Icons.Outlined.DateRange`），既有「考试日」格就是 `yyyy-MM-dd` 文本 + 防抖 + 错误串
 * （旧考试日块，现在这份形状由 `SettingsScreen` 的 `ExamRowBlock` 逐行沿用）。清单沿用同一形状，
 * 用户在旧格上会的东西到新格上一样会。
 */

/** 一门学科名的长度上限：够写「高等数学（上）」，不够把整题干粘进来 */
private const val SUBJECT_MAX_LEN = 20

/** 一科都塞不下 8 门不叫考试安排，叫抄目录 */
private const val SUBJECT_MAX_COUNT = 8

/** 逗号（半角/全角）、顿号、空白都是分隔符；去空、去重（保留首次出现顺序） */
internal fun examSubjectsFromText(raw: String): List<String> =
    raw.split(',', '，', '、', ' ', '\n', '\t', ';', '；')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { it.take(SUBJECT_MAX_LEN) }
        .distinctBy { it.lowercase() }
        .take(SUBJECT_MAX_COUNT)

internal fun examSubjectsToText(subjects: List<String>): String = subjects.joinToString("、")

internal data class ExamDraftError(
    val date: String?,
    val sprint: String?,
    val window: String?,
) {
    val hasError: Boolean get() = date != null || sprint != null || window != null
}

/** 解析冲刺值：收 `95`、`94.5` 也收 `0.95`；读不通返回 null */
private fun parseSprint(raw: String): Double? {
    val v = raw.trim().toDoubleOrNull() ?: return null
    return if (v <= 1.0) v else v / 100.0
}

internal fun validateExamDraft(dateText: String, sprintText: String, windowText: String): ExamDraftError {
    val date = dateText.trim().takeIf { it.isNotEmpty() }?.let { raw ->
        runCatching { LocalDate.parse(raw) }.getOrNull()
    }
    val dateError = when {
        date == null -> "按 2026-12-19 这样的 yyyy-MM-dd 填"
        date.toEpochDay() !in AppSettings.EXAM_DAY_RANGE ->
            "日期要在 ${LocalDate.ofEpochDay(AppSettings.EXAM_DAY_RANGE.first)} 到 " +
                LocalDate.ofEpochDay(AppSettings.EXAM_DAY_RANGE.last) + " 之间"
        else -> null
    }
    val sprint = parseSprint(sprintText)
    val sprintError = when {
        sprint == null -> "填 50 到 99 之间的数，或 0.5 到 0.99 之间的小数"
        sprint !in AppSettings.EXAM_SPRINT_RANGE -> "冲刺保留要在 50% 到 99% 之间"
        else -> null
    }
    val window = windowText.trim().toIntOrNull()
    val windowError = when {
        window == null -> "提前多少天开始冲刺，填整数（0=只在考试当天）"
        window !in AppSettings.EXAM_WINDOW_RANGE -> "提前的天数要在 0 到 ${AppSettings.EXAM_WINDOW_RANGE.last} 之间"
        else -> null
    }
    return ExamDraftError(dateError, sprintError, windowError)
}

/**
 * 草稿 -> 条目；任一字段读不通返回 null（调用方就不该把这条写进库）。
 * [fallback] 只用于「编辑已有条目时某格没改动」，新建时传一个占位条目即可。
 */
internal fun examFromDraft(
    name: String,
    dateText: String,
    subjects: List<String>,
    sprintText: String,
    windowText: String,
    fallback: ExamEntry,
): ExamEntry? {
    if (validateExamDraft(dateText, sprintText, windowText).hasError) return null
    val day = LocalDate.parse(dateText.trim()).toEpochDay()
    return ExamEntry(
        name = name.trim().take(40).ifBlank { ExamEntry.DEFAULT_NAME },
        epochDay = day,
        subjects = subjects,
        rSprint = parseSprint(sprintText) ?: fallback.rSprint,
        windowDays = windowText.trim().toIntOrNull() ?: fallback.windowDays,
    )
}

/** 文档 L1895 逐字：`考前 %1$d 天内，这科卡片的目标保留率线性提到冲刺值；复习量会涨，曲线里能看到涌量。` */
internal fun rampHint(windowDays: Int): String =
    "考前 $windowDays 天内，这科卡片的目标保留率线性提到冲刺值；复习量会涨，曲线里能看到涌量。"

/** 文档 L1896 逐字：`定时 %1$d 分钟，暂停%2$s计时。超时未答的题进待批改队列。`（%2$s=也/不） */
internal fun mockTimingHint(limitMin: Int, pauseCounts: Boolean): String {
    val timing = if (limitMin <= 0) "这套卷不限时" else "定时 $limitMin 分钟，暂停${if (pauseCounts) "也照常" else "不照常"}计时"
    return "$timing。超时未答的题进待批改队列。"
}
