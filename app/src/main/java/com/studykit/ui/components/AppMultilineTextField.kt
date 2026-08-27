package com.studykit.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import com.studykit.ui.theme.DesignTokens

/**
 * 多行输入框：与 [AppTextField] 同一视觉语言（底部分割线），
 * 用于书摘内容、书评内容等长文本输入。
 */
@Composable
fun AppMultilineTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    minLines: Int = 4,
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
            modifier      = Modifier.fillMaxWidth(),
            minLines      = minLines,
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
                            color       = DesignTokens.Divider,
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
