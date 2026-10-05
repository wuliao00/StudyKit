package com.studykit.ui.optimize

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.studykit.AppContainer
import com.studykit.StudyKitApp
import com.studykit.data.dao.ParamSnapshotDao
import com.studykit.data.memory.FsrsModel
import com.studykit.data.repository.ReviewLogRepository
import com.studykit.data.repository.SettingsRepository
import com.studykit.optimize.Decision
import com.studykit.optimize.EvalMetrics
import com.studykit.optimize.FsrsGate
import com.studykit.optimize.FsrsTrainer
import com.studykit.optimize.LogPacker
import com.studykit.optimize.NativeFsrsTrainer
import com.studykit.optimize.Packed
import com.studykit.optimize.ParamSnapshot
import com.studykit.optimize.TrainReport
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 校准流程对界面暴露的**唯一状态**（v2.9 计划 Task 11）。
 *
 * 四态是"一条链的形状"，不是四个页面：[Idle] → [Running] → 收在 [Result] 或 [Failed] 之一。
 * 关键区别在 [Result] 与 [Failed] 的分界**不是"有没有报错"，而是"能不能摆出一份供人点头的报告"**：
 *  - 门禁**硬门槛**不过（数据不足 / 数值异常 / 改善不足）→ [Failed]：没有报告可确认，也没有东西可采纳；
 *  - 硬门槛过了、软门槛存疑（[Decision.Manual]）→ **仍是** [Result]：报告是真的，只是要人自己看一眼再决定。
 *
 * 这条分界由 `com.studykit.optimize.CalibrateGuardrailTest` 逐条钉住。
 */
sealed class CalibrateState {

    /** 从没跑过。 */
    object Idle : CalibrateState()

    /** 正在打包 / 训练 / 评估 / 回测。设备上可能是秒级，界面据此画进度而不是禁用按钮。 */
    object Running : CalibrateState()

    /**
     * 跑出了一份可给人看的报告。
     *
     * @param report 训练器回来的参数与 loss 轨迹（**采纳时写的就是这一份**）
     * @param current 在位参数在 val 侧的指标（改善幅度的分母）
     * @param candidate 候选参数在 val 侧的同一份指标（快照里的 logloss 取于此）
     * @param decision 门禁结论；[Decision.Manual] 时 [reasons] 就是要摆给人看的理由
     * @param dryRun 这一次是干跑：报告照出，但 [CalibrateViewModel.adopt] 会拒绝写库
     * @param adopted 这份报告已经被采纳（已写快照、已换活跃参数）；界面据此把"采纳"变成"已生效"
     */
    data class Result(
        val report: TrainReport,
        val current: EvalMetrics,
        val candidate: EvalMetrics,
        val decision: Decision,
        val nTrainTerms: Int,
        val nValTerms: Int,
        val dryRun: Boolean,
        val adopted: Boolean = false,
        /** 回测在位参数的平均间隔（天）；文档 §③ 弹窗「预计平均间隔」的左值（编排层算出后带上来，供界面显示）。 */
        val intervalBefore: Float = 0f,
        /** 回测候选参数的平均间隔（天）；文档 §③ 弹窗「预计平均间隔」的右值。 */
        val intervalAfter: Float = 0f,
    ) : CalibrateState() {
        /** 需要人看一眼的理由（软门槛 + 切片注记）。 */
        val reasons: List<String> get() = reasonsOf(decision)

        /** 验证集上的相对改善（%），与门禁那条硬门槛同一口径：`(cur - cand) / cur × 100`。 */
        val improvePercent: Float
            get() = if (current.logloss > 0f && current.logloss.isFinite() && candidate.logloss.isFinite()) {
                (current.logloss - candidate.logloss) / current.logloss * 100f
            } else {
                Float.NaN
            }
    }

    /** 一句人话的失败原因（数据不足 / 训练器不可用 / 门禁拒绝 / 编排异常）。 */
    data class Failed(val reason: String) : CalibrateState()

