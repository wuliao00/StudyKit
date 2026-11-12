package com.studykit.ui.study

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.AppPill
import com.studykit.ui.theme.AppTheme

/**
 * 首页「今天从哪开始」引导卡（主动用户改版，见 docs/active-user-redesign.md）。
 *
 * ## 它解决什么
 *
 * Production Paradox：用户是来「完成任务」的，不是来「学这个产品」的。所以首屏不给教程，
 * 只给一张卡、三件 30~90 秒能做完的小事（签到 / 复习 / 小测）。每行点一下就去做，
 * 做完那行立刻变成绿勾 —— 学习系统被伪装成「把今天的事做完」。
 *
 * ## 数据从哪来（不新造假数据）
 *
 * 卡上每个数字都是 StudyViewModel 里已有的真实读数：
 * 签到 = SignInSummary.signedToday / currentStreak；
 * 复习 = 今天真实写下的复习时间戳条数 + 当前到期词数；
 * 小测 = 今天真实写下的练习时间戳条数 + 题库里有没有题。
 * 完成态全部在 [DailyStartPlanner] 里现算，所以跨过零点自动归零 —— 需求里的「每日重置」
 * 不靠定时器，靠这三条时间戳数据本身。
 *
 * ## 可逆 / 可退场
 *
 * 「今天先收起」只收起当天（写 epochDay，不写布尔，见
 * [com.studykit.data.AppSettings.dailyStartDismissedEpochDay]），明天自动回来；
 * 卡片下方还有一条「为什么这样安排」，用四句话交代本地优先 / 可撤销 / 无账号 / 为什么是这三步（系统模型可见）。
 */

/** 一张引导卡里的一步；全部字段都由真实数据算出，不在这里写死任何计数。 */
data class DailyStartStep(
    val key: String,
    /** 行标题：一句人话的小任务 */
    val title: String,
    /** 第二行：今天真实做到了多少 / 还差多少 */
    val detail: String,
    /** 行尾可点的那句话（做完之后仍然可点，进各自页面，不留死路） */
    val actionLabel: String,
    val done: Boolean,
)

/** 引导卡的三步 + 汇总。 */
data class DailyStartUi(
    val steps: List<DailyStartStep>,
    val doneCount: Int,
    val allDone: Boolean,
)

/**
 * 「今天从哪开始」的纯判定（无 Compose / 无 Android 依赖，单测直接钉）。
 *
 * 三个完成态的判定口径：
 * - 签到：今天已签到（SignInSummary.signedToday，真实一行签到记录）。
 * - 复习：词库非空，且「今天复习满 [REVIEW_TARGET] 个」**或者**「今天已经没有到期词」。
 *   后者是为不死路：库里只剩 2 个到期词时，用户全复习完也到不了 5，不能让他永远差一格。
 * - 小测：题库非空，且今天真实作答满 [QUIZ_TARGET] 道。
 *   题库为空时这条不算完成、也不算失败，文案改成「去题库录一道」——这才是下一步。
 *
 * 这几条判定与 StudyHomeUiState 里那三个「今日」读数共用同一个日切区间，
 * 因此每天零点（本地时区）自动重新从 0/3 开始。
 */
object DailyStartPlanner {

    const val KEY_SIGN_IN = "sign_in"
    const val KEY_REVIEW = "review"
    const val KEY_QUIZ = "quiz"

    /** 复习一步的小目标：5 个词（论文里 2 分钟的粒度） */
    const val REVIEW_TARGET = 5

    /** 小测一步的小目标：3 道题（1 分钟的粒度） */
    const val QUIZ_TARGET = 3

    /** 卡片的总步数：三段进度条与 x/3 都用它，杜绝两处各写一个 3 */
    const val STEP_COUNT = 3

    fun plan(
        signedToday: Boolean,
        currentStreak: Int,
        reviewedToday: Int,
        dueCount: Int,
        wordTotal: Int,
        practicedToday: Int,
        hasQuestions: Boolean,
    ): DailyStartUi {
        val signIn = DailyStartStep(
            key = KEY_SIGN_IN,
            title = "签到打卡",
            detail = when {
                signedToday && currentStreak > 1 -> "今天已签到 · 连续 " + currentStreak + " 天"
                signedToday -> "今天已签到"
                currentStreak > 0 -> "1 秒签到，接上连续 " + currentStreak + " 天"
                else -> "1 秒签到，给今天开个头"
            },
            actionLabel = if (signedToday) "签到日历" else "去签到",
            done = signedToday,
        )

        // 到期词清零也算完成：没有可复习的，就不该让这一格永远空着。
        val reviewDone = wordTotal > 0 && (reviewedToday >= REVIEW_TARGET || dueCount == 0)
        val review = DailyStartStep(
            key = KEY_REVIEW,
            title = "复习单词",
            detail = when {
                wordTotal == 0 -> "词库还是空的，先加几个词"
                reviewDone && reviewedToday == 0 -> "今天没有到期词，不用硬凑"
                reviewDone -> "今天已复习 " + reviewedToday + " 个词"
                else -> "今天已复习 " + reviewedToday + " 个 · 还有 " + dueCount + " 个到期"
            },
            actionLabel = when {
                wordTotal == 0 -> "去加词"
                reviewDone -> "去词库"
                else -> "开始复习"
            },
            done = reviewDone,
        )

        val quizDone = hasQuestions && practicedToday >= QUIZ_TARGET
        val quiz = DailyStartStep(
            key = KEY_QUIZ,
            title = "做个小测",
            detail = when {
                !hasQuestions -> "题库还没有题，先录一道"
                quizDone -> "今天已练 " + practicedToday + " 道"
                practicedToday > 0 -> "今天已练 " + practicedToday + " 道 · 再做 " + (QUIZ_TARGET - practicedToday) + " 道"
                else -> "3 道题，大约 1 分钟"
            },
            actionLabel = when {
                !hasQuestions -> "去录题"
                quizDone -> "继续练"
                else -> "开始小测"
            },
            done = quizDone,
        )

        val steps = listOf(signIn, review, quiz)
        val done = steps.count { it.done }
        return DailyStartUi(steps = steps, doneCount = done, allDone = done == STEP_COUNT)
    }
}

