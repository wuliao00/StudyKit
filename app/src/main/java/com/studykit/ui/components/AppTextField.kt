package com.studykit.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.studykit.ui.theme.DesignTokens

@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    isError: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Column(modifier = modifier) {
        if (label != null) {
            Text(
                text     = label,
                style    = DesignTokens.Caption,
                modifier = Modifier.padding(bottom = DesignTokens.SpacingXs),
            )
        }
        BasicTextField(
            value         = value,
            onValueChange = onValueChange,
            textStyle     = DesignTokens.Body,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier      = Modifier.fillMaxWidth(),
            singleLine    = true,
            decorationBox = { innerTextField ->
                Column {
                    if (value.isEmpty() && placeholder != null) {
                        Text(
                            text  = placeholder,
                            style = DesignTokens.Body.copy(color = DesignTokens.SecondaryText),
                        )
                    }
                    innerTextField()
                    Spacer(modifier = Modifier.height(DesignTokens.SpacingSm))
                    Canvas(modifier = Modifier.fillMaxWidth()) {
                        drawLine(
                            color       = if (isError) DesignTokens.Warning else DesignTokens.Divider,
                            start       = Offset(0f, size.height),
                            end         = Offset(size.width, size.height),
                            strokeWidth = 1f,
                        )
                    }
                }
            },
        )
    }
}
