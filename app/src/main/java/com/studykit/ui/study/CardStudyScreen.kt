package com.studykit.ui.study

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draggable
import androidx.compose.foundation.gestures.Orientation
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
import androidx.compose.foundation.layout.onSizeChanged
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.data.entity.Word
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.ConfettiBurst
import com.studykit.ui.components.EmptyState
import com.studykit.ui.components.RingGauge
import com.studykit.ui.motion.MotionSpec
import com.studykit.ui.theme.AppTheme
import com.studykit.ui.theme.DesignTokens
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 卡片学习页：队列逐张 3D 翻面学习，右滑「认识」/左滑「忘记」推进间隔重复状态机；
 * 一轮结束展示庆祝小结卡。
 *
 * 颜色与文字样式统一取 `AppTheme`，间距/圆角仍走 [DesignTokens] 的 dp 常量。
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
    val state = session

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = DesignTokens.PageHorizontalPadding),
    ) {
        Spacer(Modifier.height(DesignTokens.SpacingSm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = colors.accentInk,
                )
            }
            Spacer(Modifier.width(DesignTokens.SpacingXs))
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
                Spacer(Modifier.height(DesignTokens.SpacingXl * 2))
                EmptyState(
                    title = "没有待复习的单词",
                    caption = "当前没有到期的学习任务，先录入一些单词吧",
                    icon = Icons.Outlined.Star,
                )
                Spacer(Modifier.height(DesignTokens.SpacingLg))
                AppButton(text = "录入单词", onClick = onAddWord)
                Spacer(Modifier.height(DesignTokens.SpacingMd))
                AppButton(text = "返回", secondary = true, onClick = onBack)
            }

            state.finished -> SessionSummary(
                knownCount = state.knownCount,
                unknownCount = state.unknownCount,
                onRestart = { viewModel.startCardSession() },
                onBack = onBack,
            )

            else -> {
                Spacer(Modifier.height(DesignTokens.SpacingMd))
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
                Spacer(Modifier.height(DesignTokens.SpacingLg))
                AnimatedContent(
                    targetState = state.index,
                    transitionSpec = {
                        val enter = slideInHorizontally(
                            animationSpec = spring(dampingRatio = 0.8f, stiffness = 260f),
                        ) { it / 3 } + fadeIn(animationSpec = tween(durationMillis = 180))
                        val exit = slideOutHorizontally { -it / 3 } +
                            fadeOut(animationSpec = tween(durationMillis = 120))
                        enter.togetherWith(exit)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    label = "cardSwitch",
                ) { idx ->
                    SwipeRatingCard(
                        word = state.queue[idx],
                        onGrade = { known ->
                            if (known) viewModel.markKnown() else viewModel.markUnknown()
                        },
                    )
                }
            }
        }
    }
}

/**
 * 滑动评价卡：三层结构，每层只负责一种变换，互不污染。
 *
 * ```
 * 布局根 Box —— weight(1f) 撑满剩余高度 + onSizeChanged 测宽（阈值/飞出距离都按它算）
 *   ├─ 滑动层 Box —— translationX / rotationZ / scale + draggable：卡片本体跟手倾斜、飞出
 *   │    └─ 翻面平面 Box —— rotationY = flip + clip + clickable：3D 翻面
 *   │         ├─ CardFace 正面（flip < 90°）
 *   │         └─ CardFace 背面（flip >= 90°，自身再转 180° 抵消镜像）
 *   └─ GradeBadge ×2 —— 只吃 alpha，位置由布局根 align 决定
 * ```
 *
 * 角标**不能**挂在滑动层之下：否则它会被 `translationX` 连卡片一起带出屏幕、被 `rotationZ`
 * 带着倾斜、翻到背面时还会跟着 `rotationY` 镜像成反字。挂在布局根上时它就是一个稳定的角落提示，
 * 只有透明度跟拖拽位移变化（发起结算即刻归零）。
 *
 * 手势阈值与 [MotionSpec] 保持一致：位移越过 `宽 * 0.33f`，或松手甩速越过 ±900f px/s
 * （且方向与位移同号）即结算；飞出目标 ±`宽 * 1.5f` 走 `MotionSpec.flyOut()`，否则
 * [MotionSpec.snap] 回弹。翻面走 [MotionSpec.flip] spring，逐帧跟手、不设时长。
 *
 * 语义与墨墨一致：左右滑都**不要求**先翻面即可评价，只有「认识」按钮要求已翻面。
 *
 * @param onGrade 结算当前卡（`true` = 认识）。同一张卡只会回调一次，见上方 `claim()` 守卫。
 */
