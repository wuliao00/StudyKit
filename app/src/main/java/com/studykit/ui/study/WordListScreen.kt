package com.studykit.ui.study

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.data.entity.Word
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.EmptyState
import com.studykit.ui.theme.AppTheme

/**
 * 单词熟练度指示：12dp 状态色点 + 「柔底药丸」状态标签。
 *
 * 取色：`新词=divider`、`学习中=accent`、`已掌握=success`，三个文案原样保留。
 * 药丸容器与色点同族但走 soft 档（`successSoft`/`accentSoft`，其余 `Transparent`），
 * 于是三态在「一眼扫过」时就能分辨 —— 只有色点时浅色卡上 `divider` 点几乎不可见、
 * `accent` 与 `success` 又只差一点色差，状态差异被抹平了。
 *
 * 药丸文字墨色取本族 ink：`已掌握 → successSoft 底 + successInk`（T15 墨水批次：浅色压深后
 * 压在这层柔底 4.96:1、夜间品牌色本身 5.65:1，两主题都达 AA）、`学习中 → accentSoft 底 + accentInk`
 * （T1 裁定：文本态 accent 仅 3.04:1）、`新词 → Transparent + secondaryText`。
 *
 * 色点仍是纯装饰（同一行已有等价文案），故不加 `semantics`/`contentDescription`，
 * 避免读屏把状态念两遍。
 */
@Composable
private fun WordStatusIndicator(status: String) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val (label, dotColor) = when (status) {
        Word.STATUS_MASTERED -> "已掌握" to colors.success
        Word.STATUS_LEARNING -> "学习中" to colors.accent
        else -> "新词" to colors.divider
    }
    val (pillColor, pillInk) = when (status) {
        Word.STATUS_MASTERED -> colors.successSoft to colors.successInk
        Word.STATUS_LEARNING -> colors.accentSoft to colors.accentInk
        else -> Color.Transparent to colors.secondaryText
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(AppTheme.space.sm))
        Box(
            modifier = Modifier
                .background(color = pillColor, shape = RoundedCornerShape(AppTheme.radius.md))
                .padding(horizontal = AppTheme.space.sm, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = texts.caption.copy(color = pillInk, fontWeight = FontWeight.Medium),
            )
        }
    }
}

/**
 * 单词列表页：开始学习主按钮 + 全部单词（单词 + 释义一行 + 熟练度色点与柔底药丸标签）。
 *
 * 颜色与文字样式统一取 `AppTheme`，间距/圆角取 `AppTheme.space` / `AppTheme.radius` 的 dp 常量。
 * 列表条目挂 `Modifier.animateItem()`（[androidx.compose.foundation.lazy.LazyItemScope]）：
 * 录入回来的新词淡入、背完/删除的条目淡出，其余条目位置用 spring 补间让路，
 * 不再出现「整列瞬间跳一格」。进出动画要求条目带 `key`，本页以 `word.id` 为键
 * （`items(words, key = { it.id })`，无 key 时 animateItem 不会生效）。
 */
@Composable
fun WordListScreen(
    viewModel: StudyViewModel,
    onBack: () -> Unit,
    onStartStudy: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val words by viewModel.words.collectAsStateWithLifecycle()
    val home by viewModel.homeState.collectAsStateWithLifecycle()

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
                    // 图标落在页面底色上，按 T1 裁定走 accentInk（accent 仅 3.04:1）
                    tint = colors.accentInk,
                )
            }
            Spacer(Modifier.width(AppTheme.space.xs))
            Text(text = "单词库", style = texts.pageTitle)
            Spacer(Modifier.weight(1f))
            Text(
                text = "共 ${words.size} 个",
                style = texts.caption,
            )
        }

        Spacer(Modifier.height(AppTheme.space.md))
        AppButton(
            text = "开始学习（今日待复习 ${home.dueCount} 个）",
            onClick = onStartStudy,
        )
        Spacer(Modifier.height(AppTheme.space.md))

        if (words.isEmpty()) {
            Spacer(Modifier.height(AppTheme.space.xl * 2))
            EmptyState(
                title = "还没有单词",
                caption = "回到学习首页，点击右上角「录入」添加第一个单词",
                icon = Icons.Outlined.Star,
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(AppTheme.space.sm),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(words, key = { it.id }) { word ->
                    AppCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem(),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = word.word,
                                style = texts.cardTitle,
                            )
                            Spacer(Modifier.width(AppTheme.space.md))
                            Text(
                                text = word.meaning,
                                style = texts.caption,
                                maxLines = 1,
                                modifier = Modifier.weight(1f),
                            )
                            WordStatusIndicator(word.status)
                        }
                    }
                }
                item { Spacer(Modifier.height(AppTheme.space.md)) }
            }
        }
    }
}
