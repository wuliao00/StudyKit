package com.studykit.ui.book

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.AppPill
import com.studykit.ui.components.EmptyState
import com.studykit.ui.components.HeroSummaryCard
import com.studykit.tips.StudyTips
import com.studykit.tips.TipEvent
import com.studykit.ui.components.StatTile
import com.studykit.ui.components.StatTileTier
import com.studykit.ui.components.TipCard
import com.studykit.ui.motion.MotionSpec
import com.studykit.ui.motion.StaggeredIn
import com.studykit.ui.theme.AppTheme

/** 书脊色带宽度：贴在卡片左边缘的装饰，不占内容排版宽度 */
private val SpineBandWidth: Dp = 6.dp

/**
 * 状态标签：一枚 [com.studykit.ui.components.AppPill]，`在读 = accentSoft + accentInk`、
 * `读完 = successSoft + successInk`。
 *
 * 旧写法是「Success/Accent 实色 12% 底 + 同色文字」，而品牌色作文字色在浅色卡上只有
 * 3.04:1（accent）/ ≈2.2:1（success），都不达文本 AA（T1 裁定：文本态一律走 `*Ink`）。
 * T15 补齐 `successInk` 后，两态墨色统一取本族 ink 而非 `primaryText`：容器负责「有状态」，
 * 墨色负责「哪个状态」，色觉之外也能靠深浅读出区别。圆角/内边距/字号这些几何参数在波 3
 * 收进了 [com.studykit.ui.components.AppPill]，与单词库、错题本、习惯卡上的药丸同一份实现。
 */
@Composable
private fun StatusTag(finished: Boolean) {
    val colors = AppTheme.colors
    AppPill(
        container = if (finished) colors.successSoft else colors.accentSoft,
        ink = if (finished) colors.successInk else colors.accentInk,
        label = if (finished) "读完" else "在读",
    )
}

/**
 * 书架页：页标题 + 在读 hero（第一等数字 + 这一屏唯一的实心主行动）+ 参考统计 + 带书脊色带的书籍卡片。
 *
 * 顶部原先是三块**等重**的统计磁贴，于是「在读几本」（今天继续读哪本）和「一共攒了多少书摘」
 * （查得到就行）在视觉上同权，整屏读起来像一列白板子。现在按四屏共享的口径重排：
 * [HeroSummaryCard] 承担第一等数字与主行动，其余数字退成 [StatTileTier.Reference] 的参考档。
 */
@Composable
fun BookShelfScreen(
    viewModel: BookViewModel,
    onOpenBook: (Long) -> Unit,
    onAddClick: () -> Unit,
) {
    val texts = AppTheme.texts
    val state by viewModel.shelfState.collectAsStateWithLifecycle()

    // RECALL_NOTES「只有划线没有检索」贴士（v2.7 计划 B Task 18）：书架上存在 7 天前建、却从没检索过
    // （review_count==0）的书摘时首屏提一次；每次安装一次（AppSettings.excerptTipSeen）。
    // 局部 pending 保住「这次在本页看得见」，落库的 seen 只挡后续安装——纪律与习惯列表那条 GapDay
    // （OneShotTipCard + markGapTipSeen）完全一致。
    val excerptTipSeen = AppTheme.settings.excerptTipSeen
    var excerptTipShown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.hasStaleExcerpt, excerptTipSeen) {
        if (!excerptTipShown && state.hasStaleExcerpt && !excerptTipSeen) {
            excerptTipShown = true
            viewModel.markExcerptTipSeen()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = AppTheme.space.pageH),
    ) {
        Spacer(Modifier.height(AppTheme.space.sm))
        Text(text = "读书", style = texts.largeTitle)

        if (state.items.isEmpty()) {
            Spacer(Modifier.height(AppTheme.space.xl * 2))
            EmptyState(
                title = "书架还是空的",
                caption = "添加一本书，开始记录你的阅读旅程",
                icon = Icons.Outlined.Favorite,
            )
            Spacer(Modifier.height(AppTheme.space.lg))
            AppButton(text = "添加第一本书", onClick = onAddClick)
        } else {
            Spacer(Modifier.height(AppTheme.space.md))
            // 「在读」是这一屏的第一等数字：书架要回答的是「还有几本在路上」，
            // 而不是「一共存了几本」。主行动（添加）随之从页头那枚文字按钮**搬进** hero ——
            // 同一个动作在一屏里留两条路（右上角 + hero）重点就散了，
            // 学习首页删掉「背单词」入口卡是同一条口径。
            //
            // caption 曾写成「已读完 N 本 · 书摘 M 条」，与紧挨着的那两块参考磁贴**字面重复** ——
            // 同一对数字在上下相邻的两块里各出现一次，读起来像渲染了两遍（2026-09-27 真机截图撞到）。
            // 改成回答「接着读哪本」：数字说的是"还有几本"，这句说的是"下一本是谁"。
            val readingNow = state.items.firstOrNull { !it.isFinished }
            HeroSummaryCard(
                label = "在读",
                value = "${state.readingCount}",
                caption = readingNow?.let {
                    "《${it.book.title}》读到 ${it.book.currentPage} / ${it.book.totalPages} 页"
                } ?: "书架上的书都读完了",
                actionLabel = "添加一本书",
                onAction = onAddClick,
            )

            if (excerptTipShown) {
                val recallNotesTip = StudyTips.forEvent(TipEvent.ExcerptOnlyNoRecall)
                if (recallNotesTip != null) {
                    Spacer(Modifier.height(AppTheme.space.md))
                    TipCard(tip = recallNotesTip, modifier = Modifier.fillMaxWidth())
                }
            }

            Spacer(Modifier.height(AppTheme.space.md))
            // 主指标改数「检索练习次数」（v2.7 计划 B Task 18）：这一屏要强调的不再是「攒了多少条书摘」
            // （被动划线），而是「做过多少次主动检索」——书摘自评次数（review_count>0）之和 + 章节自测行数，
            // 由 BookDao 一条 UNION SQL 纯计数得到，不编造。它走 Primary 档，
            // 「书摘总数」「已读完」退成「查得到」的参考档（字号墨色描边各降一档）。
            Row(
                horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm),
                modifier = Modifier.height(IntrinsicSize.Max),
            ) {
                StatTile(
                    value = "${state.retrievalCount}",
                    label = "检索练习次数",
                    modifier = Modifier.weight(1f),
                    tier = StatTileTier.Primary,
                )
                StatTile(
                    value = "${state.excerptCount}",
                    label = "书摘总数",
                    modifier = Modifier.weight(1f),
                    tier = StatTileTier.Reference,
                )
                StatTile(
                    value = "${state.finishedCount}",
                    label = "已读完",
                    modifier = Modifier.weight(1f),
                    tier = StatTileTier.Reference,
                )
            }

            Spacer(Modifier.height(AppTheme.space.lg))
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(AppTheme.space.md),
                modifier = Modifier.fillMaxSize(),
            ) {
                // 按书 id 为键：新书记录插在首位（`ORDER BY started_at DESC`），既有卡片只会让位、
                // 不会丢失各自的入场状态（StaggeredIn 的 shown 跟着条目 identity 走）。
                itemsIndexed(state.items, key = { _, item -> item.book.id }) { index, item ->
                    StaggeredIn(
                        index = minOf(index, MotionSpec.StaggerIndexCap),
                        modifier = Modifier.animateItem(),
                    ) {
                        BookCard(
                            item = item,
                            onClick = { onOpenBook(item.book.id) },
                        )
                    }
                }
                item { Spacer(Modifier.height(AppTheme.space.md)) }
            }
        }
    }
}