@Composable
private fun SwipeRatingCard(
    word: Word,
    onGrade: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val density = LocalDensity.current.density
    var widthPx by remember { mutableIntStateOf(0) }
    var flipped by rememberSaveable(word.id) { mutableStateOf(false) }
    val flip by animateFloatAsState(
        targetValue = if (flipped) 180f else 0f,
        animationSpec = MotionSpec.flip,
        label = "flip",
    )
    val offsetX = remember { Animatable(initialValue = 0f) }
    val scope = rememberCoroutineScope()
    val onGradeNow by rememberUpdatedState(onGrade)
    // ── 结算守卫 ─────────────────────────────────────────────────────────
    // ViewModel 的 index 要等 DB 写完才推进，所以「滑动 flyOut 协程」与「按钮点击」若在同帧
    // 各结算一次，会把同一张卡复习两遍（recordReview 双写、间隔被后一次覆盖）。
    // 所有路径都先 claim 抢权限，抢不到即视为重复触发。
    val graded = remember { mutableStateOf(false) }

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

    /** 滑动路径：先抢权限（飞行途中角标让位、按钮再点也无效），飞出结束才真正结算 */
    fun flyAndGrade(targetPx: Float, known: Boolean) {
        if (!claim()) return
        scope.launch {
            offsetX.animateTo(targetValue = targetPx, animationSpec = MotionSpec.flyOut())
            onGradeNow(known)
        }
    }

    val threshold = { (widthPx * 0.33f).coerceAtLeast(1f) }
    val drag = offsetX.value / threshold()
    val knownA = if (graded.value) 0f else drag.coerceIn(0f, 1f)
    val unknownA = if (graded.value) 0f else (-drag).coerceIn(0f, 1f)
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
                        rotationZ = drag * 6f
                        scaleX = 1f - 0.04f * drag.coerceIn(-1f, 1f).let { it * it }
                        scaleY = scaleX
                    }
                    .draggable(
                        state = dragState,
                        orientation = Orientation.Horizontal,
                        onDragStopped = { velocity ->
                            val w = widthPx.toFloat().coerceAtLeast(1f)
                            when {
                                offsetX.value > threshold() ||
                                    (velocity > 900f && offsetX.value > 0f) ->
                                    flyAndGrade(w * 1.5f, true)

                                offsetX.value < -threshold() ||
                                    (velocity < -900f && offsetX.value < 0f) ->
                                    flyAndGrade(-w * 1.5f, false)

                                else -> scope.launch {
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
                            rotationY = flip
                            cameraDistance = 16f * density
                        }
                        .clip(RoundedCornerShape(DesignTokens.CornerRadiusLg))
                        .clickable { flipped = !flipped },
                ) {
                    CardFace(
                        visible = flip < 90f,
                        content = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(word.word, style = texts.largeTitle, textAlign = TextAlign.Center)
                                Spacer(Modifier.height(DesignTokens.SpacingMd))
                                Text("点击翻面 · 右滑认识 左滑忘记", style = texts.caption)
                            }
                        },
                    )
                    CardFace(
                        visible = flip >= 90f,
                        mirrored = true,
                        content = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(word.meaning, style = texts.pageTitle, textAlign = TextAlign.Center)
                                if (word.example.isNotBlank()) {
                                    Spacer(Modifier.height(DesignTokens.SpacingMd))
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
                color = colors.success,
                alpha = knownA,
                align = Alignment.TopEnd,
                rotation = 12f,
            )
            GradeBadge(
                glyph = Icons.Filled.Close,
                contentDescription = "不认识",
                color = colors.warning,
                alpha = unknownA,
                align = Alignment.TopStart,
                rotation = -12f,
            )
        }
        Spacer(Modifier.height(DesignTokens.SpacingLg))
        Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.SpacingMd)) {
            OutlinedButton(
                onClick = { grade(false) },
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
                shape = RoundedCornerShape(DesignTokens.CornerRadiusXl),
                border = BorderStroke(1.5.dp, colors.warning),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.warning),
            ) {
                Text("不认识", style = texts.body)
            }
            OutlinedButton(
                onClick = { grade(true) },
                enabled = flipped,
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
                shape = RoundedCornerShape(DesignTokens.CornerRadiusXl),
                border = BorderStroke(1.5.dp, colors.success),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.success),
            ) {
                Text("认识", style = texts.body)
            }
        }
        Spacer(Modifier.height(DesignTokens.SpacingLg))
    }
}

