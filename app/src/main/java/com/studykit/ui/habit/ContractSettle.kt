package com.studykit.ui.habit

import com.studykit.data.entity.Contract
import java.time.LocalDate
import java.time.ZoneId

/**
 * 自我契约对账（v2.4 批次五）：**纯函数**，把一张到期契约按事实判成达成/未达成。
 *
 * 判据是结构化字段（`completedCount >= goalCount`），承诺文案不参与判定 ——
 * 自由文本当判据，对账就是玄学（Contract 实体的红线，见其 KDoc）。
 *
 * settledAt 的口径：取 **`today` 当天 0 点的 epochMilli**（系统时区），不用 `System.currentTimeMillis()`。
 * 理由：对账是"日期级"的事实 —— 用 wall clock 会让同一张契约因对账时刻不同而拿到不同的
 * 时间戳，测试也没法不依赖当前时刻复现；0 点锚定的是"在哪一天判的"，稳定且可测。
 *
 * 防重：只有 `status == ACTIVE` 且 `deadline < today` 才对账。已对账（ACHIEVED/FAILED）的
 * 原样返回，不重算 —— 判定一旦落库就是历史，事后打卡变化不追溯。
 */
fun settle(contract: Contract, completedCount: Int, today: LocalDate): Contract {
    val deadline = LocalDate.ofEpochDay(contract.deadlineEpochDay)
    val due = contract.status == Contract.STATUS_ACTIVE && deadline.isBefore(today)
    if (!due) return contract
    return contract.copy(
        status = if (completedCount >= contract.goalCount) {
            Contract.STATUS_ACHIEVED
        } else {
            Contract.STATUS_FAILED
        },
        settledAt = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
    )
}
