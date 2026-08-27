package com.studykit.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.studykit.ui.theme.DesignTokens

@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    secondary: Boolean = false,
    enabled: Boolean = true,
) {
    if (secondary) {
        OutlinedButton(
            onClick    = onClick,
            enabled    = enabled,
            modifier   = modifier.fillMaxWidth().height(50.dp),
            shape      = RoundedCornerShape(DesignTokens.CornerRadius),
            border     = BorderStroke(1.dp, DesignTokens.Accent),
            colors     = ButtonDefaults.outlinedButtonColors(
                contentColor = DesignTokens.Accent,
            ),
        ) {
            Text(text, style = DesignTokens.Body)
        }
    } else {
        Button(
            onClick    = onClick,
            enabled    = enabled,
            modifier   = modifier.fillMaxWidth().height(50.dp),
            shape      = RoundedCornerShape(DesignTokens.CornerRadius),
            colors     = ButtonDefaults.buttonColors(
                containerColor = DesignTokens.Accent,
                contentColor   = DesignTokens.Card,
            ),
        ) {
            Text(text, style = DesignTokens.Body)
        }
    }
}
