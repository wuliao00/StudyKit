package com.studykit.ui.habit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.data.entity.Contract
import com.studykit.data.entity.Habit
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.AppPill
import com.studykit.ui.components.AppTextField
import com.studykit.ui.components.ConfettiBurst
import com.studykit.ui.components.ConfirmDialog
import com.studykit.ui.components.EmptyState
import com.studykit.ui.motion.MotionSpec
import com.studykit.ui.theme.AppTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 期限预设档（天）。**与 spec 的偏离**：spec 写"自由日期"，这里做成 7/14/30/60 的预设 Chips ——
 * 不做日期解析 UI（手敲 yyyy-MM-dd 的校验与错误提示是设置页考试日那一格的老大难），
 * Ariely 2002 的 self-imposed deadline 结论也不依赖具体是哪天，预设档反而更接近"自己选的期限"。
 */
private val DeadlinePresets = listOf(7, 14, 30, 60)

/** 承诺文案的 if-then 模板（Gollwitzer 1999）：预填而非空框，填空处可改 */
private const val PROMISE_TEMPLATE = "如果到了【填写场景】，我就完成当日打卡。"

/**
 * 「已经收下过哪几张仪式」的存档器（契约 id 列表）。
 * 自动存档只认基础类型与 `ArrayList`，`Set` 存不进 Bundle，所以显式给一条 toLongArray 的路。
 */
private val TakenRitualsSaver: Saver<List<Long>, LongArray> = Saver(
    save = { it.toLongArray() },
    restore = { it.toList() },
)

/**
 * 自我契约页（v2.4 批次五）：监督计划的**离线替身**。
 *
 * 第一句话就是定位语 —— Ariely 意义上的 precommitment 没有外部监督人，约束力弱于真人监督，
 * 界面必须先把这个说清，不吹（设计文档 §6/§9 的硬性要求）。
 *
 * 颜色与文字只取 `AppTheme` tokens；达成态用 gold 系（goldSoft 药丸 + goldInk 字，
 * 品牌色 gold 只做卡面描边这一处非文本用途）；未达成把当初自己写的后果原文摆出来。
 * 页面结构与表单页同一套纪律：内容 Column 只有 `fillMaxSize + verticalScroll + padding(pageH)`，
 * insets 全交给 AppNav 根 Scaffold 的 innerPadding。
 *
 * 根是一层 `Box`：契约被结算成 ACHIEVED 时（[ContractsViewModel.achievedToCelebrate]，计划 R1）
 * 上面盖一整页仪式 [ContractRitualLayer]，彩带按 [ConfettiBurst] 的 KDoc 挂在**根 Box** 的
 * `matchParentSize` 层上（卡片 Surface 有圆角裁剪，粒子放进卡里会被切成方框）。
 */
