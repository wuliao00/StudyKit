package com.studykit.ui.study

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.data.entity.Word
import com.studykit.data.memory.ReviewGrade
import com.studykit.data.memory.Scheduling
import com.studykit.data.memory.kernelStateFor
import com.studykit.data.memory.recallForDisplay
import com.studykit.data.memory.toKernelRating
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppButtonTone
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.ConfettiBurst
import com.studykit.ui.components.EmptyState
import com.studykit.ui.components.QuizOptionTile
import com.studykit.ui.components.RingGauge
import com.studykit.ui.motion.MotionSpec
import com.studykit.ui.theme.AppTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 卡片学习页：队列逐张 3D 翻面学习，右滑「认识」/左滑「忘记」推进间隔重复状态机；
 * 一轮结束展示庆祝小结卡。
 *
 * 颜色与文字样式统一取 `AppTheme`，间距/圆角取 `AppTheme.space` / `AppTheme.radius` 的 dp 常量。
 *
 * ## v2.5 §3 加的两件事，以及为什么状态放在**这一层**
 *
 * - **检索优先闸门**：`AppSettings.recallBeforeGrade`（默认开）开着且这张卡还没露过答案时，
 *   滑动不结算而是翻面、三档自评与两颗按钮一起置灰。判定只有 [decideRecallGate] 与
 *   [canGradeNow] 两处（`RecallGate.kt`），入口有三个，所以"答案露过没有"这枚状态
 *   ([revealed]) 必须记在页面这一层：记在卡片里，下面那行三档自评就看不见它，
 *   三个入口就会各拦一半 —— 而"关掉开关完全退回旧行为"这条承诺要求拦截点唯一。
 *   闸门**关**时三个入口的条件同时退化成旧写法（`enabled = !graded`、滑动直接结算），
 *   不留半截拦截。
 * - **新词预测试**：闸门开着 + 该词是新词 + 能凑出 3 条互不相同的释义时，
 *   这一格先摆三选一（[RecallPretestPanel]），选一个 → 立刻标出正确项 → 确认。
 *   确认即 `revealed = true`：释义已经在屏幕上了，再逼用户点一次翻面去看他刚看过答案的东西
 *   是空转，而且会让闸门看起来"坏了"。预测试是闸门的**一条合法通过路径**，不是又叠一道。
 *   猜的结果一个字都不落库（见 `StudyViewModel.loadRecallPretest`）。凑不满三条就整轮跳过，
 *   直接进原有的翻面+评分。
 */
