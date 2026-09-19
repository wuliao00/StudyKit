package com.studykit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.studykit.ui.theme.AppTheme

/** 状态药丸左侧色点的直径：与 [AppPill] 的 `caption` 文案并排，纯装饰 */
private val PillDotSize: Dp = 12.dp

/**
 * 状态药丸：`radius.sm` 柔底容器 + 本族 ink 文案，可选左侧 12dp 色点。
 *
 * 全 app 的「有状态的小标签」只有这一份实现（单词熟练度 / 读书状态 / 错题来源 / 习惯达成 /
 * 日历「今天」），几何与墨色规则因此不再由各页各写一遍。
 *
 * 参数命名与取色口径按 [AppButton] 那套：
 * - [container] 传 `*Soft` 柔底（`successSoft` / `accentSoft` / `warningSoft` / `goldSoft`），
 *   退回无底色时传 `Color.Transparent`。
 * - [ink] 传**同族 ink**（`successInk` 等，T15 墨水批次）：品牌色本身作文字在浅色卡面上只有
 *   2.22–3.07:1，不达文本 AA；容器负责「有状态」，ink 负责「哪个状态」。
 * - [leadingDot] 传品牌色档（`success` / `accent` / `divider`）：色点是非文本元素，
 *   且旁边就等价文字，按装饰元素处理，故**不**走 ink。
 *
 * **不可点击**：这里刻意不挂 `clickable`，也不按「可点药丸」的 `clip → background → clickable`
 * 顺序写（那是 `MistakeListScreen.FilterChip` / `MistakeDetailScreen.ReviewOption` 这类筛选与
 * 快捷项的形态）。点击态的触摸目标归一化在波 4 处理。
 *
 * @param modifier 挂在药丸**根 Row** 上（即容器之外），因此调用方传 `padding`/`weight` 都会
 *   落在容器外面，不会把柔底撑出意外的一圈。
 * @param leadingDot 非空时在文案左侧画一枚同色圆点，用于「色点 + 文字」双编码。
 */
@Composable
fun AppPill(
    container: Color,
    ink: Color,
    label: String,
    modifier: Modifier = Modifier,
    leadingDot: Color? = null,
) {
    val texts = AppTheme.texts
    Row(
        modifier = modifier
            .background(
                color = container,
                shape = RoundedCornerShape(AppTheme.radius.sm),
            )
            .padding(horizontal = AppTheme.space.sm, vertical = AppTheme.space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leadingDot?.let { dotColor ->
            Box(
                modifier = Modifier
                    .size(PillDotSize)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Spacer(Modifier.width(AppTheme.space.sm))
        }
        Text(
            text = label,
            style = texts.caption.copy(color = ink, fontWeight = FontWeight.Medium),
        )
    }
}