@Composable
fun ContractsScreen(
    viewModel: ContractsViewModel,
    onBack: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val contracts by viewModel.contracts.collectAsStateWithLifecycle()
    val habits by viewModel.habits.collectAsStateWithLifecycle()
    val allHabits by viewModel.allHabits.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val toCelebrate by viewModel.achievedToCelebrate.collectAsStateWithLifecycle()
    var showCreate by rememberSaveable { mutableStateOf(false) }

    // 「这张已经放过」按契约 id 记住，转屏/重组不重放（第一道闸在 ViewModel 的队列里，
    // 收下时那张会被摘掉 —— 两道闸各挡什么见 ContractsViewModel.achievedToCelebrate 的说明）。
    // 必须显式给存档器：`emptyList()` 不是 ArrayList，自动存档会直接把这一格存崩（转屏即抛）。
    var takenRituals by rememberSaveable(stateSaver = TakenRitualsSaver) {
        mutableStateOf(emptyList<Long>())
    }
    // allHabits 还没吐过第一帧时先不摆仪式：那时 contractHabitName 的兜底会把一张活习惯
    // 说成「已删除的习惯」，那是句假话。习惯到得很快，晚一帧进场没人看得出来。
    val ritual = toCelebrate.firstOrNull { it.id !in takenRituals }?.takeIf { allHabits.isNotEmpty() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppTheme.space.pageH),
        ) {
            Spacer(modifier = Modifier.height(AppTheme.space.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = colors.accentInk,
                    )
                }
                Spacer(modifier = Modifier.width(AppTheme.space.xs))
                Text(text = "自我契约", style = texts.pageTitle, modifier = Modifier.weight(1f))
                if (habits.isNotEmpty()) {
                    TextButton(onClick = { showCreate = true }) {
                        Text(
                            text = "写一份",
                            style = texts.aux.copy(
                                color = colors.accentInk,
                                fontWeight = FontWeight.Medium,
                            ),
                        )
                    }
                }
            }

            // 定位语：先说清约束从哪来、弱在哪，再谈功能
            Text(
                text = "没有监督人，约束靠你自己 —— 这是把 Ariely 的 precommitment 装进单机的样子",
                style = texts.caption.copy(color = colors.secondaryText),
                modifier = Modifier.padding(top = AppTheme.space.xs),
            )
            Spacer(modifier = Modifier.height(AppTheme.space.md))

            if (contracts.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(modifier = Modifier.height(AppTheme.space.xl * 2))
                    EmptyState(
                        title = "还没有契约",
                        caption = "把目标、期限和违约后果写下来并签名，到期由数据自动对账",
                    )
                    Spacer(modifier = Modifier.height(AppTheme.space.lg))
                    // 空态这一枚按钮原来无条件可点，而表头的「写一份」是 `habits.isNotEmpty()` 才出现的
                    // —— 两条入口对同一个前提不一致。零习惯（清除学习数据之后、或还没建过习惯）时点它，
                    // 会开出一个没有单选项的弹层，「签名生效」永远灰着且不告诉为什么。
                    // 所以限制画在被限制的对象上：按钮自己变灰，旁边说清缺什么。
                    AppButton(
                        text = "签第一份契约",
                        enabled = habits.isNotEmpty(),
                        onClick = { showCreate = true },
                    )
                    if (habits.isEmpty()) {
                        Spacer(modifier = Modifier.height(AppTheme.space.sm))
                        Text(
                            text = "契约按打卡次数对账，先在「习惯」页建一个习惯",
                            style = texts.caption.copy(color = colors.secondaryText),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = AppTheme.space.lg),
                        )
                    }
                }
            } else {
                contracts.forEach { contract ->
                    ContractCard(
                        contract = contract,
                        habitName = contractHabitName(contract.habitId, allHabits),
                        completedCount = progress[contract.id] ?: 0,
                        // 删除入口回调**逐张**捕获自己的 contract：挂在页面级循环外面会删错张
                        onDelete = { viewModel.deleteContract(contract.id) },
                    )
                    Spacer(modifier = Modifier.height(AppTheme.space.md))
                }
            }

            Spacer(modifier = Modifier.height(AppTheme.space.xl))
        }

        ritual?.let { settled ->
            ContractRitualLayer(
                contract = settled,
                habitName = contractHabitName(settled.habitId, allHabits),
                completedCount = progress[settled.id] ?: 0,
                onDismiss = {
                    takenRituals = takenRituals + settled.id
                    viewModel.dismissRitual(settled.id)
                },
            )
            // 彩带挂在根 Box 上、排在仪式层之后（盖在它上面），不是仪式层的子节点：
            // 挂进卡面那种带圆角裁剪的容器里粒子出不了框，见 ConfettiBurst 的 KDoc。
            // 「减弱动效」开着时它自己不跑（组件内部早退），这一层的入场动效另有闸门。
            ConfettiBurst(
                trigger = settled.id,
                modifier = Modifier.matchParentSize(),
            )
        }
    }

    if (showCreate) {
        CreateContractDialog(
            habits = habits,
            onDismiss = { showCreate = false },
            onConfirm = { habitId, deadline, goalCount, promise, consequence ->
                // 提交后清空并收起：表单状态全部是 rememberSaveable，收起时重置
                showCreate = false
                viewModel.createContract(habitId, deadline, goalCount, promise, consequence, onSaved = { })
            },
        )
    }
}

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