@Composable
fun CardStudyScreen(
    viewModel: StudyViewModel,
    onBack: () -> Unit,
    onAddWord: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val session by viewModel.session.collectAsStateWithLifecycle()
    val scheduling by viewModel.scheduling.collectAsStateWithLifecycle()
    val pretest by viewModel.pretest.collectAsStateWithLifecycle()
    val state = session
    // ── `AppSettings.recallBeforeGrade` 的读取点（设置页那条"每条设置必须有消费点"的规矩）──
    // 组合期读的是一枚布尔设置，不是任何逐帧值；它往下只喂给 RecallGate 那两个纯函数。
    val gateEnabled = AppTheme.settings.recallBeforeGrade
    val gateHintSeen = AppTheme.settings.recallGateHintSeen

    /** 闸门第一次拦下滑动时那条一次性说明；置真的键在 `AppSettings.recallGateHintSeen` */
    var showGateHint by rememberSaveable { mutableStateOf(false) }
    val dismissGateHint: () -> Unit = {
        showGateHint = false
        // 「知道了」与"点外面关掉"走同一条：都算看过。留着"下次再拦就再讲一遍"
        // 只会让同一条说明反复打断同一件事，那不是提醒是 nag。
        viewModel.markRecallGateHintSeen()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = AppTheme.space.pageH),
    ) {
        Spacer(Modifier.height(AppTheme.space.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = colors.accentInk,
                )
            }
            Spacer(Modifier.width(AppTheme.space.xs))
            Text(text = "卡片学习", style = texts.pageTitle)
            Spacer(Modifier.weight(1f))
            if (state != null && state.total > 0 && !state.finished) {
                Text(
                    text = "第 ${state.index + 1} / ${state.total} 张",
                    style = texts.caption,
                )
            }
        }

        when {
            state == null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = colors.accent)
                }
            }

            state.total == 0 -> {
                Spacer(Modifier.height(AppTheme.space.xl * 2))
                EmptyState(
                    title = "没有待复习的单词",
                    caption = "当前没有到期的学习任务，先录入一些单词吧",
                    icon = Icons.Outlined.Star,
                )
                Spacer(Modifier.height(AppTheme.space.lg))
                AppButton(text = "录入单词", onClick = onAddWord)
                Spacer(Modifier.height(AppTheme.space.md))
                AppButton(text = "返回", secondary = true, onClick = onBack)
            }

            state.finished -> SessionSummary(
                knownCount = state.knownCount,
                unknownCount = state.unknownCount,
                onRestart = { viewModel.startCardSession() },
                onBack = onBack,
            )

            else -> {
                Spacer(Modifier.height(AppTheme.space.md))
                // 进度条留在 AnimatedContent 之外：切卡时它只推进长度，不参与整卡滑入滑出
                val progress = state.index.toFloat() / state.total
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = colors.accent,
                    trackColor = colors.divider,
                    strokeCap = StrokeCap.Round,
                )
                Spacer(Modifier.height(AppTheme.space.lg))
                val card = state.current
                // 「这张卡的答案在屏幕上露过没有」= 旧文案里的 flipped。
                // saveable + 按当前卡的 id 键控：换卡自动归零、转屏不丢（与卡片里 `graded`
                // 同一条纪律）。用 MutableState 而不是 `by` 委托，是为了把同一个真相交给
                // 卡片里的滑动手势与下面那行三档自评两处读写。
                val revealed = rememberSaveable(card?.id) { mutableStateOf(false) }
                // 预测试选中的那一项：-1 = 还没选。同样按 id 键控，切卡即复位
                var picked by rememberSaveable(card?.id) { mutableIntStateOf(-1) }

                // 出题要查一次词库（同词库的其他释义），所以挂在副作用里而不是组合期
                LaunchedEffect(card?.id, gateEnabled) {
                    card?.let { viewModel.loadRecallPretest(word = it, gateEnabled = gateEnabled) }
                }

                // 这一题只在**当前这张卡**上摆：`wordId` 对不上就当没有题（切得很快时上一张的
                // 查询可能后到，挂到这张卡上就是"释义对不上单词"）；已露过答案也不再摆 ——
                // 预测试是闸门的一条合法通过路径，不是叠在上面的又一道。
                val quiz = pretest?.takeIf { q ->
                    !revealed.value && card != null && q.wordId == card.id
                }

                AnimatedContent(
                    targetState = state.index,
                    transitionSpec = {
                        // 淡入淡出走 MotionSpec 工厂（时长是规格常量：切卡比导航短促）；
                        // slide 的 spring 同样走规格（波 4 项 4：原本这里是内联字面量，
                        // 数值原样上收成 `MotionSpec.cardSwitch`，一分未改）
                        val enter = slideInHorizontally(
                            animationSpec = MotionSpec.cardSwitch,
                        ) { it / 3 } + MotionSpec.fadeEnter(
                            durationMillis = MotionSpec.CardSwitchInMs,
                        )
                        val exit = slideOutHorizontally { -it / 3 } + MotionSpec.fadeExit(
                            durationMillis = MotionSpec.CardSwitchOutMs,
                        )
                        enter.togetherWith(exit)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    label = "cardSwitch",
                ) { idx ->
                    // index 与 queue 来自同一份快照，理论上不会错位；但 session 是异步推进的，
                    // AnimatedContent 转场期间旧 idx 仍可能被重组一次 —— getOrNull 兜底，宁可不画也不崩。
                    val shown = state.queue.getOrNull(idx) ?: return@AnimatedContent
                    if (quiz != null && shown.id == quiz.wordId) {
                        RecallPretestPanel(
                            word = shown,
                            pretest = quiz,
                            picked = picked,
                            onPick = { picked = it },
                            onConfirm = { revealed.value = true },
                        )
                    } else {
                        SwipeRatingCard(
                            word = shown,
                            revealed = revealed,
                            gateEnabled = gateEnabled,
                            onGateBlocked = {
                                // 只有"这条说明还没被看过"时才打断；看过之后闸门自己安静工作
                                if (!gateHintSeen) showGateHint = true
                            },
                            onGrade = { known ->
                                if (known) viewModel.markKnown() else viewModel.markUnknown()
                            },
                        )
                    }
                }
                // 评分按钮印出「这次会排到几天后」：整套模型唯一的可见出口。
                // 放在 AnimatedContent 之外，切卡时只有数字变，按钮本身不跟着滑走。
                card?.let { current ->
                    GradeRow(
                        word = current,
                        scheduling = scheduling,
                        // 闸门开着、答案还没露出来（或正在做预测试）时这一行整排置灰：
                        // 沿用 AppButton 那副禁用态（文字走 secondaryText），不新造视觉。
                        // 条件与卡片里滑动/两颗按钮那两处同出一个 [canGradeNow]，
                        // 不会出现"这排灰了、那边还能结算"。
                        enabled = canGradeNow(
                            gateEnabled = gateEnabled,
                            revealed = revealed.value,
                            pretestActive = quiz != null,
                        ),
                        onGrade = { grade -> viewModel.gradeCard(grade) },
                    )
                }
            }
        }
    }

    // 闸门第一次真正拦下一次滑动时的那条说明（每次安装只出现一次：点「知道了」把
    // `AppSettings.recallGateHintSeen` 置真，此后不再出现）。骨架与 ConfirmDialog 同一份
    // （AlertDialog + surfaceContainerHigh + cardTitle/aux 两档字），只是这一条只有一个出口。
    if (showGateHint) {
        AlertDialog(
            onDismissRequest = dismissGateHint,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text(text = RECALL_GATE_HINT_TITLE, style = texts.cardTitle) },
            text = { Text(text = RECALL_GATE_HINT_BODY, style = texts.aux) },
            confirmButton = {
                TextButton(onClick = dismissGateHint) {
                    Text(text = "知道了", color = colors.accentInk)
                }
            },
        )
    }
}

// ── 滑动判定：纯函数，零 Compose / 零 Android 依赖 ───────────────────────────
// 抽出来的唯一动机是可测性：阈值 / 甩速 / 同向这套规则是本页最容易「手感回归」的地方，
// 留在 Composable 里就只能靠真机试手势。数值与 [MotionSpec] 的调校保持一致，
// 改数值请连 SwipeDecisionTest 一起改。

/** 位移阈值比例：拖过卡片宽度的 33% 即判定（与 [MotionSpec] 的手感调校一致） */
private const val SwipeDistanceRatio = 0.33f

/** 甩速阈值（px/s）：位移没到阈值时用它补判，方向必须与位移同号 */
private const val SwipeVelocityFloor = 900f

/** [SwipeRatingCard] 一次拖拽结束的三态结论 */
internal enum class SwipeDecision {
    /** 位移与速度都不够 —— 回弹 */
    NONE,

    /** 右滑够格 —— 认识 */
    KNOWN,

    /** 左滑够格 —— 不认识 */
    UNKNOWN,
}

