package com.studykit.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.studykit.ui.motion.rememberPressScale
import com.studykit.ui.theme.AppTheme
import kotlin.math.PI
import kotlin.math.sin

/** 抖动时长（ms）：一个 tween 把 0→1 走完即停，配合衰减包络总时长固定，不随帧数变化 */
private const val ShakeDurationMs = 420

/** 抖动首帧振幅（dp）：`× LocalDensity.density` 换成 px，随屏幕密度物理等宽 */
private const val ShakeAmplitudeDp = 10f

/** 刷题选项砖块反馈态；映射规则见 `ui/study/QuizScreen.kt` 的 `quizOptionState` */
enum class QuizOptionState { Idle, Selected, Correct, Wrong }

/**
 * 刷题选项砖块：字母位（A/B/C/D，判定后换成 ✓/✗ 图标）+ 选项文案，
 * 按压走 [rememberPressScale]（`MotionSpec.press` spring）回弹，答错抖一下。
 *
 * 抖动的键与收敛：`LaunchedEffect(state)` 以「state 变成 [QuizOptionState.Wrong]」这个事件为键，
 * 420ms 的 tween 把进度 t 从 0 推到 1，位移取 `sin(6π·t) · 10dp · (1 - t)` —— 三个来回、
 * 振幅线性衰减，t=1 时振幅天然归零，因此**不会循环播放**；`try/finally` 兜住「用户提前切题
 * 把 tween 取消」的情况，位移一定被 snap 回 0，不会留下半截偏移。
 * 转屏时 `remember { Animatable(...) }` 会重建，故另用一个 saveable 标记记住「本次 Wrong 已抖过」，
 * 配置变更后不再凭空重播。
 *
 * 按压回弹只在**可点**时生效：`enabled = false`（本题已作答 / 已锁定）时 `clickable(enabled = false)`
 * 既不吃事件也不画 ripple，砖块「看着还在、点不动」，避免答后再按一次留下空转波纹、
 * 也堵掉同帧误触下一题的入口；判定配色与答错抖动**不受** `enabled` 影响（那是反馈，不是交互）。
 *
 * 取色按主题显式取自 [AppTheme.colors]（不依赖 M3 的局部覆写）：
 * - 描边/底色：`Correct=success`、`Wrong=warning`、`Selected=accent`、`Idle=divider`，
 *   容器用对应的 `*Soft` 淡底。
 * - 字母/图标色与描边**分离**：Selected 走 `accentInk`、Correct/Wrong 走 `successInk/warningInk`
 *   （T15 墨水批次：品牌色作文字/图标在浅色主题只有 2.22:1 / 3.07:1，不达 AA）、
 *   Idle 走 `secondaryText`（`divider` 太淡，沿用描边色等于把字母画隐形）。
 *
 * @param index 选项下标，用于渲染字母（`'A' + index`）。
 * @param onClick 点击回调；「是否还能点」由 [enabled] 承担——调用方在本题锁定后传 `enabled = false`，
 *   本组件随即不再派发交互（无 ripple、无按压回弹），不会再依赖 ViewModel 的幂等兜底。
 * @param enabled 是否可点击，默认 `true`；本题判定/锁定后由调用方传 `false`，此时不响应点击。
 */
@Composable
fun QuizOptionTile(
    optionText: String,
    index: Int,
    state: QuizOptionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val density = LocalDensity.current.density
    val interaction = remember { MutableInteractionSource() }
    val scale by rememberPressScale(interaction)
    val shake = remember { Animatable(initialValue = 0f) }
    var wrongShaken by rememberSaveable(state) { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (state == QuizOptionState.Wrong && !wrongShaken) {
            wrongShaken = true
            shake.snapTo(targetValue = 0f)
            try {
                shake.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = ShakeDurationMs),
                )
            } finally {
                shake.snapTo(targetValue = 0f)
            }
        }
    }

    val borderColor = when (state) {
        QuizOptionState.Correct -> colors.success
        QuizOptionState.Wrong -> colors.warning
        QuizOptionState.Selected -> colors.accent
        QuizOptionState.Idle -> colors.divider
    }
    val container = when (state) {
        QuizOptionState.Correct -> colors.successSoft
        QuizOptionState.Wrong -> colors.warningSoft
        QuizOptionState.Selected -> colors.accentSoft
        QuizOptionState.Idle -> colors.card
    }
    val inkColor = when (state) {
        QuizOptionState.Correct -> colors.successInk
        QuizOptionState.Wrong -> colors.warningInk
        QuizOptionState.Selected -> colors.accentInk
        QuizOptionState.Idle -> colors.secondaryText
    }

    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                // 锁定时按压位彻底让路：禁用后 interaction 不会再收到 Release，
                // 万一「按下 → 同一帧被锁」还留着 Pressed，直接读 scale 会把砖块卡在 0.96。
                scaleX = if (enabled) scale else 1f
                scaleY = if (enabled) scale else 1f
                translationX = sin(shake.value * 6f * PI.toFloat()) *
                    ShakeAmplitudeDp * density * (1f - shake.value)
            },
        shape = RoundedCornerShape(AppTheme.radius.lg),
        color = container,
        border = BorderStroke(1.5.dp, borderColor),
    ) {
        Row(
            modifier = Modifier.padding(AppTheme.space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(borderColor.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                when (state) {
                    // 图标 contentDescription=null：语义已由「描边 + 底色 + 判定文案」承担，
                    // 读屏不该把一个小勾再念一遍；正确/错误的具体信息靠文案与选项文字本身。
                    // 图标与字母占的是同一个槽位，故沿用 inkColor（墨水＝文字/图标同一档）
                    QuizOptionState.Correct -> Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = inkColor,
                        modifier = Modifier.size(16.dp),
                    )

                    QuizOptionState.Wrong -> Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = null,
                        tint = inkColor,
                        modifier = Modifier.size(16.dp),
                    )

                    else -> Text(
                        text = ('A' + index).toString(),
                        style = texts.cardTitle.copy(color = inkColor),
                    )
                }
            }
            Spacer(Modifier.size(AppTheme.space.md))
            Text(text = optionText, style = texts.body, modifier = Modifier.weight(1f))
        }
    }
}
