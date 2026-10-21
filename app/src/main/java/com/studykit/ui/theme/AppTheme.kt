package com.studykit.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.studykit.data.AppSettings

/**
 * 双主题颜色令牌：唯一取色入口。
 *
 * 注意：[StudyKitTheme] 只**部分**覆写了 Material3 `ColorScheme`（仅 primary/background/surface/outline 等
 * 少数字段，secondary、container 系列仍走 M3 默认推导）。因此组件与页面的填充色/文本色必须**显式**取自
 * `AppTheme.colors.*`，不要依赖 `MaterialTheme.colorScheme.*`，否则夜间主题会拿到未覆写的默认色。
 *
 * `accentInk` = accent 的「墨水」变体，专用于纯文字/图标场景（描边按钮文案、空态图标等），
 * 因为浅色主题下 accent(#00A78E) 在白底只有 3.04:1，不满足正文文本 AA。
 *
 * `onAccent` = 实底按钮上的文字色：浅色为纯白（accentInk `#00735F` 上 5.81:1），夜间为近黑墨
 * `#07110E`（accent `#3ECBB6` 上 9.51:1，白字只有约 2.0:1）。注意 `card` **随主题变化**
 * （light `#FFFFFF` / dark `#15181D`），不能再被当作「白」来给文字着色。
 *
 * ## 墨水令牌（T15 批次）
 *
 * `successInk / goldInk / warningInk` 是三个品牌色的**文字专用**变体，规则与 `accentInk` 相同：
 * 品牌色本身只用于**填充、描边、色点、彩带粒子**这类非文本元素（浅色卡上 success 2.22:1、
 * gold 1.79:1、warning 3.07:1，都不够文本 AA 的 4.5:1）；颜色一旦充当**文字**（含顶替文字槽位的
 * 图形，如刷题选项字母位上的 ✓/✗），必须取对应的 `*Ink`。金色额外覆盖达成态的**细环**
 * （`gold` 作环在浅色卡面几乎看不见）。
 * 浅色压深（`#1A7A3C` / `#8A5A00` / `#C0332C`，实测卡面 5.39 / 5.93 / 5.60:1），
 * 夜间沿用提亮后的品牌色（暖黑卡面上已是 7.84 / 10.12 / 6.12:1，再压深反而看不清）。
 * **不在本批范围内**的仍是品牌色：soft 容器里与文案并排的装饰性图标（入口卡图标、图例点等），
 * 它们旁边就有等价文字，按装饰元素处理。
 *
 * 需要「色块 + 文字」时优先 **soft 底 + ink 字**（`successSoft`+`successInk`、`warningSoft`+`warningInk`、
 * `goldSoft`+`goldInk`，即 T10 药丸那一套），**不再增设** `onSuccess/onWarning`：原先「白字/白图压
 * 实底」的三处（打卡勾、滑动角标、答案选择器）全部改成这一套，「白字压实底」只保留 accent 一处
 * （`accentInk` + `onAccent`，见 [com.studykit.ui.components.AppButton]），其余实底配白字都会在浅色主题掉到 2.2:1 左右。
 *
 * `heatIdle` = 热力图「没打卡」格：卡面上要看得见但不抢戏（装饰性元素，只求可分辨、不套 AA）。
 * `lightbox` = 全屏看图的取景框黑，两主题同为纯黑（照片对比不受界面底色影响），入库只为让页面里
 * 不再出现硬编码色值。
 */
@Immutable
data class AppColors(
    val background: Color, val card: Color,
    val primaryText: Color, val secondaryText: Color,
    val accent: Color, val accentSoft: Color, val accentInk: Color, val onAccent: Color,
    val success: Color, val successSoft: Color, val successInk: Color,
    val gold: Color, val goldSoft: Color, val goldInk: Color,
    val warning: Color, val warningSoft: Color, val warningInk: Color,
    val divider: Color,
    val heatIdle: Color, val lightbox: Color,
    /**
     * 玻璃材质的两枚底色（`ui/material/Glass.kt` 用）。它们**不是**文本色：
     * 玻璃只上浮层，浮层上的文字仍走 `primaryText` / `secondaryText` / `*Ink` 那一套。
     */
    val glassTint: Color, val glassSpec: Color,
)