/**
 * 位移阈值（px）：`宽 * [SwipeDistanceRatio]`，并夹到至少 1px。
 *
 * `onSizeChanged` 测量到真实宽度之前 `widthPx` 是 0，不夹紧会得到「阈值 0、碰一下就判定」
 * 以及后续 `offsetX / threshold` 的除零。
 */
internal fun swipeThresholdPx(widthPx: Int): Float =
    (widthPx * SwipeDistanceRatio).coerceAtLeast(1f)

/**
 * 判定一次拖拽结束该往哪个方向结算：只做算术，不读快照/组合状态，因此可在 JVM 单测里直取。
 *
 * 规则：
 *  - 位移越过 `±[swipeThresholdPx]` 即按**位移方向**判定；
 *  - 位移未过阈但甩速越过 `±[SwipeVelocityFloor]` px/s，且甩速与位移**同号**才补判
 *    （往回拽的途中松手不该结算，故不同号一律不算）；
 *  - 位移为 0 时速度分支必然不成立（没有可依据的方向）。
 *
 * 位移已过阈时速度不再参与方向判断：反向甩速不会推翻位移结论，只会让卡片飞得更快。
 */
internal fun decideSwipe(offsetX: Float, velocityX: Float, widthPx: Int): SwipeDecision {
    val threshold = swipeThresholdPx(widthPx = widthPx)
    return when {
        offsetX > threshold ||
            (velocityX > SwipeVelocityFloor && offsetX > 0f) -> SwipeDecision.KNOWN

        offsetX < -threshold ||
            (velocityX < -SwipeVelocityFloor && offsetX < 0f) -> SwipeDecision.UNKNOWN

        else -> SwipeDecision.NONE
    }
}

/**
 * 滑动评价卡：三层结构，每层只负责一种变换，互不污染。
 *
 * ```
 * 布局根 Box —— weight(1f) 撑满剩余高度 + onSizeChanged 测宽（阈值/飞出距离都按它算）
 *   ├─ 滑动层 Box —— translationX / rotationZ / scale + draggable：卡片本体跟手倾斜、飞出
 *   │    └─ 翻面平面 Box —— rotationY = flipState + clip + clickable：3D 翻面
 *   │         ├─ CardFace 正面（flip < 90°）
 *   │         └─ CardFace 背面（flip >= 90°，自身再转 180° 抵消镜像）
 *   └─ GradeBadge ×2 —— 只吃 alpha（收 State<Float>），位置由布局根 align 决定
 * ```
 *
 * 逐帧值（`offsetX` / `flipState` 与由它们派生的 `drag` 与两枚角标 alpha）**一律不在组合期读**：
 * 它们只出现在 `graphicsLayer {}` 与 `derivedStateOf {}` 里，组合次数因此只随
 * index / graded / 量到的宽度变化（终审 C2：120Hz 拖动期整卡重组）。
 *
 * 角标**不能**挂在滑动层之下：否则它会被 `translationX` 连卡片一起带出屏幕、被 `rotationZ`
 * 带着倾斜、翻到背面时还会跟着 `rotationY` 镜像成反字。挂在布局根上时它就是一个稳定的角落提示，
 * 只有透明度跟拖拽位移变化（发起结算即刻归零）。
 *
 * 手势阈值与 [MotionSpec] 保持一致，判定本身抽成纯函数 [decideSwipe]（位移越过 `宽 * 0.33f`，
 * 或松手甩速越过 ±900f px/s 且与位移同号即结算）；飞出目标 ±`宽 * 1.5f` 走 `MotionSpec.flyOut()`，
 * 否则 [MotionSpec.snap] 回弹。翻面走 [MotionSpec.flip] spring，逐帧跟手、不设时长。
 *
 * **滑动语义（v2.5 §3.1 改过一次）**：闸门开着（`AppSettings.recallBeforeGrade`，默认开）而
 * 这张卡还没露过答案时，够格的滑动**不结算**，而是变成一次翻面（弹回原位 + 翻过来），
 * 翻面之后左右滑按原语义直接结算。闸门关掉时 [decideRecallGate] 对两个方向都给"结算"，
 * 于是完全退回旧行为（不翻面也能滑 = 墨墨的口径，"可关"承诺的全部内容就是不留半截拦截）。
 * 为什么不是"拦下滑动什么都不做"：这个卡片的主动作就是滑，点了没反应会被当成 bug。
 *
 * 拦截结论只来自 [decideRecallGate]（滑动）与 [canGradeNow]（两颗按钮 + 下面那行三档自评），
 * 三者共用页面那一层的 [revealed] —— 状态留在卡片里的话下面那行就看不见它，
 * 三个入口必然各拦一半。
 *
 * 结算一旦发起（`graded` 置真）就同时关掉拖拽手势与两颗按钮：飞出动画期间再起手会把
 * `animateTo` cancel 掉，`onGradeNow` 便永远执行不到，而 `graded` 已置真 ⇒ 该卡再也无法评价（软锁）。
 * **闸门没有改动这条不变量**：`GateAction.FLIP` 那一支不 `claim()`（这张卡还没结算），
 * 但它也不碰 `graded`，弹回与翻面都照常走；真正结算仍只有 `flyAndGrade` 一个入口。
 *
 * `graded` 自终审 I6 起是 `rememberSaveable(word.id)`：转屏后「同一张卡只结算一次」依然成立。
 * 残留一种极端情形没有自愈——飞出动画**在飞途中**转屏/进程被销毁时，claim 已置真而
 * `onGradeNow` 还没跑，重建后这张卡既不能滑也不能按（`offsetX` 是 `remember` 的 Animatable，
 * 归零了）。要救它就得把评价方向也存下来并在重建时补投一次，但「补投」与「上一次其实已落库」
 * 之间没有可靠的判据（`_session.index` 只在 DB 写完后才推进），反而会引入双写复习记录的风险，
 * 故本波按**已知残留**处理，列入真机核验（见 final-wave-2 报告）。
 *
 * 转场期间这一张（正在飞出的旧卡）读的是**当前那张卡**的 `revealed`：键控在 `state.current?.id`
 * 上，旧卡的 id 已经不是那个键。后果只有外观 —— 旧卡在 120ms 的退场里可能正显示它的正面，
 * 而它已经 `graded`（手势与按钮都关着），不会被误当成"还能评"。
 *
 * @param revealed 这张卡的答案是否已经露过（翻面 / 预测试确认过都算）。组合期只当布尔读，
 *   写它只有三处：点卡片、闸门把滑动变成翻面、外面预测试点「确认」。
 * @param gateEnabled `AppSettings.recallBeforeGrade`。为 false 时本卡片的行为与 v2.4 逐字相同。
 * @param onGateBlocked 每次**真的**拦下一手时回调一次（页面用它决定那条一次性说明）。
 * @param onGrade 结算当前卡（`true` = 认识）。同一张卡只会回调一次，见上方 `claim()` 守卫。
 */
