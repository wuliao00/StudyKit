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
 * 自我契约页（v2.4 批次五）：契约列表 + 到期自动对账 + 创建。
 *
 * 对账的触发点是**每次契约列表更新**：列表一变就逐张检查，ACTIVE 且已过期的查打卡计数、
 * [settle] 判定后落库。防抖就是过滤本身 —— 只有 `deadline < today` 的到期 ACTIVE 契约才会
 * 触发写库，未到期的与已对账的只是读，flow 重放不会反复 update。对账写在
 * `viewModelScope.launch` 的协程里逐张 await：Room 的 suspend 调用串行排队，天然不并发抢写。
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

    init {
        // 订阅源头 flow（不是 stateIn 的那份）：对账在 stateIn 之前发生，落库后的重放
        // 会同时刷新 contracts 与 progress 两份下游
        viewModelScope.launch {
            contractRepository.observeAll().collect { all ->
                settleDue(all)
                _progress.value = all.associate { contract -> contract.id to currentCount(contract) }
            }
        }
    }

    /**
     * 对到期的 ACTIVE 契约逐张对账并落库。
     * 计数区间 = 签约日..截止日（闭区间）：契约目标就是"这段约定里的打卡"，签约之前的
     * 历史不计入 —— precommitment 承诺的是"从现在起"。
     */
    private suspend fun settleDue(all: List<Contract>) {
        val today = LocalDate.now()
        val todayEpochDay = today.toEpochDay()
        all.filter { it.status == Contract.STATUS_ACTIVE && it.deadlineEpochDay < todayEpochDay }
            .forEach { contract ->
                val count = contractRepository.countCheckInsBetween(
                    contract.habitId,
                    LocalDate.ofEpochDay(contract.signedAtEpochDay),
                    LocalDate.ofEpochDay(contract.deadlineEpochDay),
                )
                contractRepository.update(settle(contract, count, today))
            }
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
