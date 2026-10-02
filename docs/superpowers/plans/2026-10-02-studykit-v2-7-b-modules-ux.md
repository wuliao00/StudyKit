# StudyKit v2.7 计划 B：模块界面接线 + Tips + 度量 + 真机验收

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在计划 A 的内核与 schema 之上，把 app.docx 的五个模块交互全部接出来：信心条、模考、错题接管、执行意图、检索式书摘、Tips 接线、延迟后测卡，并完成真机升级验证与发版文档。

**Architecture:** 每个模块一个"纯逻辑先行"的可测核（已在计划 A 的 Hypercorrection/MistakeMastery/HabitGuard），UI 只做状态机接线；Compose 改动一律配 Robolectric 渲染守卫（照 `CheckInSheetRenderTest` / `RecallGateRenderTest` 先例）。

**Tech Stack:** 同计划 A。所有命令在 `C:\Users\Administrator\Desktop\workspace\StudyKit`，`.\gradlew`。

**前置：** 计划 A 全部 10 个任务完成且全量测试绿。**每个任务 Step 0 的"取证读"不许跳** —— 计划里给的行号是写作时点的，实施时以实际内容为准并把偏差记进提交说明。

---

### Task 11: 背词信心条（M1）+ 闸门两点 Tips 接线

**Files:**
- Create: `ui/study/ConfidencePill.kt`（纯 Compose 组件，无状态所有权）
- Modify: `ui/study/CardStudyScreen.kt`（翻面后、GradeRow 之前插入信心行；`gradeCard` 传 conf）
- Modify: `ui/study/StudyViewModel.kt`（`gradeCard(grade, reactionMs, conf)` 形参；`Hypercorrection` 命中时额外写 `priority`/当日再见；`WordReview.confidence` 落库）
- Modify: `data/repository/WordRepository.kt`（`recordGradedReview` 加 `confidence: Int? = null`，透传 DAO）
- Test: `ui/study/ConfidencePillRenderTest.kt`

- [ ] **Step 0 取证**：读 `CardStudyScreen.kt:180-300`（翻面状态 `revealed`/预测试挂载点）、`StudyViewModel.kt:224-280` 现状（计划 A Task 9 改后的形态）、`WordDao.insertReview`、`ui/habit/CheckInSheetRenderTest.kt:70-90`（`setContent` 里 `CompositionLocalProvider(LocalAppTheme provides AppThemeVals(…))` 的真实写法——下面的新测试**逐字复用它的 provider 块**，一个测试只能 setContent 一次）。
- [ ] **Step 1: 失败渲染守卫测试**

```kotlin
package com.studykit.ui.study

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.studykit.data.memory.Confidence
// 下面三行 import 与 AppThemeVals 的构造参数，以 CheckInSheetRenderTest 实文件为准
import com.studykit.ui.theme.AppThemeVals
import com.studykit.ui.theme.LocalAppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConfidencePillRenderTest {
    @get:Rule val rule = createComposeRule()

    @Test fun `three tiers render and selection reports back`() {
        var picked: Confidence? = null
        rule.setContent {
            // CompositionLocalProvider(…AppThemeVals 同 CheckInSheetRenderTest…) {
            ConfidenceRow(selected = null, onPick = { picked = it })
            // }
        }
        rule.onNodeWithText("非常确定").performClick()
        assertEquals(Confidence.SURE, picked)
        rule.onNodeWithText("有点印象").assertExists()
        rule.onNodeWithText("瞎猜").assertExists()
    }
}
```

（provider 注释两行在实现时替换成从 `CheckInSheetRenderTest` 抄来的真实代码，不许裸跑——`LocalAppTheme` 虽有默认值，但默认值绑的是哪套色没承诺过，跟存量测试走。）

- [ ] **Step 2: 红。**
- [ ] **Step 3: 实现 `ConfidencePill.kt`**

