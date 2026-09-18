# StudyKit v2.0 · M1 视觉与动效升级 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 StudyKit 四模块升级为「暖纸感日/夜双主题 + spring 动效体系」，含跟手 3D 翻面/滑动评价背单词卡片与庆祝动效，CI 产出 debug APK 供真机验证。

**Architecture:** 新增 `AppColors/AppTexts` CompositionLocal 双主题令牌层（旧 `DesignTokens` 颜色暂共存、最后一任务删除）；`MotionSpec` 集中 spring 规格；共享视觉组件（RingGauge/ConfettiBurst/StaggeredIn/热力图）Canvas 自绘零素材；逐模块迁移到新令牌。

**Tech Stack:** Kotlin 2.0.21, Compose BOM 2024.12.01 (Material3 1.3.1), Room 2.6.1, JUnit4（JVM 单测）。

**Spec:** `docs/superpowers/specs/2026-09-18-studykit-v2-design.md`（本计划实现其第 1–4 节 M1 部分；第 5 节录入自动化 = M2，待 M1 真机验收后另出计划）

## Global Constraints

- 仓库：`E:\项目\studykit`（GitHub `wuliao00/StudyKit`），工作分支 `feat/v2-visual-motion`（T1 创建）。
- **本机无 JDK/Android SDK，禁止本地跑 gradlew。** 每任务验证 = `git push` 后 `gh run list --branch feat/v2-visual-motion --limit 1` 取 run id → `gh run watch <id>`（或稍后 `gh run view <id>`），期望 lint+testDebugUnitTest+assembleDebug 全绿。gh 网络异常时按既有经验检查代理。
- 颜色/文字样式禁止硬编码，也禁止再用 `DesignTokens` 的颜色/TextStyle（迁移期共存，T15 删除）；一律 `AppTheme.colors.*` / `AppTheme.texts.*`。间距/圆角/字号度量仍引用 `DesignTokens` 静态值。
- 动效参数一律引用 `MotionSpec`；不引入任何新依赖（唯一例外 T16 的 `androidx.profileinstaller`）。零图片/字体素材。
- 不改数据库语义与业务规则；Room 不动 schema（M1 无迁移）。
- 提交信息中文、conventional 前缀，与仓库现有风格一致；每任务一个或多个小提交。
- 不合并、不推 `main`、不发 Release——M1 完成后由用户真机验收决定。

## 统一迁移映射（多任务引用，缩写「令牌迁移」）

对任务列出的每个文件执行：先 `Read` 全文，再做替换（左→右），语义不变仅换令牌来源：

| 旧 | 新 |
|---|---|
| `DesignTokens.Background/Card/PrimaryText/SecondaryText/Accent/Success/Gold/Warning/Divider` | `AppTheme.colors.background/card/primaryText/secondaryText/accent/success/gold/warning/divider` |
| `.copy(alpha = 0.10f/0.12f/0.15f)` 跟在 Accent/Success/Gold 后 | `AppTheme.colors.accentSoft/successSoft/goldSoft` |
| `DesignTokens.LargeTitle/PageTitle/CardTitle/Body/Auxiliary/Caption` | `AppTheme.texts.largeTitle/pageTitle/cardTitle/body/aux/caption` |
| `.copy(color = DesignTokens.SecondaryText)` 等样式内覆写颜色 | 直接用对应预设样式或 `color = AppTheme.colors.secondaryText` |
| `tween(DesignTokens.AnimDurationMs, easing = DesignTokens.AnimEasing)` | `MotionSpec.snap`（float）或 `tween(MotionSpec.FadeMs)`（alpha/颜色），逐处选择 |

替换后如出现未使用 import 一并清理。`DesignTokens` 的间距/圆角/`PageHorizontalPadding`/`CardPadding` 引用保持不变。

## 文件结构总览

```
app/src/main/java/com/studykit/
  ui/theme/   AppTheme.kt(新) Palette.kt(新) Theme.kt(改) DesignTokens.kt(改→T15 再改)
  ui/motion/  MotionSpec.kt(新，含 rememberPressScale/StaggeredIn)
  ui/components/  AppCard/AppButton/StatTile/EmptyState/AppTextField/AppMultilineTextField(改)
                  RingGauge.kt(新) ConfettiBurst.kt(新) HeatmapWeeks.kt(新)
  ui/habit/   HeatmapLogic.kt(新) HabitListScreen/HabitCalendarScreen/GlobalCalendarScreen/HabitCreateScreen/CheckInSheet(改)
  ui/nav/     AppNav.kt(改)
  ui/study/   StudyStreak.kt(新) StudyHomeScreen/CardStudyScreen/QuizScreen/WordListScreen/
              WordCreateScreen/QuestionCreateScreen/StudyViewModel(改)
  ui/book/    BookShelfScreen/BookDetailScreen/BookEditScreen/ExcerptEditScreen/ReviewEditScreen(改) BookSpine.kt(新)
  ui/mistake/ MistakeListScreen/MistakeDetailScreen/MistakeCaptureScreen(改)
app/src/test/java/com/studykit/ui/study/StudyStreakTest.kt(新)
app/src/test/java/com/studykit/ui/habit/HeatmapCellsTest.kt(新)
app/src/test/java/com/studykit/ui/book/BookSpineTest.kt(新)
app/src/main/baseline-prof.txt(新,T16)  .github/workflows/ci.yml(改,T1)
```

---

### Task 1: 双主题令牌层 + Theme + CI 产物上传

**Files:**
- Create: `app/src/main/java/com/studykit/ui/theme/Palette.kt`, `app/src/main/java/com/studykit/ui/theme/AppTheme.kt`
- Modify: `app/src/main/java/com/studykit/ui/theme/Theme.kt`, `.github/workflows/ci.yml`
- Branch: 基于 `main`(commit 8f11d57) 创建 `feat/v2-visual-motion`

**Interfaces:**
- Produces: `AppTheme.colors: AppColors`、`AppTheme.texts: AppTexts`（@Composable getter）；`AppColors(background,card,primaryText,secondaryText,accent,accentSoft,success,successSoft,gold,goldSoft,warning,warningSoft,divider)`；`AppTexts(largeTitle,pageTitle,cardTitle,body,aux,caption,statValue,heroNumber)`；`LightColors`/`DarkColors` 常量。后续所有任务依赖。
- Produces: CI 上传 debug APK 步骤（artifact 名 `StudyKit-debug`）。

- [ ] **Step 1: 建分支** `git checkout -b feat/v2-visual-motion`

- [ ] **Step 2: 写 Palette.kt**（仅颜色常量，供 AppTheme 组装）

```kotlin
package com.studykit.ui.theme

import androidx.compose.ui.graphics.Color

/** 暖纸感色板：数值来自 spec §1，禁止在页面直接使用，统一经 AppTheme.colors */
object Palette {
    // 浅色
    val LightBackground = Color(0xFFFAF8F2)
    val LightCard = Color(0xFFFFFFFF)
    val LightPrimaryText = Color(0xFF1F1D1A)
    val LightSecondaryText = Color(0xFF8A857C)
    val LightAccent = Color(0xFF00A78E)
    val LightSuccess = Color(0xFF34C759)
    val LightGold = Color(0xFFFFB300)
    val LightWarning = Color(0xFFFF5A52)
    val LightDivider = Color(0xFFE7E2D8)
    // 深色（暖黑，非纯黑）
    val DarkBackground = Color(0xFF1C1B18)
    val DarkCard = Color(0xFF26241F)
    val DarkPrimaryText = Color(0xFFF0EDE6)
    val DarkSecondaryText = Color(0xFF9A958B)
    val DarkAccent = Color(0xFF33C7AB)
    val DarkSuccess = Color(0xFF4CD07D)
    val DarkGold = Color(0xFFFFC94D)
    val DarkWarning = Color(0xFFFF7A73)
    val DarkDivider = Color(0xFF3A372F)
}
```

