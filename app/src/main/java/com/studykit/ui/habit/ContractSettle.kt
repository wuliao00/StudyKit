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

/**
 * 结算完一批之后**新的仪式队列**长什么样 —— [ContractsViewModel.achievedToCelebrate] 的转移函数，
 * 四条规则全在这里（协程里那一段只是调它），因为这些规则**少一条就整个功能不响**，而三种坏法在界面上
 * 只表现为"没放 / 只放了一张 / 同一张放了两遍"，没有任何日志会说话。
 *
 * 1. **只加不减**：[justAchieved] 追加在 [current] 之后（DAO 顺序即播放顺序）。覆盖式写法会当场把
 *    正在展示的仪式抹没：落库之后 Room 立刻重放 `observeAll()`，第二趟什么都结算不出来，
 *    "覆盖成本次的结果"于是等于"新的一趟是空表"。
 * 2. **跟现状对一遍，只放行还活着的**：[current] 里那一张如果在 [snapshot] 里已经没有这行（被「撤销」
 *    了，或整个库被「清除学习数据」抹了），或者已经不再是 ACHIEVED，就摘出去 —— 为一行不存在的契约
 *    摆整页仪式是句假话。
 * 3. **本趟挑出来的要一起放行**：[snapshot] 是**结算前**的那一份列表，刚被判成 ACHIEVED 的
 *    几张在里面还写着 ACTIVE。少了这一步并集，规则 2 会把规则 1 刚加进来的全筛掉，
 *    整页仪式一次都不会出现 —— 这不是假想，本仓第一版就写错在这里，症状是"功能整个不响、
 *    而且没有任何日志会说话"。
 * 4. **同一张不许进两次（按 id 幂等）**：id 已经在 [current] 里的那一张，本趟再报一次也不追加。
 *    为什么需要这一条：追加的语义是"这是本趟**新**结算出来的"，可 [justAchieved] 只保证
 *    "这张的状态在这一趟里从 ACTIVE 变成了 ACHIEVED"，它没法保证同一批落库只被看见一次 ——
 *    一批多张逐张提交时，Room 每提交一张就失效一次 `contracts` 表，中间那份快照里
 *    还没提交的那几张照旧写着 ACTIVE 且已过期，于是被再判一次、再报一次。真机上表现为
 *    三张真达成占了五个槽（「第 1 / 共 5 张」），收下时同一张放两遍。
 *    写入侧已改成整批一桩事务（`ContractRepository.updateAll`）把中间快照消掉，这条规则是
 *    套在它外面那道带扣：将来任何写路径再让 emission 交错，坏法必须是"什么也没多放"，
 *    而不是"用户为同一张契约庆祝两遍"。
 *    去重只拦**追加**：被拦下的那张照旧走规则 3 放行，留在 [current] 原来的位置上 ——
 *    连放行名单一起摘掉就变成"仪式闪一下就没了"，那是更难查的另一个坑。
 *
 * 返回的元素取自 `current + 本趟要追加的那几张`，**不会**换成 [snapshot] 里那一份：结算后的副本带着
 * `settledAt`，换成快照那份就落不了款（`contractRitualSignatureLine` 读的就是它）。
 * 收下那一张由 `dismissRitual` 从 [current] 里摘掉；本函数只从 `current + justAchieved` 里挑，
 * 所以摘掉的不会因为"库里它还是 ACHIEVED"自己长回来 —— 这也是"这张已经放过"唯一的记法，
 * 页面不再另存一份 id 清单（两处记同一件事迟早各说一套）。
 *
 * @param current 当前队列（还没被收下的那几张，按 DAO 顺序）；同一张只许占一个槽（规则 4）
 * @param justAchieved 本趟结算真判成 ACHIEVED 的那几张（[newlySettled] 的结果再按状态筛一道）；
 *   不叫 `newlyAchieved` 是为了不跟同文件那个顶层纯函数 [newlyAchieved] 撞名
 * @param snapshot 这一趟拿到的**结算前**契约列表：只用来判断"那一张还在不在库里、还是不是 ACHIEVED"
 */
internal fun nextRitualQueue(
    current: List<Contract>,
    justAchieved: List<Contract>,
    snapshot: List<Contract>,
): List<Contract> {
    // 放行名单用**整份** justAchieved，不用去掉重复之后的那份 —— 规则 4 只拦追加，
    // 拦完那张仍得留在原位，否则规则 2 会把它从队列里摘掉（见 KDoc 规则 4 末尾那段）。
    val stillAchieved = snapshot.filter { it.status == Contract.STATUS_ACHIEVED }
        .map { it.id }
        .toSet() + justAchieved.map { it.id }
    // add 返 true = 这个 id 头一回见：既挡住"已经在队列里的那张再追加一次"，
    // 也挡住同一趟里被重复报到的那一张
    val seen = current.mapTo(mutableSetOf()) { it.id }
    val appended = justAchieved.filter { seen.add(it.id) }
    return (current + appended).filter { it.id in stillAchieved }
}

/**
 * 收下某一张之后队列长什么样 —— [ContractsViewModel.dismissRitual] 的转移函数。
 *
 * 为什么要把它抽出来：裁决 R6 之后，"这张放过没有"**全靠这一手摘除**记着
 * （页面上那份重复的 id 清单已删），所以它是整条仪式链上唯一的记忆点。
 * 而记忆点留在 `ViewModel` 里的话 JVM 单测就够不着它 —— 起 `AndroidViewModel` 要
 * `Application`，连带起 `AppContainer` 与真库。抽成纯函数之后它和 [nextRitualQueue]
 * 一样可测，`dismissRitual` 只剩"调它并写回 StateFlow"这一行壳。
 *
 * 两条性质各钉一条用例（见 `ContractRitualQueueTest` 的「摘除」那组）：
 * ① **只摘点名的那一张**，别的连顺序都不动 —— 队列顺序就是播放顺序（DAO 的
 *    `deadline_epoch_day ASC`），重排会让用户先看到不该先看到的那张；
 * ② **摘掉的不许自己长回来** —— 这条看着显然，但它成立的前提是"库里它已经是 ACHIEVED，
 *    [settle] 不再动它"，而那个前提写在 [newlySettled] 里。这里钉的是形状：
 *    拿一份"库里仍是 ACHIEVED"的快照再喂一趟 [nextRitualQueue]，被摘掉的那张也不该回来
 *    （它既不在 `current` 也不在 `justAchieved`，规则 3 的放行名单救不了它）。
 *
 * id 不在队列里时原样返回：那是"同一帧里连点两次收下"或者"收下与 Room 重放撞上"，
 * 不该因此把队列清空，也不该抛。
 */
internal fun dismissedQueue(current: List<Contract>, contractId: Long): List<Contract> =
    current.filterNot { it.id == contractId }