    companion object {
        /** 门禁结论里的理由：[Decision.Manual] 的要显示，[Decision.Reject] 的在编排层已折进 [Failed.reason]。 */
        fun reasonsOf(decision: Decision): List<String> = when (decision) {
            is Decision.Manual -> decision.reasons
            is Decision.Reject -> decision.reasons
            Decision.Adopt -> emptyList()
        }
    }
}

/**
 * 端侧 FSRS 校准的**编排层**（v2.9 计划 Task 11）。
 *
 * ## 这条链做了什么
 *
 * ```
 * review_log 全量 → LogPacker.pack → FsrsTrainer.train(起点 = 在位参数)
 *   → FsrsGate.s0OrderViolation(常识校验)
 *   → FsrsTrainer.evaluate(在位) + evaluate(候选)      ← 两次都在 val 侧
 *   → FsrsGate.backtest(同一批 items × 两组参数)
 *   → FsrsGate.decide → 出 Result / Failed
 *                                    （采纳是**另一次用户动作**：adopt()）
 * ```
 *
 * ## 护栏的次序就是省钱次序
 *
 * 数据量闸放在**调用训练器之前**：设备上跑一次 AdamW 是秒级又耗电的事，而 term 数 <
 * [FsrsGate.MIN_TERMS] 时训练结果无论如何都会被门禁以"数据不足"拒掉——那就别先烧这一次电
 * （`CalibrateGuardrailTest` 直接断言训练器一次都没被叫到）。常识校验
 * （[FsrsGate.s0OrderViolation]）放在**评估与回测之前**：它只看四个数，最便宜，且一旦错序
 * 后面所有指标都不再有意义。写库只可能发生在 [adopt]，而 `adopt()` 的每一个前置条件
 * （状态是报告、不是干跑、门禁没拒、常识没违反、参数正好 [FsrsModel.NP] 项）都是护栏之一。
 *
 * 一次报告**永远不会自己换核**：`run()` 只出状态，哪怕门禁给的是 [Decision.Adopt] 也要等人（或等
 * 用户点的"采纳"）来调 [adopt]。充电+空闲那条后台链路（[CalibrateWorker]）只做干跑，写不进参数。
 *
 * ## 为什么构造器注入而不是本仓常规的 `AndroidViewModel(application)`
 *
 * 本仓的 ViewModel 一律 `AndroidViewModel` + 在构造里取 `(application as StudyKitApp).container`
 * （见 [com.studykit.ui.settings.SettingsViewModel]），`AppNav` 里直接 `viewModel()`。那个形状在
 * **纯 JVM 单测里构造不出来**（`Application` 是 android.jar 的桩，一碰就 `Stub!`）。而 Task 11 的全部
 * 价值恰恰是"编排能被单测钉住"，所以这里把依赖摊进构造器，生产装配走 [Companion.of] 与 [Factory]
 * （仍然零 Hilt，与 [NativeFsrsTrainer] 一起由 [AppContainer] 供）。这是一处**有意偏离**。
 *
 * @param trainer 训练接缝（见 [FsrsTrainer]）：真机给 [NativeFsrsTrainer]，单测给假实现
 * @param log 日志出口，默认打 logcat；单测注入一个收集器，好断言"异常没被偷偷咽掉"
 * @param now 时间源，快照的 `ts` 取自此，单测固定它才能逐字断言
 */