/**
 * 契约卡：状态药丸 + 习惯名 + 承诺原文 + 进度 + 截止日 + 签名，右下角一枚撤销/删除入口。
 * 达成态：goldSoft 底药丸 + gold 描边整卡突出（品牌色只做描边这一处非文本用途）；
 * 未达成：warning 系药丸 + 违约后果原文 —— 当初自己写的话，原样摆出来。
 *
 * 确认框的状态开在**本卡作用域**里，并以 `contract.id` 为 remember 键（`DictStoreScreen`
 * 的 `ImportedListRow` 同一写法）：列表会因为删除而重排，不带键的话 slot 复用会把
 * "正在确认"这个布尔传给挪进来的下一张卡。
 */
@Composable
private fun ContractCard(
    contract: Contract,
    habitName: String,
    completedCount: Int,
    onDelete: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val achieved = contract.status == Contract.STATUS_ACHIEVED
    val failed = contract.status == Contract.STATUS_FAILED
    var confirming by rememberSaveable(contract.id) { mutableStateOf(false) }

    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (achieved) {
                    Modifier.border(
                        width = 1.dp,
                        color = colors.gold,
                        shape = RoundedCornerShape(AppTheme.radius.lg),
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = habitName,
                style = texts.cardTitle,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(AppTheme.space.sm))
            StatusPill(status = contract.status)
        }
        if (contract.promiseText.isNotBlank()) {
            Spacer(modifier = Modifier.height(AppTheme.space.sm))
            Text(text = "「${contract.promiseText}」", style = texts.aux)
        }
        Spacer(modifier = Modifier.height(AppTheme.space.sm))
        val goal = contract.goalCount.coerceAtLeast(1)
        Text(
            text = "进度 $completedCount/${contract.goalCount} 次",
            style = texts.caption,
        )
        Spacer(modifier = Modifier.height(AppTheme.space.xs))
        LinearProgressIndicator(
            progress = { (completedCount.toFloat() / goal).coerceIn(0f, 1f) },
            color = if (achieved) colors.gold else colors.accent,
            trackColor = colors.divider,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(AppTheme.space.sm))
        val deadline = LocalDate.ofEpochDay(contract.deadlineEpochDay)
        val today = LocalDate.now()
        Text(
            text = when {
                !failed && !achieved && deadline.isBefore(today) -> "已到期，待对账"
                !achieved && !failed -> "截止 $deadline · 剩 ${deadline.toEpochDay() - today.toEpochDay()} 天"
                else -> "截止 $deadline"
            },
            style = texts.caption,
        )
        if (failed && contract.consequenceText.isNotBlank()) {
            Spacer(modifier = Modifier.height(AppTheme.space.xs))
            Text(
                text = "当初写下的后果：${contract.consequenceText}",
                style = texts.caption.copy(color = colors.warningInk),
            )
        }
        Spacer(modifier = Modifier.height(AppTheme.space.xs))
        val signedAt = LocalDate.ofEpochDay(contract.signedAtEpochDay)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "署名：${contract.signedBy.ifBlank { "未署名" }} · $signedAt",
                style = texts.caption.copy(color = colors.secondaryText),
                modifier = Modifier.weight(1f),
            )
            // 入口是一枚有字的 TextButton（同 OrganizeScreen 的「归档」、DictStoreScreen 的「撤销」），
            // 不用图标按钮 —— contentDescription 只有读屏用户听得见，而这颗按钮是删数据的门
            TextButton(onClick = { confirming = true }) {
                Text(
                    text = contractDeleteLabel(contract.status),
                    style = texts.caption.copy(color = colors.warningInk),
                )
            }
        }
    }

    if (confirming) {
        val (title, body) = contractDeleteConfirmText(contract.status)
        ConfirmDialog(
            title = title,
            body = body,
            confirmLabel = contractDeleteConfirmLabel(contract.status),
            // 放弃按钮的措辞也按状态取：ACTIVE 那支的动词是「撤销」，默认的「取消」会和它撞车
            dismissLabel = contractDeleteDismissLabel(contract.status),
            danger = true,
            onConfirm = {
                confirming = false
                onDelete()
            },
            onDismiss = { confirming = false },
        )
    }
}