val LightColors = AppColors(
    background = Palette.LightBackground, card = Palette.LightCard,
    primaryText = Palette.LightPrimaryText, secondaryText = Palette.LightSecondaryText,
    accent = Palette.LightAccent, accentSoft = Palette.LightAccent.copy(alpha = 0.10f),
    accentInk = Palette.LightAccentInk, onAccent = Palette.LightOnAccent,
    success = Palette.LightSuccess, successSoft = Palette.LightSuccess.copy(alpha = 0.10f),
    successInk = Palette.LightSuccessInk,
    gold = Palette.LightGold, goldSoft = Palette.LightGold.copy(alpha = 0.14f),
    goldInk = Palette.LightGoldInk,
    warning = Palette.LightWarning, warningSoft = Palette.LightWarning.copy(alpha = 0.10f),
    warningInk = Palette.LightWarningInk,
    divider = Palette.LightDivider,
    heatIdle = Palette.LightHeatIdle, lightbox = Palette.LightLightbox,
    glassTint = Palette.LightGlassTint, glassSpec = Palette.LightGlassSpec,
)

val DarkColors = AppColors(
    background = Palette.DarkBackground, card = Palette.DarkCard,
    primaryText = Palette.DarkPrimaryText, secondaryText = Palette.DarkSecondaryText,
    accent = Palette.DarkAccent, accentSoft = Palette.DarkAccent.copy(alpha = 0.16f),
    accentInk = Palette.DarkAccentInk, onAccent = Palette.DarkOnAccent,
    success = Palette.DarkSuccess, successSoft = Palette.DarkSuccess.copy(alpha = 0.16f),
    successInk = Palette.DarkSuccessInk,
    gold = Palette.DarkGold, goldSoft = Palette.DarkGold.copy(alpha = 0.18f),
    goldInk = Palette.DarkGoldInk,
    warning = Palette.DarkWarning, warningSoft = Palette.DarkWarning.copy(alpha = 0.16f),
    warningInk = Palette.DarkWarningInk,
    divider = Palette.DarkDivider,
    heatIdle = Palette.DarkHeatIdle, lightbox = Palette.DarkLightbox,
    glassTint = Palette.DarkGlassTint, glassSpec = Palette.DarkGlassSpec,
)

@Immutable
data class AppTexts(
    val largeTitle: TextStyle, val pageTitle: TextStyle, val cardTitle: TextStyle,
    val body: TextStyle, val aux: TextStyle, val caption: TextStyle,
    val statValue: TextStyle, val heroNumber: TextStyle,
)

/**
 * 字号阶梯（v3.2 微调）：标题收紧字距、正文降半档、数字统一等宽（`tnum`）——
 * 等宽数字让「3 / 8」「1165」这类读数在换值时不左右跳，也是这套界面"精密"观感的来源之一。
 * 只动视觉，不动任何文案；正文从 17 收到 16 是为了让卡片里的行距更从容。
 */
fun buildAppTexts(c: AppColors): AppTexts = AppTexts(
    largeTitle = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp, color = c.primaryText),
    pageTitle = TextStyle(fontSize = 23.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp, color = c.primaryText),
    cardTitle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.1).sp, color = c.primaryText),
    body = TextStyle(fontSize = 16.sp, color = c.primaryText),
    aux = TextStyle(fontSize = 14.sp, color = c.primaryText),
    caption = TextStyle(fontSize = 13.sp, letterSpacing = 0.1.sp, color = c.secondaryText),
    statValue = TextStyle(
        fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp,
        fontFeatureSettings = "tnum", color = c.primaryText,
    ),
    heroNumber = TextStyle(
        fontSize = 46.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.2).sp,
        fontFeatureSettings = "tnum", color = c.primaryText,
    ),
)

@Immutable
data class AppThemeVals(
    val colors: AppColors,
    val texts: AppTexts,
    /**
     * 用户本地设置随主题一起下发。放这里而不是另开一个 CompositionLocal，
     * 是因为材质（`AppTheme.glass`）与动效（`AppTheme.settings.reduceMotion`）都必须与配色同时生效，
     * 分两个 Local 就会有两份"谁是最新"的问题。
     */
    val settings: AppSettings = AppSettings(),
)

val LocalAppTheme = staticCompositionLocalOf {
    AppThemeVals(LightColors, buildAppTexts(LightColors))
}