- [ ] **Step 3: 写 AppTheme.kt**

```kotlin
package com.studykit.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Immutable
data class AppColors(
    val background: Color, val card: Color,
    val primaryText: Color, val secondaryText: Color,
    val accent: Color, val accentSoft: Color,
    val success: Color, val successSoft: Color,
    val gold: Color, val goldSoft: Color,
    val warning: Color, val warningSoft: Color,
    val divider: Color,
)

val LightColors = AppColors(
    background = Palette.LightBackground, card = Palette.LightCard,
    primaryText = Palette.LightPrimaryText, secondaryText = Palette.LightSecondaryText,
    accent = Palette.LightAccent, accentSoft = Palette.LightAccent.copy(alpha = 0.10f),
    success = Palette.LightSuccess, successSoft = Palette.LightSuccess.copy(alpha = 0.10f),
    gold = Palette.LightGold, goldSoft = Palette.LightGold.copy(alpha = 0.14f),
    warning = Palette.LightWarning, warningSoft = Palette.LightWarning.copy(alpha = 0.10f),
    divider = Palette.LightDivider,
)

val DarkColors = AppColors(
    background = Palette.DarkBackground, card = Palette.DarkCard,
    primaryText = Palette.DarkPrimaryText, secondaryText = Palette.DarkSecondaryText,
    accent = Palette.DarkAccent, accentSoft = Palette.DarkAccent.copy(alpha = 0.16f),
    success = Palette.DarkSuccess, successSoft = Palette.DarkSuccess.copy(alpha = 0.16f),
    gold = Palette.DarkGold, goldSoft = Palette.DarkGold.copy(alpha = 0.18f),
    warning = Palette.DarkWarning, warningSoft = Palette.DarkWarning.copy(alpha = 0.16f),
    divider = Palette.DarkDivider,
)

@Immutable
data class AppTexts(
    val largeTitle: TextStyle, val pageTitle: TextStyle, val cardTitle: TextStyle,
    val body: TextStyle, val aux: TextStyle, val caption: TextStyle,
    val statValue: TextStyle, val heroNumber: TextStyle,
)

fun buildAppTexts(c: AppColors): AppTexts = AppTexts(
    largeTitle = TextStyle(34.sp, FontWeight.Bold, color = c.primaryText),
    pageTitle = TextStyle(22.sp, FontWeight.SemiBold, color = c.primaryText),
    cardTitle = TextStyle(17.sp, FontWeight.Medium, color = c.primaryText),
    body = TextStyle(17.sp, color = c.primaryText),
    aux = TextStyle(15.sp, color = c.primaryText),
    caption = TextStyle(13.sp, color = c.secondaryText),
    statValue = TextStyle(34.sp, FontWeight.Bold, letterSpacing = (-0.5).sp, color = c.primaryText),
    heroNumber = TextStyle(40.sp, FontWeight.Bold, letterSpacing = (-1).sp, color = c.primaryText),
)

@Immutable
data class AppThemeVals(val colors: AppColors, val texts: AppTexts)

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
}
```

注：`TextStyle(fontSize, fontWeight, letterSpacing, color)` 全命名参数按上例书写（`TextStyle(34.sp, FontWeight.Bold, color = ...)` 前两个为位置参数，合法）。

- [ ] **Step 4: 重写 Theme.kt 支持双主题 + 状态栏图标色**

```kotlin
package com.studykit.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private fun scheme(dark: Boolean, c: AppColors) = if (dark) darkColorScheme(
    primary = c.accent, onPrimary = c.card,
    background = c.background, onBackground = c.primaryText,
    surface = c.card, onSurface = c.primaryText,
    surfaceVariant = c.background, outline = c.divider,
    error = c.warning, onError = c.card,
) else lightColorScheme(
    primary = c.accent, onPrimary = c.card,
    background = c.background, onBackground = c.primaryText,
    surface = c.card, onSurface = c.primaryText,
    surfaceVariant = c.background, outline = c.divider,
    error = c.warning, onError = c.card,
)

@Composable
fun StudyKitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val vals = remember(colors) { AppThemeVals(colors, buildAppTexts(colors)) }
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let {
            WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    CompositionLocalProvider(LocalAppTheme provides vals) {
        MaterialTheme(
            colorScheme = scheme(darkTheme, colors),
            shapes = Shapes(
                medium = RoundedCornerShape(DesignTokens.CornerRadius),
                large = RoundedCornerShape(DesignTokens.CornerRadiusLg),
            ),
            content = content,
        )
    }
}
```

同时把 `DesignTokens.CornerRadius` 改为 `16.dp`、`CornerRadiusLg` 改为 `20.dp`（卡片升圆角）。**本任务不删除 DesignTokens 其余内容**（迁移期共存）。

- [ ] **Step 5: ci.yml 末尾追加产物上传步骤**（`- run: ./gradlew ...` 之后）

```yaml
      - name: Upload debug APK
        uses: actions/upload-artifact@v4
        with:
          name: StudyKit-debug
          path: app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 6: 提交并推送验证**

```bash
git add -A && git commit -m "feat(theme): 暖纸感日/夜双主题令牌层（AppColors/AppTexts）+ CI 上传 debug APK"
git push -u origin feat/v2-visual-motion && gh run list --branch feat/v2-visual-motion --limit 1
```
Expected: run 绿；Actions 页可见 StudyKit-debug 产物。

---

### Task 2: MotionSpec 集中动效规格

**Files:**
- Create: `app/src/main/java/com/studykit/ui/motion/MotionSpec.kt`

**Interfaces:**
- Produces: `MotionSpec.press/snap/flip/ring: Spring<Float>`；`MotionSpec.dpSpring(): Spring<Dp>`；`MotionSpec.flyOut(): Spring<Float>`；常量 `FadeMs=220`、`StaggerMs=40`；转场工厂 `navEnter/navExit/navPopEnter/navPopExit: Enter/ExitTransition`；`rememberPressScale(interactionSource): State<Float>`；`StaggeredIn(index, modifier, content)`。

- [ ] **Step 1: 写 MotionSpec.kt**

```kotlin
package com.studykit.ui.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** 全局 spring 动效规格：spring 逐帧跟随 vsync，高刷屏按 90/120Hz 渲染 */
object MotionSpec {
    const val FadeMs = 220
    const val StaggerMs = 40L

    val press = spring<Float>(dampingRatio = 0.55f, stiffness = 420f)
    val snap = spring<Float>(dampingRatio = 0.72f, stiffness = 380f)
    val flip = spring<Float>(dampingRatio = 0.68f, stiffness = 240f)
    val ring = spring<Float>(dampingRatio = 0.85f, stiffness = 160f)

    fun dpSpring() = spring<androidx.compose.ui.unit.Dp>(
        dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 600f,
    )
    fun flyOut() = spring<Float>(dampingRatio = 0.62f, stiffness = 550f)