/** 状态药丸：进行中 accent 系 / 达成 gold 系（金边同族）/ 未达成 warning 系，全部 soft 底 + ink 字 */
@Composable
private fun StatusPill(status: String) {
    val colors = AppTheme.colors
    val (container, ink, label) = when (status) {
        Contract.STATUS_ACHIEVED -> Triple(colors.goldSoft, colors.goldInk, "达成")
        Contract.STATUS_FAILED -> Triple(colors.warningSoft, colors.warningInk, "未达成")
        else -> Triple(colors.accentSoft, colors.accentInk, "进行中")
    }
    AppPill(
        container = container,
        ink = ink,
        label = label,
    )
}

/**
 * 达成仪式（计划 R1/R2）：一张契约被结算成 ACHIEVED 的那一刻盖**一整页**，不是一枚 toast。
 *
 * 内容自上而下六样，一句多一句都算吵：「契约达成」/ 习惯名 / 承诺原文（引号包裹，同卡面）/
 * 进度 N/goal 次 / 落款那一行 / 一枚「收下」。不做音效、不做震动、不做分享图。
 *
 * ## 配色：只用 gold 系 tokens
 * `goldSoft` 底 + `goldInk` 字 + `gold` 描边，与卡面的达成态同族（品牌色 gold 做非文本用途
 * **只允许描边这一处**，见 `AppTheme` 的 T15 墨水纪律）。唯一的例外是「收下」那枚 [AppButton]：
 * 它吃全仓同一份实底按钮（`accentInk` 容器 + `onAccent` 字），因为 gold 压白字在浅色主题只有
 * 1.79:1 —— 按钮是操作件，不该为了配色统一把可达性押上去。
 *
 * ## 这一层盖住整页
 * 挂在 `ContractsScreen` 根 Box 上、排在页面内容之后（画在上面），底色取 `colors.background`，
 * 并用 `pointerInput` 把落在空白处的点击**吞掉**：不吞的话 Compose 的命中测试会继续往下找
 * 有输入节点的兄弟，于是仪式明明盖着页面，用户还能隔着它点到「写一份」和卡片上的「撤销」。
 *
 * ## 动效
 * 入场是淡入 + 从下方 `space.lg` 落位 + 轻微放大（[rememberRitualEntrance]，减弱动效时不跑），
 * 彩带由调用方挂在根 Box 上（`ConfettiBurst` 自己那道闸门见其 KDoc）。两处合起来才是
 * "减弱动效开着 = 这一页直接摆在那儿"。
 *
 * 这一层保持 `private`，**不**为渲染测试放开可见性：Robolectric 的渲染守卫在这个仓里给不出可复现的
 * 判定 —— 同一份测试单独跑绿、进全量跑就被同 JVM 里前一条 Robolectric 测试留下的未捕获异常毒成
 * `UncaughtExceptionsBeforeTest`（`StudyKitApp.onCreate` 起的设置观察者会在后台线程调 WorkManager，
 * 而 Robolectric 下它没初始化，异常落在哪条测试头上取决于跑序，详见 task-2 报告）。
 * 这一页的内容正确性因此由下面那六句字面量与真机复验负责；"该不该放"那条判定另有纯函数与它的
 * JVM 单测（`newlySettled` / `newlyAchieved`）。
 */