/**
 * 翻面的一侧：与兄弟面同尺寸铺满翻面平面（`fillMaxSize`），因此正反面文字长度不同也不会
 * 让卡片在翻面瞬间改变高度。
 *
 * 可见性用 90° 硬切（`alpha = if (visible) 1f else 0f`）：此刻平面正好侧立、投影宽度为 0，
 * 切换点肉眼不可见，也不会出现「两面同时半透明互相透出」的穿帮。
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
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = DesignTokens.SpacingXl * 1.5f),
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
 * 刻意不放文案：17sp 文字压在 `success`/`warning` 实底上，浅色主题只有 ~2.2:1，属于可读性硬伤；
 * 而 ✓/✗ 是**非文本元素**，靠形状 + 颜色 + 位置表意，配 [contentDescription] 交给读屏，
 * 文字版语义仍由下方「不认识 / 认识」两颗按钮承担。
 */
@Composable
private fun BoxScope.GradeBadge(
    glyph: ImageVector,
    contentDescription: String,
    color: Color,
    alpha: Float,
    align: Alignment,
    rotation: Float,
) {
    if (alpha <= 0f) return
    Box(
        modifier = Modifier
            .align(align)
            .padding(DesignTokens.SpacingMd)
            .graphicsLayer {
                this.alpha = alpha
                rotationZ = rotation
            }
            .clip(RoundedCornerShape(DesignTokens.CornerRadius))
            .background(color)
            .padding(DesignTokens.SpacingSm),
    ) {
        Icon(
            imageVector = glyph,
            contentDescription = contentDescription,
            tint = AppTheme.colors.onAccent,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * 一轮结束小结卡：中央达成环（认识占比，满达成转 gold）+ 两侧认识/忘记数字滚动进场，
 * 顶层叠全屏彩带庆祝。
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
    var born by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { born = true }
    val shownKnown by animateIntAsState(
        targetValue = if (born) knownCount else 0,
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "summaryKnown",
    )
    val shownUnknown by animateIntAsState(
        targetValue = if (born) unknownCount else 0,
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
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
                Spacer(Modifier.height(DesignTokens.SpacingLg))
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
                            color = if (progress >= 1f && total > 0) colors.gold else colors.success,
                        )
                        Text(
                            text = "${(progress * 100f).roundToInt()}%",
                            style = texts.heroNumber,
                        )
                    }
                }
                Spacer(Modifier.height(DesignTokens.SpacingLg))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "$shownKnown",
                            style = texts.statValue.copy(color = colors.success),
                        )
                        Spacer(Modifier.height(DesignTokens.SpacingXs))
                        Text(text = "认识", style = texts.caption)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "$shownUnknown",
                            style = texts.statValue.copy(color = colors.warning),
                        )
                        Spacer(Modifier.height(DesignTokens.SpacingXs))
                        Text(text = "不认识", style = texts.caption)
                    }
                }
            }
            Spacer(Modifier.height(DesignTokens.SpacingLg))
            AppButton(text = "返回", onClick = onBack)
            Spacer(Modifier.height(DesignTokens.SpacingMd))
            AppButton(text = "再来一轮", secondary = true, onClick = onRestart)
        }
        ConfettiBurst(
            trigger = total,
            modifier = Modifier.matchParentSize(),
        )
    }
}