```kotlin
package com.studykit.ui.study

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.studykit.data.memory.Confidence
import com.studykit.ui.theme.AppTheme

/**
 * 翻面后、评分前的 10 秒信心条（app.docx 模块1 P0"自评信心"/第三部分#3）。
 * 文案刻意中性：这是给用户自己看的校准，不是打分表演。
 * 跳过（selected=null 且用户直接评分）= confidence 记 NULL，不参与超纠正。
 *
 * 没复用 [com.studykit.ui.components.AppPill]：它的签名是 (container, ink, label, modifier, leadingDot)，
 * **无 onClick 无选中态**（只展示）；选中/未选只用已有 AppColors token（accentSoft/accentInk/card/
 * secondaryText），不新增色值、不动 AppPill 的公共 API。
 */
private val LABELS = listOf(
    Confidence.GUESS to "瞎猜",
    Confidence.FAIR to "有点印象",
    Confidence.SURE to "非常确定",
)

@Composable
fun ConfidenceRow(
    selected: Confidence?,
    onPick: (Confidence) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("有多大把握？", style = texts.caption, color = colors.secondaryText)
        LABELS.forEach { (value, label) ->
            val isSelected = selected == value
            Text(
                text = label,
                style = texts.caption.copy(fontWeight = FontWeight.Medium),
                color = if (isSelected) colors.accentInk else colors.secondaryText,
                modifier = Modifier
                    .clip(RoundedCornerShape(AppTheme.radius.sm))
                    .background(if (isSelected) colors.accentSoft else colors.card)
                    .clickable { onPick(value) }
                    .padding(horizontal = AppTheme.space.md, vertical = AppTheme.space.sm),
            )
        }
    }
}
```

（`texts.caption` 字段名已被仓内多处使用（如 GradeRow 的"预计还记得"行），`radius/space` 同 AppPill 现有引用；若 Step 0 发现字段名不同，以存量为准。）

- [ ] **Step 4: 页面接线**：`CardStudyScreen` 增加 `remember { mutableStateOf<Confidence?>(null) }`，在 `GradeRow` 上方按 `settings.confidenceEnabled && revealed && !pretestActive` 渲染 `ConfidenceRow`；`onGrade` 调用改成 `viewModel.gradeCard(grade, reactionMs, conf)`；换卡时把 conf 状态清零（与 `revealed` 同生命周期，写在同一个 `LaunchedEffect(word.id)` 里）。
- [ ] **Step 5: ViewModel/落库**：`gradeCard(grade: ReviewGrade, reactionMs: Long? = null, conf: Confidence? = null)`；`recordGradedReview(..., confidence = conf?.ordinal?.plus(1))`；评完正常排期后：

```kotlin
            Hypercorrection.retestDelayMinutes(conf, recalled = grade != ReviewGrade.FORGET)?.let { minutes ->
                // 当日再见一次：不动 nextReviewAt（那是内核的正常排期），
                // 而是把 priority=1 写进 words（计划 A 未建列 —— 这里补一条 v7 内的说明：
                // priority 列在 mistakes 上；words 侧用 nextReviewAt 取 min(next, now+minutes) 实现，
                // 只提前不推后，FSRS/HalfLife 同一规则）
                wordRepository.pullForward(word.id, now + minutes * 60_000L)
            }
```

`WordDao`：`@Query("UPDATE words SET next_review_at = MIN(next_review_at, :at) WHERE id = :id")  suspend fun pullForward(id: Long, at: Long)`（经 `WordRepository.pullForward` 转发；"只提前不推后"写进函数注释）。
- [ ] **Step 6: Tips 两点接线**：闸门首次拦下滑动处（既有 hint 展示点旁）`tipFor(TipEvent.NewCardFirstLook)`；翻面动作完成处 `TipEvent.AboutToFlip`（用 `OneShotGate`/`recallGateHintSeen` 同款"每次安装一次"纪律给 AboutToFlip 记 `KEY_FLIP_TIP_SEEN`，`AppSettings` 补一个布尔键，fromMap 口径同 Task 8）。
- [ ] **Step 7: 首页目标梯度提示行（app.docx 模块1 P1，spec §4 补录项）**：纯函数先行——

