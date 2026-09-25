package com.studykit.ui.habit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.studykit.data.entity.Contract
import com.studykit.data.entity.Habit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 契约卡上的习惯名。**纯函数**，传全量习惯（含已归档）：
 *
 * - 习惯还在、也没归档 → 原名；
 * - 习惯被归档 → `名字（已归档）`。归档只是从习惯页收起来，打卡记录与契约都还活着，
 *   说成"已删除"是假的（本应用没有"删除单个习惯"这个动作，只有归档和「清除学习数据」）；
 * - habit_id 指向不存在的行（清库残留、或从旧备份恢复出来的契约）→ `已删除的习惯`。
 */
internal fun contractHabitName(habitId: Long, allHabits: List<Habit>): String {
    val habit = allHabits.firstOrNull { it.id == habitId } ?: return "已删除的习惯"
    return if (habit.archived) "${habit.name}（已归档）" else habit.name
}

/**
 * 契约的对账日：取 [settle] 写进 `settledAt` 的那一天（它的口径是"当天 0 点"，见其 KDoc）。
 * 仪式落款上那个日子从这一处读，不拿 `LocalDate.now()` 顶 —— 结算发生在哪天就是哪天，
 * 用户第二天才有空打开这一页，也不该把对账日写成"今天"。
 *
 * `settledAt` 为 null 返回 null：那只能是脏数据（手改库、恢复来的旧行带着 ACHIEVED 却没有对账时刻），
 * 界面这时**不编一个日子**，这一行只署自己的名。
 */
internal fun contractSettledDate(contract: Contract): LocalDate? = contract.settledAt?.let {
    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
}

/**
 * 仪式最后一行的落款（纯函数）：`署名：X · 对账于 yyyy-MM-dd`。
 *
 * 昵称为空写「未署名」，与卡面同一份措辞（`ContractCard` 那一行）—— 不代填假名。
 * 日期用 `LocalDate.toString()`，本就是 yyyy-MM-dd，与卡面、创建弹层里那几处同一个口径。
 */
internal fun contractRitualSignatureLine(contract: Contract): String {
    val signed = "署名：${contract.signedBy.ifBlank { "未署名" }}"
    val settledOn = contractSettledDate(contract) ?: return signed
    return "$signed · 对账于 $settledOn"
}

/**
 * 仪式上那一行「还有 n 张待收下」（纯函数）：让出口**可数**（裁决 R8，措辞见 R8′）。
 *
 * 为什么要有这一行：对账跑在整个进程寿命里（`ContractsViewModel` 在 `AppNav` 根上就创建了），
 * 攒下十几张是可达状态；而仪式每收下一张才出下一张，中间没有别的出口。没有计数的时候，
 * "再点一次收下"和"这一层永远在"在界面上长得一模一样 —— 那是把用户关在一个没说清边界的房间里。
 *
 * 为什么是计数器、不是给一次访问封顶（R8 的那一行理由）：**封顶要凭空回答"剩下的什么时候算"**
 * —— 它们已经结完账了，没有哪个时刻是它们该被庆祝的；而 n 的上限就是用户自己签下的契约数，
 * 是个他造得出来、也数得完的数。
 *
 * 只有一张时返回 null：单张不需要报数（仪式那句"一句多一句都算吵"的纪律还在），
 * 这一行只在真的成批时出现。
 *
 * 队列里没有 [currentId] 也返回 null：那是"这一张正在被摘掉的路上"，宁可少一行也不编一个数。
 * [queue] 就是 [ContractsViewModel.achievedToCelebrate] 那一份队列；n 数的是还剩几张
 * （**含正在摆的这一张** —— 与旧口径一致，改的只是措辞）。
 *
 * 为什么措辞是"还剩几张"而不是"第 k / 共 n 张"：仪式永远摆队列头那一张，收下即把它摘掉，
 * 所以 k **恒等于 1** —— 一行里那个永远不动的分子读起来像卡住了，而真正在变的只有 n。
 * 旧实现里为了算 k 而有的 `index > 0` 分支从唯一调用点**不可达**，属于"给函数写的通式
 * 被当成界面会走到的档位"。现在只报 n，那个死分支一并去掉。
 */
internal fun contractRitualPositionLine(queue: List<Contract>, currentId: Long): String? {
    if (queue.size <= 1) return null
    if (queue.none { it.id == currentId }) return null
    return "还有 ${queue.size} 张待收下"
}

/**
 * 卡片上删除入口的标签（纯函数）。
 *
 * ACTIVE 用「撤销」—— 契约还没判，撤掉的是**一份自己刚签下的承诺**；
 * ACHIEVED / FAILED 用「删除」—— 账已经结完，抹掉的是**一条历史**。
 * 动词跟着契约的生命周期走，不写成统一的「删除」，是为了不把"撤掉一份进行中的约定"
 * 说成"删一条记录"（那听着像清理缓存，用户会以为撤了还能找回）。
 */
internal fun contractDeleteLabel(status: String): String =
    if (status == Contract.STATUS_ACTIVE) "撤销" else "删除"

/**
 * 确认框的标题与正文（纯函数，`danger = true` 由调用方固定给）。
 *
 * 两种文案都必须把"删了就没了"说清（计划 R3 定为物理删除、没有回收站、撤销后不可恢复），
 * 同时说清**不会**顺手带走什么 —— ACTIVE 那句要点名"已打的打卡不受影响"，
 * 因为契约与打卡是两张表，用户担心的正是"撤契约会不会把卡也抹了"。
 */
internal fun contractDeleteConfirmText(status: String): Pair<String, String> =
    if (status == Contract.STATUS_ACTIVE) {
        "撤销这份契约？" to "撤销后这条契约连同它的判定一起消失，不会留下记录；已打的打卡不受影响。"
    } else {
        "删除这条记录？" to "这是已经判完的历史，删了就找不回来。"
    }

/** 确认框里那枚确认按钮的标签（纯函数）：说清点下去发生的是撤销还是删除 */
internal fun contractDeleteConfirmLabel(status: String): String =
    if (status == Contract.STATUS_ACTIVE) "撤销契约" else "删除记录"

/**
 * 确认框里那枚放弃按钮的标签（纯函数）。
 *
 * ACTIVE 这一支**不能**吃组件默认的「取消」：入口和确认按钮的动词都是「撤销」，而
 * "取消这份契约"正是「撤销」的日常同义词 —— 想撤的人点「取消」，字面读起来是"把契约取消掉"，
 * 实际只是关掉了对话框，什么也没说明，那份契约还留在列表里。换成「留着」，
 * 与 `DictStoreScreen` 撤销词库那枚对话框同一个判断（那边的破坏性动词也是「撤销」）。
 *
 * 已结算那一支的动词是「删除」，「取消」不是它的同义词、没有这条误读，所以维持默认措辞，
 * 与设置页那三枚（动词是清空/清除/覆盖）一个口径。它仍然写成显式分支而不是省略参数：
 * "放弃按钮的措辞跟不跟状态走"这条判定要留在能被单测钉住的地方，不做内联 when。
 */
internal fun contractDeleteDismissLabel(status: String): String =
    if (status == Contract.STATUS_ACTIVE) "留着" else "取消"