class CalibrateViewModel(
    private val reviewLogs: ReviewLogRepository,
    private val snapshots: ParamSnapshotDao,
    private val settings: SettingsRepository,
    private val trainer: FsrsTrainer,
    private val iters: Int = FsrsTrainer.DEFAULT_ITERS,
    private val log: (String, Throwable?) -> Unit = { msg, err ->
        if (err == null) Log.i(TAG, msg) else Log.e(TAG, msg, err)
    },
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _ui = MutableStateFlow<CalibrateState>(CalibrateState.Idle)

    /** 界面只读这一条流。 */
    val ui: StateFlow<CalibrateState> = _ui.asStateFlow()

    /**
     * 界面入口：后台跑一次校准，结果落进 [ui]。
     *
     * 正在跑的时候**直接吃掉第二次点击**，而不是排队：两次训练并发跑，慢的那一份会后落到 [ui]，
     * 于是界面可能显示一份"已经不是最新数据"的报告——那比少点一次糟糕得多。
     */
    fun start(dryRun: Boolean = false) {
        if (_ui.value is CalibrateState.Running) return
        viewModelScope.launch { run(dryRun) }
    }

    /**
     * 跑完整条链并**把结果返回**（同时落进 [ui]）。
     *
     * 单测调这一个方法而不是 [start]：`viewModelScope` 绑 `Dispatchers.Main`，纯 JVM 上没有那条调度器；
     * 把重活写成 `suspend` 之后，测试用 `runBlocking` 就能同步拿到终态，断言不用等任何人。
     * [CalibrateWorker] 也走这一条（WorkManager 自带协程，不需要 scope）。
     *
     * 本方法**永不抛**：任何异常都折成 [CalibrateState.Failed] 并带原文落一条日志——
     * 调用方可能是 WorkManager 的后台协程，异常逃出协程会落到"下一个测试"或整个进程
     * （本仓在 [com.studykit.worker.ReminderScheduler] 上为这件事烧过 10 轮 CI，见其 KDoc）。
     */
    suspend fun run(dryRun: Boolean): CalibrateState {
        _ui.value = CalibrateState.Running
        return try {
            withContext(Dispatchers.Default) { orchestrate(dryRun) }
        } catch (t: Throwable) {
            log("FSRS 校准编排异常：${t.flatMessage()}", t)
            fail("校准异常：${t.flatMessage()}")
        }
    }

    /**
     * 采纳当前这份报告：写一份 [ParamSnapshot] → 裁到最近 [ParamSnapshot.MAX_KEEP] 份 → 置活跃参数。
     *
     * 返回 false = **什么都没写**。四种情况会走到 false：
     *  1. 没有报告可采纳（还没跑过 / 上次是 Failed）；
     *  2. 那次是干跑（[CalibrateState.Result.dryRun]）——干跑的意义就是"只看报告不动核"；
     *  3. 门禁当时就拒了（状态里不可能留 Reject，这道是双保险）；
     *  4. 常识校验不过，或参数不是正好 [FsrsModel.NP] 项（同 [orchestrate]，双保险：
     *     万一有人把状态绕过编排层塞进来）。
     *
     * 先写快照、后换活跃参数：顺序反过来的话，中途进程被约会留下"新参数已生效但没有任何一份可回滚的记录"。
     * 反过来的最坏结果只是"多一份没人用的快照"，而排期行为与换核前逐比特一致。
     */
    suspend fun adopt(): Boolean {
        val state = _ui.value as? CalibrateState.Result ?: return false
        if (state.dryRun) {
            log("干跑的报告不许直接换核", null)
            return false
        }
        if (state.decision is Decision.Reject) return false
        val candidate = state.report.paramsAsDoubles()
        if (candidate.size != FsrsModel.NP) {
            log("候选参数不是 ${FsrsModel.NP} 项，拒绝写库：实际 ${candidate.size} 项", null)
            return false
        }
        FsrsGate.s0OrderViolation(candidate)?.let {
            log("采纳前常识校验不过：$it", null)
            return false
        }
        return try {
            // 串的口径与 AppSettings.fsrsActiveParams 完全同一个（逗号拼、Double.toString 可精确往返）：
            // 快照里那份与真正生效的那份必须逐字符相等，否则"回滚到上一份"会读出另一个数
            snapshots.insert(
                ParamSnapshot(
                    params = candidate.joinToString(","),
                    ts = now(),
                    nTerms = state.report.nTerms,
                    logloss = state.candidate.logloss.toDouble(),
                    engineVer = ParamSnapshot.ENGINE_VER,
                ),
            )
            snapshots.pruneToMostRecent(ParamSnapshot.MAX_KEEP)
            settings.update { it.copy(fsrsActiveParams = candidate) }
            _ui.value = state.copy(adopted = true)
            log(
                "已采纳校准参数：loss ${state.current.logloss.pct3()}→${state.candidate.logloss.pct3()}，" +
                    "快照留 ${ParamSnapshot.MAX_KEEP} 份可回滚",
                null,
            )
            true
        } catch (t: Throwable) {
            log("写快照 / 换活跃参数失败：${t.flatMessage()}", t)
            false
        }
    }

    /**
     * 恢复默认：把活跃参数清成 null（[com.studykit.ui.study.KernelHub] 随即回到
     * [FsrsModel.DEFAULT_PARAMS]），**快照一份都不动**。
     *
     * 为什么不顺手清快照：快照存在的意义是"能回到上一步"，把本钱烧掉的"恢复默认"等于把用户
     * 自己训出来的东西删了——而这条链路上"清空学习数据"本来就不删 `review_log`（Task 4 的纪律），
     * 同理这里也不删历史参数。返回 false = 没写成（读库 / 写库异常）。
     */
    suspend fun restoreDefault(): Boolean = try {
        settings.update { it.copy(fsrsActiveParams = null) }
        log("已恢复默认 FSRS 参数（快照原样保留）", null)
        true
    } catch (t: Throwable) {
        log("恢复默认失败：${t.flatMessage()}", t)
        false
    }

    // ── 编排本体 ────────────────────────────────────────────

    private suspend fun orchestrate(dryRun: Boolean): CalibrateState {
        val rows = reviewLogs.getAllSince()
        if (rows.isEmpty()) {
            return fail("还没有可校准的复习记录：先去背几天，让 review_log 攒下轨迹")
        }
        val packed = LogPacker.pack(rows)
        // ① 数据量闸在训练**之前**：不足量就别烧一次设备训练
        if (packed.nTrainTerms < FsrsGate.MIN_TERMS) {
            return fail("数据不足：${packed.nTrainTerms}/${FsrsGate.MIN_TERMS}")
        }

        val active = settings.current().fsrsActiveParams?.takeIf { it.size == FsrsModel.NP }
        val init = active ?: FsrsModel.DEFAULT_PARAMS

        // ② 训练：null = 原生不可用 / JNI 失败 → 保留旧参数，只说这一句
        val report = trainer.train(packed, init, iters)
            ?: return failWithLog("端侧训练器没跑出结果（缺少 libfsrsjni.so 或原生训练失败），已保留原参数")
        val candidate = report.paramsAsDoubles()

        // ③ 常识校验（最便宜的一道，放在所有评估与回测之前）
        FsrsGate.s0OrderViolation(candidate)?.let {
            return failWithLog("候选参数被常识校验拦下：$it")
        }

        val curMetrics = trainer.evaluate(packed, init)
            ?: return failWithLog("验证集评估没跑出结果（原生评估失败），已保留原参数")
        val candMetrics = trainer.evaluate(packed, candidate)
            ?: return failWithLog("验证集评估没跑出结果（原生评估失败），已保留原参数")

        // 回测要的是 items（Packed 只有摊平后的数组），所以同一批日志再走一次 clean + items
        val items = LogPacker.items(LogPacker.clean(rows))
        val btCur = FsrsGate.backtest(items, init)
        val btCand = FsrsGate.backtest(items, candidate)

        val decision = FsrsGate.decide(curMetrics, candMetrics, report, btCur, btCand, sliceNotes(packed))
        if (decision is Decision.Reject) {
            return failWithLog("门禁拒绝：${decision.reasons.joinToString("；")}")
        }

        val result = CalibrateState.Result(
            report = report,
            current = curMetrics,
            candidate = candMetrics,
            decision = decision,
            nTrainTerms = packed.nTrainTerms,
            nValTerms = packed.nValTerms,
            dryRun = dryRun,
            intervalBefore = btCur.meanInterval,
            intervalAfter = btCand.meanInterval,
        )
        _ui.value = result
        log(
            "校准${if (dryRun) "干跑" else "完成"}：train ${packed.nTrainTerms} 项 / val ${packed.nValTerms} 项，" +
                "loss ${curMetrics.logloss.pct3()}→${candMetrics.logloss.pct3()}，结论 ${decisionLabel(decision)}",
            null,
        )
        return result
    }

    /**
     * 切片样本注记（**只附注不拦截**，与 [FsrsGate.decide] 的 `sliceNotes` 约定一致）：
     * val 侧太薄时，那个"相对改善 ≥1%"的分母本身就不太可信，这句要让人在报告里看见。
     */
    private fun sliceNotes(packed: Packed): List<String> {
        val notes = mutableListOf<String>()
        if (packed.nValTerms < VAL_THIN_TERMS) {
            notes.add("验证集偏少（${packed.nValTerms} 项），改善幅度参考价值有限")
        }
        return notes
    }

    /** 失败出口：落状态 + 回状态，让 `return fail(...)` 读起来就是一句人话。 */
    private fun fail(reason: String): CalibrateState.Failed =
        CalibrateState.Failed(reason).also { _ui.value = it }

    /** 既要给人一句人话、又要在 logcat 留一条能查的痕的失败。 */
    private fun failWithLog(reason: String): CalibrateState.Failed = reason.also { log(it, null) }.let(::fail)

    private fun Throwable.flatMessage(): String =
        generateSequence(this) { it.cause }
            .joinToString(" / ") { it.javaClass.simpleName + ": " + (it.message ?: "") }
            .take(300)

    private fun Float.pct3(): String =
        if (!isFinite()) "NaN" else String.format(Locale.US, "%.3f", this)

    companion object {
        private const val TAG = "CalibrateViewModel"

        /** val 侧低于这么多 term 就在报告里附一句"改善幅度参考价值有限"。 */
        const val VAL_THIN_TERMS = 200

        /** 生产装配：从 [AppContainer] 起一份用原生训练器的实例。 */
        fun of(container: AppContainer): CalibrateViewModel = CalibrateViewModel(
            reviewLogs = container.reviewLogRepository,
            snapshots = container.database.paramSnapshotDao(),
            settings = container.settingsRepository,
            trainer = container.fsrsTrainer,
        )

        /**
         * 给 `viewModel(factory = CalibrateViewModel.Factory(application))` 用的工厂
         * （本仓无 Hilt；见类内 KDoc"为什么构造器注入"）。
         */
        class Factory(
            private val application: Application,
        ) : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(CalibrateViewModel::class.java)) {
                    "未知 ViewModel 类型：${modelClass.name}"
                }
                return of((application as StudyKitApp).container) as T
            }
        }

        private fun decisionLabel(decision: Decision): String = when (decision) {
            Decision.Adopt -> "可采纳"
            is Decision.Manual -> "需人工确认（${decision.reasons.joinToString("；")}）"
            is Decision.Reject -> "拒绝（${decision.reasons.joinToString("；")}）"
        }
    }
}

