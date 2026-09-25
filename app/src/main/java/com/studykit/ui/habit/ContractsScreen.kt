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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
 * 自我契约页（v2.4 批次五）：监督计划的**离线替身**。
 *
 * 第一句话就是定位语 —— Ariely 意义上的 precommitment 没有外部监督人，约束力弱于真人监督，
 * 界面必须先把这个说清，不吹（设计文档 §6/§9 的硬性要求）。
 *
 * 颜色与文字只取 `AppTheme` tokens；达成态用 gold 系（goldSoft 药丸 + goldInk 字，
 * 品牌色 gold 只做卡面描边这一处非文本用途）；未达成把当初自己写的后果原文摆出来。
 * 页面结构与表单页同一套纪律：根就是那棵内容 `Column`，只有 `fillMaxSize + verticalScroll + padding(pageH)`，
 * insets 全交给 AppNav 根 Scaffold 的 innerPadding。
 *
 * 契约被结算成 ACHIEVED 时（[ContractsViewModel.achievedToCelebrate]，计划 R1），仪式 [ContractRitualDialog]
 * 开在**自己那一扇窗口**里（裁决 R7）—— 它不是盖在页面根上的一层浮层：浮层管不住别的窗口，
 * 而本仓的确认框 [ConfirmDialog] 与创建弹层都是 Material3 `AlertDialog`、各自活在自己的窗口里、
 * 永远画在页面之上，于是一张确认框能坐在庆祝页上面，而浮层那套吞点击与摘语义都伸不进那一层。
 * 系统返回也由那扇窗口接住，走的是与「收下」同一条路。窗口自己就把手势与焦点关在外面，
 * 内容 Column 在仪式期间仍然**摘掉语义**（`clearAndSetSemantics`）：那是 belt-and-braces，
 * 两道防线各管什么、哪一道还没上设备验过，都写在它旁边那段注释里。
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

    // 队列头就是"还等着摆的那一张"：哪几张放过由 `dismissRitual` 在 ViewModel 那一侧记着，
    // 页面不另存一份 id 清单（裁决 R6 —— 同一件事记两处，两处就会各说一套）。
    // allHabits 还没吐过第一帧时先不摆仪式：那时 contractHabitName 的兜底会把一张活习惯
    // 说成「已删除的习惯」，那是句假话。习惯到得很快，晚一帧进场没人看得出来。
    val ritual = toCelebrate.firstOrNull()?.takeIf { allHabits.isNotEmpty() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppTheme.space.pageH)
            // 第二道防线，不是唯一那道：仪式已经搬进自己的窗口（`ContractRitualDialog`），
            // 窗口把触摸关在外面、下面的卡片按结构就点不到。这一句管的是另一件事 —— **语义树**：
            // 窗口对 Explore-by-touch / TalkBack 是否真的隔离，本仓**没有在设备上验过**（在控制器的
            // 真机清单里），而这一句是本地可证的：空块的 clearAndSetSemantics 把整棵子树从语义里
            // 摘掉，不影响绘制与布局。留着它，读屏用户就不会在一页写着「收下」的界面底下
            // 摸到「写一份」和每张卡上的「撤销」/「删除」——「删除」是破坏性的。
            // 同一手法见 `CardStudyScreen` 里给 CardFace 挂的那一行。
            // 条件用 ritual（这一页此刻到底摆没摆），所以"allHabits 未出第一帧、仪式不摆"
            // 那条分支不会误伤语义：仪式不在，语义就在。
            .then(if (ritual == null) Modifier else Modifier.clearAndSetSemantics { }),
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
                // 每一项按 id 建键：`ContractCard` 里"确认框开没开"那格记在组合槽位上，
                // 删除会让列表重排，不建键的话 slot 复用会把"正在确认"传给挪进来的下一张卡 ——
                // 与下面仪式那层 `key(契约 id)` 是同一类 bug，那一处上一轮评审抓到过，这一处是漏掉的另半边。
                key(contract.id) {
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
        }

        Spacer(modifier = Modifier.height(AppTheme.space.xl))
    }

    ritual?.let { settled ->
        // 键必须是**契约 id**：一批多张时"收下"那一下摘掉队列头，队列头由 X 直接换成 Y，
        // 而调用点这个 `let` 组从头到尾没离开过组合 —— 不另起 keyed 组的话
        // `rememberRitualEntrance` 里那枚 `remember` 已经是 true、`animateFloatAsState` 的目标值也没变，
        // 第二张就**没有入场动效**（只有彩带会重放，它按 trigger 重建），`rememberScrollState()` 那一份
        // 滚动位置还会从 X 带到 Y。搬进窗口之后这道键更省不得：整扇 Dialog 连同它的窗口都会重挂，
        // 那才是"再放一张"该有的样子。"一次多张全选中"是 brief 点名的四种情况之一，动效不许悄悄缺席。
        key(settled.id) {
            ContractRitualDialog(
                contract = settled,
                habitName = contractHabitName(settled.habitId, allHabits),
                completedCount = progress[settled.id] ?: 0,
                positionLine = contractRitualPositionLine(queue = toCelebrate, currentId = settled.id),
                onDismiss = { viewModel.dismissRitual(settled.id) },
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
 * 达成仪式那扇**窗口**（计划 R1/R2，形态按裁决 R7）：一张契约被结算成 ACHIEVED 就摆出**一整页**，
 * 不是一枚 toast。
 *
 * ## 为什么是一扇窗口，而不是页面里的一层
 * 挂在页面根上的浮层盖得住自家的卡片，盖不住**别的窗口**：本仓的确认框 [ConfirmDialog] 与创建弹层
 * 都是 Material3 `AlertDialog`，各自活在自己的窗口里、永远画在页面之上 —— 于是确认框能坐在庆祝页上面，
 * 而浮层那套吞点击与摘语义都伸不进那一层。浮层也接不住系统返回：没有 `BackHandler` 时那一下直接
 * 弹栈，而队列活在 Activity 作用域的 ViewModel 里，"返回"于是悄悄变成"这次仪式无限期推迟"。
 * 窗口把这两件事都换成结构性的：页面在它下面点不到，[Dialog] 的 `onDismissRequest` 与「收下」
 * 是同一个动作，所以返回**收下当前这一张**、而不是逃出这一页（本仓挡返回的先例是
 * `ui/habit/FocusScreen.kt` 里那枚 `BackHandler`）。
 *
 * 一处没关死、也不假装关死：确认框**先**开着、仪式**后**结算出来时两扇窗口会同在，谁叠在谁上面
 * 没在设备上验过（在控制器的真机清单里）。那一刻用户手上是他自己按下去的破坏性确认，
 * 把确认框盖掉是更坏的处理，所以这里只把话说清、不改行为。
 *
 * @param positionLine 一批多张时那一行「还有 n 张待收下」（[contractRitualPositionLine]，裁决 R8）；
 *   单张传 null，就不多这一行
 * @param onDismiss 收下这一张 —— 系统返回走的也是这里
 */
@Composable
private fun ContractRitualDialog(
    contract: Contract,
    habitName: String,
    completedCount: Int,
    positionLine: String?,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            // 仪式吃满整扇窗口（`MistakeDetailScreen` 看图灯箱同一写法），不是屏幕中间一小块
            usePlatformDefaultWidth = false,
            // 内容铺满时本就没有"外面"：这两行写死是为了把"只能从「收下」或返回走"钉在代码上，
            // 而不是依赖默认值
            dismissOnClickOutside = false,
            dismissOnBackPress = true,
            // `decorFitsSystemWindows` 保持默认（true）：与仓内那枚全屏看图灯箱同口径，
            // 窗口让开系统栏，钉在下面的「收下」就不会躲进导航条底下。代价是状态栏那一条
            // 落在窗口之外（那儿露出的是窗口自己的遮罩），观感如何没上设备看过 —— 在真机清单里。
        ),
    ) {
        // 窗口自己的根 Box：`ConfettiBurst` 的 KDoc 要求彩带挂在**根 Box** 的 `matchParentSize`
        // 层上（取父尺寸、不反过来参与父测量），窗口里这一棵就是那层"全屏、不裁剪"的容器。
        Box(modifier = Modifier.fillMaxSize()) {
            ContractRitualLayer(
                contract = contract,
                habitName = habitName,
                completedCount = completedCount,
                positionLine = positionLine,
                onDismiss = onDismiss,
            )
            // 彩带排在仪式层之后（盖在它上面），不是仪式层的子节点：挂进卡面那种带圆角裁剪的
            // 容器里粒子出不了框。trigger = 契约 id，一张一个稳定标识（`ImportResultScreen` 的
            // 先例：别用会变的 trigger，否则重组再放一次）。「减弱动效」开着时它自己不跑
            // （组件内部早退），这一页的入场动效另有闸门。
            ConfettiBurst(
                trigger = contract.id,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/**
 * 仪式那一页摆出来的东西（由 [ContractRitualDialog] 放在它自己的窗口里）。
 *
 * 内容自上而下六样，一句多一句都算吵：「契约达成」/ 习惯名 / 承诺原文（引号包裹，同卡面）/
 * 进度 N/goal 次 / 落款那一行 / 一枚「收下」。成批时另外多一行「还有 n 张待收下」——那是出口
 * 的一部分（裁决 R8：让"还要收几张"数得出来），不是第七句庆祝词。
 * 不做音效、不做震动、不做分享图。
 *
 * 什么时候摆由队列说，不由"看到卡片是达成态"说（计划 R1）：结算可能发生在用户停在别的页面的时候
 * （`ContractsViewModel` 全程活着），那几张就攒在队列里，等他回到契约页才摆 —— 这一页本身
 * 只挂在契约页的组合里。
 *
 * ## 配色：只用 gold 系 tokens
 * 金卡那一截是 `goldSoft` 底 + `goldInk` 字 + `gold` 描边，与卡面的达成态同族（品牌色 gold 做
 * 非文本用途**只允许描边这一处**，见 `AppTheme` 的 T15 墨水纪律）。金卡之外这一层只多两个元素：
 * 计数那一行走 `secondaryText`（它是辅助信息，不是庆祝词的一部分），以及「收下」那枚 [AppButton]
 * —— 后者吃全仓同一份实底按钮（`accentInk` 容器 + `onAccent` 字），因为 gold 压白字在浅色主题只有
 * 1.79:1：按钮是操作件，不该为了配色统一把可达性押上去。
 *
 * ## 出口钉在滚动区之外
 * 承诺是用户自己写的自由文本，创建表单那一格也没给它设长度上限：长起来能把整页顶到可以滚。
 * 于是可滚的只有那张金卡（`weight(1f, fill = false)` = 最多吃到"屏幕减去出口"那么多，短内容
 * 仍按内容高），「收下」与计数那一行留在滚动区外面 —— 原来出口挂在滚动区末尾，长承诺能把
 * **唯一的**出口顶到折叠线以下（评审 M3）。金卡仍然可滚，仪式不许把承诺原文裁掉。
 *
 * ## 它在自己那一扇窗口里
 * 底色取 `colors.background` 铺满窗口，并用 `pointerInput` 把落在空白处的点击**吞掉**。
 * 搬进窗口之后这一句从"必须"降级成"留着不亏"：不吞的话 Compose 的命中测试会往下找有输入节点的
 * 兄弟，隔着浮层就点得到下面的「写一份」和「撤销」；而页面在另一扇窗口之下时，那一下根本送不到它手上。
 * 语义那一侧是同一层意思 —— 被盖住的那棵内容子树还要**摘掉语义**（belt-and-braces），
 * 见调用点那枚 `clearAndSetSemantics`。
 *
 * ## 动效
 * 入场是淡入 + 从下方 `space.lg` 落位 + 轻微放大（[rememberRitualEntrance]，减弱动效时不跑），
 * 彩带由 [ContractRitualDialog] 挂在窗口那棵根 Box 上（`ConfettiBurst` 自己那道闸门见其 KDoc）。
 * 两处合起来才是"减弱动效开着 = 这一页直接摆在那儿"。一批多张时每一张都要有这套入场动效，
 * 靠的是调用点那层 `key(契约 id)`：队列头换成下一张时整组连同窗口会重挂，否则
 * `rememberRitualEntrance` 里的 `remember` 已经是"放过一次"的样子，第二张就没有动效
 * （彩带反倒会重放，它另按 trigger 重建）。
 *
 * 这一层保持 `private`，**不**为渲染测试放开可见性：Robolectric 的渲染守卫在这个仓里给不出可复现的
 * 判定。实测到的形状是：同一份测试单独跑绿、进全量跑就红成 `UncaughtExceptionsBeforeTest`，红的不是
 * 断言而是**别处**残留的未捕获异常 —— 实测到的那一条来自后台线程上的 WorkManager 调用
 * （`StudyKitApp.onCreate` 挂上的设置观察者 → `ReminderScheduler.applyInterval`，那个 scope 是
 * `Dispatchers.Default`），异常落在哪条测试头上取决于跑序。**为什么**那一趟里它会抛没查清，也就不写进结论：
 * manifest 里没有关掉 WorkManager 的默认 initializer（只声明了 FileProvider），`StudyKitApp` 也没实现
 * `Configuration.Provider`，所以"Robolectric 下它没初始化"是当时的一句猜测，别再当理由传下去。
 * 想让这一页有渲染防线，得先把那枚异常查清楚（那是独立的一票，不在本功能里顺手做），
 * 不是在这里放开可见性。这一页的内容正确性因此由下面那几句字面量与真机复验负责；
 * "该不该放、放完还剩哪几张、这一张排第几"那几条判定另有纯函数与它们的 JVM 单测
 * （`newlySettled` / `newlyAchieved` / `nextRitualQueue` / `contractRitualPositionLine`）。
 */
@Composable
private fun ContractRitualLayer(
    contract: Contract,
    habitName: String,
    completedCount: Int,
    positionLine: String?,
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
                .padding(horizontal = AppTheme.space.pageH),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // fill = false：短内容时金卡只占内容高（外层 Box 会把它居中），
                    // 长内容时它最多长到"屏幕减去出口"，出口因此永远在折叠线以上
                    .weight(1f, fill = false)
                    .clip(shape)
                    .background(colors.goldSoft)
                    .border(width = 1.dp, color = colors.gold, shape = shape)
                    // 承诺原文可能很长：可滚的是这一张卡，仪式不许把它裁掉
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
            }
            // 出口这一截不滚：承诺再长也长不到它头上（评审 M3）
            Spacer(modifier = Modifier.height(AppTheme.space.lg))
            if (positionLine != null) {
                Text(
                    text = positionLine,
                    style = texts.caption.copy(color = colors.secondaryText),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(AppTheme.space.sm))
            }
            AppButton(text = "收下", onClick = onDismiss)
            Spacer(modifier = Modifier.height(AppTheme.space.lg))
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