@Composable
internal fun SwipeRatingCard(
    word: Word,
    revealed: MutableState<Boolean>,
    gateEnabled: Boolean,
    onGateBlocked: () -> Unit,
    onGrade: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val density = LocalDensity.current.density
    var widthPx by remember { mutableIntStateOf(0) }
    // 翻面角度是逐帧值：只进 graphicsLayer。参与「哪一面朝前」这个分支的量另折成布尔派生值
    // （只在跨过 90° 的那一帧变），于是翻面期间整卡最多重组一次，而不是每帧一次（C2 同类）。
    val flipState = animateFloatAsState(
        targetValue = if (revealed.value) 180f else 0f,
        animationSpec = MotionSpec.flip,
        label = "flip",
    )
    val frontShown by remember(flipState) { derivedStateOf { flipState.value < 90f } }
    val offsetX = remember { Animatable(initialValue = 0f) }
    val scope = rememberCoroutineScope()
    val onGradeNow by rememberUpdatedState(onGrade)
    // ── 结算守卫 ─────────────────────────────────────────────────────────
    // ViewModel 的 index 要等 DB 写完才推进，所以「滑动 flyOut 协程」与「按钮点击」若在同帧
    // 各结算一次，会把同一张卡复习两遍（recordReview 双写、间隔被后一次覆盖）。
    // 所有路径都先 claim 抢权限，抢不到即视为重复触发。
    //
    // saveable 且以 `word.id` 键控（终审 I6）：`remember` 的话转屏后这枚守卫归零，
    // 同一张卡能被结算第二遍；换卡时按 id 自动复位，不会带着上一张的残锁。
    // 与页面那一层的 `revealed`、`QuizScreen` 的 `pending/advanced` 是同一条纪律。
    val graded = rememberSaveable(word.id) { mutableStateOf(false) }

    /** 抢占本卡的结算权；首次返回 `true`，之后一律 `false` */
    fun claim(): Boolean {
        if (graded.value) return false
        graded.value = true
        return true
    }

    /** 按钮路径：立即结算 */
    fun grade(known: Boolean) {
        if (claim()) onGradeNow(known)
    }

    // 此刻能不能按按钮：与下面那行三档自评同一个 [canGradeNow]（闸门开且未露答案 = 三个入口一起灰）。
    // 「认识」那颗另加 `revealed.value` 一道门槛，那是 v2.4 就有的语义，闸门关掉也不该把它一起放开。
    val canGrade = canGradeNow(gateEnabled = gateEnabled, revealed = revealed.value, pretestActive = false)

    /** 滑动路径：先抢权限（飞行途中角标让位、按钮再点也无效），飞出结束才真正结算 */
    fun flyAndGrade(targetPx: Float, known: Boolean) {
        if (!claim()) return
        scope.launch {
            offsetX.animateTo(targetValue = targetPx, animationSpec = MotionSpec.flyOut())
            onGradeNow(known)
        }
    }

    // ── 拖拽派生值：三个都只在 lambda 里读 ────────────────────────────────
    // 终审 C2 的正主：旧写法在组合期读 `offsetX.value`，于是 120Hz 屏上每拖一帧整卡
    // （2×AppCard + 4×Text）就重组一次。`derivedStateOf` 把逐帧值留在绘制侧，
    // 组合次数此后只随 index / graded / widthPx 变化。
    // 键是**被捕获的 State 对象本身**（不是它的值）：`graded` 是 `rememberSaveable(word.id)`，
    // 换卡会换成另一个 State 实例，无键 remember 会让派生值一直读着上一张卡的旧守卫。
    val drag = remember(offsetX) { derivedStateOf { offsetX.value / swipeThresholdPx(widthPx = widthPx) } }
    val knownAlpha = remember(graded, drag) {
        derivedStateOf { if (graded.value) 0f else drag.value.coerceIn(0f, 1f) }
    }
    val unknownAlpha = remember(graded, drag) {
        derivedStateOf { if (graded.value) 0f else (-drag.value).coerceIn(0f, 1f) }
    }
    val dragState = rememberDraggableState { dx ->
        scope.launch { offsetX.snapTo(targetValue = offsetX.value + dx) }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // ── 布局根：只负责尺寸与角标定位，不吃任何变换 ─────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onSizeChanged { widthPx = it.width },
        ) {
            // ── 滑动层：平移 / 倾角 / 微缩放，拖拽手势也挂在整卡区域上 ───────
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        translationX = offsetX.value
                        rotationZ = drag.value * 6f
                        scaleX = 1f - 0.04f * drag.value.coerceIn(-1f, 1f).let { it * it }
                        scaleY = scaleX
                    }
                    .draggable(
                        state = dragState,
                        orientation = Orientation.Horizontal,
                        // 修软锁：graded 之后彻底关掉手势。飞出途中再起手会 snapTo/animateTo 抢
                        // 同一个 Animatable，把 flyAndGrade 的 animateTo cancel 掉，
                        // 于是 onGradeNow 永不执行、而该卡又已 claim ⇒ 这张卡再也评不了。
                        enabled = !graded.value,
                        onDragStopped = { velocity ->
                            val w = widthPx.toFloat().coerceAtLeast(1f)
                            // 逐帧值只在这一刻（事件回调，不是组合期）读一次，判完就丢
                            val action = decideRecallGate(
                                decision = decideSwipe(
                                    offsetX = offsetX.value,
                                    velocityX = velocity,
                                    widthPx = widthPx,
                                ),
                                gateEnabled = gateEnabled,
                                revealed = revealed.value,
                            )
                            when (action) {
                                GateAction.SETTLE_KNOWN ->
                                    flyAndGrade(targetPx = w * 1.5f, known = true)

                                GateAction.SETTLE_UNKNOWN ->
                                    flyAndGrade(targetPx = -w * 1.5f, known = false)

                                // 闸门拦下：这一滑变成"翻面"，卡片弹回原位。**不 claim**
                                // （这张卡还没结算，翻完之后照样能滑），也不写任何数据。
                                GateAction.FLIP -> {
                                    revealed.value = true
                                    onGateBlocked()
                                    scope.launch {
                                        offsetX.animateTo(
                                            targetValue = 0f,
                                            animationSpec = MotionSpec.snap,
                                        )
                                    }
                                }

                                // 回弹同理：已发起结算就不碰 Animatable（否则照样掐掉飞行）
                                GateAction.NONE -> if (!graded.value) scope.launch {
                                    offsetX.animateTo(
                                        targetValue = 0f,
                                        animationSpec = MotionSpec.snap,
                                    )
                                }
                            }
                        },
                    ),
            ) {
                // ── 翻面平面：两张 CardFace 共用这一个 rotationY，
                //     背面那侧再自行回转 180° 抵消镜像，文字才不会反写 ──────────
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            rotationY = flipState.value
                            cameraDistance = 16f * density
                        }
                        .clip(RoundedCornerShape(AppTheme.radius.lg))
                        .clickable { revealed.value = !revealed.value },
                ) {
                    CardFace(
                        visible = frontShown,
                        content = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(word.word, style = texts.largeTitle, textAlign = TextAlign.Center)
                                Spacer(Modifier.height(AppTheme.space.md))
                                // 闸门开着时这一句要说清"先想再对"，否则用户滑了一下卡片只是翻了面，
                                // 会以为手势坏了。闸门关掉时保持 v2.4 那句原话，别留一句描述不上行为的文案。
                                Text(
                                    text = if (gateEnabled) {
                                        "先想答案 · 滑动或点击翻面"
                                    } else {
                                        "点击翻面 · 右滑认识 左滑忘记"
                                    },
                                    style = texts.caption,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        },
                    )
                    CardFace(
                        visible = !frontShown,
                        mirrored = true,
                        content = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(word.meaning, style = texts.pageTitle, textAlign = TextAlign.Center)
                                if (word.example.isNotBlank()) {
                                    Spacer(Modifier.height(AppTheme.space.md))
                                    Text(
                                        word.example,
                                        style = texts.aux.copy(color = colors.secondaryText),
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        },
                    )
                }
            }
            // ── 角标：布局根直接摆位，只随拖拽位移改透明度（只有 ✓/✗ 图形，不带文案）──
            GradeBadge(
                glyph = Icons.Filled.Check,
                contentDescription = "认识",
                container = colors.successSoft,
                ink = colors.successInk,
                alpha = knownAlpha,
                align = Alignment.TopEnd,
                rotation = 12f,
            )
            GradeBadge(
                glyph = Icons.Filled.Close,
                contentDescription = "不认识",
                container = colors.warningSoft,
                ink = colors.warningInk,
                alpha = unknownAlpha,
                align = Alignment.TopStart,
                rotation = -12f,
            )
        }
        Spacer(Modifier.height(AppTheme.space.lg))
        Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.space.md)) {
            // 两颗都走 AppButton 的描边档（终审波 3）：52dp 胶囊高、按压 spring、
            // fontScale 防裁切版心由组件统一给，本页只留「哪一族色」与 enabled 判定。
            // 语义与墨色由 tone 成对给出（描边 = 品牌色、文字 = 本族 ink）。
            AppButton(
                text = "不认识",
                onClick = { grade(false) },
                // 判定进行中（飞出途中 graded 已置真）两颗按钮一起失效，避免与手势抢同一张卡。
                // 闸门开着且没翻面时这颗也要灰掉 —— 它是绕过闸门的最近路：
                // 不灰的话用户点一下就能在没见过答案的情况下结算，闸门只剩一半。
                enabled = canGrade && !graded.value,
                modifier = Modifier.weight(1f),
                secondary = true,
                tone = AppButtonTone.Warning,
            )
            AppButton(
                text = "认识",
                onClick = { grade(true) },
                // 「认识」这道"要先翻面"的门槛比闸门早（v2.4 就有），**不跟闸门开关走**：
                // 关掉闸门退回的是"不翻面也能滑"，不是"不翻面也能点认识"。
                enabled = revealed.value && !graded.value,
                modifier = Modifier.weight(1f),
                secondary = true,
                tone = AppButtonTone.Success,
            )
        }
        Spacer(Modifier.height(AppTheme.space.lg))
    }
}

