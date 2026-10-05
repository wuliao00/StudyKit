package com.studykit.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
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
 * 接下来两周的复习负荷带（v3.0-A Task 6）：把 [com.studykit.data.memory.DueForecast] 算出的
 * `mean ± sd` 画成一条带。
 *
 * ## 这张图回答的是唯一一件前面几块图没回答的事
 * 「未来 7 天要复习多少」只数**已排期**的那批，所以今天不练、明天照样显示原样；负荷带由稳定度
 * **反解**到期日（见 [DayBand] 的来源 [com.studykit.data.memory.DueForecast] 的类 KDoc），
 * 于是"没练"会如实变成"堆积"。带子（±一个标准差）给的则是这个期望值有多不确定——
 * 阴影宽的那几天，数字本身不值得当成承诺。
 *
 * ## 为什么图上没有「每日容量」这条线（本轮删掉的，别再加回来）
 * 上一版把 `AppSettings.dailyWordGoal` 当容量线画在这里，逐日比较 `mean > capacityPerDay`。那个比较
 * 从定义上就赢不了：`dailyWordGoal` 是**每日新词目标**，而带子里的 mean 除了到期复习，还含这批新词
 * 随后产生的学习步（`newPerDay × learningPerNew`，见 [com.studykit.data.memory.DueForecast] 第三条
 * 质量来源）。于是每一天的 mean 至少是那个"容量"的 1.25 倍——一张卡都没有也照样超，超载提示常亮，
 * 空态永远走不到，看板上"有排期的词 0"和"第 1 天预计 N 项，超过每日容量"还能同屏出现。
 * 本仓**没有**"每日复习容量"这个设置。真要加，得先立一个概念（它和复习政策有关，不是 UI 能定的），
 * 用 `dailyWordGoal` 乘个系数派生一个出来只是把同一个谎换个写法。所以这里只描述，不判超载。
 *
 * ## 画法沿用 [RetentionChart] 的既有约定
 * `Canvas` + `AppTheme.colors`，横轴线性取 `bands.size` 天、纵轴 `0..max(mean+sd)` 上浮 15%，
 * 折线走 `drawPath + style = Stroke`（`DrawScope.drawLine` 没有收 Path 的重载），
 * 整张图**一条** `contentDescription`。不新增色值：带子是 `accent.copy(alpha = …)`。
 *
 * ## 无障碍：那一条描述里必须有数字，而且数字还得看得见
 * Canvas 对读屏等于一张壁纸，所以整张图唯一的语义节点说的不是画法而是**内容**：窗口长度、峰值在第
 * 几天、那天预计多少项（与 [ForecastBars] 逐日念出数字是同一个要求，只是不许拆成 14 个节点——
 * 读屏会把同一张图念十四遍）。同一组数字另外还有一行**可见**文字（"最忙的是第 N 天，预计 M 项"）：
 * 横轴没有刻度，只把数字放进描述就等于反着来——视力正常的人什么数都读不到，读屏用户却能。
 * 编码方式（实线/阴影各是什么）写在图下那行 caption 里，看得见的和听得见的各拿一半，两份合起来才是完整的一张图。
 *
 * ## 空态
 * 见 [LoadBandChart] 里的判定：这条带**只在一条线都画不出来时**才空。零卡不是空态——每日新词目标
 * 照样会产生学习步，那本身就是对"照这个练法开下去会有多忙"的如实预测，把它画出来比写"没有排期"诚实。
 *
 * 纯展示：不读库、不算算法，`bands` 由 [StatsViewModel] 在主线程之外算好递进来。
 */
@Composable
fun LoadBandChart(bands: List<DayBand>, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    // isEmpty() 写在前面：空列表时 `all` 也返回 true，但 Canvas 那侧的 maxOf/bands[0] 要在
    // 非空判定之后才求值，读的人不必自己推理 `all` 的空真值（全零同理：贴底平线不是数据）。
    if (bands.isEmpty() || bands.all { it.mean <= 0.0 }) {
        EmptyState(
            title = "接下来两周还没有可画的负荷",
            caption = "评满一轮分、排期落库之后，这里才会画出负荷。",
        )
        return
    }
    // 峰值那天是这张图唯一值得单报的数：横轴没有刻度（14 个标签在窄屏挤成一团），
    // 而"最忙的是哪天、大概多少项"正是这块看板要回答的那一句话。
    val peak = bands.maxBy { it.mean }
    val description = "未来 ${bands.size} 天复习负荷带：峰值在第 ${peak.dayIndex + 1} 天，" +
        "预计 ${peak.mean.roundToInt()} 项；实线是每天的期望项数，阴影是它的波动范围。"

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .semantics { contentDescription = description },
        ) {
            // 纵轴上界取「mean+sd 的峰」并上浮 15%：峰贴着顶会让人读不出那天上面还剩多少空间。
            // 空态已保证 bands 非空且至少一天 mean > 0，故这个上界不为 0，y() 不会除零。
            val top = bands.maxOf { it.mean + it.sd } * 1.15
            fun x(i: Int) = size.width * (i.toFloat() / (bands.size - 1).coerceAtLeast(1))
            fun y(v: Double) = size.height * (1f - (v / top).toFloat().coerceIn(0f, 1f))

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
        }
        Spacer(Modifier.height(AppTheme.space.xs))
        // 同一组数字的可见版：横轴没有刻度，只剩描述里的数字就等于把数只给读屏用户，
        // 与本仓 [ForecastBars]（逐日把数字写在柱子顶上）正相反。取的是同一个 `peak`、同一个 roundToInt，
        // 所以看得见的和听得见的不会各说一套。
        Text(
            text = "最忙的是第 ${peak.dayIndex + 1} 天，预计 ${peak.mean.roundToInt()} 项。",
            style = texts.body,
            color = colors.primaryText,
        )
        Spacer(Modifier.height(AppTheme.space.xs))
        Text(
            text = "实线是那天预计要处理的项数，阴影是它的波动范围（±1 个标准差）。" +
                "带子里除了已排期的到期复习，还含两笔：忘了的卡按返工概率摊到之后几天，" +
                "以及设置里「每日新词」目标随后产生的学习步（每张新词约 2.5 次）。" +
                "所以这里的项数会比「有排期的词」那个数大，一张卡都还没背时也不会是零。",
            style = texts.caption,
            color = colors.secondaryText,
        )
    }
}
