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

/**
 * 一批契约里**本次真的被结算**的那几张（返回 [settle] 判定之后的副本，调用方直接拿去落库）。
 *
 * 「本次被结算了」这条判据不写第二遍：[settle] 是唯一权威 —— 它把状态改了才算这一次结了一次账
 * （计划 R1 的口径）。防重也就落在这一条上：库里已经是 ACHIEVED / FAILED 的契约，[settle] 原样返回、
 * 状态没变，于是它**永远不再**进这份清单，达成仪式也就不会为同一张契约放第二次。
 *
 * [completedCounts] 里没有某张的计数 = 这张没被判（不当 0 次用）：调用方只给"到期且进行中"的契约
 * 查打卡计数，未到期的、已经判完的不该被查、更不该被结算 —— 见 `ContractsViewModel.settleDue`。
 *
 * 未到期与已经判完的在这里就被筛掉，所以**要落库的清单与"要不要庆祝"用的是同一份**：
 * 两者只差 [newlyAchieved] 那一步按状态过一道，不存在"写的是一套、庆祝的是另一套"的两处判定。
 */
internal fun newlySettled(
    all: List<Contract>,
    completedCounts: Map<Long, Int>,
    today: LocalDate,
): List<Contract> = all.mapNotNull { contract ->
    val count = completedCounts[contract.id] ?: return@mapNotNull null
    val settled = settle(contract = contract, completedCount = count, today = today)
    settled.takeIf { it.status != contract.status }
}

/**
 * [newlySettled] 的结果里判成 **ACHIEVED** 的那几张 —— 达成仪式只认它们。
 *
 * 未达成的不做仪式（计划 R2）：它的处理是"把当初自己写的后果原文摆出来"，已经在卡面上并且真机验过，
 * 给失败再做一个整页仪式是另一种产品气质，不在规格里。
 * 一次结算多张时这里给的就是多张，页面按队列顺序一张张放（见 `ContractsScreen`）。
 */
internal fun newlyAchieved(settled: List<Contract>): List<Contract> =
    settled.filter { it.status == Contract.STATUS_ACHIEVED }
