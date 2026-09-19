package com.studykit.ui.study

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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.AppTextField
import com.studykit.ui.motion.StaggeredIn
import com.studykit.ui.theme.AppTheme
import com.studykit.ui.theme.DesignTokens

/**
 * 单词录入页：单词 + 释义 + 例句表单，保存入 words 表。
 *
 * 三个输入框并入一张 [AppCard]（卡片自带 16dp 版心，字段间距由页级的 24dp 收成 16dp），
 * 保存走 `AppButton`（52dp 胶囊 + 按压 spring）。页面顶部自上而下错峰入场：
 * 标题行 → 表单卡 → 保存按钮（`StaggeredIn` 每级 40ms，下标均为固定小值、无需 minOf 限幅）。
 *
 * 输入框本身沿用 T3 已迁移的 [AppTextField]：文本 `texts.body`、占位 `secondaryText`、
 * 光标 `SolidColor(colors.accent)`、底线 `colors.divider`，本页不再重复给色。
 */
@Composable
fun WordCreateScreen(
    viewModel: StudyViewModel,
    onBack: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    var word by rememberSaveable { mutableStateOf("") }
    var meaning by rememberSaveable { mutableStateOf("") }
    var example by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DesignTokens.PageHorizontalPadding),
    ) {
        Spacer(Modifier.height(DesignTokens.SpacingSm))
        StaggeredIn(index = 0, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        // 图标落在页面底色上，按 T1 裁定走 accentInk（accent 仅 3.04:1）
                        tint = colors.accentInk,
                    )
                }
                Spacer(Modifier.width(DesignTokens.SpacingXs))
                Text(text = "录入单词", style = texts.pageTitle)
            }
        }

        Spacer(Modifier.height(DesignTokens.SpacingLg))
        StaggeredIn(index = 1, modifier = Modifier.fillMaxWidth()) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                AppTextField(
                    value = word,
                    onValueChange = { word = it },
                    label = "单词",
                    placeholder = "例如：serendipity",
                )
                Spacer(Modifier.height(DesignTokens.SpacingMd))
                AppTextField(
                    value = meaning,
                    onValueChange = { meaning = it },
                    label = "释义",
                    placeholder = "例如：n. 意外发现珍宝的运气",
                )
                Spacer(Modifier.height(DesignTokens.SpacingMd))
                AppTextField(
                    value = example,
                    onValueChange = { example = it },
                    label = "例句（可选）",
                    placeholder = "例如：Finding that book was pure serendipity.",
                )
            }
        }

        Spacer(Modifier.height(DesignTokens.SpacingXl))
        StaggeredIn(index = 2, modifier = Modifier.fillMaxWidth()) {
            AppButton(
                text = "保存单词",
                enabled = word.isNotBlank() && meaning.isNotBlank(),
                onClick = {
                    viewModel.saveWord(word, meaning, example) { onBack() }
                },
            )
        }
        Spacer(Modifier.height(DesignTokens.SpacingXl))
    }
}