```kotlin
// ui/study/GoalCue.kt
package com.studykit.ui.study

/** 目标梯度提示（Kivetz et al. 2006）：只陈述"还差多少"，不碰任何数字口径与进度环配色 */
object GoalCue {
    fun text(done: Int, goal: Int): String? =
        if (goal <= 0 || done >= goal) null else "距今日目标还差 ${goal - done} 词"
}
```

测试 `GoalCueTest`：`text(18, 20) == "距今日目标还差 2 词"`；`text(20, 20) == null`；`text(3, 0) == null`（目标非法不吭声）。挂载点：`StudyHomeScreen.kt` 的 `val goal = AppSettings.dailyWordGoal` 那块 hero 区（Step 0 读 :250-300 找副标题槽），非 null 才加一行 `caption` 文本；达成/空日不出现，不新增颜色。
- [ ] **Step 8: `--tests "*ConfidencePillRenderTest*"` 绿 → 全量 `.\gradlew :app:testDebugUnitTest` 绿 → `assembleDebug` → 真机安装看词卡信心条与首页提示行（`adb install -r`，弹窗处理按用户既定流程）。**

---

### Task 12: 题库信心 + 超纠正置顶（M2）

**Files:**
- Modify: `ui/study/QuizScreen.kt`（提交按钮上方渲染 `ConfidenceRow`，提交时随 `PracticeRecord` 落 `confidence`；答对/答错结算处接 `Hypercorrection`）
- Modify: `data/dao/PracticeDao.kt` + `data/entity/PracticeRecord.kt` 写入点（confidence 透传）
- Modify: 错题入库路径（答错自动入 `mistakes` 处）：`priority = if (Hypercorrection 命中) 1 else 0`
- Test: `QuizConfidenceWiringTest`（纯逻辑：错误+SURE → mistakes.priority=1 的构造函数）

- [ ] **Step 0 取证**：`QuizScreen.kt:120-250`（提交/反馈流）、错题自动入本的函数（grep `SOURCE_PRACTICE`）。
- [ ] **Step 1: 先把"答错入库带优先级"抽成纯函数并测**：`MistakeIntake.fromWrongAnswer(question, selected, confidence): Mistake` —— 断言 `priority == 1` 当且仅当 `confidence == SURE`。
- [ ] **Step 2: 红 → Step 3: 实现接线**（提交前信心条复用 `ConfidenceRow`；受 `confidenceEnabled` 控制；现有 HYPERCORRECTION Tip 触发点 `QuizScreen.kt:442-444` 的代理条件 `hintLevel == 0` 升级为真信心：`conf == Confidence.SURE && !right`，代理逻辑删掉并在注释写明"v2.7 起用真实置信度"）。
- [ ] **Step 4: 列表置顶**：`MistakeDao.observeUnmastered` 排序改 `ORDER BY priority DESC, created_at DESC`（改动处注释说明；`observeMastered` 不动）。
- [ ] **Step 5: 全量绿 + 真机走一遍"非常确定→答错"路径，错题本第一条是它。**

---

### Task 13: 模考模式（M2，延迟反馈）

**Files:**
- Create: `ui/study/MockExamScreen.kt`（复用 QuizScreen 的题面 Composable 或抽取 `QuestionCard`；无反馈、无提示按钮、顶栏"交卷"）
- Create: `ui/study/ExamResultScreen.kt`（逐题走 `QuizFeedback` 三层 + 完整解析；答错项批量入错题本 priority 规则同 Task 12）
- Modify: `ui/study/StudyHomeScreen.kt`（入口 tile「模考」）+ `ui/nav/AppNav.kt`（两条路由）
- Create: `ui/study/MockExamState.kt`（纯状态机：`select(index)/submit()` → 每提交前不产生任何对错信息）
- Test: `MockExamStateTest.kt`

- [ ] **Step 1: 失败测试**

