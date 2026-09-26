package com.studykit.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.studykit.data.Disclaimer
import com.studykit.ui.components.AppButton
import com.studykit.ui.theme.AppTheme
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.studykit.ui.components.AppCard
import com.studykit.data.UpdateChecker
import com.studykit.BuildConfig
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import android.net.Uri
import android.content.Intent
import android.app.Activity

/**
 * 首次启动的免责声明：**必须同意才能用**。
 *
 * 「不同意」直接退出应用（由调用方 `finish()`）。这不是为难人 —— 这份声明里写着
 * "数据只在本机、卸载会丢"，用户在不接受这句话的前提下使用，出了事双方都没有依据。
 *
 * 全文可滚动：六段文案在小屏 + 大字号下一定超过一屏，而"看不到的内容不算同意过"。
 */
@Composable
fun DisclaimerScreen(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = AppTheme.space.pageH),
    ) {
        Spacer(Modifier.height(AppTheme.space.lg))
        Text(text = "使用前请先了解", style = texts.largeTitle)
        Spacer(Modifier.height(AppTheme.space.sm))
        Text(
            text = "这几条都是这个应用真实存在的边界，看完再决定要不要用。",
            style = texts.caption,
        )
        Spacer(Modifier.height(AppTheme.space.md))
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Disclaimer.paragraphs.forEach { p ->
                Text(text = p, style = texts.aux)
                Spacer(Modifier.height(AppTheme.space.md))
            }
            Spacer(Modifier.height(AppTheme.space.sm))
            Text(text = "源码与问题反馈：${Disclaimer.REPO_URL}", style = texts.caption)
            Spacer(Modifier.height(AppTheme.space.lg))
        }
        AppButton(text = "同意并继续", onClick = onAccept, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(AppTheme.space.sm))
        // 退出走文字按钮：它是严肃的出口，但**不是**这一屏的默认动作。
        TextButton(
            onClick = onDecline,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = "不同意并退出", style = texts.caption.copy(color = colors.secondaryText))
        }
        Spacer(Modifier.height(AppTheme.space.md))
    }
}

/** 首启引导的一页 */
private data class TutorialPage(val title: String, val lines: List<String>)

private val onboardingPages = listOf(
    TutorialPage(
        "学习：把词装进来，再翻着复习",
        listOf(
            "四种录入方式：手输一条、批量粘贴、截图取词、在线词库下载。",
            "复习是卡片翻面：认识 / 不认识决定它下次什么时候出现。",
            "「今日待办」是今天的全部任务，它就是首页最上面那个数。",
        ),
    ),
    TutorialPage(
        "习惯：打卡、补卡、看长期",
        listOf(
            "两种习惯：计数型（喝水 8 杯）和天数型（每天一次）。",
            "漏了可以补，补的打卡会标出来，不冒充当天打的。",
            "近 20 周热力图 + 连续天数，看的是长期而不是今天。",
        ),
    ),
    TutorialPage(
        "自我契约：先写下后果，再到期对账",
        listOf(
            "签一份：选习惯、定期限、写承诺与违约后果。",
            "到期按真实打卡数对账，达成会给你一整页仪式，不是一枚提示。",
            "签错了可以撤销，已经打过的卡不受影响。",
        ),
    ),
    TutorialPage(
        "读书与错题",
        listOf(
            "书架记录在读与进度，摘录可以导出。",
            "错题拍照录入，按学科分组、设复习提醒。",
            "题目答错会自动进错题本。",
        ),
    ),
    TutorialPage(
        "数据：只在你手机里",
        listOf(
            "没有服务器，也就没有「找回密码」这种事。",
            "换包前请到「设置 → 数据管理」导出备份。",
            "卸载或清除数据会真的删掉，没有云端副本。",
        ),
    ),
)