    fun navEnter(): EnterTransition =
        slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = 240f)) { it / 3 } +
            fadeIn(tween(FadeMs))
    fun navExit(): ExitTransition =
        slideOutHorizontally(spring(dampingRatio = 0.9f, stiffness = 300f)) { it / 4 } +
            fadeOut(tween(160))
    fun navPopEnter(): EnterTransition =
        slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = 240f)) { -it / 4 } +
            fadeIn(tween(FadeMs))
    fun navPopExit(): ExitTransition =
        slideOutHorizontally(spring(dampingRatio = 0.9f, stiffness = 300f)) { it / 3 } +
            fadeOut(tween(160))
}

/** 按压回弹缩放：0.96 按下 → spring 恢复，供按钮/卡片/导航项复用 */
@Composable
fun rememberPressScale(source: MutableInteractionSource): State<Float> {
    val pressed by source.collectIsPressedAsState()
    return animateFloatAsState(if (pressed) 0.96f else 1f, MotionSpec.press, label = "pressScale")
}

/** 列表/卡片错峰入场：淡入 + 24dp 上移回弹；index 提供逐级延迟 */
@Composable
fun StaggeredIn(index: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shift = with(LocalDensity.current) { 24.dp.toPx() }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (index > 0) delay(index * MotionSpec.StaggerMs)
        shown = true
    }
    val p by animateFloatAsState(if (shown) 1f else 0f,
        spring(dampingRatio = 0.78f, stiffness = 260f), label = "stagger")
    Box(
        modifier.graphicsLayer {
            alpha = (p * 2f).coerceIn(0f, 1f)
            translationY = (1f - p) * shift
        },
        content = { content() },
    )
}
```

（import：`LaunchedEffect/mutableStateOf/getValue/setValue`、`kotlinx.coroutines.delay`、`Box`、`Modifier`、`LocalDensity`。）

- [ ] **Step 2: 提交推送，CI 绿** `git commit -am "feat(motion): MotionSpec spring 动效规格集中定义" && git push`

---

### Task 3: 基础组件迁移新令牌（胶囊按钮/大数字磁贴/卡片）

**Files:**
- Modify: `ui/components/AppCard.kt, AppButton.kt, StatTile.kt, EmptyState.kt, SectionHeader.kt, AppTextField.kt, AppMultilineTextField.kt`

**Interfaces:**
- Consumes: Task 1 `AppTheme`；Task 2 `rememberPressScale`。
- Produces: 签名不变（`AppButton(text, onClick, modifier, secondary, enabled)` 等），仅视觉升级。

- [ ] **Step 1: AppButton 重写**（胶囊形 + 按压 spring）

```kotlin
@Composable
fun AppButton(
    text: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, secondary: Boolean = false, enabled: Boolean = true,
) {
    val colors = AppTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val scale by rememberPressScale(interaction)
    if (secondary) {
        OutlinedButton(
            onClick = onClick, enabled = enabled, interactionSource = interaction,
            modifier = modifier.fillMaxWidth().height(52.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale },
            shape = RoundedCornerShape(DesignTokens.CornerRadiusXl),
            border = BorderStroke(1.5.dp, colors.accent),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = colors.accent, containerColor = Color.Transparent),
        ) { Text(text, style = AppTheme.texts.body.copy(fontWeight = FontWeight.Medium)) }
    } else {
        Button(
            onClick = onClick, enabled = enabled, interactionSource = interaction,
            modifier = modifier.fillMaxWidth().height(52.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale },
            shape = RoundedCornerShape(DesignTokens.CornerRadiusXl),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accent, contentColor = colors.card),
        ) { Text(text, style = AppTheme.texts.body.copy(fontWeight = FontWeight.Medium)) }
    }
}
```

在 `DesignTokens` 追加静态值 `val CornerRadiusXl: Dp = 26.dp`（胶囊）。

- [ ] **Step 2: AppCard 重写**（20dp 圆角 + 柔光描边降阴影）

```kotlin
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = AppTheme.colors
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(DesignTokens.CornerRadiusLg),
        color = colors.card,
        border = BorderStroke(1.dp, colors.divider.copy(alpha = 0.6f)),
        shadowElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(DesignTokens.CardPadding)) { content() }
    }
}
```

- [ ] **Step 3: StatTile 重写**（大数字 statValue）

```kotlin
@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(DesignTokens.CornerRadiusLg),
        color = colors.card,
        border = BorderStroke(1.dp, colors.divider.copy(alpha = 0.6f)),
        shadowElevation = 0.dp,
    ) {
        Column(modifier = Modifier.padding(DesignTokens.CardPadding)) {
            Text(text = value, style = AppTheme.texts.statValue)
            Spacer(Modifier.height(DesignTokens.SpacingXs))
            Text(text = label, style = AppTheme.texts.caption)
        }
    }
}
```

- [ ] **Step 4: EmptyState/SectionHeader/两个输入框** 执行「令牌迁移」映射；EmptyState 图标容器改 72dp、`colors.accentSoft` 底。

- [ ] **Step 5: 提交推送，CI 绿** `git commit -m "feat(components): 基础组件对齐新令牌（胶囊按钮/大数字磁贴/柔边卡片）" && git push`

---

### Task 4: 视觉套件组件 RingGauge / ConfettiBurst

**Files:**
- Create: `ui/components/RingGauge.kt`, `ui/components/ConfettiBurst.kt`

**Interfaces:**
- Produces: `RingGauge(progress: Float, modifier: Modifier, strokeWidth: Dp = 10.dp, color: Color = accent, trackColor: Color = divider)`；`ConfettiBurst(trigger: Any?, modifier: Modifier, particleCount: Int = 56, durationMillis: Int = 1100)`——`trigger` 变化即重播一次。Task 7/8/9/11 使用。

- [ ] **Step 1: RingGauge.kt**（spring 驱动的进度环，自 HabitListScreen 私有版泛化）

```kotlin
package com.studykit.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.studykit.ui.motion.MotionSpec
import com.studykit.ui.theme.AppTheme

@Composable
fun RingGauge(
    progress: Float,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 10.dp,
    color: Color = AppTheme.colors.accent,
    trackColor: Color = AppTheme.colors.divider,
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = MotionSpec.ring,
        label = "ringGauge",
    )
    Canvas(modifier) {
        val sw = strokeWidth.toPx()
        val inset = sw / 2f
        val arc = size.minDimension - sw
        val topLeft = Offset(inset, inset)
        val sz = Size(arc, arc)
        drawArc(color = trackColor, startAngle = 0f, sweepAngle = 360f,
            useCenter = false, topLeft = topLeft, size = sz, style = Stroke(sw))
        if (animated > 0f) {
            drawArc(color = color, startAngle = -90f, sweepAngle = 360f * animated,
                useCenter = false, topLeft = topLeft, size = sz,
                style = Stroke(sw, cap = StrokeCap.Round))
        }
    }
}
```

- [ ] **Step 2: ConfettiBurst.kt**（一次性粒子彩带，纯 Canvas 零素材）

```kotlin
package com.studykit.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.rotate
import com.studykit.ui.theme.AppTheme
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** trigger 变化即从中心爆发一次彩带；播放结束（t>=1）不再绘制 */
@Composable
fun ConfettiBurst(
    trigger: Any?,
    modifier: Modifier = Modifier,
    particleCount: Int = 56,
    durationMillis: Int = 1100,
) {
    val palette = listOf(
        AppTheme.colors.accent, AppTheme.colors.success,
        AppTheme.colors.gold, AppTheme.colors.warning,
    )
    val particles = remember(trigger) {
        val rnd = Random(((trigger?.hashCode() ?: 0) * 31L).toLong())
        List(particleCount) {
            val angle = rnd.nextFloat() * 6.2831853f
            Particle(
                v = 0.35f + rnd.nextFloat() * 0.75f, cosA = cos(angle), sinA = sin(angle),
                wf = 0.012f + rnd.nextFloat() * 0.016f, aspect = 0.45f + rnd.nextFloat() * 0.4f,
                g = 1.1f + rnd.nextFloat() * 0.9f, spin = (rnd.nextFloat() - 0.5f) * 14f,
                colorIndex = rnd.nextInt(palette.size), delay = rnd.nextFloat() * 0.12f,
            )
        }
    }
    val progress = remember(trigger) { Animatable(0f) }
    LaunchedEffect(trigger) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis, easing = FastOutSlowInEasing))
    }
    Canvas(modifier) {
        val t = progress.value
        if (t <= 0f || t >= 1f) return@Canvas
        val w = size.width; val h = size.height
        val cx = w / 2f; val cy = h * 0.35f
        particles.forEach { p ->
            val pt = ((t - p.delay) / (1f - p.delay)).coerceIn(0f, 1f)
            if (pt <= 0f) return@forEach
            val dist = p.v * pt * w
            val x = cx + p.cosA * dist
            val y = cy + p.sinA * dist * 0.8f + p.g * pt * pt * h * 0.4f
            val alpha = if (t > 0.7f) (1f - t) / 0.3f else 1f
            val pw = p.wf * w; val ph = pw * p.aspect
            rotate(degrees = p.spin * pt * 360f, pivot = Offset(x, y)) {
                drawRect(color = palette[p.colorIndex], alpha = alpha,
                    topLeft = Offset(x - pw / 2f, y - ph / 2f), size = Size(pw, ph))
            }
        }
    }
}

