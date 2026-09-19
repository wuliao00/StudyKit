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
    val LightAccentInk = Color(0xFF00735F)   // 文本/图标专用深accent：实测 5.81:1（card #FFFFFF）/ 5.47:1（页面底 #FAF8F2），均达 AA；accent 本身仅 3.04:1
    val LightOnAccent = Color(0xFFFFFFFF)    // 实底按钮文字色（浅色主题＝卡面白）：accentInk #00735F 上 5.81:1
    val LightSuccess = Color(0xFF34C759)
    // ── T15 墨水批次：品牌色「作文字/作图标」时的达标变体（浅色压深、夜间保持提亮）────────
    // 实测口径：WCAG 相对亮度比，vs 卡面 #FFFFFF / 页面底 #FAF8F2 / 同族 soft 柔底（下面各行的括号顺序一致）
    val LightSuccessInk = Color(0xFF1A7A3C)  // 5.39:1 / 5.08:1 / 4.96:1（successSoft＝success 10% 压白）；success 本身仅 2.22:1
    val LightGold = Color(0xFFFFB300)
    val LightGoldInk = Color(0xFF8A5A00)     // 5.93:1 / 5.58:1 / 5.42:1（goldSoft＝gold 14% 压白）；gold 本身仅 1.79:1
    val LightWarning = Color(0xFFFF5A52)
    val LightWarningInk = Color(0xFFC0332C)  // 5.60:1 / 5.27:1 / 5.02:1（warningSoft＝warning 10% 压白）；warning 本身仅 3.07:1
    val LightDivider = Color(0xFFE7E2D8)
    val LightHeatIdle = LightDivider          // 热力图空格直接取 divider 满不透明（1.29:1 vs 卡面）；此前 divider@50% 只有 1.13:1
    val LightLightbox = Color(0xFF000000)    // 全屏看图的取景框黑（不随主题变，两主题同值）
    // 深色（暖黑，非纯黑）
    val DarkBackground = Color(0xFF1C1B18)
    val DarkCard = Color(0xFF26241F)
    val DarkPrimaryText = Color(0xFFF0EDE6)
    val DarkSecondaryText = Color(0xFF9A958B)
    val DarkAccent = Color(0xFF33C7AB)
    val DarkAccentInk = Color(0xFF65D7C2)    // DarkAccent 同色相提亮版：暖黑底上文本更清晰
    val DarkOnAccent = Color(0xFF1C1B18)     // 实底按钮文字色（暖墨）：accentInk #65D7C2 上 9.88:1；白字在此底仅 1.74:1
    val DarkSuccess = Color(0xFF4CD07D)
    // 夜间墨水＝品牌色本身（故直接取别名，改品牌色时墨水自动跟上）：暖黑卡面 #26241F 上
    // 三者分别有 7.84 / 10.12 / 6.12:1，压在各自 soft 柔底（提亮后的暗卡）上也有 5.65 / 6.46 / 4.66:1
    val DarkSuccessInk = DarkSuccess
    val DarkGold = Color(0xFFFFC94D)
    val DarkGoldInk = DarkGold
    val DarkWarning = Color(0xFFFF7A73)
    val DarkWarningInk = DarkWarning
    val DarkDivider = Color(0xFF3A372F)
    val DarkHeatIdle = Color(0xFF454136)     // 热力图空格：比 divider 再提一档（1.52:1 vs 卡面，与 accent 实底格 4.80:1）；divider 满不透明只 1.30:1、@50% 仅 1.14:1
    val DarkLightbox = Color(0xFF000000)     // 同浅色：灯箱取景框不随主题变
}
