package com.studykit.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.studykit.ui.theme.AppTheme

/** 页面/分组标题：22sp SemiBold，颜色随主题 primaryText。 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = AppTheme.texts.pageTitle,
        modifier = modifier,
    )
}