private class Particle(
    val v: Float, val cosA: Float, val sinA: Float, val wf: Float, val aspect: Float,
    val g: Float, val spin: Float, val colorIndex: Int, val delay: Float,
)
```

注：`Random` 种子表达式在 JVM 上对同一 trigger 稳定即可（仅用于打散粒子形态，无需跨设备一致）。

- [ ] **Step 3: 提交推送，CI 绿** `git commit -m "feat(components): RingGauge 进度环与 ConfettiBurst 彩带粒子（Canvas 自绘）" && git push`

---

### Task 5: 全局导航——转场动画 + 药丸底栏 + 令牌迁移

**Files:**
- Modify: `ui/nav/AppNav.kt`

**Interfaces:**
- Consumes: Task 1 `AppTheme`，Task 2 `MotionSpec`、`rememberPressScale`。

- [ ] **Step 1: NavHost 加转场**。在 `NavHost(...)` 参数中追加：

```kotlin
val tabRoutes = remember { Tabs.map { it.route }.toSet() }
NavHost(
    navController = navController,
    startDestination = Tab.Study.route,
    modifier = Modifier.padding(innerPadding),
    enterTransition = {
        if (targetState.destination.route in tabRoutes) fadeIn(tween(MotionSpec.FadeMs))
        else MotionSpec.navEnter()
    },
    exitTransition = {
        if (initialState.destination.route in tabRoutes) fadeOut(tween(MotionSpec.FadeMs))
        else MotionSpec.navExit()
    },
    popEnterTransition = {
        if (targetState.destination.route in tabRoutes) fadeIn(tween(MotionSpec.FadeMs))
        else MotionSpec.navPopEnter()
    },
    popExitTransition = {
        if (initialState.destination.route in tabRoutes) fadeOut(tween(MotionSpec.FadeMs))
        else MotionSpec.navPopExit()
    },
) { /* 子路由原样保留 */ }
```

- [ ] **Step 2: AppBottomBar 重写**：`containerColor = AppTheme.colors.card`、`tonalElevation = 0.dp`；每个 `NavigationBarItem` 传入 `interactionSource = remember(tab) { MutableInteractionSource() }`，图标外套 `Modifier.graphicsLayer { val s by rememberPressScale(interaction); scaleX = s; scaleY = s }`（在 item 组合层先取 `val scale by rememberPressScale(interaction)` 再进 graphicsLayer）；`colors` 用 `AppTheme.colors.accent/secondaryText/accentSoft`。
- [ ] **Step 3:** 删除文件底部未被使用的 `PlaceholderTab`（死代码）。对其余部分执行「令牌迁移」。
- [ ] **Step 4: 提交推送，CI 绿** `git commit -m "feat(nav): 全局 push/pop 转场与药丸底栏按压 spring" && git push`

---

### Task 6: 连续学习天数——纯逻辑 + 数据链路（TDD）

**Files:**
- Create: `ui/study/StudyStreak.kt`, `app/src/test/java/com/studykit/ui/study/StudyStreakTest.kt`
- Modify: `data/dao/WordDao.kt`（+`observeReviewTimestamps`）, `data/dao/PracticeDao.kt`（+`observeActivityTimestamps`）, `data/repository/WordRepository.kt`, `data/repository/QuestionRepository.kt`（透传；若无 practiceDao 引用则按 AppContainer 现状注入）, `ui/study/StudyViewModel.kt`

**Interfaces:**
- Produces: `StudyStreak.streakDays(timestampsMs: List<Long>, zone: ZoneId, today: LocalDate): Int`；`StudyHomeUiState` 新字段 `streakDays: Int = 0`、`todayDone: Int = 0`；`WordRepository.observeReviewTimestamps(): Flow<List<Long>>`；`QuestionRepository.observePracticeTimestamps(): Flow<List<Long>>`。Task 7 依赖。

- [ ] **Step 1: 写失败测试** `StudyStreakTest.kt`

```kotlin
package com.studykit.ui.study

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class StudyStreakTest {
    private val zone = ZoneOffset.UTC
    private fun ts(iso: String) = LocalDate.parse(iso).atStartOfDay(zone).toInstant().toEpochMilli()
    private val today = LocalDate.parse("2026-09-18")

    @Test fun `空数据 streak 为 0`() {
        assertEquals(0, StudyStreak.streakDays(emptyList(), zone, today))
    }
    @Test fun `今天与昨天学习连续计数`() {
        assertEquals(2, StudyStreak.streakDays(listOf(ts("2026-09-18"), ts("2026-09-17")), zone, today))
    }
    @Test fun `今天没学但昨天学了从昨天起算`() {
        assertEquals(1, StudyStreak.streakDays(listOf(ts("2026-09-17")), zone, today))
    }
    @Test fun `中间断档清零`() {
        assertEquals(0, StudyStreak.streakDays(listOf(ts("2026-09-15")), zone, today))
    }
    @Test fun `同一天多次只算一天`() {
        assertEquals(1, StudyStreak.streakDays(listOf(ts("2026-09-18"), ts("2026-09-18")), zone, today))
    }
}
```

- [ ] **Step 2: 实现 StudyStreak.kt**

```kotlin
package com.studykit.ui.study

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 连续学习天数：复习与刷题时间戳合并，从今天（或昨天，若今天未学）向前连数 */
object StudyStreak {
    fun streakDays(timestampsMs: List<Long>, zone: ZoneId, today: LocalDate): Int {
        if (timestampsMs.isEmpty()) return 0
        val days = timestampsMs.map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }.toHashSet()
        var cursor = if (days.contains(today)) today else today.minusDays(1)
        if (!days.contains(cursor)) return 0
        var count = 0
        while (days.contains(cursor)) { count++; cursor = cursor.minusDays(1) }
        return count
    }
}
```

- [ ] **Step 3: DAO/Repository 透传**

`WordDao.kt` 追加：
```kotlin
    /** 学习活跃日统计用：全部复习时间戳 */
    @Query("SELECT reviewed_at FROM word_reviews")
    fun observeReviewTimestamps(): Flow<List<Long>>
