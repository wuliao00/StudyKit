package com.studykit.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.rotate
import com.studykit.ui.theme.AppTheme
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 一次性彩带爆发（纯 Canvas 自绘，零图片素材、零依赖）：庆祝「打卡成功 / 连击达成」等瞬时反馈。
 *
 * 行为契约：[trigger] 每次变化（equals 比较）即重新播种粒子并从画布中心偏上处重播一次；
 * 归一化进度 `t >= 1` 后不再绘制，因此常驻组合也没有绘制开销。
 * **首次组合也会播放一次**——若只想在事件发生时庆祝，请把 `trigger` 传成事件标识
 * （如 `lastCheckInAt`）或用 `if (show) ConfettiBurst(trigger = Unit)` 控制挂载时机。
 *
 * 粒子形态由 `trigger.hashCode()` 播种，同一 trigger 稳定、不同 trigger 打散，
 * 无需跨设备一致（只影响观感，不参与任何业务判定）。
 *
 * @param particleCount 粒子数；默认 56 颗在 1.1s 内足够热闹又不至于掉帧。
 * @param durationMillis 单次爆发总时长；尾段（后 30%）统一淡出。
 */
@Composable
fun ConfettiBurst(
    trigger: Any?,
    modifier: Modifier = Modifier,
    particleCount: Int = 56,
    durationMillis: Int = 1100,
) {
    val palette = listOf(
        AppTheme.colors.accent,
        AppTheme.colors.success,
        AppTheme.colors.gold,
        AppTheme.colors.warning,
    )
    val colorCount = palette.size
    val particles = remember(trigger, particleCount, colorCount) {
        val rnd = Random(seed = (trigger?.hashCode() ?: 0) * 31L)
        List(size = particleCount) {
            val angle = rnd.nextFloat() * 6.2831853f
            Particle(
                v = 0.35f + rnd.nextFloat() * 0.75f,
                cosA = cos(angle),
                sinA = sin(angle),
                wf = 0.012f + rnd.nextFloat() * 0.016f,
                aspect = 0.45f + rnd.nextFloat() * 0.4f,
                g = 1.1f + rnd.nextFloat() * 0.9f,
                spin = (rnd.nextFloat() - 0.5f) * 14f,
                colorIndex = rnd.nextInt(colorCount),
                delay = rnd.nextFloat() * 0.12f,
            )
        }
    }
    val progress = remember(trigger) { Animatable(initialValue = 0f) }
    LaunchedEffect(trigger) {
        progress.snapTo(value = 0f)
        progress.animateTo(
            target = 1f,
            animationSpec = tween(durationMillis = durationMillis, easing = FastOutSlowInEasing),
        )
    }
    Canvas(modifier = modifier) {
        val t = progress.value
        if (t <= 0f || t >= 1f) return@Canvas
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h * 0.35f
        particles.forEach { p ->
            val pt = ((t - p.delay) / (1f - p.delay)).coerceIn(0f, 1f)
            if (pt <= 0f) return@forEach
            val dist = p.v * pt * w
            val x = cx + p.cosA * dist
            val y = cy + p.sinA * dist * 0.8f + p.g * pt * pt * h * 0.4f
            val alpha = if (t > 0.7f) (1f - t) / 0.3f else 1f
            val pw = p.wf * w
            val ph = pw * p.aspect
            rotate(degrees = p.spin * pt * 360f, pivot = Offset(x = x, y = y)) {
                drawRect(
                    color = palette[p.colorIndex],
                    alpha = alpha,
                    topLeft = Offset(x = x - pw / 2f, y = y - ph / 2f),
                    size = Size(width = pw, height = ph),
                )
            }
        }
    }
}

/** 单颗彩带：`v` 初速、`cosA/sinA` 方向、`wf` 宽度占比、`aspect` 长宽比、`g` 重力、`spin` 自转圈数 */
private class Particle(
    val v: Float,
    val cosA: Float,
    val sinA: Float,
    val wf: Float,
    val aspect: Float,
    val g: Float,
    val spin: Float,
    val colorIndex: Int,
    val delay: Float,
)