object AppTheme {
    private val current: AppThemeVals
        @Composable @ReadOnlyComposable get() = LocalAppTheme.current
    val colors: AppColors
        @Composable @ReadOnlyComposable get() = current.colors
    val texts: AppTexts
        @Composable @ReadOnlyComposable get() = current.texts

    /** 用户本地设置（主题之外的全部偏好），见 [AppThemeVals.settings] */
    val settings: AppSettings
        @Composable @ReadOnlyComposable get() = current.settings

    // ── 度量令牌：与主题无关的 dp 常量，取 `AppTheme.space.* / .radius.* / .elevation.*` ──
    // 与 colors/texts 不同，这些是普通 `val`（不需要 CompositionLocal），任何位置都能直接读。
    // 动效时长/缓动不在此处 —— 那份规格统一由 `ui/motion/MotionSpec.kt` 提供。

    /** 间距：8dp 网格 + 页面/卡片两处专用内边距 */
    object space {
        val xs: Dp = 4.dp
        val sm: Dp = 8.dp
        val md: Dp = 16.dp
        val lg: Dp = 24.dp
        val xl: Dp = 32.dp

        /** 页面左右留白（所有屏的根 Column 统一用） */
        val pageH: Dp = 20.dp

        /** 卡片内边距（[com.studykit.ui.components.AppCard] 内容列的 padding） */
        val card: Dp = 16.dp
    }

    /**
     * 圆角：`md`/`lg` 即 `MaterialTheme.shapes` 的 medium/large（见 Theme.kt，这条口径不变），
     * `xl` 只用于胶囊按钮。
     */
    object radius {
        /** 状态药丸（[com.studykit.ui.components.AppPill]）：介于 4dp 误用与 md 之间的一档 */
        val sm: Dp = 8.dp

        /** 常规卡片与**可点** chip（不可点的状态药丸见 [sm]） */
        val md: Dp = 16.dp

        /** 大图、图片卡 */
        val lg: Dp = 20.dp

        /** 胶囊按钮专用，恒为 [size.pill] 高度的一半 */
        val xl: Dp = 26.dp
    }

    /** 尺寸：被圆角等其它令牌引用的固定度量 */
    object size {
        /** 主/次操作按钮与打卡圆钮的高度（`radius.xl` 即其一半） */
        val pill: Dp = 52.dp
    }

    /** 阴影 */
    object elevation {
        /** 无抬升：只靠描边分层的容器（如 [com.studykit.ui.components.StatTile]）取这档，不写裸 0.dp */
        val none: Dp = 0.dp

        /** 一丝抬升：[com.studykit.ui.components.AppCard] 那类「柔光描边已经负责分层」的卡面 */
        val hairline: Dp = 1.dp

        /** 卡片与浮层的默认抬升 */
        val low: Dp = 2.dp
    }

    /**
     * 玻璃材质度量（`ui/material/Glass.kt`）。与主题无关，故与 `space`/`radius` 一样是普通 `val`；
     * 颜色部分不在这里，在 `AppTheme.colors.glassTint` / `.glassSpec`。
     *
     * `tintAlphaSoft` 的 0.78 是**下限**而不是审美选择：低于它，浮层后面的内容会直接参与
     * 浮层上那行 13sp 标签的对比度计算，而本仓的 AA 数字全是按实底卡面算的。
     * STRONG 只把底压得更实（0.92），不改高光与描边 —— 浓度的差别应当是"透不透"，不是"亮不亮"。
     *
     * v3.2 去掉了流动亮带：底部导航与打卡弹层上的那道绿光是整屏最像"廉价高光"的东西。
     * 现在玻璃只有三层（中性底、顶部白高光、一圈细边缘），不需要任何滚动/按压输入。
     */
    object glass {
        val tintAlphaSoft: Float = 0.78f
        val tintAlphaStrong: Float = 0.92f

        /** 顶部入射光的强度（往下约 45% 处淡到不可见；纯白，不再带品牌色） */
        val glossTopAlpha: Float = 0.10f

        /** 内描边：上缘亮、下缘几乎消失；像一片玻璃的切割边，不像一圈描边 */
        val edgeAlphaLight: Float = 0.16f
        val edgeAlphaDark: Float = 0.04f
        val edgeWidth: Dp = 1.dp
    }
}
