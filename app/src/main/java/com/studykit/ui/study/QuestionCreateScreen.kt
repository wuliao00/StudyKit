package com.studykit.ui.study

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.AppMultilineTextField
import com.studykit.ui.components.AppTextField
import com.studykit.ui.motion.StaggeredIn
import com.studykit.ui.theme.AppTheme

/**
 * 题目录入页：学科 + 题干 + 4 选项 + 正确答案选择 + 解析，保存入 questions 表。
 *
 * 输入区按语义并成三张 [AppCard]：题目（学科 / 题干）、选项与正确答案（A–D + 选择器）、
 * 解析；保存走 `AppButton`。页面自上而下错峰入场（标题行 → 三张卡 → 按钮，`StaggeredIn`
 * 每级 40ms，下标都是固定小值、无需 minOf 限幅）。
 *
 * 答案选择器：选中项 = `successSoft` 柔底 + `successInk` 字色 + `SemiBold` + `success` 描边，
 * 未选中 = `background` 底 + `divider` 描边 + `primaryText`；圆角仍取 `AppTheme.radius.md`。
 * 未选中底色不再取 `card`——四格本来就落在 `AppCard` 的 `card` 面上，同色只剩 1dp 描边区分；
 * 换 `background` 后是「卡面上的浅凹槽」，两主题都能一眼看出可点。
 * 字色走 `successInk`（T15 墨水批次）：旧写法是 `success` 实底 + `onAccent`，浅色主题那格白字
 * 压在亮绿上只有 2.2:1；白字/白图压实底按同一裁定只留给 accent 一处，其余一律「soft 底 + ink 字」。
 * 输入框沿用 T3 已迁移的 [AppTextField] / [AppMultilineTextField]（accent 光标、
 * divider 底线），本页不再重复给色。
 */
@Composable
fun QuestionCreateScreen(
    viewModel: StudyViewModel,
    onBack: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    var subject by rememberSaveable { mutableStateOf("") }
    var stem by rememberSaveable { mutableStateOf("") }
    var optionA by rememberSaveable { mutableStateOf("") }
    var optionB by rememberSaveable { mutableStateOf("") }
    var optionC by rememberSaveable { mutableStateOf("") }
    var optionD by rememberSaveable { mutableStateOf("") }
    var answerIndex by rememberSaveable { mutableStateOf(0) }
    var explanation by rememberSaveable { mutableStateOf("") }

    val options = listOf(optionA, optionB, optionC, optionD)
    val canSave = subject.isNotBlank() && stem.isNotBlank() && options.all { it.isNotBlank() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppTheme.space.pageH),
    ) {
        Spacer(Modifier.height(AppTheme.space.sm))
        StaggeredIn(index = 0, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        // 图标落在页面底色上，按 T1 裁定走 accentInk（accent 仅 3.04:1）
                        tint = colors.accentInk,
                    )
                }
                Spacer(Modifier.width(AppTheme.space.xs))
                Text(text = "录入题目", style = texts.pageTitle)
            }
        }

        Spacer(Modifier.height(AppTheme.space.lg))
        StaggeredIn(index = 1, modifier = Modifier.fillMaxWidth()) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                AppTextField(
                    value = subject,
                    onValueChange = { subject = it },
                    label = "学科",
                    placeholder = "例如：数学",
                )
                Spacer(Modifier.height(AppTheme.space.md))
                AppMultilineTextField(
                    value = stem,
                    onValueChange = { stem = it },
                    label = "题干",
                    placeholder = "输入题目内容",
                    minLines = 3,
                )
            }
        }

        Spacer(Modifier.height(AppTheme.space.lg))
        StaggeredIn(index = 2, modifier = Modifier.fillMaxWidth()) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                AppTextField(
                    value = optionA,
                    onValueChange = { optionA = it },
                    label = "选项 A",
                    placeholder = "输入选项内容",
                )
                Spacer(Modifier.height(AppTheme.space.md))
                AppTextField(
                    value = optionB,
                    onValueChange = { optionB = it },
                    label = "选项 B",
                    placeholder = "输入选项内容",
                )
                Spacer(Modifier.height(AppTheme.space.md))
                AppTextField(
                    value = optionC,
                    onValueChange = { optionC = it },
                    label = "选项 C",
                    placeholder = "输入选项内容",
                )
                Spacer(Modifier.height(AppTheme.space.md))
                AppTextField(
                    value = optionD,
                    onValueChange = { optionD = it },
                    label = "选项 D",
                    placeholder = "输入选项内容",
                )

                Spacer(Modifier.height(AppTheme.space.lg))
                Text(text = "正确答案", style = texts.caption)
                Spacer(Modifier.height(AppTheme.space.sm))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 这是一组「四选一」，读屏必须知道它是个单选组：横向滑动浏览选项、
                        // 并播报「已选中/未选中」（终审 I10）。纯语义修饰，不参与布局。
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm),
                ) {
                    listOf("A", "B", "C", "D").forEachIndexed { index, letter ->
                        val selected = index == answerIndex
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                // 格体约 36dp 高（15sp 字母 + 上下 8dp），够不上 48dp 最小可点
                                // 目标（终审 I9）。挂在链首：撑大的只是不可见的点击槽位，
                                // 下面 clip/background/border 都在它下游，磁贴本身一分不变。
                                .minimumInteractiveComponentSize()
                                .clip(RoundedCornerShape(AppTheme.radius.md))
                                .background(if (selected) colors.successSoft else colors.background)
                                .border(
                                    width = 1.dp,
                                    color = if (selected) colors.success else colors.divider,
                                    shape = RoundedCornerShape(AppTheme.radius.md),
                                )
                                // `selectable` 而非 `clickable`：给语义挂上 Role.RadioButton + 选中态。
                                // 它把 interactionSource / indication 一律传 null，而 clickable 对
                                // null indication 的定义就是「用本地默认 indication」⇒ ripple 照旧，
                                // 视觉与手感一分不改。
                                .selectable(
                                    selected = selected,
                                    role = Role.RadioButton,
                                    onClick = { answerIndex = index },
                                )
                                .padding(vertical = AppTheme.space.sm),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = letter,
                                style = texts.aux.copy(
                                    color = if (selected) colors.successInk else colors.primaryText,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                ),
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(AppTheme.space.lg))
        StaggeredIn(index = 3, modifier = Modifier.fillMaxWidth()) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                AppMultilineTextField(
                    value = explanation,
                    onValueChange = { explanation = it },
                    label = "解析（可选）",
                    placeholder = "解释正确答案的思路",
                    minLines = 2,
                )
            }
        }

        Spacer(Modifier.height(AppTheme.space.xl))
        StaggeredIn(index = 4, modifier = Modifier.fillMaxWidth()) {
            AppButton(
                text = "保存题目",
                enabled = canSave,
                onClick = {
                    viewModel.saveQuestion(subject, stem, options, answerIndex, explanation) {
                        onBack()
                    }
                },
            )
        }
        Spacer(Modifier.height(AppTheme.space.xl))
    }
}
