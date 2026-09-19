package com.studykit.ui.book

import androidx.compose.ui.graphics.Color

/**
 * 书脊色位：把书名折成 [SpinePalette] 的下标（纯函数，零 Android / Compose 运行时依赖）。
 *
 * **为什么跨运行稳定**：`seed.hashCode()` 在 Kotlin/JVM 上就是 `java.lang.String.hashCode`，
 * 规范定义为 `s[0]*31^(n-1) + s[1]*31^(n-2) + … + s[n-1]` 的 int 多项式（溢出按二进制补码回绕），
 * **不带任何进程盐** —— 与 Python/Ruby 那种「每次启动换种子」的 hash 不同。于是同一本书在冷启动、
 * App 升级、换设备（ART 与 HotSpot 同实现）之后拿到的都是同一个下标；`BookSpineTest` 里钉死了
 * 若干具体下标当哨兵，将来换散列算法（那会同时挪动每一本书的书脊色）会先在那里红灯。
 *
 * **为什么非负**：长书名溢出后 hashCode 会为负（`seed-0` = `-906232876`，一千个样本里约 1% 为负），
 * 而 Kotlin 的 `%` 与被除数同号，故再 `(x + n) % n` 折一次。这里刻意没用 `abs(h) % n`：
 * `abs(Int.MIN_VALUE)` 仍是负数，会漏出越界下标；双重取模对 `Int.MIN_VALUE` 也成立（`-2147483648 % 8 == 0`）。
 *
 * @param paletteSize 色板容量，契约 `>= 1`。非正数按取模除零抛 [ArithmeticException]（编程错误，
 * 调用点一律传 [SpinePalette.size]，运行时不可达；该契约由 `BookSpineTest` 锁住）。
 */
fun spineColorIndex(seed: String, paletteSize: Int): Int =
    ((seed.hashCode() % paletteSize) + paletteSize) % paletteSize

/**
 * 书脊色板：暖纸感低饱和 8 色，**双主题共用**（[spineColorIndex] 的下标就落在这里）。
 *
 * 刻意不取 `AppTheme.colors`：主题色是**语义**色（强调/成功/警告……），换主题要跟着换；
 * 书脊色是**这本书的身份**色，不承担任何状态含义，只需要 8 个彼此分得开的色相。若让它随主题
 * 提亮，用户建立的「那本蓝绿脊的书」联想会在夜间断掉。
 *
 * 对比度实测：八色在夜间卡面 `#26241F` 上是 4.6–7.2:1、浅色卡面白上是 2.2–3.4:1。色带是纯装饰
 * （其上不放文字，状态由 `StatusTag` 与进度条承载），故不受文本 AA 门槛约束。
 */
val SpinePalette: List<Color> = listOf(
    Color(0xFF00A78E), Color(0xFFE8A33D), Color(0xFFD96C6C), Color(0xFF7A8BBF),
    Color(0xFF9A8CBE), Color(0xFF5FAF8E), Color(0xFFC98A6B), Color(0xFF8C9A86),
)
