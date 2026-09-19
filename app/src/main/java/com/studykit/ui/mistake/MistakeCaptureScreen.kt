package com.studykit.ui.mistake

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.AppMultilineTextField
import com.studykit.ui.components.AppTextField
import com.studykit.ui.components.SectionHeader
import com.studykit.ui.motion.MotionSpec
import com.studykit.ui.theme.AppTheme

/**
 * 拍照录入页：拍照返回后选择学科（已有学科 + 可输入新学科）+ 填写标题/备注 → 保存。
 *
 * 颜色与文字样式统一取 [AppTheme]，间距/圆角取 `AppTheme.space` / `AppTheme.radius` 的度量常量。
 * 两处动效/观感：照片预览按「图片卡」口径给 `radius.lg` 圆角 + 1dp `divider` 发丝描边
 * （夜间卡面 `#26241F` 与照片暗部同亮度时，没有描边会看不出图片边界）；
 * 学科选项的选中态底色/描边/墨色用 `animateColorAsState` + `tween(MotionSpec.FadeMs)` 交叉补间
 * （`MotionSpec` 的 spring 都是 `Float` 向，颜色补间按映射表走 tween 分支）。
 *
 * 四个表单字段（已选学科 / 新学科 / 标题 / 备注）一律 `rememberSaveable`：转屏不再吞掉已输入内容，
 * 与其余五个录入页（单词 / 题目 / 习惯 / 书籍 / 书摘·书评）同一条纪律（终审 C3）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MistakeCaptureScreen(
    viewModel: MistakeViewModel,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val pending by viewModel.pendingCapture.collectAsStateWithLifecycle()
    val subjects by viewModel.subjects.collectAsStateWithLifecycle()
    var selectedSubject by rememberSaveable { mutableStateOf("") }
    var newSubject by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }

    val finalSubject = newSubject.ifBlank { selectedSubject }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppTheme.space.pageH),
    ) {
        Spacer(Modifier.height(AppTheme.space.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                viewModel.discardPendingCapture()
                onBack()
            }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    // 图标落在页面底色上，按 T1 裁定走 accentInk（accent 仅 3.04:1）
                    tint = colors.accentInk,
                )
            }
            Text(text = "拍照录入", style = texts.pageTitle)
        }

        Spacer(Modifier.height(AppTheme.space.md))
        val captured = pending
        // 磁盘 IO 移出组合：LaunchedEffect 在 IO 线程异步校验临时文件是否存在，
        // 初值乐观置 true 避免新照片进入时闪烁回退文案
        var capturedExists by remember(captured) { mutableStateOf(captured != null) }
        LaunchedEffect(captured) {
            capturedExists = withContext(Dispatchers.IO) { captured?.exists() == true }
        }
        val previewShape = RoundedCornerShape(AppTheme.radius.lg)
        if (captured != null && capturedExists) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                AsyncImage(
                    model = captured,
                    contentDescription = "拍照预览",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(previewShape)
                        .border(width = 1.dp, color = colors.divider, shape = previewShape),
                )
            }
        } else {
            Text(text = "未获取到照片，请返回重新拍照", style = texts.caption)
        }

        Spacer(Modifier.height(AppTheme.space.lg))
        SectionHeader(title = "选择学科")
        Spacer(Modifier.height(AppTheme.space.md))
        if (subjects.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm),
                verticalArrangement = Arrangement.spacedBy(AppTheme.space.sm),
            ) {
                subjects.forEach { subject ->
                    SubjectOption(
                        label = subject,
                        selected = selectedSubject == subject && newSubject.isBlank(),
                    ) {
                        selectedSubject = subject
                        newSubject = ""
                    }
                }
            }
            Spacer(Modifier.height(AppTheme.space.md))
        }
        AppTextField(
            value = newSubject,
            onValueChange = { newSubject = it },
            label = "或输入新学科",
            placeholder = "如：物理、化学…",
        )

        Spacer(Modifier.height(AppTheme.space.lg))
        AppTextField(
            value = title,
            onValueChange = { title = it },
            label = "标题",
            placeholder = "给这道错题起个名字",
        )

        Spacer(Modifier.height(AppTheme.space.lg))
        AppMultilineTextField(
            value = note,
            onValueChange = { note = it },
            label = "备注 / 题目内容",
            placeholder = "抄录题干、错因分析或解题思路…",
            minLines = 4,
        )

        Spacer(Modifier.height(AppTheme.space.xl))
        AppButton(
            text = "保存错题",
            enabled = finalSubject.isNotBlank(),
            onClick = {
                viewModel.savePhotoMistake(
                    subject = finalSubject,
                    title = title,
                    note = note,
                    onSaved = onSaved,
                )
            },
        )
        Spacer(Modifier.height(AppTheme.space.xl))
    }
}

/**
 * 学科选项：未选中 = 卡面底 + divider 描边 + primaryText；选中 = `accentSoft` 底 +
 * `accentInk` 描边与墨色（brief 的「文字 accent」按 T1 裁定落回 ink 变体，
 * 1dp 细描边同族走 ink —— T13 引用竖条同一条理由）。三档颜色各自 `animateColorAsState`
 * 补间 FadeMs，切学科时不是硬切。容器仍按可点药丸的既有顺序
 * `clip → background(color) → border → clickable`（与 `QuestionCreateScreen` 的选项格一致，
 * `clip` 在前才把按压 ripple 裁成圆角）。
 */
@Composable
private fun SubjectOption(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val shape = RoundedCornerShape(AppTheme.radius.md)
    val container by animateColorAsState(
        targetValue = if (selected) colors.accentSoft else colors.card,
        animationSpec = tween(durationMillis = MotionSpec.FadeMs),
        label = "subjectOptionContainer",
    )
    val stroke by animateColorAsState(
        targetValue = if (selected) colors.accentInk else colors.divider,
        animationSpec = tween(durationMillis = MotionSpec.FadeMs),
        label = "subjectOptionStroke",
    )
    val ink by animateColorAsState(
        targetValue = if (selected) colors.accentInk else colors.primaryText,
        animationSpec = tween(durationMillis = MotionSpec.FadeMs),
        label = "subjectOptionInk",
    )
    Box(
        modifier = Modifier
            .clip(shape)
            .background(container)
            .border(width = 1.dp, color = stroke, shape = shape)
            .clickable(onClick = onClick)
            .padding(horizontal = AppTheme.space.md, vertical = AppTheme.space.sm),
    ) {
        Text(
            text = label,
            style = texts.aux.copy(
                color = ink,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            ),
        )
    }
}
