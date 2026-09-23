package com.studykit.ui.habit

import android.app.Application
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