/**
 * 一行摘要：给 [CalibrateWorker] 存进设置当"上次干跑报告"，也给校准页的诊断段直接摆出来。
 *
 * 只记不判——这一行不参与任何排期算术，纯粹是"上一次后台跑到底怎么样了"。
 */
fun CalibrateState.describeForSettings(): String = when (this) {
    CalibrateState.Idle -> "还没跑过校准"
    CalibrateState.Running -> "校准进行中"
    is CalibrateState.Failed -> "上次校准：$reason"
    is CalibrateState.Result -> buildString {
        append(if (dryRun) "干跑" else "校准")
        append(" train ").append(nTrainTerms).append(" 项 / val ").append(nValTerms).append(" 项，loss ")
        append(String.format(Locale.US, "%.3f", current.logloss))
        append("→").append(String.format(Locale.US, "%.3f", candidate.logloss))
        val improve = improvePercent
        if (improve.isFinite()) append(String.format(Locale.US, "（%+.1f%%）", improve))
        append("，结论 ")
        when (decision) {
            Decision.Adopt -> append(if (adopted) "已采纳" else "可采纳")
            is Decision.Manual -> append("需人工确认：").append(reasons.joinToString("；"))
            is Decision.Reject -> append("拒绝：").append(reasons.joinToString("；"))
        }
    }
}
