package com.studykit.ui.habit

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studykit.StudyKitApp
import com.studykit.data.entity.Contract
import com.studykit.data.entity.Habit
import com.studykit.util.OneShotGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

/**
 * 自我契约页（v2.4 批次五）：契约列表 + 到期自动对账 + 创建 + 达成仪式的触发源。
 *
 * 对账的触发点是**每次契约列表更新**：列表一变就逐张检查，ACTIVE 且已过期的查打卡计数、
 * [settle] 判定后落库。防抖就是过滤本身 —— 只有 `deadline < today` 的到期 ACTIVE 契约才会
 * 触发写库，未到期的与已对账的只是读，flow 重放不会反复 update。对账写在
 * `viewModelScope.launch` 的协程里逐张 await：Room 的 suspend 调用串行排队，天然不并发抢写。
 * 每一趟结算把真判成 ACHIEVED 的那几张推进 [achievedToCelebrate]，页面的整页仪式读它（计划 R1/R2）。
 */
class ContractsViewModel(application: Application) : AndroidViewModel(application) {

    private val contractRepository = (application as StudyKitApp).container.contractRepository
    private val habitRepository = (application as StudyKitApp).container.habitRepository
    private val settingsRepository = (application as StudyKitApp).container.settingsRepository

    /** 契约列表（按截止日升序，DAO 已排序） */
    val contracts: StateFlow<List<Contract>> = contractRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 可选习惯（创建表单的选择列表；归档的本来就不在 observeAll 里） */
    val habits: StateFlow<List<Habit>> = habitRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 全部习惯，**含已归档**：只用来给契约卡解析习惯名。
     *
     * 为什么不能拿 [habits] 那份去查：归档只是从习惯页收起来，打卡记录和契约都还在，
     * 用"看不见"的列表反查就会把一张活契约标成「已删除的习惯」—— 而"删除习惯"这个动作
     * 在本应用里根本不存在（只有归档和「清除学习数据」）。名字解析必须看全量。
     */
    val allHabits: StateFlow<List<Habit>> = habitRepository.observeAllIncludingArchived()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 每张契约的当前进度（N/goalCount 的 N），契约 id → 次数 */
    private val _progress = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val progress: StateFlow<Map<Long, Int>> = _progress.asStateFlow()

    /**
     * 等着摆整页达成仪式的契约队列（本次 `settleDue` 真判成 ACHIEVED 的那几张，按 DAO 顺序）。
     *
     * ## 触发条件是"结算发生了"，不是"卡片显示达成"（计划 R1）
     *
     * 对账是惰性的，而 `settle()` 只动 `ACTIVE && deadline < today` 的契约 —— **一张契约一生只被结算
     * 一次**，判完它就变 ACHIEVED/FAILED，之后每次 flow 重放都只是原样读出来。所以"本次 settleDue
     * 真的写成了 ACHIEVED"天然就是 once-per-contract，**不需要加"是否看过"的数据库列，也不需要
     * Room 迁移**：这条队列就是"看过没有"的载体，它活在本 ViewModel 里就够了
     * （为什么不必落库，见下面"摘除为什么必须在 ViewModel 这一侧"那一段）。
     *
     * ## 为什么是 StateFlow 队列而不是 Channel / SharedFlow（P2）
     *
     * 跟着本仓庆祝事件的既有写法走：`ui/habit/HabitListScreen.kt` 的 `celebration` 就是
     * "可观察状态 + 页面侧记住这条已经放过"（`LaunchedEffect` 把新事件写进一个 state，页面按 state
     * 挂 `ConfettiBurst`），没有事件总线也没有新库。这里同理：页面渲染队列第一条没收下的，
     * 收下时把它从队列里摘掉（[dismissRitual]），并在页面侧按契约 id 记住。
     *
     * 入队是**往上加**而不是"覆盖成本次的结果"：`settleDue` 落库之后 Room 会立刻重放
     * `observeAll()`，第二趟什么都不结算 —— 覆盖式写法会在仪式出现后的下一批重组里把队列抹成空列表，
     * 用户只看见闪一下就没了。摘出去只由 [dismissRitual] 负责。
     *
     * 摘除为什么必须在 ViewModel 这一侧：`ContractsViewModel` 是在 `AppNav` 根 composable 里
     * `viewModel()` 拿的，作用域是 **Activity**，退出契约页再进来它不会重建 —— 只靠页面的
     * `rememberSaveable` 记"这张放过"，页面一销毁那份记录就没了，队列里的旧货会被再放一次，
     * 正是 R1 要避免的噪音。页面那一层记录是第二道闸（防同一份队列在重组里重放），见 `ContractsScreen`。
     *
     * 代价（计划 R1 明写可以接受）：结算发生之后、用户收下之前整个进程被杀掉，这次仪式就没了 ——
     * 对账本身也是那一刻才发生的，卡面上的达成态仍然摆着，不补第二次庆祝。
     */
    private val _achievedToCelebrate = MutableStateFlow<List<Contract>>(emptyList())
    val achievedToCelebrate: StateFlow<List<Contract>> = _achievedToCelebrate.asStateFlow()

