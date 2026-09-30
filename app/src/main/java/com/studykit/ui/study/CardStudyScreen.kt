package com.studykit.ui.study

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.data.entity.Word
import com.studykit.srs.Confidence
import com.studykit.srs.Rating
import com.studykit.tips.Tip
import com.studykit.tips.TipEvent
import com.studykit.tips.StudyTips
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.EmptyState
import com.studykit.ui.components.TipCard
import com.studykit.ui.theme.DesignTokens

/**
 * 卡片学习页：检索优先的三阶段会话（预测试 → 强迫回忆 → 翻面评分），
 * 评分走 FSRS 排期；一轮结束展示小结卡。
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
                phase = state.phase,
                confidence = state.confidence,
                consecutiveAgain = state.consecutiveAgain,
                progress = state.index.toFloat() / state.total,
                onEndPretest = { skipped -> viewModel.endPretest(skipped) },
                onSelectConfidence = { viewModel.selectConfidence(it) },
                onRate = { viewModel.rate(it) },
            )
        }
    }
}

/** 按阶段与连错次数决定当前该挂的小贴士事件；无匹配返回 null */
private fun tipEventFor(phase: CardPhase, consecutiveAgain: Int): TipEvent? = when {
    consecutiveAgain >= 2 -> TipEvent.StrugglingReview
    phase == CardPhase.PRETEST -> TipEvent.NewCardFirstLook
    phase == CardPhase.RECALL -> TipEvent.AboutToFlip
    else -> null
}

/** 单张卡片：按 PRETEST / RECALL / ANSWER 三阶段渲染，翻面用交叉淡入淡出（250ms） */
@Composable
private fun ColumnScope.StudyCard(
    word: Word,
    phase: CardPhase,
    confidence: Confidence?,
    consecutiveAgain: Int,
    progress: Float,
    onEndPretest: (Boolean) -> Unit,
    onSelectConfidence: (Confidence) -> Unit,
    onRate: (Rating) -> Unit,
) {
    val flipped = phase == CardPhase.ANSWER
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
            .height(6.dp),
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
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = DesignTokens.SpacingXl),
                contentAlignment = Alignment.Center,
            ) {
                // 正面：单词 + 阶段引导语
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
                    Text(text = frontCaption(phase), style = DesignTokens.Caption)
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

    tipEventFor(phase, consecutiveAgain)?.let { event ->
        StudyTips.forEvent(event)?.let { tip: Tip ->
            TipCard(tip = tip, modifier = Modifier.fillMaxWidth())
        }
    }

    when (phase) {
        CardPhase.PRETEST -> {
            AppButton(text = "心里有答案了", onClick = { onEndPretest(false) })
            Spacer(Modifier.height(DesignTokens.SpacingSm))
            AppButton(text = "跳过预测试", secondary = true, onClick = { onEndPretest(true) })
        }

        CardPhase.RECALL -> {
            Text(
                text = "回忆不起来也没关系，先给个把握度",
                style = DesignTokens.Caption,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(DesignTokens.SpacingSm))
            Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.SpacingSm)) {
                Confidence.entries.forEach { level ->
                    ChoiceButton(
                        text = confidenceLabel(level),
                        color = DesignTokens.Accent,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelectConfidence(level) },
                    )
                }
            }
        }

        CardPhase.ANSWER -> {
            Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.SpacingSm)) {
                Rating.entries.forEach { rating ->
                    ChoiceButton(
                        text = ratingLabel(rating),
                        color = ratingColor(rating),
                        modifier = Modifier.weight(1f),
                        onClick = { onRate(rating) },
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(DesignTokens.SpacingLg))
}

/** 正面引导语：预测试鼓励先猜，回忆阶段提醒先提取再翻面 */
private fun frontCaption(phase: CardPhase): String = when (phase) {
    CardPhase.PRETEST -> "先凭印象猜一下释义"
    CardPhase.RECALL -> "在脑子里回忆释义，再选把握度"
    CardPhase.ANSWER -> ""
}

private fun confidenceLabel(confidence: Confidence): String = when (confidence) {
    Confidence.GUESS -> "瞎猜"
    Confidence.VAGUE -> "有点印象"
    Confidence.SURE -> "非常确定"
}

private fun ratingLabel(rating: Rating): String = when (rating) {
    Rating.AGAIN -> "忘了"
    Rating.HARD -> "有点难"
    Rating.GOOD -> "记住了"
    Rating.EASY -> "很简单"
}

private fun ratingColor(rating: Rating): Color = when (rating) {
    Rating.AGAIN -> DesignTokens.Warning
    Rating.HARD -> DesignTokens.SecondaryText
    Rating.GOOD -> DesignTokens.Success
    Rating.EASY -> DesignTokens.Accent
}

/** 描边选择按钮：文案色即语义色，复用于信心三档与评分四档 */
@Composable
private fun ChoiceButton(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(50.dp),
        shape = RoundedCornerShape(DesignTokens.CornerRadius),
        border = BorderStroke(1.dp, color),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
    ) {
        Text(text, style = DesignTokens.Caption)
    }
}

/** 一轮结束小结卡：记住数 / 需重学数 */
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
                    Text(text = "记住", style = DesignTokens.Caption)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "$unknownCount",
                        style = DesignTokens.LargeTitle.copy(color = DesignTokens.Warning),
                    )
                    Spacer(Modifier.height(DesignTokens.SpacingXs))
                    Text(text = "需重学", style = DesignTokens.Caption)
                }
            }
        }
        Spacer(Modifier.height(DesignTokens.SpacingLg))
        AppButton(text = "返回", onClick = onBack)
        Spacer(Modifier.height(DesignTokens.SpacingMd))
        AppButton(text = "再来一轮", secondary = true, onClick = onRestart)
    }
}
