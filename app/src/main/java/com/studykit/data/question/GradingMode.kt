package com.studykit.data.question

/**
 * 判分模式，由题型纯派生，不落库。
 *
 * - [AUTO_KEYED]：答案是一个/一组选项下标，可逐字判定（单选/多选/判断）。
 * - [AUTO_NORMALIZED]：答案是文本，先做规范化逐字比对（填空/计算）；不命中再让用户自评。
 * - [SELF_RUBRIC]：主观题，只能看参考答案/评分点后自评；绝不假装自动判分。
 */
enum class GradingMode { AUTO_KEYED, AUTO_NORMALIZED, SELF_RUBRIC }