/**
 * 翻面的一侧：与兄弟面同尺寸铺满翻面平面（`fillMaxSize`），因此正反面文字长度不同也不会
 * 让卡片在翻面瞬间改变高度。
 *
 * 可见性用 90° 硬切（`alpha = if (visible) 1f else 0f`）：此刻平面正好侧立、投影宽度为 0，
 * 切换点肉眼不可见，也不会出现「两面同时半透明互相透出」的穿帮。
 *
 * 语义与 alpha 同源：外层翻面平面是 `clickable` 节点，它会把**两面**的文字合并成一条朗读，
 * 所以 [visible] 为假时整棵子树用 `clearAndSetSemantics` 摘掉 —— TalkBack 任一刻只报朝前的
 * 那一面（正面＝单词，翻面后＝释义），而不是把两句念在一起。
 *
 * @param mirrored 背面专用：在自身 `graphicsLayer` 上再转 180°，抵消父层的镜像，
 *   否则背面文字会左右反写。
 */
@Composable
private fun CardFace(
    visible: Boolean,
    mirrored: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current.density
    AppCard(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = if (visible) 1f else 0f
                if (mirrored) {
                    rotationY = 180f
                    cameraDistance = 16f * density
                }
            }
            // 两面恒在组合里（翻面要有东西可转），朝后的那面只是 alpha=0 —— 图形上看不见，
            // 但在 a11y 树里照样存在，TalkBack 会把单词和释义一起念成一句（终审 I9）。
            // 空块的 clearAndSetSemantics 把整棵子树从语义里摘掉：正面可读单词、
            // 翻过去之后可读释义，任何时刻只报当前可见的那一面。不影响绘制与布局。
            .then(if (visible) Modifier else Modifier.clearAndSetSemantics { }),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = AppTheme.space.xl * 1.5f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            content = content,
        )
    }
}

