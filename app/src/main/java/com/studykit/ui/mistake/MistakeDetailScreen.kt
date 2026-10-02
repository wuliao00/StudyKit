package com.studykit.ui.mistake

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.studykit.data.entity.Mistake
import com.studykit.data.entity.Question
import com.studykit.data.memory.ReviewGrade
import com.studykit.tips.StudyTips
import com.studykit.tips.TipEvent
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.AppTextField
import com.studykit.ui.components.ConfirmDialog
import com.studykit.ui.components.TipCard
import com.studykit.ui.motion.MotionSpec
import com.studykit.ui.study.HINT_TIER_COUNT
import com.studykit.ui.study.nextHintLevel
import com.studykit.ui.study.revealedHints
import com.studykit.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private fun sourceLabel(source: String): String = when (source) {
    Mistake.SOURCE_WORD -> "单词学习"
    Mistake.SOURCE_PRACTICE -> "刷题收录"
    Mistake.SOURCE_PHOTO -> "拍照录入"
    else -> source
}

/**
 * 错题详情页：大图查看（点击放大）+ 题面与重做门（答案与解析默认遮住）+ 学科/来源/时间信息，
 * 操作：编辑学科、**覆盖排期**（次级）、标记已掌握、删除。
 *
 * **route 上的 `mistakeId` 是本页唯一的准绳**（终审 C1）：`openDetail` 由本页 `LaunchedEffect` 发起，
 * VM 交出的行要与它比过才敢渲染（[renderMistakeDetail]），比对不过就是加载态 —— 加载态里整棵内容树
 * 不组合，故「标记掌握 / 覆盖排期 / 删除 / 改学科」四条写路径没有一条能在数据还没答到这一道时按下；
 * 四条写路径传的也都是 `mistakeId` 本身，而不是渲染出来的那一行的 id。
 *
 * 颜色与文字样式统一取 [AppTheme]，间距/圆角取 `AppTheme.space` / `AppTheme.radius` 的 dp 常量；
 * 「标记掌握 / 已掌握 / 删除」这类**文字**走 `successInk/warningInk`（品牌色作字在浅色主题不达 AA）。
 *
 * 「标记掌握」是本页唯一的手势动效：点击后按钮内文字用 `MotionSpec.press` **放大回弹**，
 * 过了放大峰值（[MotionSpec.FadeMs] 后）才 `popBackStack` —— 立刻返回会把这一帧吃掉，
 * 用户看到的只是「按钮自己消失了」。`markMastered` 让 `current.mastered` 当场翻 true，
 * 所以按钮的可见条件是「未掌握 **或** 回弹进行中」，否则动画的第一帧就被状态变更抹掉。
 * 该门控用 `rememberSaveable`：转屏后既不重播弹跳、也不会把已按下的按钮复活。
 * 返回是**一次性**的（`leaveOnce`）：本页三条返回路径（两处返回箭头、删除、弹跳到期）共用同一道门，
 * 免得「手动返回 + 弹跳到期」在 280ms popExit 窗口里叠成两次 pop。
 * 另：`markMastered` 之后该题从列表默认的「待复习」列里消失（spec 划线消失，由列表侧
 * `animateItem()` 播退场），已掌握清单改由列表页顶的「已掌握」chip 进入。
 *
 * ## 重做优先（v2.5 S3）
 * 本页**默认不展示答案与解析**：进来只看得到题面（`splitForRedo` 从正文里按行首标记切出的那前半），
 * 按过「我重做了一遍」才展开答案与解析段、备注段（若它被当作解析遮住）与一条固定的自我解释提问。
 * 依据是检索练习优于再读解析（Roediger & Karpicke 2006；Karpicke & Blunt 2011, Science）：
 * 答案在场时的「看懂」不算提取过。
 * 判定与文案全在 `RedoFlow.kt`（纯函数，钉在 `RedoFlowTest`），页面只读 [RedoGate] 的字段。
 *
 * 阶段用 `rememberSaveable(mistakeId)` 存**阶段名串**：转屏/换屏不丢，换一道题（同一 entry 上
 * `mistakeId` 变了）不会把上一道题的展开态带过来；存档里读到认不出的串时
 * [decodeRedoPhase] 回到遮住那一侧，宁可让用户多点一次按钮。
 * 阶段**不落库**：REDO/CHECK 这一位只是页面瞬时态；逐次重做历史（`mistake_redos`，表自 v7 就在）
 * 由 v2.7 B15 的重做判定经 [MistakeViewModel.gradeRedo] 写入 —— 展开/收起这一步本身仍一次都不写，
 * [redoHistoryBoundary] 那句就是把这条界线说清楚：看过解析不等于记过一次重做。
 * 排期本体（v2.7 B14）：算法评一次分就把下次到期写进 `mistakes.review_at`，
 * 页面上的三档手选因此从主菜单**降级**成次级「覆盖排期」（同一个列、两个人写）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MistakeDetailScreen(
    viewModel: MistakeViewModel,
    mistakeId: Long,
    onBack: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    // 请求由**页面**发起（与 `BookDetailScreen` / `HabitCalendarScreen` 同一套纪律）：
    // 放在 `AppNav` 的 entry 里时，本页首帧读到的仍是上一道错题的 detail —— 见 [renderMistakeDetail]。
    //
    // 路由键守卫（终审 C1 的同类站点）：三页同一条规则，但只有这里是**三相**判定而不是
    // `?.takeIf { it.id == routeId }` —— `MistakeDetailState` 额外带了 `answered`（库是否已就
    // 该 id 答过一次），所以本页分得出「还没答到」与「库里真没有（被别处删了）」；
    // 另两页的 VM 没有这一位，两相会塌成一相，只能一律按加载态挡回去。
    // 判定本身是纯函数，逐条钉在 `MistakeDetailRenderTest` 里。
    LaunchedEffect(mistakeId) { viewModel.openDetail(mistakeId) }
    val detailState by viewModel.detailState.collectAsStateWithLifecycle()
    val redoCtx by viewModel.redoQuestionContext.collectAsStateWithLifecycle()
    val render = renderMistakeDetail(state = detailState, mistakeId = mistakeId)
    // 重做阶段声明在任何页相 return **之前**：加载态与就绪态走的是两条不同的组合路径，
    // 状态排在 return 之后就会在重新进入就绪态那一次组合里回到初值 —— 用户刚按开的
    // 展开态就这么抹掉了（其余三枚 saveable 状态同一口径）。
    var redoPhaseRaw by rememberSaveable(mistakeId) {
        mutableStateOf(encodeRedoPhase(RedoPhase.REDO))
    }
    val redoAction = { action: RedoAction ->
        redoPhaseRaw = encodeRedoPhase(nextRedoPhase(decodeRedoPhase(redoPhaseRaw), action))
    }
    // v2.7 B15：重做区的四枚会话态，全键在 mistakeId 上（与 redoPhaseRaw 同一纪律——声明在任何
    // 页相 return 之前，加载态→就绪态那一次组合才不会把刚点开的展开/提示抹掉）。
    //  - hintLevel：挤到第几档提示（换一道同考点的即变题，点变式时归零）；
    //  - sessionQuestionId：本次会话手动换到的变式题 id（null = 用回溯到的原题），**不落库**；
    //  - restated：拍照题是否过了「我已重述」重述门（→ gradeRedo 的 hadNoteRebuild）；
    //  - graded：这一次重做是否已判定入库（挡连点往 mistake_redos 写两条）。
    var hintLevel by rememberSaveable(mistakeId) { mutableStateOf(0) }
    var sessionQuestionId by rememberSaveable(mistakeId) { mutableStateOf<Long?>(null) }
    var restated by rememberSaveable(mistakeId) { mutableStateOf(false) }
    var graded by rememberSaveable(mistakeId) { mutableStateOf(false) }
    var showFullImage by remember { mutableStateOf(false) }
    // 学科对话框带的是**用户输入**，故开合与文本一起 saveable（终审 C3 的同类站点）：
    // 转屏后对话框还在、已输入的学科还在。删除确认与看图浮层不留输入，仍按瞬时态处理。
    var showSubjectDialog by rememberSaveable { mutableStateOf(false) }
    // 覆盖排期对话框里只有选项没有输入，但它是「按错了还能退出」的那一步，开合仍留 saveable
    var showOverrideDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    // 回弹进行中：true 之后按钮要继续留在屏幕上，否则动画第一帧就被 mastered 状态变更抹掉
    var masteredBounce by rememberSaveable { mutableStateOf(false) }
    // 本页只允许返回一次：弹跳的 220ms 里用户可能已经手动返回，而该 entry 在 280ms popExit
    // 期间仍在组合，`LaunchedEffect` 会再 pop 一次 —— 于是多弹一层，直接落到「学习」页。
    // 门控用 rememberSaveable：转屏重建组合后也不给第二次 pop 开门（`AppNav` 那头还有一道
    // 「entry 不是栈顶就不 pop」的兜底，系统返回键绕过这里时同样不会多弹）。
    var returned by rememberSaveable { mutableStateOf(false) }
    val leaveOnce = {
        if (!returned) {
            returned = true
            onBack()
        }
    }
    val masteredScale by animateFloatAsState(
        targetValue = if (masteredBounce) 1.16f else 1f,
        animationSpec = MotionSpec.press,
        label = "masteredBounce",
    )
    LaunchedEffect(masteredBounce) {
        if (masteredBounce) {
            // `press`（ζ=0.55, k=420）的首个峰值在 π/ωd ≈ 183ms，220ms 已越过峰值开始回落，
            // 这一帧交接给返回转场正好读得出「按下去 → 弹回来」
            delay(MotionSpec.FadeMs.toLong())
            leaveOnce()
        }
    }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val reviewFormat = remember { SimpleDateFormat("MM月dd日", Locale.getDefault()) }

    // ── 页相一：VM 还没答到这一道 → 加载态。整棵内容树（含标记掌握 / 设复习时间 / 删除 /
    //    编辑学科四类写动作）都不组合，也就没有任何一条路径能写到别的行上去。
    if (render is MistakeDetailLoading) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = AppTheme.space.pageH),
        ) {
            Spacer(Modifier.height(AppTheme.space.sm))
            IconButton(onClick = { leaveOnce() }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    // 图标落在页面底色上，按 T1 裁定走 accentInk（accent 仅 3.04:1）
                    tint = colors.accentInk,
                )
            }
            Spacer(Modifier.height(AppTheme.space.xl * 2))
            // 与 `CardStudyScreen` 会话装载中的 loading 同一枚观感，不新造视觉
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = colors.accent)
            }
        }
        return
    }

    // ── 页相二：答完了但库里没有这一行（被别处删了）
    if (render is MistakeDetailMissing) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = AppTheme.space.pageH),
        ) {
            Spacer(Modifier.height(AppTheme.space.sm))
            IconButton(onClick = { leaveOnce() }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    // 图标落在页面底色上，按 T1 裁定走 accentInk（accent 仅 3.04:1）
                    tint = colors.accentInk,
                )
            }
            Text(text = "错题不存在或已删除", style = texts.caption)
        }
        return
    }

    val current = (render as MistakeDetailReady).mistake
    // 到这里 `current.id == mistakeId` 是由 [renderMistakeDetail] 保证的：
    // 下面所有写动作一律喂 route 上的 [mistakeId]，不喂渲染出来的行。
    // 重做门控只看这一行的正文与备注，算一次就够（纯函数，无 IO）。
    val redoGate = buildRedoGate(
        content = current.content,
        note = current.note,
        phase = decodeRedoPhase(redoPhaseRaw),
    )
    // 重做目标题目：默认是 note 里 qid 回溯到的那道题；点过「换一道同考点的」则换成会话选中的那道
    // （只改这一个局部变量，错题行一个字不动）。拍照 / 无 qid 的错题拿不到题 → null，提示阶梯不出。
    val variant = redoCtx.variantPool.firstOrNull { it.id == sessionQuestionId }
    val redoQuestion: Question? = variant ?: redoCtx.linked
    // 「换一道同考点的」可不可用：口径与纯函数 VariantPicker.pick 完全一致
    // （目标存在、标签非空、池里除自己还有同标签的题）；按钮出现即代表 pick 会给非 null。
    val variantAvailable = redoQuestion != null &&
        redoQuestion.conceptTag.isNotBlank() &&
        redoCtx.variantPool.any { it.conceptTag == redoQuestion.conceptTag && it.id != redoQuestion.id }
    // 拍照题的重述门：未过之前，答案与解析段保持折叠（见 RedoFlow.restateGateRequired）。
    val restateBlocking = restateGateRequired(current.source) && !restated
    val imageFile = current.imagePath?.let { viewModel.resolveImage(it) }
    // 组合期不 stat 磁盘（终审 I8）：与 `MistakeCaptureScreen` 的照片预览同一写法 ——
    // 路径在组合期拼好，存在性由 IO 线程回填。初值乐观取「有路径就当有图」，
    // 否则大图会在「塌陷 → 弹回」之间跳一次，正文跟着抖一屏。
    var imageExists by remember(imageFile) { mutableStateOf(imageFile != null) }
    LaunchedEffect(imageFile) {
        imageExists = withContext(Dispatchers.IO) { imageFile?.exists() == true }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppTheme.space.pageH),
        ) {
            Spacer(Modifier.height(AppTheme.space.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { leaveOnce() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        // 图标落在页面底色上，按 T1 裁定走 accentInk（accent 仅 3.04:1）
                        tint = colors.accentInk,
                    )
                }
                Spacer(Modifier.weight(1f))
                // 未掌握、或回弹正在进行时都保留按钮：后者是「点击 → 放大回弹 → 返回」的可见前提
                if (!current.mastered || masteredBounce) {
                    TextButton(
                        onClick = {
                            if (!masteredBounce) {
                                viewModel.markMastered(mistakeId)
                                masteredBounce = true
                            }
                        },
                    ) {
                        Text(
                            text = "标记掌握",
                            style = texts.aux.copy(
                                color = colors.successInk,
                                fontWeight = FontWeight.Medium,
                            ),
                            // 缩放只走绘制层，不反过来把 TopBar 撑高
                            modifier = Modifier.graphicsLayer {
                                scaleX = masteredScale
                                scaleY = masteredScale
                            },
                        )
                    }
                }
            }

            Text(text = current.title, style = texts.pageTitle)
            Spacer(Modifier.height(AppTheme.space.xs))
            Text(
                text = "${current.subject} · ${sourceLabel(current.source)} · ${dateFormat.format(current.createdAt)}",
                style = texts.caption,
            )
            // 回弹那一瞬按钮还在，两个标签同帧会互相抢读，故等动画交接完再显示
            if (current.mastered && !masteredBounce) {
                Spacer(Modifier.height(AppTheme.space.xs))
                Text(
                    text = "已掌握",
                    // success 作文字浅色只有 2.22:1，走 T15 墨水批次的 successInk（白卡 5.39:1）
                    style = texts.caption.copy(
                        color = colors.successInk,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }

            // ── 大图 ──────────────────────────────────────────────────────
            if (imageFile != null && imageExists) {
                Spacer(Modifier.height(AppTheme.space.md))
                AsyncImage(
                    model = imageFile,
                    // 不写 current.title：标题就在上方一行，重复一次是噪声（终审 I9）。
                    // 但这枚图**可点**（点开灯箱），清空描述会留下一个「未加标签的按钮」，
                    // 反而更糟 —— 故换成它真正做的事。
                    contentDescription = "查看大图",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(AppTheme.radius.lg))
                        // 图片卡：1dp divider 发丝描边，夜间卡面与照片暗部分层
                        .border(
                            width = 1.dp,
                            color = colors.divider,
                            shape = RoundedCornerShape(AppTheme.radius.lg),
                        )
                        .clickable { showFullImage = true },
                )
            }

            // ── 题面 / 重做门 / 答案与解析 / 备注 ──────────────────────────
            if (redoGate.stemText.isNotBlank()) {
                Spacer(Modifier.height(AppTheme.space.md))
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "题目内容",
                        style = texts.caption.copy(fontWeight = FontWeight.Medium),
                    )
                    Spacer(Modifier.height(AppTheme.space.xs))
                    // 重做阶段只给题面：整段正文以答案标记开头时 stemText 为空，这块就不出卡
                    Text(text = redoGate.stemText, style = texts.body)
                }
            }

            // 重做门：先自己做一遍，才给对答案的机会
            Spacer(Modifier.height(AppTheme.space.md))
            RedoGateCard(
                gate = redoGate,
                onAction = redoAction,
                redoQuestion = redoQuestion,
                hintLevel = hintLevel,
                variantAvailable = variantAvailable,
                needsRestate = restateGateRequired(current.source),
                restated = restated,
                graded = graded,
                onHint = { hintLevel = nextHintLevel(hintLevel) },
                onVariant = {
                    // 只拿同考点池里非自身的一道换进来（会话内），不写库；换完把提示归零
                    val from = redoQuestion?.id ?: return@RedoGateCard
                    VariantPicker.pick(from, redoCtx.variantPool)?.let {
                        sessionQuestionId = it.id
                        hintLevel = 0
                    }
                },
                onRestate = { restated = true },
                onVerdict = { grade ->
                    // 一次重做只判定一次：挡连点往 mistake_redos 写两条（先状态后历史已封在 VM.gradeRedo 里）
                    if (!graded) {
                        graded = true
                        viewModel.gradeRedo(
                            id = mistakeId,
                            grade = grade,
                            conf = null,
                            hintsUsed = hintLevel,
                            hadNoteRebuild = restated,
                        )
                    }
                },
            )

            // 答案与解析：对照阶段才出现，出现就是原文那一段（不重写、不重排）；拍照题未过重述门前一直折着
            if (redoGate.answerKey.isNotBlank() && redoGate.answerKeyVisible && !restateBlocking) {
                Spacer(Modifier.height(AppTheme.space.md))
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = answerSectionTitle(),
                        style = texts.caption.copy(fontWeight = FontWeight.Medium),
                    )
                    Spacer(Modifier.height(AppTheme.space.xs))
                    Text(text = redoGate.answerKey, style = texts.body)
                }
            }

            // 备注：正文里认不出答案段时，它是本轮唯一可能被遮住的一段（见 `buildRedoGate`）；
            // 刷题收录那行 `qid:` 标记不是解析，任何阶段都照常显示。拍照题同样受重述门约束。
            if (redoGate.noteText.isNotBlank() && redoGate.noteVisible && !restateBlocking) {
                Spacer(Modifier.height(AppTheme.space.md))
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "备注",
                        style = texts.caption.copy(fontWeight = FontWeight.Medium),
                    )
                    Spacer(Modifier.height(AppTheme.space.xs))
                    Text(text = redoGate.noteText, style = texts.body)
                }
            }

            // ── 复习排期 ──────────────────────────────────────────────────
            Spacer(Modifier.height(AppTheme.space.lg))
            Text(text = reviewScheduleTitle(), style = texts.cardTitle)
            Spacer(Modifier.height(AppTheme.space.xs))
            // 这一行读的就是算法写回来的那一列（`review_at`）；手动覆盖过也是它，不再分辨两本账
            Text(
                text = nextReviewLine(current.reviewAt?.let { reviewFormat.format(it) }),
                style = texts.caption,
            )
            // v2.7 B14（spec §6）：v2.5 §2.4 那句「复习时间由你自己定，这里没有算法排期。」已退役，
            // 换成下面这句同一位置的实话：排期归系统，人只保留覆盖权。
            // 文案全在 `MistakeScheduling.kt`，被改回旧承诺会被 `MistakeDetailRenderTest` 拦下。
            Spacer(Modifier.height(AppTheme.space.sm))
            Text(
                text = systemSchedulingNote(),
                style = texts.caption,
                color = colors.secondaryText,
            )
            Spacer(Modifier.height(AppTheme.space.md))
            // 三档手选不再占主位：收进次级入口（与「编辑学科归类」「删除错题」同档）
            AppButton(
                text = scheduleOverrideLabel(),
                secondary = true,
                onClick = { showOverrideDialog = true },
            )

            // ── 学科归类 ──────────────────────────────────────────────────
            Spacer(Modifier.height(AppTheme.space.lg))
            AppButton(text = "编辑学科归类", secondary = true, onClick = { showSubjectDialog = true })

            Spacer(Modifier.height(AppTheme.space.md))
            AppButton(
                text = "删除错题",
                secondary = true,
                onClick = { showDeleteDialog = true },
            )
            Spacer(Modifier.height(AppTheme.space.xl))
        }

        // ── 全屏看图 ──────────────────────────────────────────────────────
        if (showFullImage && imageFile != null) {
            FullImageOverlay(file = imageFile, onDismiss = { showFullImage = false })
        }
    }

    // ── 编辑学科对话框 ──────────────────────────────────────────────────────
    if (showSubjectDialog) {
        SubjectEditDialog(
            initial = current.subject,
            onDismiss = { showSubjectDialog = false },
            onConfirm = { subject ->
                viewModel.updateSubject(mistakeId, subject)
                showSubjectDialog = false
            },
        )
    }

    // ── 覆盖排期对话框 ──────────────────────────────────────────
    if (showOverrideDialog) {
        OverrideScheduleDialog(
            onDismiss = { showOverrideDialog = false },
            onPick = { days ->
                showOverrideDialog = false
                viewModel.setReviewAt(mistakeId, dayOffset(days))
            },
        )
    }

    // ── 删除确认 ──────────────────────────────────────────────────────────
    if (showDeleteDialog) {
        ConfirmDialog(
            title = "删除错题",
            body = "删除后不可恢复，相关图片也会一并清理。",
            confirmLabel = "删除",
            danger = true,
            onConfirm = {
                showDeleteDialog = false
                viewModel.delete(mistakeId) { leaveOnce() }
            },
            onDismiss = { showDeleteDialog = false },
        )
    }
}

/**
 * 重做门：把「先自己做一遍」摆在「对答案」前面。
 *
 * 三相共用同一枚 [AppCard]（本仓卡片口径与 `ImportResultScreen` 的折叠区同一套），只换内容：
 * - [RedoPhase.REDO]：行为提示（[redoCoverHint]）+「少了什么」（[coveredLabel]）+
 *   提示阶梯（[revealedHints]/[nextHintLevel]，仅当回溯到题目时出；首次点提示带出 PRODUCTIVE_STRUGGLE）+
 *   同考点变式钮（[variantButtonLabel]，仅当 VariantPicker 会给非 null 时出）+ 主行动钮「我重做了一遍」。
 * - [RedoPhase.CHECK] 且拍照题未过重述门：只出重述提示（[restatePrompt]）+「我已重述」（[restateConfirmLabel]），解析保持折叠。
 * - [RedoPhase.CHECK] 其余：自我解释提问 + 边界说明 + 「重新遮住答案」（仅当真有东西可遮）+判定（[redoVerdictPrompt]）。
 *
 * 判定一次只认一次（graded 挡连点）；「做出来了」→ RECALL、「没做出来」→ FORGET，均走 [MistakeViewModel.gradeRedo]
 * （先写排期状态、再写 mistake_redos 历史，顺序纪律封在 VM里）。主/次行动钮不新造视觉；
 * 文案全在 `RedoFlow.kt`，逐句钉在 `RedoFlowTest`。
 */
