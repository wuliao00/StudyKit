package com.studykit.ui.theme

import androidx.compose.ui.graphics.Color

/** 暖纸感色板：数值来自 spec §1，禁止在页面直接使用，统一经 AppTheme.colors */
object Palette {
    // 浅色
    val LightBackground = Color(0xFFFAF8F2)
    val LightCard = Color(0xFFFFFFFF)
    val LightPrimaryText = Color(0xFF1F1D1A)
    // v3.2：从 #8A857C 压深到 #766F64 —— 次要文字要「退后」，但不能退到 13sp 只剩 3.9:1；
    // 现卡面 4.89:1 / 页面底 4.68:1，夜间侧同档为 6.99:1，两边都过 AA 仍有主次差。
    val LightSecondaryText = Color(0xFF766F64)
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
    // 热力图空格：真机浅色下 divider 满不透明只有 1.29:1，格子几乎看不见；
    // 单独提一档到 1.53:1，与夜间侧 DarkHeatIdle 的 1.52:1 同量级（装饰元素，不套文本 AA）。
    val LightHeatIdle = Color(0xFFD8D0BF)     // 1.53:1 vs 卡面 #FFFFFF / 1.44:1 vs 页面底 #FAF8F2
    val LightLightbox = Color(0xFF000000)    // 全屏看图的取景框黑（不随主题变，两主题同值）
    // ── 玻璃材质（v2.2 起，v3.2 重做）：只用于浮层，永不承载正文；但底栏/弹层上那行标签
    //    是**文字**，所以 tint 保持高不透明（SOFT 0.78 / STRONG 0.92），文字对比不因玻璃而变。
    //    v3.2 不再有「流动亮带」：那是把玻璃做成廉价高光的根源；现在只剩一层中性底 + 顶部一道
    //    白高光 + 一圈细切割边缘。浅色底接近纸色，深色底接近石墨，两边都不再带品牌色的绿。
    val LightGlassTint = Color(0xFFFBF9F3)
    val LightGlassSpec = Color(0xFFFFFFFF)   // 高光/边缘：浅色用纯白，压在暖白上只显出一道切割
    // 深色（v3.2「夜读」：冷石墨，不再是暖褐）
    // 逐对实测（vs 卡面 #15181D / 页面底 #0C0E11）：主文字 16.31 / 17.72，
    // 次文字 6.99 / 7.59，accent 8.83 / 9.59，accentInk 12.18 / 13.24 —— 全部 AA 以上。
    val DarkBackground = Color(0xFF0C0E11)
    val DarkCard = Color(0xFF15181D)
    val DarkPrimaryText = Color(0xFFF4F5F7)
    val DarkSecondaryText = Color(0xFF9CA3AC)
    val DarkAccent = Color(0xFF3ECBB6)
    val DarkAccentInk = Color(0xFF8AE6D7)    // DarkAccent 同色相提亮版：深石墨上文本更清晰
    // 实底按钮文字色（近黑墨）：#3ECBB6 上 9.51:1；白字在此底只有 2.0:1
    val DarkOnAccent = Color(0xFF07110E)
    val DarkSuccess = Color(0xFF4CD07D)
    // 夜间墨水＝品牌色本身：新卡面 #15181D 上三者 9.00 / 11.62 / 7.02:1；
    // 压在各自 soft 柔底上 6.64 / 7.60 / 5.46:1（accentSoft 上的 accentInk 为 8.93:1）
    val DarkSuccessInk = DarkSuccess
    val DarkGold = Color(0xFFFFC94D)
    val DarkGoldInk = DarkGold
    val DarkWarning = Color(0xFFFF7A73)
    val DarkWarningInk = DarkWarning
    val DarkDivider = Color(0xFF262A31)
    val DarkHeatIdle = Color(0xFF343A42)     // 热力图空格：1.55:1 vs 卡面（divider 1.24:1），装饰元素不套 AA
    val DarkLightbox = Color(0xFF000000)     // 同浅色：灯箱取景框不随主题变
    // 玻璃材质夜间侧：中性石墨，不再掺薄荷 —— 之前的 #2E2C25 + #B9E4DA 叠在暗绿页面上
    // 就是用户说的「很丑的毛玻璃」：底是橄榄色、顶上一道光又把整块刷绿。
    // 现在只让玻璃比页面亮半档（#12151A），高光用纯白低透明，边缘靠一道细切割。
    val DarkGlassTint = Color(0xFF12151A)
    val DarkGlassSpec = Color(0xFFFFFFFF)
}
