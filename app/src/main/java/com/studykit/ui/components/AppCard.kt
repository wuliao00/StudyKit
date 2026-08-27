package com.studykit.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.studykit.ui.theme.DesignTokens

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier      = modifier,
        shape         = RoundedCornerShape(DesignTokens.CornerRadiusLg),
        color         = DesignTokens.Card,
        shadowElevation = DesignTokens.ShadowElevation,
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(DesignTokens.CardPadding),
        ) {
            content()
        }
    }
}
