package com.studykit.ui.study

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.EmptyState
import com.studykit.ui.theme.DesignTokens

/**
 * 卡片学习页：队列逐张翻面学习，翻面后「认识 / 不认识」推进间隔重复状态机；
 * 一轮结束展示小结卡。
 */
@Composable
fun CardStudyScreen(
    viewModel: StudyViewModel,
    onBack: () -> Unit,
    onAddWord: () -> Unit,
) {
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
                    tint = DesignTokens.Accent,
                )
            }
            Spacer(Modifier.width(DesignTokens.SpacingXs))
            Text(text = "卡片学习", style = DesignTokens.PageTitle)
            Spacer(Modifier.weight(1f))
            if (state != null && state.total > 0 && !state.finished) {
                Text(
                    text = "第 ${state.index + 1} / ${state.total} 张",
                    style = DesignTokens.Caption,
                )
            }
        }

        when {
            state == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = DesignTokens.Accent)
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

            else -> StudyCard(
                word = state.current!!,
                progress = state.index.toFloat() / state.total,
                onKnown = { viewModel.markKnown() },
                onUnknown = { viewModel.markUnknown() },
            )
        }
    }
}

/** 当前卡片：点击翻面（正反面交叉淡入淡出，250ms），翻面后显示「不认识 / 认识」 */
@Composable
private fun ColumnScope.StudyCard(
    word: com.studykit.data.entity.Word,
    progress: Float,
    onKnown: () -> Unit,
    onUnknown: () -> Unit,
) {
    var flipped by rememberSaveable(word.id) { mutableStateOf(false) }
    val flip by animateFloatAsState(
        targetValue = if (flipped) 1f else 0f,
        animationSpec = tween(DesignTokens.AnimDurationMs, easing = DesignTokens.AnimEasing),
        label = "cardFlip",
    )

    Spacer(Modifier.height(DesignTokens.SpacingMd))
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .alpha(1f),
        color = DesignTokens.Accent,
        trackColor = DesignTokens.Divider,
        strokeCap = StrokeCap.Round,
    )

    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(vertical = DesignTokens.SpacingLg),
        contentAlignment = Alignment.Center,
    ) {
        AppCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { flipped = !flipped },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = DesignTokens.SpacingXl),
                contentAlignment = Alignment.Center,
            ) {
                // 正面：单词大标题
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(1f - flip),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = word.word,
                        style = DesignTokens.LargeTitle,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(DesignTokens.SpacingMd))
                    Text(text = "点击卡片查看释义", style = DesignTokens.Caption)
                }
                // 背面：释义 + 例句
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(flip),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = word.meaning,
                        style = DesignTokens.PageTitle,
                        textAlign = TextAlign.Center,
                    )
                    if (word.example.isNotBlank()) {
                        Spacer(Modifier.height(DesignTokens.SpacingMd))
                        Text(
                            text = word.example,
                            style = DesignTokens.Auxiliary.copy(color = DesignTokens.SecondaryText),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.SpacingMd)) {
        OutlinedButton(
            onClick = onUnknown,
            enabled = flipped,
            modifier = Modifier
                .weight(1f)
                .height(50.dp),
            shape = RoundedCornerShape(DesignTokens.CornerRadius),
            border = BorderStroke(1.dp, DesignTokens.Warning),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = DesignTokens.Warning),
        ) {
            Text("不认识", style = DesignTokens.Body)
        }
        OutlinedButton(
            onClick = onKnown,
            enabled = flipped,
            modifier = Modifier
                .weight(1f)
                .height(50.dp),
            shape = RoundedCornerShape(DesignTokens.CornerRadius),
            border = BorderStroke(1.dp, DesignTokens.Success),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = DesignTokens.Success),
        ) {
            Text("认识", style = DesignTokens.Body)
        }
    }
    Spacer(Modifier.height(DesignTokens.SpacingLg))
}

/** 一轮结束小结卡：认识数 / 不认识数 */
@Composable
private fun SessionSummary(
    knownCount: Int,
    unknownCount: Int,
    onRestart: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "本轮完成",
                style = DesignTokens.PageTitle,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(DesignTokens.SpacingLg))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "$knownCount",
                        style = DesignTokens.LargeTitle.copy(color = DesignTokens.Success),
                    )
                    Spacer(Modifier.height(DesignTokens.SpacingXs))
                    Text(text = "认识", style = DesignTokens.Caption)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "$unknownCount",
                        style = DesignTokens.LargeTitle.copy(color = DesignTokens.Warning),
                    )
                    Spacer(Modifier.height(DesignTokens.SpacingXs))
                    Text(text = "不认识", style = DesignTokens.Caption)
                }
            }
        }
        Spacer(Modifier.height(DesignTokens.SpacingLg))
        AppButton(text = "返回", onClick = onBack)
        Spacer(Modifier.height(DesignTokens.SpacingMd))
        AppButton(text = "再来一轮", secondary = true, onClick = onRestart)
    }
}