/**
 * 书籍卡片：书脊色带 + 书名 + 作者 + 进度条 + 状态标签 + 页码与百分比。
 *
 * **书脊色带**是 [SpinePalette] 里由 [spineColorIndex] 按书名取的一个色位（同名恒定同色）。
 * 它是盖在卡片左边缘的 overlay，而不是内容行的第一格：[AppCard] 自带 16dp 版心与 20dp 圆角，
 * 塞进内容行只能得到一根「离边缘 16dp 的短色柱」，读起来不像书脊。
 * 写法取 `matchParentSize()`（子项按父 Box 实测尺寸定高，且**不反过来撑大**父容器）：
 * 外层盒裁成卡片左端圆角轮廓，内层色柱 `fillMaxHeight()` 拿到的就是卡片实高。
 *
 * 进度条补间用 [MotionSpec.ring]（与 [com.studykit.ui.components.RingGauge] 同一条进度 spring）：
 * 逐帧跟随 vsync，高刷屏按 90/120Hz 渲染；spring 有轻微过冲，故喂给指示器前显式钳回 0..1。
 */
@Composable
private fun BookCard(
    item: BookItemUi,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val animatedProgress by animateFloatAsState(
        targetValue = item.progress,
        animationSpec = MotionSpec.ring,
        label = "bookProgress",
    )
    val spineColor = SpinePalette[spineColorIndex(item.book.title, SpinePalette.size)]

    Box(modifier = Modifier.fillMaxWidth()) {
        AppCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(text = item.book.title, style = texts.cardTitle)
                    Spacer(Modifier.height(2.dp))
                    Text(text = item.book.author, style = texts.caption)
                }
                StatusTag(finished = item.isFinished)
            }

            Spacer(Modifier.height(AppTheme.space.md))
            LinearProgressIndicator(
                progress = { animatedProgress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = if (item.isFinished) colors.success else colors.accent,
                trackColor = colors.divider,
                strokeCap = StrokeCap.Round,
            )
            Spacer(Modifier.height(AppTheme.space.sm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${item.book.currentPage} / ${item.book.totalPages} 页",
                    style = texts.caption,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${item.percent}%",
                    style = texts.caption.copy(
                        color = if (item.isFinished) colors.successInk else colors.accentInk,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }
        }

        // 书脊色带（overlay，见函数 KDoc）
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(
                    RoundedCornerShape(
                        topStart = AppTheme.radius.lg,
                        bottomStart = AppTheme.radius.lg,
                    ),
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(SpineBandWidth)
                    .background(spineColor),
            )
        }
    }
}