/**
 * 引导卡本体。
 *
 * @param plan [DailyStartPlanner] 按真实数据算出的三步
 * @param onStepAction 行被点：参数是 [DailyStartStep.key]，由首页分派到既有入口
 * @param onWhy 打开「为什么这样安排」底部弹层
 * @param onDismiss 收起当天（明天自动回来）
 */
@Composable
fun DailyStartCard(
    plan: DailyStartUi,
    onStepAction: (String) -> Unit,
    onWhy: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts

    AppCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (plan.allDone) "今日已起飞" else "今天从哪开始",
                    style = texts.pageTitle,
                )
                Spacer(Modifier.height(AppTheme.space.xs))
                Text(
                    text = if (plan.allDone) {
                        "三件事都做完了 · 明天这里会自动刷新"
                    } else {
                        "先做一件小事，剩下的慢慢来 · 三步约 3 分钟"
                    },
                    style = texts.caption,
                    color = colors.secondaryText,
                )
            }
            Spacer(Modifier.width(AppTheme.space.sm))
            AppPill(
                container = if (plan.allDone) colors.goldSoft else colors.accentSoft,
                ink = if (plan.allDone) colors.goldInk else colors.accentInk,
                label = if (plan.allDone) "已起飞 3/3" else plan.doneCount.toString() + "/3",
            )
        }

        Spacer(Modifier.height(AppTheme.space.md))
        // 三段进度：与下面三行一一对应。已完成 = success（绿），未完成 = divider。
        Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.space.xs)) {
            plan.steps.forEach { step ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(if (step.done) colors.success else colors.divider.copy(alpha = 0.80f)),
                )
            }
        }
        Spacer(Modifier.height(AppTheme.space.xs))

        plan.steps.forEachIndexed { index, step ->
            if (index > 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.divider.copy(alpha = 0.55f)),
                )
            }
            DailyStartStepRow(
                step = step,
                index = index,
                onClick = { onStepAction(step.key) },
            )
        }

        if (plan.allDone) {
            // 完成之后给下一步建议（真实入口：题库练习），但不逼用户继续。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AppTheme.radius.md))
                    .clickable(role = Role.Button) { onStepAction(DailyStartPlanner.KEY_QUIZ) }
                    .padding(horizontal = AppTheme.space.xs, vertical = AppTheme.space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "想多学一点？再去题库练一组",
                    style = texts.caption.copy(color = colors.accentInk),
                    modifier = Modifier.weight(1f),
                )
                Text(text = "›", style = texts.body, color = colors.accentInk)
            }
        }

        Spacer(Modifier.height(AppTheme.space.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(AppTheme.radius.sm))
                    .clickable(role = Role.Button, onClick = onWhy)
                    .padding(horizontal = AppTheme.space.xs, vertical = AppTheme.space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = colors.secondaryText,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(AppTheme.space.xs))
                Text(text = "为什么这样安排", style = texts.caption, color = colors.secondaryText)
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "今天先收起",
                style = texts.caption,
                color = colors.secondaryText,
                modifier = Modifier
                    .clip(RoundedCornerShape(AppTheme.radius.sm))
                    .semantics { contentDescription = "收起今天从哪开始，明天会自动出现" }
                    .clickable(role = Role.Button, onClick = onDismiss)
                    .padding(horizontal = AppTheme.space.xs, vertical = AppTheme.space.sm),
            )
        }
    }
}

/** 一行任务：序号/绿勾 + 标题 + 真实进度 + 行尾动作；整行可点（文案里已写明动作）。 */
@Composable
private fun DailyStartStepRow(
    step: DailyStartStep,
    index: Int,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val stateText = if (step.done) "已完成" else "未完成"
    val stepNumber = index + 1
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppTheme.radius.md))
            .semantics {
                contentDescription = "第 " + stepNumber + " 步，" + step.title + "，" + stateText + "，" +
                    step.detail + "，" + step.actionLabel
            }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = AppTheme.space.xs, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(if (step.done) colors.successSoft else colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            if (step.done) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = colors.successInk,
                    modifier = Modifier.size(17.dp),
                )
            } else {
                Text(
                    text = stepNumber.toString(),
                    style = texts.caption.copy(
                        color = colors.accentInk,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
        }
        Spacer(Modifier.width(AppTheme.space.sm))
        Column(Modifier.weight(1f)) {
            Text(text = step.title, style = texts.cardTitle)
            Spacer(Modifier.height(2.dp))
            Text(text = step.detail, style = texts.caption, color = colors.secondaryText)
        }
        Spacer(Modifier.width(AppTheme.space.sm))
        Text(
            text = step.actionLabel + " ›",
            style = texts.caption.copy(color = colors.accentInk, fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
    }
}
