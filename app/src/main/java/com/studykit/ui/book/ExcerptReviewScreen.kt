package com.studykit.ui.book

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.data.entity.Excerpt
import com.studykit.data.memory.Confidence
import com.studykit.data.memory.ReviewGrade
import com.studykit.data.memory.excerptFrontHalf
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppButtonTone
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.EmptyState
import com.studykit.ui.study.ConfidenceRow
import com.studykit.ui.theme.AppTheme

/**
 * 检索式书摘复习页（v2.7 计划 B Task 18 / spec §7.2）。
 *
 * 把到期书摘从「被动重看划线」变成一次主动检索：先看前半截回忆 → 展开全文对照 → 三档自评。
 * 自评走活跃内核重排 `stability / review_count / next_review_at`（排期纯逻辑在
 * [com.studykit.data.memory.ExcerptReview.schedule]，本文件只管交互与把结果交回 ViewModel）。
 *
 * 队列以进入页面那一刻的 `now` 为快照条件：评过或移出的一条其 `next_review_at` 必然落到未来
 * （`ExcerptReview.schedule` 的下限是 now+5min），于是它自动退出「>0 且 <= now」这条查询、
 * 队列随之收窄——所以「当前」恒取队列头部，无需自增下标，也天然规避了评一次的竞态。
 * （连点由 `BookViewModel.gradingExcerpt` 那枚一次性门兜住，页面不重复写库。）
 */
@Composable
fun ExcerptReviewScreen(
    viewModel: BookViewModel,
    onBack: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val confidenceEnabled = AppTheme.settings.confidenceEnabled

    val now = rememberSaveable { System.currentTimeMillis() }
    val due by viewModel.observeDueExcerpts(now).collectAsStateWithLifecycle(initialValue = emptyList())

    var revealed by rememberSaveable { mutableStateOf(false) }
    var pickedConf by remember { mutableStateOf<Confidence?>(null) }

    val current = due.firstOrNull()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppTheme.space.pageH),
    ) {
        Spacer(Modifier.height(AppTheme.space.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = colors.accentInk,
                )
            }
            Spacer(Modifier.width(AppTheme.space.xs))
            Text(text = "复习书摘", style = texts.pageTitle)
            Spacer(Modifier.weight(1f))
            if (current != null) {
                Text(text = "剩 ${due.size} 条", style = texts.caption)
            }
        }

        Spacer(Modifier.height(AppTheme.space.lg))

        if (current == null) {
            EmptyState(
                title = "没有到期的书摘",
                caption = "记下的书摘会自动排进复习队列；回到书籍详情用「复习书摘」开始一次检索",
                icon = Icons.Outlined.Favorite,
            )
        } else {
            ExcerptReviewBody(
                excerpt = current,
                revealed = revealed,
                selectedConfidence = pickedConf,
                confidenceEnabled = confidenceEnabled,
                onReveal = { revealed = true },
                onPickConfidence = { pickedConf = it },
                onGrade = { grade ->
                    viewModel.gradeExcerptReview(current.id, grade, pickedConf)
                    revealed = false
                    pickedConf = null
                },
                onOptOut = {
                    viewModel.setExcerptEnrolled(current.id, enrolled = false)
                    revealed = false
                    pickedConf = null
                },
            )
        }
        Spacer(Modifier.height(AppTheme.space.xl))
    }
}

/**
 * 复习页的**无状态内容层**：只吃「这条书摘 + 是否已展开 + 已选信心」，吐四个回调。
 * 抽出来是为了能被 [ExcerptReviewRenderTest] 直接组出来、用点击驱动回忆→展开→自评这条线，
 * 而不必牵进 Room / AndroidViewModel（排期数值本身由 `ExcerptReviewTest` 的纯函数用例覆盖）。
 */
@Composable
internal fun ExcerptReviewBody(
    excerpt: Excerpt,
    revealed: Boolean,
    selectedConfidence: Confidence?,
    confidenceEnabled: Boolean,
    onReveal: () -> Unit,
    onPickConfidence: (Confidence) -> Unit,
    onGrade: (ReviewGrade) -> Unit,
    onOptOut: () -> Unit,
) {
    val texts = AppTheme.texts
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.space.md)) {
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Text(text = "这条书摘，你还记得全文吗？", style = texts.caption)
            Spacer(Modifier.height(AppTheme.space.sm))
            if (revealed) {
                Text(text = excerpt.content, style = texts.body)
                excerpt.pageNo?.let {
                    Spacer(Modifier.height(AppTheme.space.xs))
                    Text(text = "第 $it 页", style = texts.caption)
                }
            } else {
                // 未展开：只露前半截，逼先在脑子里补完剩下的
                Text(text = excerptFrontHalf(excerpt.content), style = texts.body)
            }
        }

        if (!revealed) {
            AppButton(text = "展开全文对照", onClick = onReveal)
        } else {
            if (confidenceEnabled) {
                ConfidenceRow(selected = selectedConfidence, onPick = onPickConfidence)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm),
                modifier = Modifier.fillMaxWidth(),
            ) {
                AppButton(
                    text = "忘了",
                    onClick = { onGrade(ReviewGrade.FORGET) },
                    tone = AppButtonTone.Warning,
                    modifier = Modifier.weight(1f),
                )
                AppButton(
                    text = "模糊",
                    onClick = { onGrade(ReviewGrade.VAGUE) },
                    modifier = Modifier.weight(1f),
                )
                AppButton(
                    text = "记得",
                    onClick = { onGrade(ReviewGrade.RECALL) },
                    tone = AppButtonTone.Success,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = "自评只是给自己看：记得 / 模糊 / 忘了 决定下一次隔多久再见。",
                style = texts.caption,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            AppButton(text = "把这条移出复习队列", secondary = true, onClick = onOptOut)
        }
    }
}