```kotlin
package com.studykit.ui.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MockExamStateTest {
    @Test fun `answer recorded but verdict withheld until submit`() {
        val st = MockExamState(questionIds = listOf(1L, 2L), answerCount = 4)
        st.answer(1L, selected = 2)
        assertNull(st.verdictOf(1L))          // 交卷前永远拿不到对错
        st.answer(2L, selected = 0)
        val result = st.submit()
        assertEquals(2, result.size)
        assertEquals(2, result.getValue(1L).selected)
    }
    @Test fun `unanswered question submits as skipped not crash`() {
        val result = MockExamState(listOf(9L), 4).submit()
        assertEquals(null, result.getValue(9L).selected)
    }
}
```

- [ ] **Step 2: 红 → Step 3: 实现 `MockExamState`**（`data class Answer(selected: Int?, correct: Boolean)` 在 submit 时用 `Question.answerIndex` 判；`submit()` 只在内部算 correct，但 UI 只有到 `ExamResultScreen` 才读它 —— 与练习模式的即时反馈路径完全分叉，练习模式代码不动）。
- [ ] **Step 4: UI 接线 + 渲染守卫**：`MockExamRenderTest` 用 `onNodeWithText("交卷")` 存在、作答后**断言不出现**"答对了/正确答案"字样（这一条就是"延迟反馈"承诺的机检证据，防将来有人在模考页手滑接回反馈）。
- [ ] **Step 5: 交卷后批量入错题本 + 全量绿。**

---

### Task 14: 错题本排期接管（M3 P0）

**Files:**
- Modify: `ui/mistake/MistakeDetailScreen.kt:376-390`（"明天/三天后/一周后"三选项撤下主菜单，改次级「覆盖排期」；:386 文案改为"系统按遗忘曲线排期，你可以覆盖"）
- Modify: `ui/mistake/MistakeViewModel.kt`（新入本默认 `fsrs_state=1, stability=null`，由内核首评；`setReviewAt` 保留为覆盖；新增 `gradeRedo(id, grade, conf)`：走 `KernelHub` + `MistakeMastery`）
- Modify: `MistakeListScreen`（"待复习"筛选按 `reviewAt ?: 内核到期` 双轨：有覆盖用覆盖，没有用 `fsrs_state/next`——next 落 `review_at`? **决策：算法排期写回 `review_at` 本身**，"覆盖"只是用户手动改写同一个列；列表与提醒零特判）
- Test: `MistakeSchedulingTest`（纯函数：首入本→S=null；评 AGAIN→RELEARNING + 当日再见；评 GOOD×2 间隔≥3 天→ `mastered=true` 走 `MistakeMastery`）

- [ ] **Step 0 取证**：`MistakeViewModel.kt:260-320`（setReviewAt/markMastered 现状）、`ReminderWorker` 消费 `getDueForReview` 处。
- [ ] **Step 1-2: 写测（红）→ 实现**：`gradeRedo` 结构照抄 `gradeCard` 的内核段（计划 A Task 9 成品），状态写 `mistakes` 的 fsrs 列 + `correct_streak/review_count`；`review_at` 语义注释统一为"下一次到期（算法或人工写入，同列）"；`markMastered` 手动路径保留（覆盖时清 `correct_streak` 防震荡，注释说明）。
- [ ] **Step 3: UI 文案替换 + 渲染守卫**（`MistakeDetailRenderTest` 断言「复习时间由你自己定，这里没有算法排期。」**不再出现**——旧句被钉死退役，与仓内 ContractDeleteCopyTest 同款手法）。
- [ ] **Step 4: 全量绿。**

---

### Task 15: 错题重做接提示阶梯 + 重做历史 + 变式题（M3）

**Files:**
- Modify: `ui/mistake/RedoFlow.kt` + `MistakeDetailScreen`（重做提交路径写 `mistake_redos`；"给点提示"复用 `QuizFeedback.hintTiers/nextHintLevel`）
- Modify: 拍照题流程（`had_note_rebuild`：OCR 题重做通过前解析区折叠；`MistakeCaptureScreen` 的 title/note 确认框文案加"重述关键步骤"引导，Step 0 取证 :140-170）
- Modify: `QuestionDao`/`MistakeDetailScreen`（`concept_tag` 非空且存在同 tag 其他题时出现「换一道同考点的」按钮，点击后随机替换重做对象；无匹配题不出现）
- Test: `RedoHistoryTest`（写入与计数）、`VariantPickerTest`（纯函数：同 tag、排除自身、无候选返回 null）