    init {
        // 订阅源头 flow（不是 stateIn 的那份）：对账在 stateIn 之前发生，落库后的重放
        // 会同时刷新 contracts 与 progress 两份下游
        viewModelScope.launch {
            contractRepository.observeAll().collect { all ->
                val achieved = settleDue(all)
                _progress.value = all.associate { contract -> contract.id to currentCount(contract) }
                // 队列的发布排在 progress 之后：反过来的话会有一帧"仪式已经盖住整页、进度还是空表"，
                // 仪式上就写着「进度 0/N 次」—— 那是个假数字。
                //
                // 入队是往上加（不是覆盖成本次的结果）：本趟落库之后 Room 立刻重放，第二趟什么都
                // 结算不出来，覆盖式写法会把正在展示的仪式抹没 —— 机制见 [achievedToCelebrate]。
                //
                // 加完还要跟库里的现状对一遍，只对**还活着**的：那张契约不在库里（或已经不当 ACHIEVED）
                // 就把它摘出去。挡的是"结算之后、收下之前被「清除学习数据」抹掉"那一条 ——
                // 为一行已经不存在的契约摆整页仪式是句假话。
                // 本趟的 [achieved] 必须并进来一起放行：`all` 是**结算前**的快照，那几张在库里刚变成
                // ACHIEVED，这一份快照里它们还写着 ACTIVE，不对账就等于把刚挑出来的全筛掉了。
                val stillAchieved = all.filter { it.status == Contract.STATUS_ACHIEVED }
                    .map { it.id }
                    .toSet() + achieved.map { it.id }
                _achievedToCelebrate.value =
                    (_achievedToCelebrate.value + achieved).filter { it.id in stillAchieved }
            }
        }
    }

    /**
     * 对到期的 ACTIVE 契约逐张对账并落库，返回**本次被结算成 ACHIEVED** 的契约（[newlyAchieved]）。
     *
     * 计数区间 = 签约日..截止日（闭区间）：契约目标就是"这段约定里的打卡"，签约之前的
     * 历史不计入 —— precommitment 承诺的是"从现在起"。
     *
     * 查库范围这里自己圈一次"到期 + 进行中"：未到期的与已经判完的去查打卡计数是白花钱。
     * 这不算把判定写第二遍 —— "这张到底判成什么、算不算本次结算"只由 [newlySettled] 里的
     * [settle] 说了算，这一层只是决定**要不要为它跑一次查询**。
     */
    private suspend fun settleDue(all: List<Contract>): List<Contract> {
        val today = LocalDate.now()
        val todayEpochDay = today.toEpochDay()
        val candidates = all.filter {
            it.status == Contract.STATUS_ACTIVE && it.deadlineEpochDay < todayEpochDay
        }
        if (candidates.isEmpty()) return emptyList()
        val counts = candidates.associate { contract ->
            contract.id to contractRepository.countCheckInsBetween(
                contract.habitId,
                LocalDate.ofEpochDay(contract.signedAtEpochDay),
                LocalDate.ofEpochDay(contract.deadlineEpochDay),
            )
        }
        val settledNow = newlySettled(all, counts, today)
        settledNow.forEach { contractRepository.update(it) }
        return newlyAchieved(settledNow)
    }

    /**
     * 收下某一张达成仪式：把它从队列里摘掉，页面据此决定还要不要摆下一张。
     *
     * 摘掉的这一张不会再回来：它的状态已经是 ACHIEVED，[settle] 从此不再动它，
     * 于是它也再进不了 [newlySettled] 的结果 —— 不需要任何"看过"的持久化标记。
     */
    fun dismissRitual(contractId: Long) {
        _achievedToCelebrate.value = _achievedToCelebrate.value.filterNot { it.id == contractId }
    }