/**
 * 拖拽角标：**只放 ✓ / ✗ 图形**（不放「认识/忘记」文字），贴在布局根角落、只做自身的静态倾斜，
 * 透明度跟手上位移线性渐显；`alpha <= 0` 时直接不进组合（未拖拽与已发起结算两种情况都归零）。
 *
 * `alpha` 是**逐帧**变化的派生值，故收 `State<Float>` 而不是 Float：值只在自身 `graphicsLayer`
 * 里读（C2）。要不要进组合则折成一枚布尔 `derivedStateOf`，它只在跨过 0 的那一帧翻转 ——
 * 于是拖拽期间角标只有重绘，没有重组。
 *
 * 刻意不放文案：17sp 文字压在拖拽中的卡片上会被抢走注意力，文字版语义仍由下方
 * 「不认识 / 认识」两颗按钮承担；而 ✓/✗ 是**非文本元素**，靠形状 + 颜色 + 位置表意，
 * 配 [contentDescription] 交给读屏。
 *
 * 配色走「soft 底 + ink 图形」（与 T10 药丸、[com.studykit.ui.components.QuizOptionTile] 同一套）：
 * 旧写法是 `success`/`warning` 实底压 `onAccent`，浅色主题那颗白勾只有 2.22:1；
 * 白字压实底按 T15 裁定只留给 accent 一处（`accentInk` + `onAccent`）。
 */