@Composable
private fun RedoGateCard(
    gate: RedoGate,
    onAction: (RedoAction) -> Unit,
    redoQuestion: Question?,
    hintLevel: Int,
    variantAvailable: Boolean,
    needsRestate: Boolean,
    restated: Boolean,
    graded: Boolean,
    onHint: () -> Unit,
    onVariant: () -> Unit,
    onRestate: () -> Unit,
    onVerdict: (ReviewGrade) -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = redoGateTitle(), style = texts.cardTitle)
        Spacer(Modifier.height(AppTheme.space.xs))
        if (gate.phase == RedoPhase.REDO) {
            Text(text = redoCoverHint(), style = texts.body)
            // 「少了什么」只在**真少显示了**的时候说（[RedoGate.isCovered]）：
            // 无可遮内容时 [coveredLabel] 本身给 null，两道门叠着，页面不会说「答案已遮住」而屏幕上它就在眼前
            if (gate.isCovered) {
                coveredLabel(gate.concealed)?.let { label ->
                    Spacer(Modifier.height(AppTheme.space.xs))
                    Text(text = label, style = texts.caption, color = colors.secondaryText)
                }
            }
            // ── 挤牙膏提示阶梯（复用 QuizFeedback 三档，一次只往前挪一档）──
            // 只有回溯到题目时才有提示（hintTiers 吃 subject）；拍照题拿不到题就整段不出。
            if (redoQuestion != null) {
                val hints = revealedHints(redoQuestion, hintLevel)
                if (hints.isNotEmpty()) {
                    Spacer(Modifier.height(AppTheme.space.sm))
                    hints.forEachIndexed { tier, line ->
                        Text(text = "${tier + 1}. $line", style = texts.aux)
                        Spacer(Modifier.height(AppTheme.space.xs))
                    }
                    // 错题重做里点了提示，带出 PRODUCTIVE_STRUGGLE 那条 [科学验证]（逐字钉在 StudyTipsTest）
                    StudyTips.forEvent(TipEvent.FirstHintOnRedo)?.let { tip ->
                        Spacer(Modifier.height(AppTheme.space.xs))
                        TipCard(tip = tip, modifier = Modifier.fillMaxWidth())
                    }
                }
                if (hintLevel < HINT_TIER_COUNT) {
                    Spacer(Modifier.height(AppTheme.space.sm))
                    AppButton(
                        text = if (hintLevel == 0) redoHintLabel() else "再往前推一步（第 ${hintLevel + 1} / $HINT_TIER_COUNT 档）",
                        secondary = true,
                        onClick = onHint,
                    )
                }
            }
            // ── 同考点变式：换一道来重做（只在会话内替换目标，错题行一个字不动）──
            if (variantAvailable) {
                Spacer(Modifier.height(AppTheme.space.sm))
                AppButton(text = variantButtonLabel(), secondary = true, onClick = onVariant)
            }
            Spacer(Modifier.height(AppTheme.space.sm))
            AppButton(text = redoConfirmLabel(), onClick = { onAction(RedoAction.ConfirmedRedo) })
        } else if (needsRestate && !restated) {
            // 拍照题：按过「我重做了一遍」还不够，先照自己的话重述、点「我已重述」才给展开解析
            Text(text = restatePrompt(), style = texts.body)
            Spacer(Modifier.height(AppTheme.space.sm))
            AppButton(text = restateConfirmLabel(), onClick = onRestate)
        } else {
            Text(text = selfExplainPrompt(), style = texts.body)
            Spacer(Modifier.height(AppTheme.space.sm))
            Text(
                text = redoHistoryBoundary(),
                style = texts.caption,
                color = colors.secondaryText,
            )
            if (gate.hasReveal) {
                Spacer(Modifier.height(AppTheme.space.sm))
                AppButton(
                    text = redoCoverAgainLabel(),
                    secondary = true,
                    onClick = { onAction(RedoAction.CoverAnswer) },
                )
            }
            // ── 判定：采下「这次做出来了吗」→ gradeRedo（先状态后历史）──
            Spacer(Modifier.height(AppTheme.space.md))
            Text(text = redoVerdictPrompt(), style = texts.body)
            Spacer(Modifier.height(AppTheme.space.sm))
            if (graded) {
                Text(
                    text = "已记录这一次重做：排期与重做历史都更新了。",
                    style = texts.caption,
                    color = colors.successInk,
                )
            } else {
                AppButton(
                    text = redoVerdictCorrectLabel(),
                    onClick = { onVerdict(ReviewGrade.RECALL) },
                )
                Spacer(Modifier.height(AppTheme.space.sm))
                AppButton(
                    text = redoVerdictWrongLabel(),
                    secondary = true,
                    onClick = { onVerdict(ReviewGrade.FORGET) },
                )
            }
        }
    }
}