- [ ] **Step 1: `VariantPicker` 失败测试**：输入 `Question(id, conceptTag)` 列表 → `pick(questionId, all)`：同 tag 非自身随机一题；tag 空 → null。
- [ ] **Step 2: 红→实现**：重做完成时 `insertRedo(mistakeId, correct, hintsUsed, hadNoteRebuild)` 与 `gradeRedo` 同事务顺序纪律同 `applyReview→recordGradedReview`（先状态后历史，注释引用 WordRepository 那段"污染校准样本"的理由）。
- [ ] **Step 3: UI 接线 + PRODUCTIVE_STRUGGLE Tip 挂"错题重做首次点提示"**（`StudyTips` 加 `TipEvent.FirstHintOnRedo`，`TipId.PRODUCTIVE_STRUGGLE` 文案："先只拿提示，别急着看全解 —— 自己往前推出来那一步，才真的长脑子。"evidence 用 spec D8 口径：`Productive struggle / constructive struggle（2024–2026 教育 AI 文献）`，不写撤稿文献）。
- [ ] **Step 4: 全量绿。**

---

### Task 16: 执行意图模板（M4 P0）+ IF_THEN Tip

**Files:**
- Modify: `ui/habit/HabitCreateScreen.kt`（新增"何时何地→做什么"三输入区，产出 `habits.ifThen` 整句；命名/图标等既有字段不动）
- Modify: `ui/habit/HabitListScreen.kt`（副标题优先渲染 `ifThen`，空则回退现有 `defaultText`）
- Modify: `tips/StudyTips.kt`（`TipId.IF_THEN` + `TipEvent.HabitFirstSave`）
- Test: `IfThenTemplateTest`

- [ ] **Step 1: 失败测试**

```kotlin
package com.studykit.ui.habit

import org.junit.Assert.assertEquals
import org.junit.Test

class IfThenTemplateTest {
    @Test fun `compose renders when-where-then sentence`() {
        assertEquals(
            "当早上·书桌前，我就背 10 个单词",
            IfThenTemplate.compose(whenLabel = "早上", where = "书桌前", then = "背 10 个单词"),
        )
    }
    @Test fun `missing parts degrade gracefully`() {
        assertEquals("当晚上，我就阅读", IfThenTemplate.compose("晚上", "", "阅读"))
        assertEquals("", IfThenTemplate.compose("", "", ""))
    }
}
```

- [ ] **Step 2: 红 → Step 3: 实现 `IfThenTemplate`（object，纯函数）+ 创建页三输入行**（when 用现有 `category` 选择器的枚举文案，不新造概念；`where/then` 为 `AppTextField`，`then` 必填才组装）。
- [ ] **Step 4: Tip**：文案按 spec D4 ——「把它绑到具体时间和地点：『当【何时·何地】，我就【做什么』。这类 if-then 计划的元分析效应量 d=0.65，属中到大。」evidence=`Gollwitzer & Sheeran 2006 元分析（94 项独立检验）`；触发点=首次成功保存带 `ifThen` 的习惯（每次安装一次，复用 `OneShotGate`）。
- [ ] **Step 5: 全量绿。**

---

### Task 17: 断签保护的展示层（M4）

**Files:**
- Modify: `ui/habit/HabitViewModel.kt`（连续/热力计算入口接 `HabitGuard`；:62 那段"本轮没有落库位置"注释改写为实现说明）
- Modify: `ui/habit/HeatmapLogic.kt` / `MonthGridCommon`（受保护缺卡日画"灰圈"，`HabitCalendarScreen` 日详情气泡加"未打卡（已用断签保护）"）
- Test: `HabitGuardDisplayTest`（给整月布尔串，断言连续数跨保护日延续、第 3 次缺归零；文案常量钉死）

