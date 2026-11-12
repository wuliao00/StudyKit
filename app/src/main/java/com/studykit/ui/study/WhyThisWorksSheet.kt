package com.studykit.ui.study

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.studykit.ui.components.AppButton
import com.studykit.ui.theme.AppTheme

/**
 * 首页「为什么这样安排」底部弹层（主动用户改版，见 docs/active-user-redesign.md）。
 *
 * 论文第 6 条硬约束：系统模型可见 —— 用一句人话解释本地存储、复习为什么出现、
 * 签到/补签规则，不靠说明书。所以这里只有四句话，读完就能关：
 *
 * - **本地优先**：数据在本机，断网也能用（对应的实现是 Room + 本地文件，没有服务端）。
 * - **能反悔**：补签有窗口也能撤销；错题能重练；复习只改排期，不扣分（对应的实现见
 *   SignInStats.withinMakeUpWindow / undoMakeUpSignIn、错题本的 RedoFlow、评分只写排期）。
 * - **不用账号**：不注册、不上传；设置页有 zip 导出/恢复（SettingsScreen 的备份段）。
 * - **为什么是这三步**：签到给一个开始，复习趁记忆还没凉，小测确认取不取得出来。
 *
 * 这一屏刻意不做成教程：没有分页、没有下一步、没有插图，关掉就回到首页那张卡。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhyThisWorksSheet(onDismiss: () -> Unit) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val sheetState = rememberModalBottomSheetState()
    val shape = RoundedCornerShape(AppTheme.radius.lg)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.card,
        shape = shape,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppTheme.space.pageH)
                .padding(bottom = AppTheme.space.xl),
        ) {
            Text(text = "为什么这样安排", style = texts.pageTitle)
            Spacer(Modifier.height(AppTheme.space.xs))
            Text(
                text = "不是教程，只是把规则说清楚，四句话看完。",
                style = texts.caption,
                color = colors.secondaryText,
            )
            Spacer(Modifier.height(AppTheme.space.md))

            WhyRow(
                title = "本地优先",
                body = "词库、复习记录、签到都在这台手机里。没有服务器，断网也能用。",
            )
            WhyRow(
                title = "能反悔",
                body = "近 3 天的漏签可以补（每月 3 次），补签也能撤销；错题可以重练；复习只是把词排到下一次，不会扣分。",
            )
            WhyRow(
                title = "不用账号",
                body = "不注册、不登录、不上传。设置里可以导出 zip 备份，换机再恢复。",
            )
            WhyRow(
                title = "为什么是这三步",
                body = "签到给今天一个开始；复习趁记忆还没凉；小测确认真的想得起来。三步加起来约 3 分钟。",
            )

            Spacer(Modifier.height(AppTheme.space.lg))
            AppButton(
                text = "知道了",
                onClick = onDismiss,
                secondary = true,
            )
        }
    }
}

/** 弹层里的一行：左侧一枚装饰色点 + 一句标题 + 一句人话。 */
@Composable
private fun WhyRow(title: String, body: String) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    Row(Modifier.padding(vertical = AppTheme.space.sm)) {
        Box(
            modifier = Modifier
                .padding(top = 7.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(colors.accent),
        )
        Spacer(Modifier.width(AppTheme.space.sm))
        Column {
            Text(text = title, style = texts.cardTitle)
            Spacer(Modifier.height(2.dp))
            Text(text = body, style = texts.caption, color = colors.secondaryText)
        }
    }
}
