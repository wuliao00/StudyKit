package com.studykit.ui.habit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.studykit.data.entity.Contract
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.ConfettiBurst
import com.studykit.ui.components.ConfirmDialog
import com.studykit.ui.motion.MotionSpec
import com.studykit.ui.theme.AppTheme

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
internal fun ContractRitualDialog(
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
 * ## 这一页现在**有** CI 渲染防线了
 *
 * 它是 `internal`（拆分后 `ContractsScreen` 要调它），而 `ContractRitualRenderTest` 把内容
 * 真组合出来断言。这一点值得记一笔，因为本仓在这个位置撤回过一次同样的测试（2026-09-24，
 * 烧掉 10 轮 CI）：当时"聚焦跑绿、全量跑红成 `UncaughtExceptionsBeforeTest`"，
 * 而**红的不是这条测试的断言**，是别处残留的未捕获异常按跑序落到谁头上。
 * 当时只查到"来自后台线程的一次数据库访问"，没查到是哪一条，于是把测试删了、
 * 在这里写下"给不出可复现的判定，得先把异常查清楚"。
 *
 * 2026-09-26 查清了，而且**当时那句"来自 WorkManager 调用"是错的**：
 * 真凶是 `ReminderScheduler.observeAndApply` 挂在 `object` 的全局 scope 上、
 * **没有任何东西会取消它**。Robolectric 每个测试类重建一次 `StudyKitApp`（多挂一个收集者）、
 * 类结束时拆掉 SQLite 连接，而收集者还活着 —— 下一个类一开始，它就查已关的库，
 * 抛 `IllegalStateException: Illegal connection pointer`。完整堆栈与修法见
 * `ReminderScheduler.observeAndApply` 的 KDoc。
 *
 * 所以这条防线能留下的**前提是那个观察者不再外溢异常**。谁要改 `ReminderScheduler`，
 * 请把那条 try/catch 一起考虑进来 —— 拿掉它，本仓第二条渲染守卫会再次变成随机红。
 *
 * 覆盖边界要说清：这条守卫断的是"内容在不在语义树里、出口在不在可视区"，
 * **不**断点击（Robolectric 的输入注入打不进 dialog 窗口）、**不**断入场动效
 * （`reduceMotion = true` 让它走早退分支，否则测试规则等不到 idle）。
 * 动效与观感仍由真机录屏负责；"该不该放、放完还剩哪几张"那几条判定另有纯函数与 JVM 单测
 * （`newlySettled` / `newlyAchieved` / `nextRitualQueue` / `contractRitualPositionLine`）。
 */
@Composable
internal fun ContractRitualLayer(
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
internal fun rememberRitualEntrance(): State<Float> {
    if (AppTheme.settings.reduceMotion) return remember { mutableFloatStateOf(1f) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    return animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = MotionSpec.snap,
        label = "contractRitualEntrance",
    )
}