@Composable
private fun ContractRitualLayer(
    contract: Contract,
    habitName: String,
    completedCount: Int,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val shape = RoundedCornerShape(AppTheme.radius.lg)
    val entrance by rememberRitualEntrance()
    val shift = with(LocalDensity.current) { AppTheme.space.lg.toPx() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .pointerInput(Unit) { detectTapGestures { } }
            .graphicsLayer {
                // p: 0f→1f。淡入在前半程走完（`p * 2f`），落位与放大铺满全程；
                // spring 会过冲，alpha 显式钳制，scale/translation 过冲一点正是要的那下"弹到位"。
                alpha = (entrance * 2f).coerceIn(0f, 1f)
                scaleX = 0.94f + 0.06f * entrance
                scaleY = scaleX
                translationY = (1f - entrance) * shift
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppTheme.space.pageH)
                .clip(shape)
                .background(colors.goldSoft)
                .border(width = 1.dp, color = colors.gold, shape = shape)
                // 承诺是用户自己写的自由文本，长起来没有上限：这一层可滚，仪式不许把原文裁掉
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = AppTheme.space.lg,
                    vertical = AppTheme.space.xl,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "契约达成",
                style = texts.largeTitle.copy(color = colors.goldInk),
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(AppTheme.space.md))
            Text(
                text = habitName,
                style = texts.cardTitle,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            if (contract.promiseText.isNotBlank()) {
                Spacer(modifier = Modifier.height(AppTheme.space.sm))
                Text(
                    text = "「${contract.promiseText}」",
                    style = texts.body,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(modifier = Modifier.height(AppTheme.space.md))
            Text(
                text = "进度 $completedCount/${contract.goalCount} 次",
                style = texts.aux.copy(color = colors.goldInk, fontWeight = FontWeight.Medium),
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(AppTheme.space.xs))
            Text(
                text = contractRitualSignatureLine(contract),
                style = texts.caption,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(AppTheme.space.lg))
            AppButton(text = "收下", onClick = onDismiss)
        }
    }
}

/**
 * 仪式层的入场进度（0f = 还没进场，1f = 到位）。
 *
 * 「减弱动效」开着时直接给常量 1f，而且**不建补间**：早退之后 `remember` 的状态、`LaunchedEffect`
 * 与 `animateFloatAsState` 都不进组合，于是它是不跑，而不是"跑了但看不见"
 * （`MotionSpec.rememberPressScale` 同一个理由；`StaggeredIn` 也是这一手）。
 * 只把彩带关掉是不够的 —— 入场那一下缩放与位移照样会在减弱动效档里动。
 */
@Composable
private fun rememberRitualEntrance(): State<Float> {
    if (AppTheme.settings.reduceMotion) return remember { mutableFloatStateOf(1f) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    return animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = MotionSpec.snap,
        label = "contractRitualEntrance",
    )
}

/**
 * 创建契约弹层：选习惯（点选）、期限预设档（Chips，风格同 FocusScreen 的时长选择与
 * 设置卡的 ChoiceTile）、目标次数、承诺文案（预填 if-then 模板）、违约后果（选填）。
 * 确认后由调用方清空并收起（onConfirm 之后 showCreate 置 false，saveable 状态随之重置）。
 */
@Composable
private fun CreateContractDialog(
    habits: List<Habit>,
    onDismiss: () -> Unit,
    onConfirm: (habitId: Long, deadline: LocalDate, goalCount: Int, promise: String, consequence: String) -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    var habitId by rememberSaveable { mutableStateOf<Long?>(null) }
    var presetDays by rememberSaveable { mutableStateOf(DeadlinePresets.first()) }
    var goalText by rememberSaveable { mutableStateOf("") }
    var promise by rememberSaveable { mutableStateOf(PROMISE_TEMPLATE) }
    var consequence by rememberSaveable { mutableStateOf("") }

    val goalCount = goalText.trim().toIntOrNull() ?: 0
    val deadline = LocalDate.now().plusDays(presetDays.toLong())
    val canSubmit = habitId != null && goalCount >= 1 && promise.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "签一份契约", style = texts.cardTitle) },
        text = {
            // 契约字段不多，但选习惯列表可能长：给一个限高的滚动区，键盘弹起也不塌
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(text = "选一个习惯", style = texts.caption)
                Spacer(modifier = Modifier.height(AppTheme.space.xs))
                Column(modifier = Modifier.selectableGroup()) {
                    habits.forEach { habit ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(AppTheme.radius.md))
                                .selectable(
                                    selected = habitId == habit.id,
                                    role = Role.RadioButton,
                                    onClick = { habitId = habit.id },
                                )
                                .padding(vertical = AppTheme.space.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .background(
                                        color = if (habitId == habit.id) colors.accentSoft else colors.card,
                                        shape = RoundedCornerShape(AppTheme.radius.sm),
                                    )
                                    .padding(horizontal = AppTheme.space.sm, vertical = AppTheme.space.xs),
                            ) {
                                Text(
                                    text = habit.name,
                                    style = texts.aux.copy(
                                        color = if (habitId == habit.id) colors.accentInk else colors.primaryText,
                                        fontWeight = if (habitId == habit.id) FontWeight.Medium else FontWeight.Normal,
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(AppTheme.space.md))
                Text(text = "期限（$deadline 到期对账）", style = texts.caption)
                Spacer(modifier = Modifier.height(AppTheme.space.xs))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm),
                    modifier = Modifier.selectableGroup(),
                ) {
                    DeadlinePresets.forEach { days ->
                        DeadlineChip(
                            label = "$days 天",
                            selected = presetDays == days,
                            onClick = { presetDays = days },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(AppTheme.space.md))
                Text(text = "到期累计打卡 ≥ 几次算达成", style = texts.caption)
                AppTextField(
                    value = goalText,
                    onValueChange = { raw -> goalText = raw.filter { it.isDigit() }.take(3) },
                    placeholder = "14",
                    keyboardType = KeyboardType.Number,
                )
                Spacer(modifier = Modifier.height(AppTheme.space.md))
                Text(text = "承诺（可改）", style = texts.caption)
                AppTextField(
                    value = promise,
                    onValueChange = { promise = it },
                    placeholder = PROMISE_TEMPLATE,
                )
                Spacer(modifier = Modifier.height(AppTheme.space.md))
                Text(text = "违约后果（选填，到期未达成会原样摆出来）", style = texts.caption)
                AppTextField(
                    value = consequence,
                    onValueChange = { consequence = it },
                    placeholder = "例如：当天不许刷剧",
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val id = habitId
                    if (canSubmit && id != null) {
                        onConfirm(id, deadline, goalCount, promise, consequence)
                    }
                },
                enabled = canSubmit,
            ) {
                Text(text = "签名生效", color = if (canSubmit) colors.accentInk else colors.secondaryText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消", color = colors.secondaryText)
            }
        },
    )
}

/**
 * 期限预设 chip：与 [FocusScreen] 的时长选择同一份写法（accentSoft 底 + accentInk 字）。
 * 约 34dp 高，够不上 48dp 最小可点目标 —— `minimumInteractiveComponentSize` 挂链首只撑
 * 不可见的点击槽位（终审 I9）。
 */
@Composable
private fun DeadlineChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(AppTheme.radius.md))
            .background(if (selected) colors.accentSoft else colors.card)
            .clickable(onClick = onClick)
            .padding(vertical = AppTheme.space.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = texts.aux.copy(
                color = if (selected) colors.accentInk else colors.secondaryText,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            ),
        )
    }
}