@Composable
private fun BoxScope.GradeBadge(
    glyph: ImageVector,
    contentDescription: String,
    container: Color,
    ink: Color,
    alpha: State<Float>,
    align: Alignment,
    rotation: Float,
) {
    val visible = remember(alpha) { derivedStateOf { alpha.value > 0f } }
    if (!visible.value) return
    Box(
        modifier = Modifier
            .align(align)
            .padding(AppTheme.space.md)
            .graphicsLayer {
                this.alpha = alpha.value
                rotationZ = rotation
            }
            .clip(RoundedCornerShape(AppTheme.radius.md))
            .background(container)
            .padding(AppTheme.space.sm),
    ) {
        Icon(
            imageVector = glyph,
            contentDescription = contentDescription,
            tint = ink,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * 新词预测试那一屏：词面 + 三句释义（其中一句是它的）。摆在 [AnimatedContent] 里
 * **替换**卡片本体那一只的位置，不是叠在卡片上：卡片会翻、会滑、会被角标压字，
 * 而这一屏要的只是一道安静的题 —— 而且它绝不能被顺手滑出去（滑动的语义此刻还没轮到它参与）。
 *
 * 三道门的次序（§3.3）：选一个 → 立刻把正确项标出来 → 用户确认 → 页面把 `revealed` 置真，
 * 于是这一格换回卡片本体、且背面直接朝前（释义已经在屏幕上），三档自评同时放开。
 * **确认即视为已翻面**：再逼用户点一次翻面去看他刚看过答案的东西是空转，
 * 而且会让闸门看起来"坏了"（看得见答案却不结算）。
 *
 * 选项砖块与状态映射全部复用 [QuizOptionTile] 与 [quizOptionState]（刷题页那套四态：
 * 描边/底色/字母位换成 ✓/✗/ink 取色/读屏状态后缀），这里一行视觉代码都不新写。
 *
 * 猜的结果**不落库**：本屏不写 `word_reviews`、不写任何表，`onPick` 只改本地这枚 `picked`
 * （理由见 `StudyViewModel.loadRecallPretest`）。
 *
 * @param picked 用户已选的下标，-1 = 还没选（此时三项都可点，正确项不泄露）
 */
@Composable
private fun RecallPretestPanel(
    word: Word,
    pretest: RecallPretest,
    picked: Int,
    onPick: (Int) -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val answered = picked >= 0
    // 长释义会把这一格顶高出可视区，所以整列可滚：这一格的高度是 weight(1f) 定下来的，
    // 不滚就没有"看到最后一项"的出口（这里不能用 weight(1f, fill = false) 那类写法救高度，
    // 本仓有过一次那么改完把名字压成省略号的先例）
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(text = word.word, style = texts.largeTitle)
        Spacer(Modifier.height(AppTheme.space.xs))
        Text(
            text = "先猜它的释义 · 猜错不要紧",
            style = texts.caption,
            color = colors.secondaryText,
        )
        Spacer(Modifier.height(AppTheme.space.lg))
        pretest.options.forEachIndexed { index, option ->
            QuizOptionTile(
                optionText = option,
                index = index,
                state = quizOptionState(
                    index = index,
                    answerIndex = pretest.correctIndex,
                    // 一选就把正确项标出来（没有第二次"提交"）：graded 一非空就是判定态
                    graded = if (answered) picked else null,
                    pending = -1,
                ),
                // 选定即整列锁死：判定后再点会把正确项换成另一个下标，那等于让用户自己改答案
                enabled = !answered,
                onClick = { if (!answered) onPick(index) },
            )
            Spacer(Modifier.height(AppTheme.space.sm))
        }
        if (answered) {
            Text(
                text = if (picked == pretest.correctIndex) "猜对了" else "这句才是它的释义 · 猜错不要紧",
                style = texts.caption.copy(color = if (picked == pretest.correctIndex) colors.successInk else colors.secondaryText),
            )
            Spacer(Modifier.height(AppTheme.space.md))
            AppButton(text = "记住了，继续", onClick = onConfirm)
        }
        Spacer(Modifier.height(AppTheme.space.lg))
    }
}

/**
 * 一轮结束小结卡：中央达成环（认识占比，满达成转 `goldInk`）+ 两侧认识/忘记数字滚动进场，
 * 顶层叠全屏彩带庆祝。两个计数是 34sp 文字，故走 `successInk/warningInk` 而非品牌色。
 *
 * `ConfettiBurst` 按 T4 约定挂在裁剪容器之外的 `matchParentSize` 层上：
 * 粒子会飞越画布框，放进 [AppCard] 里会被圆角裁成方框。trigger 用本轮结算总数
 * （= 队列长度）作为事件标识，小结卡首次组合即播放一次；「再来一轮」后小结卡卸载，
 * 下一轮结束重新挂载再播。
 *
 * 达成色同样只在真有完成量时出现（0/0 不显示满环，与 T7 hero 卡裁定一致）；
 * 本分支进入前已保证 `state.total > 0`。
 */
@Composable
private fun SessionSummary(
    knownCount: Int,
    unknownCount: Int,
    onRestart: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    // animateIntAsState 首帧直接落在 target，不会有「0 → N」的滚动，
    // 因此与 `FlameBadge` 同法：先记 born，再让目标值从 0 变到真值触发一次补间。
    // saveable：转屏后 born 恢复为 true ⇒ 目标值不再从 0 起步，两个计数不会凭空重播一遍
    // （与 QuizScreen 的 `born`、本页 `revealed/pending` 同一条纪律）。
    var born by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { born = true }
    val shownKnown by animateIntAsState(
        targetValue = if (born) knownCount else 0,
        animationSpec = tween(durationMillis = MotionSpec.CountUpMs, easing = MotionSpec.Easing),
        label = "summaryKnown",
    )
    val shownUnknown by animateIntAsState(
        targetValue = if (born) unknownCount else 0,
        animationSpec = tween(durationMillis = MotionSpec.CountUpMs, easing = MotionSpec.Easing),
        label = "summaryUnknown",
    )
    val total = knownCount + unknownCount
    val progress = if (total == 0) 0f else knownCount.toFloat() / total

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "本轮完成",
                    style = texts.pageTitle,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(AppTheme.space.lg))
                // AppCard 的内容列默认起始对齐，这里再用一层居中 Box 把环放到卡片正中
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier.size(132.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        RingGauge(
                            progress = progress,
                            modifier = Modifier.fillMaxSize(),
                            strokeWidth = 11.dp,
                            color = if (progress >= 1f && total > 0) colors.goldInk else colors.success,
                        )
                        Text(
                            text = "${(progress * 100f).roundToInt()}%",
                            style = texts.heroNumber,
                        )
                    }
                }
                Spacer(Modifier.height(AppTheme.space.lg))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "$shownKnown",
                            style = texts.statValue.copy(color = colors.successInk),
                        )
                        Spacer(Modifier.height(AppTheme.space.xs))
                        Text(text = "认识", style = texts.caption)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "$shownUnknown",
                            style = texts.statValue.copy(color = colors.warningInk),
                        )
                        Spacer(Modifier.height(AppTheme.space.xs))
                        Text(text = "不认识", style = texts.caption)
                    }
                }
            }
            Spacer(Modifier.height(AppTheme.space.lg))
            AppButton(text = "返回", onClick = onBack)
            Spacer(Modifier.height(AppTheme.space.md))
            AppButton(text = "再来一轮", secondary = true, onClick = onRestart)
        }
        ConfettiBurst(
            trigger = total,
            modifier = Modifier.matchParentSize(),
        )
    }
}

// ── 评分按钮：把模型的输出摊开给用户看 ────────────────────────────────────────

/** 一天的毫秒数。预览要算"这个词已经拖了多久"，与 `StudyViewModel` 里那份同值 */
private const val ONE_DAY_MS = 86400000L