- [ ] **Step 1: 失败测试**（保护月内 `streakThrough` 不断、气泡文案常量 == "未打卡（已用断签保护）"）。
- [ ] **Step 2: 实现**：`MISSING_GUARD_TEXT` 常量放 `HabitGuard.kt`（可被单测钉住）；`MISS_ONE_DAY` Tip 挂在"当次打卡界面检测到昨日缺卡且本月额度未用尽"（`TipEvent.GapDay` 从死码变活）。
- [ ] **Step 2b: SIXTY_SIX 接活（spec §8 点名的另一半）**：当日连续学习火焰徽章天数恰好 == `StudyTips.STREAK_MYTH_DAY`(21) 时，在首页 hero 下方挂一次 `TipEvent.StreakReached(21)`（每次安装一次，复用 `OneShotGate`，key 进 `AppSettings` 新布尔键 `SIXTY_SIX_TIP_SEEN`，fromMap 口径同计划 A Task 8）；测试钉：第 20/22 天不弹、第 21 天弹且只弹一次。
- [ ] **Step 3: 全量绿。**

---

### Task 18: 检索式书摘 + 章节自测 + 主指标（M5）

**Files:**
- Create: `ui/book/ExcerptReviewScreen.kt`（出摘：只显前半 → 回忆 → 展开全文自评三档 → `KernelHub` 重排 `stability/next_review_at`）
- Create: `ui/book/ChapterTestScreen.kt`（出题/作答/对照，写 `chapter_tests`）
- Modify: `ui/book/BookDetailScreen.kt`（两入口：「复习书摘」「本章自测」）
- Modify: `ui/book/BookShelfScreen.kt:138-139`（hero 主 tile 改「检索练习次数」= word_reviews+practice_records+mistake_redos+chapter_tests+excerpt 自评 的完成总数；`excerptCount` 降为次级 tile，Step 0 取证 ViewModel 提供聚合数）
- Modify: `BookDao`/新查询（`countRetrievalActions()` 一条 UNION SQL，数字来源注释逐表列明）
- Test: `RetrievalCountTest`（SQL 聚合纯查询 + Robolectric in-memory DB 计数）+ `ExcerptReviewRenderTest`（"合书回忆"提示、EXPLAIN_WHY/RECALL_NOTES Tip 挂载断言）

- [ ] **Step 1: 聚合计数先红后绿**（in-memory `Room.inMemoryDatabaseBuilder` 是既有 `AppSettingsTest` 之外的新手法，Room 内存库在 Robolectric 下可用；建 2 词 1 题 1 书摘，插评分历史后断言总数）。
- [ ] **Step 2: 书摘复习流实现**（`review_count`/`stability` 走 `FsrsKernel` 同 Task 14 的结构；摘入选 `confidenceEnabled` 同样生效——复用 `ConfidenceRow`）。
- [ ] **Step 3: 章节自测 UI 两步**：出题（`chapter_label/question/expected_answer`）与作答对照（回忆→展开→`passed` 自评）；`ChapterFinished` Tip 在保存第一道自测题后触发。
- [ ] **Step 4: `ExcerptOnlyNoRecall` Tip**：书摘列表存在 7 天前建摘且 `review_count==0` 时首屏出一条（每次安装一次）。
- [ ] **Step 5: 全量绿 + 真机截图（此页列入 Task 21 清单）。**

---

### Task 19: 延迟后测卡 + 备份 CSV（度量，spec §9）

**Files:**
- Create: `ui/stats/RetentionBuckets.kt`（纯函数：输入 `ReviewGapRow` 样式集合 → `Bucket(labeled "7d"/"30d"/"other", observedRate, predictedMean, n)`；grade=-1 的迁移前行进 `unknown` 桶且 UI 标注不计达标）
- Modify: `ui/stats/StatsScreen.kt`（新卡：三桶 observed vs predicted + 样本量 n；n<20 显示"样本还少，先别当真"）
- Modify: `util/backup/BackupArchive.kt`（zip 追加 `review_history.csv`，格式=spec 附录 A；恢复端**忽略**该文件名——只增不改，Step 0 取证恢复侧清单过滤逻辑）
- Test: `RetentionBucketsTest` + `BackupCsvEntryTest`（zip entry 名与行数；恢复容忍性）

