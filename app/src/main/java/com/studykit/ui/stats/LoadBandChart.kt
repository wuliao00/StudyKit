package com.studykit.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.studykit.data.memory.DayBand
import com.studykit.ui.components.EmptyState
import com.studykit.ui.theme.AppTheme
import kotlin.math.roundToInt

/**
 * 超容量标注最多逐日点出几天，其余折成一句总数。
 *
 * 堆到容量线上是一件事（每日新词目标定得太高 / 欠下的太多），不是十四件独立的事：
 * 十四行几乎一样的字既把图挤出屏幕，读屏也要念十四遍。超过这个数就改成"另有 N 天同样…"，
 * 用户拿到的是同一个结论，字数不随窗口长度长。
 */
private const val MaxOverdayNotes = 3

/**
 * 接下来两周的复习负荷带（v3.0-A Task 6）：把 [com.studykit.data.memory.DueForecast] 算出的
 * `mean ± sd` 画成一条带，并叠上「每日容量」这条横虚线。
 *
 * ## 这张图回答的是唯一一件前面几块图没回答的事
 * 「未来 7 天要复习多少」只数**已排期**的那批，所以今天不练、明天照样显示原样；负荷带由稳定度
 * **反解**到期日（见 [DayBand] 的来源 [com.studykit.data.memory.DueForecast] 的类 KDoc），
 * 于是"没练"会如实变成"堆积"。带子（±一个标准差）给的则是这个期望值有多不确定——
 * 阴影宽的那几天，数字本身不值得当成承诺。
 *
 * ## 画法沿用 [RetentionChart] 的既有约定
 * `Canvas` + `AppTheme.colors`，横轴线性取 `bands.size` 天、纵轴 `0..max(peak, capacity)`，
 * 折线走 `drawPath + style = Stroke`（`DrawScope.drawLine` 没有收 Path 的重载），
 * 整张图**一条** `contentDescription`。不新增色值：带子是 `accent.copy(alpha = …)`，
 * 容量线是 `divider`，超载点是 `warning`——都是本仓已有的令牌。
 *
 * ## 为什么超载信息写成文字行而不是逐日语义节点
 * 逐日 `semantics` 节点会让读屏把同一张图念十四遍（图已经有一条总描述了）。超载那几天直接以
 * 一行中文摆出来（"第 3 天预计 30 项，超过每日容量 20 项。"），可见即可读，一份语义两处用；
 * 眼睛扫到的是同一句话，所以无障碍不用额外造一个只给读屏的隐藏节点。
 * 这也是 [com.studykit.ui.stats.ForecastBars] 不用 Canvas 的同一条理由的另一半：
 * **数字要能被念出来**，图只是它的形状。
 *
 * ## 空态
 * 全零（或一条卡都没有：新库、刚切内核还没回填稳定度）时不给一条贴底的平线冒充数据，
 * 直接 [EmptyState] 说"接下来两周没有排期"——与「未来 7 天」那张卡的空态同一个处理。
 *
 * 纯展示：不读库、不算算法，`bands` 与 `capacityPerDay` 都由 [StatsViewModel] 在主线程之外算好递进来。
 */
@Composable
fun LoadBandChart(
    bands: List<DayBand>,
    capacityPerDay: Int,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    if (bands.isEmpty() || bands.all { it.mean <= 0.0 }) {
        EmptyState(
            title = "接下来两周没有排期",
            caption = "评满一轮分、排期落库之后，这里才会画出负荷。",
        )
        return
    }
    val overDays = bands.indices.filter { bands[it].mean > capacityPerDay }

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .semantics {
                    contentDescription = "未来 ${bands.size} 天复习负荷带：" +
                        "实线是期望数量，阴影是波动范围，横虚线是每日容量"
                },
        ) {
            // 纵轴上界同时容纳「mean+sd 的峰」与「容量线」，各留 15% 余量：
            // 容量线贴着顶或峰贴着顶都会让人读不出"还剩多少空间"。
            val peak = (bands.maxOf { it.mean + it.sd } * 1.15).coerceAtLeast(capacityPerDay * 1.15)
            fun x(i: Int) = size.width * (i.toFloat() / (bands.size - 1).coerceAtLeast(1))
            fun y(v: Double) = size.height * (1f - (v / peak).toFloat().coerceIn(0f, 1f))

            // 阴影带 mean±sd：正向描上沿、反向补下沿，闭合成多边形。下沿夹到 0，
            // 因为「负的一项」不存在，让它穿到横轴下面会凭空多出半格高度。
            drawPath(
                path = Path().apply {
                    moveTo(x(0), y(bands[0].mean + bands[0].sd))
                    bands.forEachIndexed { i, b -> lineTo(x(i), y(b.mean + b.sd)) }
                    bands.indices.reversed().forEach { i ->
                        lineTo(x(i), y((bands[i].mean - bands[i].sd).coerceAtLeast(0.0)))
                    }
                    close()
                },
                color = colors.accent.copy(alpha = 0.18f),
            )
            drawPath(
                path = Path().apply {
                    bands.forEachIndexed { i, b ->
                        if (i == 0) moveTo(x(i), y(b.mean)) else lineTo(x(i), y(b.mean))
                    }
                },
                color = colors.accent,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
            )
            // 每日容量线：整幅横穿的虚线，一眼看出哪几天顶到了上面
            drawLine(
                color = colors.divider,
                start = Offset(0f, y(capacityPerDay.toDouble())),
                end = Offset(size.width, y(capacityPerDay.toDouble())),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
            )
            overDays.forEach { i ->
                drawCircle(
                    color = colors.warning,
                    radius = 4.dp.toPx(),
                    center = Offset(x(i), y(bands[i].mean)),
                )
            }
        }
        Spacer(Modifier.height(AppTheme.space.xs))
        Text(
            text = "实线是那天预计要复习的项数，阴影是它的波动范围（±1 个标准差），" +
                "横虚线是每日容量 $capacityPerDay 项——线跑到虚线上面就是会超载的日子。",
            style = texts.caption,
            color = colors.secondaryText,
        )
        overDays.take(MaxOverdayNotes).forEach { i ->
            Spacer(Modifier.height(AppTheme.space.xs))
            Text(
                text = "第 ${i + 1} 天预计 ${bands[i].mean.roundToInt()} 项，超过每日容量 $capacityPerDay 项。",
                style = texts.caption,
                // 用 warningInk 而不是 warning 本身：填充色直接当文字色在浅色主题下对比度不够
                // （本仓在图例上为同一件事写过一次），见 StatsScreen 的 LegendDot。
                color = colors.warningInk,
            )
        }
        val rest = overDays.size - MaxOverdayNotes
        if (rest > 0) {
            Spacer(Modifier.height(AppTheme.space.xs))
            Text(
                text = "另有 $rest 天同样超过每日容量 $capacityPerDay 项。",
                style = texts.caption,
                color = colors.warningInk,
            )
        }
    }
}
