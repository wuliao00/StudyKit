package com.studykit.ui.book

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppMultilineTextField
import com.studykit.ui.theme.DesignTokens

/** 可点击的 1-5 星评分行：已选强调色 / 未选浅灰 */
@Composable
private fun RatingPicker(rating: Int, onRatingChange: (Int) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth()) {
        repeat(5) { index ->
            val starValue = index + 1
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = "$starValue 星",
                tint = if (starValue <= rating) DesignTokens.Accent else DesignTokens.Divider,
                modifier = Modifier
                    .size(36.dp)
                    .padding(DesignTokens.SpacingXs)
                    .clickable { onRatingChange(starValue) },
            )
        }
    }
}

/** 书评编辑页：1-5 星评分 + 内容输入 */
@Composable
fun ReviewEditScreen(
    reviewId: Long?,
    bookId: Long?,
    viewModel: BookViewModel,
    onBack: () -> Unit,
) {
    var rating by rememberSaveable { mutableIntStateOf(5) }
    var content by rememberSaveable { mutableStateOf("") }
    var loaded by rememberSaveable { mutableStateOf(reviewId == null) }
    var resolvedBookId by rememberSaveable { mutableStateOf(bookId ?: 0L) }

    if (reviewId != null) {
        val review by viewModel.observeReview(reviewId).collectAsStateWithLifecycle(initialValue = null)
        LaunchedEffect(review) {
            if (!loaded && review != null) {
                rating = review!!.rating
                content = review!!.content
                resolvedBookId = review!!.bookId
                loaded = true
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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
            Text(
                text = if (reviewId == null) "写书评" else "编辑书评",
                style = DesignTokens.PageTitle,
            )
        }

        Spacer(Modifier.height(DesignTokens.SpacingLg))
        Text(text = "评分", style = DesignTokens.Caption)
        Spacer(Modifier.height(DesignTokens.SpacingSm))
        RatingPicker(rating = rating, onRatingChange = { rating = it })

        Spacer(Modifier.height(DesignTokens.SpacingLg))
        AppMultilineTextField(
            value = content,
            onValueChange = { content = it },
            label = "书评内容",
            placeholder = "写下你的阅读感受…",
            minLines = 5,
        )

        Spacer(Modifier.height(DesignTokens.SpacingXl))
        AppButton(
            text = "保存书评",
            enabled = loaded && content.isNotBlank() && resolvedBookId > 0L,
            onClick = {
                viewModel.saveReview(reviewId, resolvedBookId, rating, content) { onBack() }
            },
        )
        Spacer(Modifier.height(DesignTokens.SpacingXl))
    }
}
