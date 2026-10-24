package com.studykit.data.question

/**
 * 题型。数据库里以**小写字符串**存储（列 `questions.question_type`，默认 `'single'`），
 * 不用 ordinal —— 枚举顺序以后可以调整，已落库的数据不该跟着变。
 *
 * 老库/老导入格式没有题型标记，一律按 [SINGLE] 解释（见 `MIGRATION_9_10` 的 DEFAULT 与
 * [fromStorage] 的兜底）。
 *
 * 判分模式由题型**纯派生**，不落库；同一个 MULTIPLE 在不同考试里的部分分规则属于
 * “评分契约”，本版统一按全对才算（部分分只展示），见 v3.3 计划文档。
 */
enum class QuestionType(
    val storageValue: String,
    val label: String,
    val gradingMode: GradingMode,
) {
    SINGLE("single", "单选", GradingMode.AUTO_KEYED),
    MULTIPLE("multiple", "多选", GradingMode.AUTO_KEYED),
    TRUE_FALSE("true_false", "判断", GradingMode.AUTO_KEYED),
    FILL("fill", "填空", GradingMode.AUTO_NORMALIZED),
    CALCULATION("calculation", "计算", GradingMode.AUTO_NORMALIZED),
    SHORT_ANSWER("short_answer", "简答", GradingMode.SELF_RUBRIC),
    ESSAY("essay", "主观", GradingMode.SELF_RUBRIC),
    CASE_ANALYSIS("case_analysis", "材料/案例", GradingMode.SELF_RUBRIC),
    ;

    /** 是否需要在录入页自己填选项（判断题选项固定为“正确/错误”，不算） */
    val usesOptionInput: Boolean get() = this == SINGLE || this == MULTIPLE

    /** 是否用可接受答案做自动规范化比对 */
    val usesAcceptedAnswers: Boolean get() = this == FILL || this == CALCULATION

    /** 是否只能看参考答案/评分点后自评 */
    val isSelfRubric: Boolean get() = gradingMode == GradingMode.SELF_RUBRIC

    /** 是否可进入模考（单下标可编码的客观题） */
    val isMockExamEligible: Boolean get() = this == SINGLE || this == TRUE_FALSE

    companion object {
        /** 认不出来（脏数据/大小写差异）时退回 [SINGLE]，与 Room 列的 DEFAULT 同口径 */
        fun fromStorage(value: String?): QuestionType {
            val normalized = value?.trim().orEmpty()
            return entries.firstOrNull {
                it.storageValue.equals(normalized, ignoreCase = true) ||
                    it.name.equals(normalized, ignoreCase = true)
            } ?: SINGLE
        }

        /** 导入前缀里的中文别名；认不出来返回 null，由调用方决定拒绝 */
        fun fromAlias(raw: String): QuestionType? = when (raw.trim()) {
            "单选", "选择", "单项选择题" -> SINGLE
            "多选", "多项选择", "不定项" -> MULTIPLE
            "判断", "判断题" -> TRUE_FALSE
            "填空", "填空题" -> FILL
            "计算", "计算题" -> CALCULATION
            "简答", "名词解释", "辨析", "简答题" -> SHORT_ANSWER
            "主观", "论述", "作文", "申论", "写作" -> ESSAY
            "材料", "材料分析", "案例", "案例分析" -> CASE_ANALYSIS
            else -> null
        }
    }
}
