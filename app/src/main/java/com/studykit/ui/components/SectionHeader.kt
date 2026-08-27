package com.studykit.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.studykit.ui.theme.DesignTokens

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text     = title,
        style    = DesignTokens.PageTitle,
        modifier = modifier,
    )
}