- [ ] **Step 1: 失败测试**（10 条评分行 7d 桶 6/8 → observedRate=0.75 断言；predicted 取 pAtReview 均值；空集返回空列表不除零）。
- [ ] **Step 2: 实现 + 全量绿。** CSV 行示例（钉进测试断言）：`word,42,1717000000000,3.0,3,0,0,7`（列序=附录 A）。

---

### Task 20: 设置页收口

**Files:**
- Modify: `ui/settings/SettingsScreen.kt` + `SettingsViewModel.kt`
- [ ] **Step 1: 取证读现有分区结构（Step 0）。**
- [ ] **Step 2:** 三件新开关按既有分区插入：①「排期内核」二选一（FSRS/旧半衰期；副标题用 spec §2.1 的诚实口径"切回旧内核后，评分历史不丢，但记忆强度读数是换算近似"）；②「先自评把握再评分」开关（`confidenceEnabled`）；③交错开关镜像进设置页（计划 A 后 QuizScreen 已有，两处同一 StateFlow，**唯一判定不变**）。
- [ ] **Step 3: 免责与来源**：设置页"关于"区若已有第三方声明清单，则追加两行——排期含 FSRS 公式思想（MIT，py-fsrs）、部分功能参考「小计划」「作业帮」仅功能形态未用其代码（reverse-ref §6.6 同文；若"关于"无此结构则只进 README/CHANGELOG，不硬造 UI）。
- [ ] **Step 4: 全量绿。**

---

### Task 21: 发版与真机验收（含截图）

- [ ] **1** 版本号：`versionCode = 16`、`versionName = "2.7.0"`（`app/build.gradle.kts:61-62`）。
- [ ] **2** `.\gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` 全绿（lint 沿用仓内"新缺陷零容忍"的既有基线口径）。
- [ ] **3** 真机覆盖升级（**旧数据库**，含 v2.6 数据的机器）：`adb install -r app\build\outputs\apk\debug\app-debug.apk` → 打开无崩溃、词卡/错题/习惯/书数据全在、"明日预计复习"仍显示（迁移真机存活 + 内核切换冒烟）。装挂按既定流程先查锁屏/安全守护弹窗。
- [ ] **4** 截图 6 张存 `docs/screenshots/`（命名 `v27_<场景>.png`，不删）：词卡信心条 / 模考交卷页 / 错题详情排期接管 / 习惯创建执行意图区 / 书摘复习页 / 延迟后测卡。
- [ ] **5** README + CHANGELOG：新特性一节（含 D4 口径的 FSRS 表述与"20%~30% 为 py-fsrs 自测口径"括注、MistakeMastery 取 2 的取舍声明、参考应用来源声明沿用 reverse-ref §6.6）；CHANGELOG 顶部 v2.7.0 一节逐条对应计划 A/B 任务号；**并补计划 A 欠账 I1**：MigrationTestHelper 网只覆盖 6→7 及以后，1..5 无旧快照仍靠真机走查；MistakeMastery/HabitGuard 的"取舍待记入"两处也在本节落地。
- [ ] **6** 向用户复述验收结果（逐任务 commit 已获用户授权，是本计划既定节奏；合并/推远端仍需单独点头）。

---

## 边界与依赖提醒（执行者必读）

- 计划 A 未完成前，本计划 Task 11/12/14/16/17/18 全部会被阻塞（引用其内核/列/纯函数）。
- 任何一步测试"红得不对劲"（编译错、ClassNotFound、Robolectric 环境错）→ 修环境/引用再跑，**不许改断言迁就实现**（RecallGate.kt 头注同源纪律）。
- 与 app.docx 的一切偏离以 spec 决策表 D1–D8 为准；实现中发现新偏离，先改 spec 再改计划，不许两头偷偷不一致。
