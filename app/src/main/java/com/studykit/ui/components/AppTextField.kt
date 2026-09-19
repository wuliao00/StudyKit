package com.studykit.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import com.studykit.ui.theme.AppTheme

/** 单行输入：细标签 + 文本 + accent 光标 + 底部 1dp 分割线（错误态转 warning）。 */
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
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    Column(modifier = modifier) {
        if (label != null) {
            Text(
                text = label,
                style = texts.caption,
                modifier = Modifier.padding(bottom = AppTheme.space.xs),
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = texts.body,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            cursorBrush = SolidColor(colors.accent),
            decorationBox = { innerTextField ->
                Column {
                    if (value.isEmpty() && placeholder != null) {
                        Text(
                            text = placeholder,
                            style = texts.body.copy(color = colors.secondaryText),
                        )
                    }
                    innerTextField()
                    Spacer(modifier = Modifier.height(AppTheme.space.sm))
                    Canvas(modifier = Modifier.fillMaxWidth()) {
                        drawLine(
                            color = if (isError) colors.warning else colors.divider,
                            start = Offset(0f, size.height),
                            end = Offset(size.width, size.height),
                            strokeWidth = 1f,
                        )
                    }
                }
            },
        )
    }
}
