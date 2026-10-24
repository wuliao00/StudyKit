package com.studykit.data.question

/**
 * 考试阶段/学段。`''` = 未标注，与 `concept_tag` 的“空串=未标注”先例一致；旧题不猜阶段。
 *
 * 阶段与学科正交：数学可以出现在初中/高中/大学/考研；行测/申论是学科名，不反推阶段。
 * 阶段也不做父子层级（考研与大学并列）。
 */
enum class ExamStage(val storageValue: String, val label: String) {
    UNSPECIFIED("", "未标注"),
    JUNIOR("junior", "初中"),
    SENIOR("senior", "高中"),
    COLLEGE("college", "大学"),
    POSTGRAD("postgrad", "考研"),
    CIVIL_SERVICE("civil", "考公"),
    ;

    companion object {
        fun fromStorage(value: String?): ExamStage {
            val normalized = value?.trim().orEmpty()
            return entries.firstOrNull {
                it.storageValue.equals(normalized, ignoreCase = true) ||
                    it.name.equals(normalized, ignoreCase = true)
            } ?: UNSPECIFIED
        }

        /** 导入前缀/旧表单里的中文别名；认不出来返回 null，由调用方决定拒绝还是退回未标注 */
        fun fromAlias(raw: String): ExamStage? = when (raw.trim()) {
            "初中", "中考" -> JUNIOR
            "高中", "高考" -> SENIOR
            "大学", "本科" -> COLLEGE
            "考研", "研究生" -> POSTGRAD
            "考公", "公考", "国考", "省考" -> CIVIL_SERVICE
            "未标注", "未分类" -> UNSPECIFIED
            else -> null
        }
    }
}