/**
 * 三个评分档，各自印出"点它之后这个词会排到多久以后"。
 *
 * 抄的是墨墨真机上最有效的一处设计：它的按钮写着 `认识 · 35 天后`、`模糊 · 今日/4 天后`、
 * `忘记 · 今日/3 天后`。用户不需要理解半衰期，但他能当场看见"我说认识，它敢不敢排 35 天"，
 * 排错了也能当场发现。**别把间隔藏进设置页**，那是这套调度唯一的可见出口。
 *
 * 滑动仍然是两档快判（右=认识、左=忘记），中间这排按钮补齐"模糊"，
 * 并且是唯一的精确入口 —— 犹豫的时候不该被迫在"全对"和"全错"里挑一个。
 *
 * [enabled] 由页面的 [canGradeNow] 给：闸门开着而这张卡还没露过答案时整排灰掉
 * （否则看一眼卡面就顺手按"认识"，闸门就白做了）；预测试从摆出选项到用户确认之间同样灰着。
 * 灰的方式沿用 [AppButton] 那副禁用态 —— 文字退到 `secondaryText`，不另造一档颜色、
 * 不加 alpha，`clickable(enabled = false)` 本身就不吃事件也不画 ripple。
 *
 * 预览走的是**活跃内核**（设置里那一个，经 [KernelHub.forId]）：与 `StudyViewModel.gradeCard`
 * 同一条 `review → nextIntervalDays` 通路、同一个 `kernelStateFor` 取状态。这不是优化，是
 * 必须的：印在按钮上的数字与点下去真排出的间隔只要有一处各算一套，用户当场就会看见它骗人
 * （v2.6 时这一行直接叫 `MemoryModel.preview`，那一头只有一个内核所以不会分家）。
 * 读设置用 [AppTheme.settings] 而不加形参：页面早就在同一份 CompositionLocal 上读闸门那两项，
 * 为这一个数字新开一条参数链会把"设置从哪来"再拆成两套。
 */
@Composable
private fun GradeRow(
    word: Word,
    scheduling: Scheduling,
    enabled: Boolean,
    onGrade: (ReviewGrade) -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val previewKernel = KernelHub.forId(AppTheme.settings.schedulingKernel)
    // 预览必须用这个词**真实已拖的时间**：拿理想排期算，overdue 的词会印出比实际短得多的间隔
    val gapDays = (System.currentTimeMillis() - (word.lastReviewAt ?: word.createdAt))
        .coerceAtLeast(0L) / ONE_DAY_MS.toDouble()
    val before = kernelStateFor(previewKernel, word)
    // 三档各跑一遍"评一次 → 该排几天"，与 gradeCard 逐字同序；
    // conf 现在恒为 null（采集信心是计划 B 的 ConfidencePill），侧信道不改这里的间隔，
    // 只可能在事后把时刻往前拉，所以按钮上印的仍是内核排出来的那个间隔
    val days = ReviewGrade.entries.associateWith { grade ->
        val rating = grade.toKernelRating()
        previewKernel.nextIntervalDays(
            previewKernel.review(before, gapDays, rating, conf = null),
            rating,
            scheduling.targetRecall,
            scheduling.maxIntervalDays,
        )
    }
    val predictedRecall = recallForDisplay(previewKernel, before, gapDays)
    Spacer(Modifier.height(AppTheme.space.sm))
    Text(
        text = "现在按下去，预计还记得 ${(predictedRecall * 100).roundToInt()}%",
        style = texts.caption,
        color = colors.secondaryText,
    )
    Spacer(Modifier.height(AppTheme.space.xs))
    Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm)) {
        GradeButton(
            label = "认识",
            interval = formatIntervalLabel(days.getValue(ReviewGrade.RECALL)),
            background = colors.successSoft,
            ink = colors.successInk,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            onClick = { onGrade(ReviewGrade.RECALL) },
        )
        GradeButton(
            label = "模糊",
            interval = formatIntervalLabel(days.getValue(ReviewGrade.VAGUE)),
            background = colors.goldSoft,
            ink = colors.goldInk,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            onClick = { onGrade(ReviewGrade.VAGUE) },
        )
        GradeButton(
            label = "忘记",
            interval = formatIntervalLabel(days.getValue(ReviewGrade.FORGET)),
            background = colors.warningSoft,
            ink = colors.warningInk,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            onClick = { onGrade(ReviewGrade.FORGET) },
        )
    }
    Spacer(Modifier.height(AppTheme.space.md))
}

/**
 * 三档自评里的一颗。[enabled] 为假时不吃点击（`clickable(enabled = false)` 连带 ripple 一起没），
 * 文字退到 `secondaryText` —— 这就是 [AppButton] 禁用态用的那一档墨，不新造视觉。
 * 底色与本族 ink 的配对（soft 容器 + ink 字）在可点时一字不改，禁用只动文字那一档，
 * 于是"这一排现在不能按"靠对比旁边的可点控件就分得出来，不需要额外的图标或提示。
 */
@Composable
private fun GradeButton(
    label: String,
    interval: String,
    background: Color,
    ink: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val inkColor = if (enabled) ink else colors.secondaryText
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(AppTheme.radius.md))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = AppTheme.space.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = label, style = texts.cardTitle, color = inkColor)
        Spacer(Modifier.height(2.dp))
        Text(text = interval, style = texts.caption, color = inkColor, textAlign = TextAlign.Center)
    }
}

/**
 * 天数 → 用户能读的一句话。
 *
 * "今日 / 明天 / N 天后"而不是"0.4 天后"：间隔是给决策用的，不是给核对用的。
 * 小于一天一律说"今日"，因为用户今天确实还会再见到它。
 */
internal fun formatIntervalLabel(days: Double): String = when {
    !days.isFinite() || days <= 0.0 -> "今日"
    days < 1.0 -> "今日"
    days < 1.5 -> "明天"
    else -> "${days.roundToInt()} 天后"
}
