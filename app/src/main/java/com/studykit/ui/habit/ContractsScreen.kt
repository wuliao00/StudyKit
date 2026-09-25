package com.studykit.ui.habit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.data.entity.Contract
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.AppPill
import com.studykit.ui.components.ConfirmDialog
import com.studykit.ui.components.EmptyState
import com.studykit.ui.theme.AppTheme
import java.time.LocalDate

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
 * 契约卡：状态药丸 + 习惯名 + 承诺原文 + 进度 + 截止日 + 签名，右下角一枚撤销/删除入口。
 * 达成态：goldSoft 底药丸 + gold 描边整卡突出（品牌色只做描边这一处非文本用途）；
 * 未达成：warning 系药丸 + 违约后果原文 —— 当初自己写的话，原样摆出来。
 *
 * 确认框的状态开在**本卡作用域**里，并以 `contract.id` 为 remember 键（`DictStoreScreen`
 * 的 `ImportedListRow` 同一写法）：列表会因为删除而重排，不带键的话 slot 复用会把
 * "正在确认"这个布尔传给挪进来的下一张卡。
 */
@Composable
internal fun ContractCard(
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
internal fun StatusPill(status: String) {
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