/**
 * 复习时间快捷项：`accentSoft` 底 + `accentInk` 字的柔底药丸。
 *
 * 旧写法是 `accent` 10% 底 + `accent` 文字（浅色下 3.04:1 不达 AA），按 ledger 的
 * 「soft pill → accentSoft + accentInk」口径换墨色；容器仍是 `clip → background(color)`
 * 那一档可点药丸写法（`clip` 在前才把按压 ripple 裁成圆角）。
 */
@Composable
private fun ReviewOption(label: String, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    Box(
        modifier = Modifier
            // 约 34dp 高，够不上 48dp 最小可点目标（终审 I9）。挂在链首 ⇒ 只撑大不可见的
            // 点击槽位，`clip/background` 在它下游，药丸本身的形状与配色一分不动。
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(AppTheme.radius.md))
            .background(colors.accentSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = AppTheme.space.md, vertical = AppTheme.space.sm),
    ) {
        Text(
            text = label,
            style = texts.aux.copy(color = colors.accentInk, fontWeight = FontWeight.Medium),
        )
    }
}

/** 距今天 +days 天后的上午 9 点时间戳 */
private fun dayOffset(days: Int): Long {
    val calendar = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, days)
        set(Calendar.HOUR_OF_DAY, 9)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return calendar.timeInMillis
}