    /** 进度条的 N：进行中数到今天为止，已对账的数到截止日（不再随之后的打卡变化） */
    private suspend fun currentCount(contract: Contract): Int {
        val today = LocalDate.now()
        val to = if (contract.status == Contract.STATUS_ACTIVE) {
            today.coerceAtMost(LocalDate.ofEpochDay(contract.deadlineEpochDay))
        } else {
            LocalDate.ofEpochDay(contract.deadlineEpochDay)
        }
        return contractRepository.countCheckInsBetween(
            contract.habitId,
            LocalDate.ofEpochDay(contract.signedAtEpochDay),
            to,
        )
    }

    /** 创建契约的一次性门（HabitViewModel.savingHabit 同一模式）：连点保存只放行第一次 */
    private val creatingContract = OneShotGate()

    /**
     * 撤销/删除契约的一次性门：与 [creatingContract] 同一模式，各用各的门
     * （同一份连点"签两次"和"删两次"是两回事，共用一门会互相挡）。
     */
    private val deletingContract = OneShotGate()

    /**
     * 撤销一份契约（物理删除，ACTIVE 与已结算都允许，为什么不软删见 `ContractDao.deleteById` 的注释）。
     *
     * 列表不用手动改：`contracts` 与 `progress` 都订在 `observeAll()` 上，
     * Room 在 DELETE 之后重放 flow，卡片自己就消失了 —— 手动摘 StateFlow 那一份
     * 只会让"界面"和"库"两套真相短暂分叉，重进页面还可能复活。
     *
     * 门是防连点双删：两次 DELETE 打到同一行 id 幂等无害，但第二次那一下
     * 会多跑一次 flow 重放与逐张对账，白花钱。
     * 门是**每 ViewModel 一道**而不是每份契约一道，所以极快连续确认两张不同卡片时
     * 第二张也会被挡下 —— 被挡这一下界面上不留痕迹（确认框已由调用方收掉），
     * 所以这条分支必须说话，见 [rejectWithToast]。
     */
    fun deleteContract(id: Long) {
        if (!deletingContract.tryEnter()) {
            rejectWithToast("上一条还在处理，这一下没有生效 —— 稍等一下再点一次")
            return
        }
        viewModelScope.launch {
            try {
                contractRepository.deleteById(id)
            } finally {
                deletingContract.leave()
            }
        }
    }

    /**
     * 挡下用户这一下时**一定要说一句话**（`HabitViewModel.rejectWithToast` 同一模式）。
     *
     * 本仓纪律：任何拒绝用户动作的分支都不许静默 —— 走查时不专门复现一次就永远发现不了，
     * 而用户那边是"我按了确认，什么也没发生"。措辞刻意不带动词（撤销/删除）：门是全页一道，
     * 被挡的可能是另一张卡，说"这张没删掉"就不一定成立；"这一下没有生效"只陈述本次调用
     * 没有执行，这是真的，并且给了下一步（稍等一下再点一次）。
     */
    private fun rejectWithToast(message: String) {
        Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
    }

    /**
     * 签一份契约：签名 = 设置里的昵称（SettingsScreen 的 nickname 字段），签约日 = 今天。
     * 昵称为空就存空串，展示侧显示"未署名" —— 不代填假名。
     */
    fun createContract(
        habitId: Long,
        deadline: LocalDate,
        goalCount: Int,
        promiseText: String,
        consequenceText: String,
        onSaved: () -> Unit,
    ) {
        if (!creatingContract.tryEnter()) return
        viewModelScope.launch {
            try {
                val nickname = settingsRepository.current().nickname
                contractRepository.insert(
                    Contract(
                        uuid = UUID.randomUUID().toString(),
                        habitId = habitId,
                        deadlineEpochDay = deadline.toEpochDay(),
                        goalCount = goalCount,
                        promiseText = promiseText.trim(),
                        consequenceText = consequenceText.trim(),
                        signedBy = nickname.trim(),
                        signedAtEpochDay = LocalDate.now().toEpochDay(),
                        status = Contract.STATUS_ACTIVE,
                    ),
                )
                onSaved()
            } finally {
                creatingContract.leave()
            }
        }
    }
}