```
`PracticeDao.kt` 追加：
```kotlin
    @Query("SELECT at FROM practice_records")
    fun observeActivityTimestamps(): Flow<List<Long>>
```
`WordRepository` / `QuestionRepository`（后者按现状若无 practiceDao 成员，参照其现有构造注入方式补）各加一行透传，方法名同 Interfaces。

- [ ] **Step 4: StudyViewModel.homeState 扩展**（combine 改 4 流）

```kotlin
    val homeState: StateFlow<StudyHomeUiState> = combine(
        wordRepository.observeAll(),
        mistakeRepository.observeUnmasteredCount(),
        wordRepository.observeReviewTimestamps(),
        questionRepository.observePracticeTimestamps(),
    ) { words, unmastered, reviewTs, practiceTs ->
        val now = System.currentTimeMillis()
        val dayStart = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        val all = reviewTs + practiceTs
        StudyHomeUiState(
            dueCount = words.count { it.status != Word.STATUS_MASTERED && it.nextReviewAt <= now },
            totalCount = words.size,
            masteredCount = words.count { it.status == Word.STATUS_MASTERED },
            mistakeCount = unmastered,
            streakDays = StudyStreak.streakDays(all, java.time.ZoneId.systemDefault(), java.time.LocalDate.now()),
            todayDone = all.count { it >= dayStart },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StudyHomeUiState())
```
（原 `mistakeCount` 即 `observeUnmasteredCount()` 的 Int 值，直接赋值；时间戳量级为个人数据，M1 不做截断优化。）

- [ ] **Step 5: 提交推送，CI 绿（含新单测）** `git commit -m "feat(study): 连续学习天数纯逻辑与首页状态数据链路" && git push`

---

### Task 7: 学习首页——今日任务 hero 卡 + 火焰徽章 + 错峰入场

**Files:**
- Modify: `ui/study/StudyHomeScreen.kt`

**Interfaces:**
- Consumes: Task 6 `StudyHomeUiState.streakDays/todayDone`；Task 4 `RingGauge`；Task 2 `StaggeredIn`。

- [ ] **Step 1: 新增 hero 卡组件**（顶部 LargeTitle 行下方、StatTile 行上方）

```kotlin
@Composable
private fun TodayHeroCard(state: StudyHomeUiState, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val total = state.todayDone + state.dueCount
    val progress = if (total == 0) 1f else state.todayDone.toFloat() / total
    AppCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
                RingGauge(progress = progress, modifier = Modifier.fillMaxSize(),
                    strokeWidth = 9.dp,
                    color = if (progress >= 1f && total > 0) colors.gold else colors.accent)
                Text("${state.todayDone}", style = texts.statValue.copy(fontSize = 28.sp))
            }
            Spacer(Modifier.width(DesignTokens.SpacingLg))
            Column(Modifier.weight(1f)) {
                Text("今日任务", style = texts.caption)
                Spacer(Modifier.height(DesignTokens.SpacingXs))
                Text("${state.todayDone} / $total", style = texts.pageTitle)
                Spacer(Modifier.height(DesignTokens.SpacingSm))
                if (state.streakDays > 0) FlameBadge(days = state.streakDays)
            }
        }
    }
}

@Composable
private fun FlameBadge(days: Int) {
    val colors = AppTheme.colors
    var born by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { born = true }
    val s by animateFloatAsState(if (born) 1f else 0.4f, MotionSpec.snap, label = "flame")
    Box(
        Modifier.graphicsLayer { scaleX = s; scaleY = s }
            .clip(RoundedCornerShape(DesignTokens.CornerRadius))
            .background(colors.goldSoft)
            .padding(horizontal = DesignTokens.SpacingSm, vertical = 4.dp),
    ) {
        Text("🔥 连续 $days 天", style = AppTheme.texts.caption.copy(
            color = colors.gold, fontWeight = FontWeight.SemiBold))
    }
}
```

- [ ] **Step 2:** hero 卡插入标题行之下；三张 `EntryCard` 各包 `StaggeredIn(index = 0/1/2)`；图标容器底色改 `accentSoft/successSoft/warningSoft`；「录入」按钮区与统计磁贴执行「令牌迁移」；`EntryCard` 的 clickable 加 `indication = ripple`（默认即可）不改结构。
- [ ] **Step 3: 提交推送，CI 绿** `git commit -m "feat(study): 学习首页今日任务 hero 卡与火焰徽章" && git push`

---

### Task 8: 背单词卡片——3D 跟手翻面 + 滑动评价 + 结算庆祝

**Files:**
- Modify: `ui/study/CardStudyScreen.kt`

**Interfaces:**
- Consumes: `StudyViewModel.session/markKnown/markUnknown/startCardSession`（现状签名不变）；Task 4 `RingGauge`/`ConfettiBurst`；Task 2 `MotionSpec`。

- [ ] **Step 1: StudyCard 替换为 SwipeRatingCard**（整段新代码；`else ->` 分支传 `onGrade = { known -> if (known) viewModel.markKnown() else viewModel.markUnknown() }`）

```kotlin
@Composable
private fun SwipeRatingCard(
    word: Word,
    onGrade: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors; val texts = AppTheme.texts
    val density = LocalDensity.current.density
    var widthPx by remember { mutableIntStateOf(0) }
    var flipped by rememberSaveable(word.id) { mutableStateOf(false) }
    val flip by animateFloatAsState(if (flipped) 180f else 0f, MotionSpec.flip, label = "flip")
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val onGradeNow by rememberUpdatedState(onGrade)
    val threshold = { (widthPx * 0.33f).coerceAtLeast(1f) }
    val drag = offsetX.value / threshold()
    val knownA = drag.coerceIn(0f, 1f)
    val unknownA = (-drag).coerceIn(0f, 1f)
    val dragState = rememberDraggableState { dy -> scope.launch { offsetX.snapTo(offsetX.value + dy) } }

    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().weight(1f, fill = false)
                .onSizeChanged { widthPx = it.width }
                .graphicsLayer {
                    translationX = offsetX.value
                    rotationZ = drag * 6f
                    rotationY = flip
                    cameraDistance = 16f * density
                    scaleX = 1f - 0.04f * drag.coerceIn(-1f, 1f).let { it * it }
                    scaleY = scaleX
                }
                .draggable(state = dragState, orientation = Orientation.Horizontal,
                    onDragStopped = { velocity ->
                        val w = widthPx.toFloat().coerceAtLeast(1f)
                        when {
                            offsetX.value > threshold() || (velocity > 900f && offsetX.value > 0f) ->
                                scope.launch { offsetX.animateTo(w * 1.5f, MotionSpec.flyOut()); onGradeNow(true) }
                            offsetX.value < -threshold() || (velocity < -900f && offsetX.value < 0f) ->
                                scope.launch { offsetX.animateTo(-w * 1.5f, MotionSpec.flyOut()); onGradeNow(false) }
                            else -> scope.launch { offsetX.animateTo(0f, MotionSpec.snap) }
                        }
                    })
                .clip(RoundedCornerShape(DesignTokens.CornerRadiusLg))
                .clickable { flipped = !flipped },
        ) {
            CardFace(
                visible = flip < 90f, content = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(word.word, style = texts.largeTitle, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(DesignTokens.SpacingMd))
                        Text("点击翻面 · 右滑认识 左滑忘记", style = texts.caption)
                    }
                },
            )
            CardFace(
                visible = flip >= 90f, mirrored = true, content = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(word.meaning, style = texts.pageTitle, textAlign = TextAlign.Center)
                        if (word.example.isNotBlank()) {
                            Spacer(Modifier.height(DesignTokens.SpacingMd))
                            Text(word.example, style = texts.aux.copy(color = colors.secondaryText),
                                textAlign = TextAlign.Center)
                        }
                    }
                },
            )
            GradeBadge("认识 ✓", colors.success, knownA, Alignment.TopEnd, 12f)
            GradeBadge("✗ 忘记", colors.warning, unknownA, Alignment.TopStart, -12f)
        }
        Spacer(Modifier.height(DesignTokens.SpacingLg))
        Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.SpacingMd)) {
            OutlinedButton(onClick = { onGrade(false) }, modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(DesignTokens.CornerRadiusXl),
                border = BorderStroke(1.5.dp, colors.warning),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.warning),
            ) { Text("不认识", style = texts.body) }
            OutlinedButton(onClick = { onGrade(true) }, enabled = flipped,
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(DesignTokens.CornerRadiusXl),
                border = BorderStroke(1.5.dp, colors.success),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.success),
            ) { Text("认识", style = texts.body) }
        }
        Spacer(Modifier.height(DesignTokens.SpacingLg))
    }
}