/**
 * 覆盖排期对话框：把原来的三档手选从主位搬进来。
 *
 * 三颗药丸仍是页面上原有的 [ReviewOption]（同一个视觉语言），只是不再一进来就摊在眼前：
 * spec §6 的口径是"默认由内核排，手动降级为次级覆盖"。文案与档位全在
 * `MistakeScheduling.kt` 的 [manualOverrideOptions]，逐档钉在 `MistakeDetailRenderTest`。
 * 不加"确定"钮：选一档就是表达完毕，当场写库并收起（原来的三颗快捷项也是同一语义）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OverrideScheduleDialog(
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = scheduleOverrideLabel(), style = texts.cardTitle) },
        text = {
            Column {
                Text(
                    text = systemSchedulingNote(),
                    style = texts.caption,
                    color = colors.secondaryText,
                )
                Spacer(Modifier.height(AppTheme.space.md))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm),
                    verticalArrangement = Arrangement.spacedBy(AppTheme.space.sm),
                ) {
                    manualOverrideOptions().forEach { (label, days) ->
                        ReviewOption(label) { onPick(days) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消", color = colors.secondaryText)
            }
        },
    )
}

/**
 * 全屏看图：黑底占满，点击关闭。
 *
 * 底色**刻意不用** `colors.background`：这是照片灯箱，纯黑是取景框（两张照片对比时不受页面底色
 * 偏色影响），且夜间主题的暖纸底色会把白底题目照片糊成一片。它不随主题变（两主题同为 `#000000`），
 * 但仍是设计意图而非临时值，故 T15 收进 `colors.lightbox` 令牌 —— 页面里不再留任何硬编码色值；
 * 允许写死色值的只有两处：`ui/theme/Palette.kt`（色板本体）与 `ui/book/BookSpine.kt`（书脊身份色，见其 KDoc）。
 */
@Composable
private fun FullImageOverlay(file: File, onDismiss: () -> Unit) {
    val colors = AppTheme.colors
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.lightbox)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = file,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 编辑学科对话框 */
@Composable
private fun SubjectEditDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    var value by rememberSaveable { mutableStateOf(initial) }
    // 注：`rememberSaveable` 的初始值只在没有存档时生效，所以转屏后保留的是用户改过的那份，
    // 不会被 `initial` 盖回去；对话框关闭再重开（组合槽回收）时才重新回填当前学科。
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "编辑学科归类", style = texts.cardTitle) },
        text = {
            AppTextField(
                value = value,
                onValueChange = { value = it },
                placeholder = "输入学科",
            )
        },
        confirmButton = {
            TextButton(
                enabled = value.isNotBlank(),
                onClick = { onConfirm(value.trim()) },
            ) {
                Text(text = "保存", color = colors.accentInk)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消", color = colors.secondaryText)
            }
        },
    )
}