/**
 * 首启引导：同意免责声明之后走一遍，五页，可跳过。
 *
 * 不拦截后续启动（看过就再也不自动出现），随时可从「设置 → 使用教程」回看 ——
 * 同一份内容两处入口，所以文案只写在这里的一份常量里。
 */
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    var page by rememberSaveable { mutableIntStateOf(0) }
    val last = page == onboardingPages.lastIndex
    val current = onboardingPages[page]

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = AppTheme.space.pageH),
    ) {
        Spacer(Modifier.height(AppTheme.space.lg))
        Text(
            text = "第 ${page + 1} / ${onboardingPages.size} 页",
            style = texts.caption,
        )
        Spacer(Modifier.height(AppTheme.space.sm))
        Text(text = current.title, style = texts.pageTitle)
        Spacer(Modifier.height(AppTheme.space.md))
        Column(Modifier.weight(1f)) {
            current.lines.forEach { line ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(text = "・", style = texts.body, color = colors.accentInk)
                    Text(text = line, style = texts.body)
                }
                Spacer(Modifier.height(AppTheme.space.sm))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm),
        ) {
            if (page > 0) {
                AppButton(
                    text = "上一步",
                    onClick = { page -= 1 },
                    modifier = Modifier.weight(1f),
                    secondary = true,
                )
            }
            AppButton(
                text = if (last) "开始使用" else "下一步",
                onClick = { if (last) onDone() else page += 1 },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(AppTheme.space.sm))
        TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(text = "跳过", style = texts.caption.copy(color = colors.secondaryText))
        }
        Spacer(Modifier.height(AppTheme.space.md))
    }
}

/** 只读的教程页（设置入口用它，与首启引导同一份文案） */
@Composable
fun TutorialScreen(modifier: Modifier = Modifier) {
    val texts = AppTheme.texts
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppTheme.space.pageH),
    ) {
        Spacer(Modifier.height(AppTheme.space.md))
        onboardingPages.forEachIndexed { index, p ->
            Text(
                text = "${index + 1}. ${p.title}",
                style = texts.cardTitle.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(AppTheme.space.sm))
            p.lines.forEach { line ->
                Text(text = "・$line", style = texts.body)
                Spacer(Modifier.height(AppTheme.space.xs))
            }
            Spacer(Modifier.height(AppTheme.space.md))
        }
        Text(text = "源码与问题反馈：${Disclaimer.REPO_URL}", style = texts.caption)
        Spacer(Modifier.height(AppTheme.space.lg))
    }
}

/**
 * 版本闸门：启动时查一次 GitHub Releases，**有新版就拦住不让用**。
 *
 * 两道护栏都在 [com.studykit.data.UpdateChecker] 里：取不到版本号一律放行、
 * 且必须是带 APK 附件的 Release 才算数。所以这个拦截的失效方向是"少拦"而不是"误拦"——
 * 拦错了会把所有人锁在门外，少拦只是少一次提醒。
 *
 * 检查只做一次（`LaunchedEffect(Unit)`），失败不重试：这是启动路径，
 * 让人等它没有意义；真要手动确认可以在设置页点。
 */
@Composable
fun UpgradeGate(versionName: String = BuildConfig.VERSION_NAME) {
    val context = LocalContext.current
    var result by remember { mutableStateOf<UpdateChecker.UpdateCheck?>(null) }
    LaunchedEffect(Unit) {
        result = withContext(Dispatchers.IO) { UpdateChecker.check(versionName) }
    }
    val newer = result as? UpdateChecker.UpdateCheck.Newer ?: return
    Dialog(
        onDismissRequest = { /* 不可关闭：这一档就是"必须去下载" */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        AppCard(modifier = Modifier.padding(AppTheme.space.pageH)) {
            Text(text = "需要更新到 ${newer.version}", style = AppTheme.texts.pageTitle)
            Spacer(Modifier.height(AppTheme.space.sm))
            Text(
                text = "当前版本 ${BuildConfig.VERSION_NAME} 已经不再支持，继续使用可能丢数据或出错。" +
                    "请下载新版安装（覆盖安装即可，数据不动）。",
                style = AppTheme.texts.body,
            )
            Spacer(Modifier.height(AppTheme.space.md))
            AppButton(
                text = "去下载",
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(newer.releaseUrl)),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(AppTheme.space.sm))
            TextButton(
                onClick = { (context as? Activity)?.finish() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = "退出", style = AppTheme.texts.caption)
            }
        }
    }
}