@Composable
private fun CardFace(visible: Boolean, mirrored: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val density = LocalDensity.current.density
    AppCard(
        modifier = Modifier.fillMaxWidth()
            .graphicsLayer {
                alpha = if (visible) 1f else 0f
                if (mirrored) { rotationY = 180f; cameraDistance = 16f * density }
            },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = DesignTokens.SpacingXl * 1.5f),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

@Composable
private fun GradeBadge(text: String, color: Color, alpha: Float, align: Alignment, rotation: Float) {
    if (alpha <= 0f) return
    Box(
        Modifier.align(align).padding(DesignTokens.SpacingMd)
            .graphicsLayer { this.alpha = alpha; rotationZ = rotation }
            .clip(RoundedCornerShape(DesignTokens.CornerRadius))
            .background(color)
            .padding(horizontal = DesignTokens.SpacingMd, vertical = DesignTokens.SpacingSm),
    ) {
        Text(text, style = AppTheme.texts.cardTitle.copy(color = AppTheme.colors.card))
    }
}
```

（滑动语义与墨墨一致：左右滑均不要求先翻面即可评价，「认识」按钮仍要求已翻面。实现后如需对称限制，在右滑分支同样判断 `flipped` 即可。）

- [ ] **Step 2: 切卡动画**。`else ->` 分支外包：

```kotlin
AnimatedContent(
    targetState = state.index,
    transitionSpec = {
        (slideInHorizontally(spring(dampingRatio = 0.8f, stiffness = 260f)) { it / 3 } +
            fadeIn(tween(180)))
            .togetherWith(slideOutHorizontally { -it / 3 } + fadeOut(tween(120)))
    },
    modifier = Modifier.weight(1f).fillMaxWidth(),
    label = "cardSwitch",
) { idx ->
    val word = state.queue[idx]
    SwipeRatingCard(word = word, onGrade = { known ->
        if (known) viewModel.markKnown() else viewModel.markUnknown()
    })
}
```

- [ ] **Step 3: SessionSummary 庆祝化**：`knownCount`/`unknownCount` 用 `animateIntAsState(target, tween(700, easing = FastOutSlowInEasing))` 滚动；中间放 `RingGauge(known/(known+unknown))`；`Box` 顶层叠 `ConfettiBurst(trigger = knownCount + unknownCount, modifier = Modifier.matchParentSize())`；按钮改 `AppButton`。
- [ ] **Step 4:** 页面其余「令牌迁移」；顶部进度 `LinearProgressIndicator` 色改 `colors.accent`、track `colors.divider`。
- [ ] **Step 5: 提交推送，CI 绿** `git commit -m "feat(study): 背单词 3D 跟手翻面与滑动评价卡片 + 结算庆祝" && git push`

---

### Task 9: 刷题页——选项 spring 反馈 + 错误抖动 + 结果环形正确率

**Files:**
- Create: `ui/components/QuizOptionTile.kt`
- Modify: `ui/study/QuizScreen.kt`

**Interfaces:**
- Produces: `QuizOptionTile(optionText: String, index: Int, state: QuizOptionState, onClick: () -> Unit, modifier: Modifier)`；`enum QuizOptionState { Idle, Selected, Correct, Wrong }`。

- [ ] **Step 1: QuizOptionTile.kt**

```kotlin
package com.studykit.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.studykit.ui.motion.MotionSpec
import com.studykit.ui.motion.rememberPressScale
import com.studykit.ui.theme.AppTheme
import kotlin.math.PI
import kotlin.math.sin

enum class QuizOptionState { Idle, Selected, Correct, Wrong }

@Composable
fun QuizOptionTile(
    optionText: String, index: Int, state: QuizOptionState,
    onClick: () -> Unit, modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors; val texts = AppTheme.texts
    val density = LocalDensity.current.density
    val interaction = remember { MutableInteractionSource() }
    val scale by rememberPressScale(interaction)
    val shake = remember { Animatable(0f) }
    LaunchedEffect(state) {
        if (state == QuizOptionState.Wrong) {
            shake.snapTo(0f); shake.animateTo(1f, tween(420)); shake.snapTo(0f)
        }
    }
    val borderColor = when (state) {
        QuizOptionState.Correct -> colors.success
        QuizOptionState.Wrong -> colors.warning
        QuizOptionState.Selected -> colors.accent
        QuizOptionState.Idle -> colors.divider
    }
    val container = when (state) {
        QuizOptionState.Correct -> colors.successSoft
        QuizOptionState.Wrong -> colors.warningSoft
        QuizOptionState.Selected -> colors.accentSoft
        QuizOptionState.Idle -> colors.card
    }
    Surface(
        onClick = onClick, interactionSource = interaction,
        modifier = modifier.fillMaxWidth().graphicsLayer {
            scaleX = scale; scaleY = scale
            translationX = sin(shake.value * 6f * PI.toFloat()) * 10f * density * (1f - shake.value)
        },
        shape = RoundedCornerShape(DesignTokens.CornerRadiusLg),
        color = container,
        border = BorderStroke(1.5.dp, borderColor),
    ) {
        Row(
            Modifier.padding(DesignTokens.SpacingMd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(borderColor.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                when (state) {
                    QuizOptionState.Correct -> Icon(Icons.Filled.Check, null,
                        tint = colors.success, modifier = Modifier.size(16.dp))
                    QuizOptionState.Wrong -> Icon(Icons.Filled.Close, null,
                        tint = colors.warning, modifier = Modifier.size(16.dp))
                    else -> Text(('A' + index).toString(), style = texts.cardTitle.copy(color = borderColor))
                }
            }
            Spacer(Modifier.size(DesignTokens.SpacingMd))
            Text(optionText, style = texts.body, modifier = Modifier.weight(1f))
        }
    }
}
```
（需 `import androidx.compose.foundation.background`、`com.studykit.ui.theme.DesignTokens`。）

- [ ] **Step 2:** `Read ui/study/QuizScreen.kt`，把选项行替换为 `QuizOptionTile`：作答反馈沿用 ViewModel 现有状态（`QuizUiState.selected` + 对错判定），映射：用户所选且错→`Wrong`，正确项→`Correct`，其余 `Idle`；未作答时 `Selected`。交卷后结果页（若现为简单文案）加 `RingGauge(progress = accuracyPercent / 100f, color = gold 若 >=80 否则 accent)` + `Text("${accuracyPercent}%", statValue)` 居中。全部执行「令牌迁移」。
- [ ] **Step 3: 提交推送，CI 绿** `git commit -m "feat(study): 刷题选项 spring 反馈、错误抖动与正确率环" && git push`

---

### Task 10: 单词列表与录入表单页——令牌迁移 + 列表动效

**Files:**
- Modify: `ui/study/WordListScreen.kt`, `ui/study/WordCreateScreen.kt`, `ui/study/QuestionCreateScreen.kt`

**Interfaces:** Consumes Task 1/2；无新导出。

- [ ] **Step 1:** 三文件执行「令牌迁移」。
- [ ] **Step 2:** WordListScreen 的 LazyColumn 条目加 `Modifier.animateItem()`（fade+slide 进出），单词熟练度改为状态色点（`New`→divider、`LEARNING`→accent、`MASTERED`→success，12dp Box CircleShape），状态文案保留。
- [ ] **Step 3:** 录入页（M2 将升级为智能批量录入）：输入框容器并入 `AppCard`，保存按钮 `AppButton`，页面顶部 `StaggeredIn`。
- [ ] **Step 4: 提交推送，CI 绿** `git commit -m "feat(study): 单词列表与录入页新令牌与列表动效" && git push`

---

### Task 11: 习惯模块——热力图 + 打卡庆祝 + RingGauge 复用

**Files:**
- Create: `ui/components/HeatmapWeeks.kt`, `app/src/test/java/com/studykit/ui/habit/HeatmapCellsTest.kt`
- Modify: `ui/habit/HabitListScreen.kt`, `ui/habit/CheckInSheet.kt`

**Interfaces:**
- Produces: `buildHeatmapCells(today: LocalDate, weeks: Int): List<List<LocalDate?>>`（纯函数，列=周、行=周一至周日，未来日 `null`）；`HeatmapWeeks(activeDays: Set<LocalDate>, weeks: Int = 8, modifier: Modifier)`。

- [ ] **Step 1: 失败测试** `HeatmapCellsTest.kt`

```kotlin
package com.studykit.ui.habit

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class HeatmapCellsTest {
    @Test fun `列数等于周数且每列七天`() {
        val cells = buildHeatmapCells(LocalDate.of(2026, 9, 18), weeks = 8)
        assertEquals(8, cells.size)
        cells.forEach { assertEquals(7, it.size) }
    }
    @Test fun `最后一列包含今天且未来为 null`() {
        val cells = buildHeatmapCells(LocalDate.of(2026, 9, 18), weeks = 8)
        val last = cells.last()
        assertEquals(LocalDate.of(2026, 9, 18), last[LocalDate.of(2026, 9, 18).dayOfWeek.value - 1])
        val after = last.drop(LocalDate.of(2026, 9, 18).dayOfWeek.value)
        assertEquals(true, after.all { it == null })
    }
    @Test fun `今天所在周起始为周一`() {
        val cells = buildHeatmapCells(LocalDate.of(2026, 9, 18), weeks = 1)
        assertEquals(DayOfWeek.MONDAY, cells.single().first()!!.dayOfWeek)
    }
}
```

- [ ] **Step 2: 实现 buildHeatmapCells + HeatmapWeeks**

```kotlin
// app/src/main/java/com/studykit/ui/habit/HeatmapLogic.kt
package com.studykit.ui.habit

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** 近 weeks 周热度格日期：列=周（旧→新），行=周一..周日；今天之后为 null */
fun buildHeatmapCells(today: LocalDate, weeks: Int): List<List<LocalDate?>> {
    val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val firstMonday = thisMonday.minusWeeks((weeks - 1).toLong())
    return (0 until weeks).map { w ->
        (0 until 7).map { d ->
            val date = firstMonday.plusWeeks(w.toLong()).plusDays(d.toLong())
            if (date.isAfter(today)) null else date
        }
    }
}
```

```kotlin
package com.studykit.ui.components

// HeatmapWeeks.kt
@Composable
fun HeatmapWeeks(
    activeDays: Set<LocalDate>,
    weeks: Int = 8,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val cells = remember(colors, weeks) { buildHeatmapCells(LocalDate.now(), weeks).flatten() }
    val gap = 4.dp
    val cols = weeks
    val rows = 7
    Column(modifier) {
        // 单 Canvas 画整张：宽高由外部约束，cell 尺寸取 min
        Canvas(
            modifier = Modifier.fillMaxWidth().height(((rows * 12) + ((rows - 1) * 4)).dp)
        ) {
            val cw = (size.width - gap.toPx() * (cols - 1)) / cols
            val ch = (size.height - gap.toPx() * (rows - 1)) / rows
            cells.forEachIndexed { i, date ->
                val col = i / 7; val row = i % 7
                val x = col * (cw + gap.toPx()); val y = row * (ch + gap.toPx())
                val c = when {
                    date == null -> Color.Transparent
                    date in activeDays -> colors.accent
                    else -> colors.divider.copy(alpha = 0.5f)
                }
                drawRoundRect(color = c, topLeft = Offset(x, y),
                    size = Size(cw, ch), cornerRadius = CornerRadius(cw * 0.22f))
            }
        }
    }
}
```
（import：`com.studykit.ui.habit.buildHeatmapCells`、`androidx.compose.foundation.Canvas`、`Offset/Size/CornerRadius`、`java.time.LocalDate`、`androidx.compose.ui.graphics.Color`。）

- [ ] **Step 3: HabitListScreen 改造**：
  - 删除私有 `ProgressRing`，改 import 组件 `RingGauge`（达成色逻辑保留：`color = if (achieved) gold else accent`）。
  - `CheckInButton`：tween 全换 `MotionSpec.press/snap`；打卡成功瞬间在卡片右侧叠 `ConfettiBurst(trigger = item.checkedInToday to item.habit.id, modifier = Modifier.matchParentSize())`（仅 `checkedInToday==true` 时组合）。
  - CalendarEntryCard 下方新增 `AppCard { Text("近 8 周坚持", caption); Spacer; HeatmapWeeks(activeDays = state.items.flatMap { it.checkedDates }.toSet()) }`。
  - `DoneFoldHeader` 箭头动画换 spring；LazyColumn 条目加 `Modifier.animateItem()`；全文件「令牌迁移」。
- [ ] **Step 4: CheckInSheet**「令牌迁移」+ 确认按钮改 `AppButton`。
- [ ] **Step 5: 提交推送，CI 绿（含热力图单测）** `git commit -m "feat(habit): 打卡热力图、庆祝粒子与新令牌" && git push`

---

### Task 12: 日历两页——新令牌 + 水波反馈

**Files:**
- Modify: `ui/habit/GlobalCalendarScreen.kt`, `ui/habit/HabitCalendarScreen.kt`, `ui/habit/HabitCreateScreen.kt`

**Interfaces:** Consumes Task 1/2。

- [ ] **Step 1:** 三文件「令牌迁移」。日历选中日格：背景 `animateColorAsState(..., MotionSpec.press)` + 图标/文字 spring 缩放（选中 1.08）；系统日程条目卡并入 `AppCard` 样式；月份切换如为按钮则触发内容 `AnimatedContent(fadeIn+slideInHorizontally 短距)`。
- [ ] **Step 2: 提交推送，CI 绿** `git commit -m "feat(habit): 日历页新令牌与选格动效" && git push`

---

### Task 13: 读书模块——书脊色带卡 + 引用样式（含纯函数 TDD）

**Files:**
- Create: `ui/book/BookSpine.kt`, `app/src/test/java/com/studykit/ui/book/BookSpineTest.kt`
- Modify: `ui/book/BookShelfScreen.kt`, `ui/book/BookDetailScreen.kt`, `ui/book/BookEditScreen.kt`, `ui/book/ExcerptEditScreen.kt`, `ui/book/ReviewEditScreen.kt`

**Interfaces:**
- Produces: `fun spineColorIndex(seed: String, paletteSize: Int): Int`（稳定、非负取模）；`SpinePalette: List<Color>`（8 个静态书脊色，双主题共用）。

- [ ] **Step 1: 失败测试**

```kotlin
package com.studykit.ui.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSpineTest {
    @Test fun `同名恒定同色`() {
        assertEquals(spineColorIndex("三体", 8), spineColorIndex("三体", 8))
    }
    @Test fun `结果为合法非负有界索引`() {
        listOf("三体", "人类简史", "", "☃", "a".repeat(500)).forEach {
            val i = spineColorIndex(it, 8)
            assertTrue(i in 0 until 8)
        }
    }
    @Test fun `任意样本均落在有界非负索引`() {
        val samples = (0..999).map { "seed-$it" } + listOf("", "☃", "a".repeat(500))
        samples.forEach { i ->
            assertTrue(spineColorIndex(i, 8) in 0 until 8)
        }
    }
}
```

- [ ] **Step 2: BookSpine.kt**

```kotlin
package com.studykit.ui.book

import androidx.compose.ui.graphics.Color

fun spineColorIndex(seed: String, paletteSize: Int): Int =
    ((seed.hashCode() % paletteSize) + paletteSize) % paletteSize

/** 书脊色板：暖纸感低饱和 8 色，双主题共用 */
val SpinePalette: List<Color> = listOf(
    Color(0xFF00A78E), Color(0xFFE8A33D), Color(0xFFD96C6C), Color(0xFF7A8BBF),
    Color(0xFF9A8CBE), Color(0xFF5FAF8E), Color(0xFFC98A6B), Color(0xFF8C9A86),
)
```

- [ ] **Step 3: 书架卡**：`BookShelfScreen` 书卡左侧加 `Box(Modifier.width(6.dp).fillMaxHeight().clip(RoundedCornerShape(startRadius...)) .background(SpinePalette[spineColorIndex(book.title, SpinePalette.size)]))`（Row 首位）；进度文案 `x/y 章` 保留但用 `texts.caption`。
- [ ] **Step 4:** `BookDetailScreen` 摘录条目改引用样式：左侧 3dp accent 竖条 + 正文 `texts.aux` + 「—— 摘录」落款；其余四文件「令牌迁移」。
- [ ] **Step 5: 提交推送，CI 绿** `git commit -m "feat(book): 书脊色带书架卡与摘录引用样式" && git push`

---

### Task 14: 错题模块——新令牌 + 列表进出动效

**Files:**
- Modify: `ui/mistake/MistakeListScreen.kt`, `ui/mistake/MistakeDetailScreen.kt`, `ui/mistake/MistakeCaptureScreen.kt`

**Interfaces:** Consumes Task 1/2。

- [ ] **Step 1:** 三文件「令牌迁移」；列表学科分组条目加 `Modifier.animateItem()`（掌握后移除有自然退场）；详情「已掌握」按钮点击后按钮内文字短暂放大回弹（`animateFloatAsState` press spec）再 popBackStack。
- [ ] **Step 2:** 拍照/图片卡：圆角 `CornerRadiusLg`，加 1dp `divider` 描边；科目筛选 chips 选中态底色 `accentSoft` 文字 `accent`。
- [ ] **Step 3: 提交推送，CI 绿** `git commit -m "feat(mistake): 错题模块新令牌与列表动效" && git push`

---

### Task 15: 删除旧令牌 + 版本号与文档

**Files:**
- Modify: `ui/theme/DesignTokens.kt`（删颜色与 TextStyle 段）, `app/build.gradle.kts`, `CHANGELOG.md`, `README.md/README.en.md/README.ru.md`

- [ ] **Step 1: 全量清理校验**

```bash
grep -rn "DesignTokens\.\(Background\|Card\|PrimaryText\|SecondaryText\|Accent\|Success\|Gold\|Warning\|Divider\|LargeTitle\|PageTitle\|CardTitle\|Body\|Auxiliary\|Caption\|AnimDurationMs\|AnimEasing\|ShadowElevation\)" app/src || echo CLEAN
```
Expected: `CLEAN`（有残留先补迁移再删）。

- [ ] **Step 2:** 从 `DesignTokens` 删除上述成员（保留间距/圆角/`CornerRadiusXl`/`PageHorizontalPadding`/`CardPadding`），文件头注释改为「设计度量令牌；颜色与文字见 AppTheme」。
- [ ] **Step 3:** `versionCode = 2`、`versionName = "2.0.0"`；CHANGELOG 记 v2.0.0（双主题/spring 动效/滑动评价/热力图），README 三语各加一行「日/夜双主题与高帧率交互动效」。
- [ ] **Step 4: 提交推送，CI 绿** `git commit -m "refactor(theme): 移除旧单主题令牌，发布 v2.0.0 版本号与文档" && git push`

---

### Task 16: 帧率保障——baseline profile + profileinstaller

**Files:**
- Create: `app/src/main/baseline-prof.txt`
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`

- [ ] **Step 1:** `libs.versions.toml` [versions] 加 `profileinstaller = "1.4.1"`；[libraries] 加
`androidx-profileinstaller = { group = "androidx.profileinstaller", name = "profileinstaller", version.ref = "profileinstaller" }`；`app/build.gradle.kts` dependencies 加 `implementation(libs.androidx.profileinstaller)`。

- [ ] **Step 2: baseline-prof.txt**（ART 文本规则，聚焦启动与高频路径，避免全量）

```
Lcom/studykit/StudyKitApp;
Lcom/studykit/MainActivity;
Lcom/studykit/AppContainerKt;
Lcom/studykit/AppContainer;
Lcom/studykit/ui/theme/** { * }
Lcom/studykit/ui/motion/** { * }
Lcom/studykit/ui/nav/** { * }
Lcom/studykit/ui/components/RingGaugeKt;
Lcom/studykit/ui/components/RingGaugeKt$** { * }
Lcom/studykit/ui/components/AppCardKt;
Lcom/studykit/ui/components/AppCardKt$** { * }
Lcom/studykit/ui/study/StudyViewModel;
Lcom/studykit/ui/study/StudyViewModel$** { * }
Lcom/studykit/ui/study/StudyHomeScreenKt;
Lcom/studykit/ui/study/StudyHomeScreenKt$** { * }
Lcom/studykit/ui/study/CardStudyScreenKt;
Lcom/studykit/ui/study/CardStudyScreenKt$** { * }
```

- [ ] **Step 3: 提交推送，确认 release 变体不因 profile 报错** `git commit -m "build: baseline profile 与 profileinstaller（首帧与热路径预编译）" && git push`

---

## 验收（计划级）

1. CI 全绿（lint + 全部 JVM 单测 + assembleDebug），Actions 下载 `StudyKit-debug` APK。
2. 用户真机走查清单：深浅色切换无残留硬色；背单词滑动跟手、回弹自然、结算有彩带；习惯打卡有庆祝与热力图；列表滚动无可见掉帧；120Hz 设备观察动画顺滑度。
3. 通过后 → 编写 M2 录入自动化计划（词库商店/解析器/SAF/分享/OCR）。
