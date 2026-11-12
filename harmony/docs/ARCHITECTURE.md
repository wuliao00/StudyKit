# HarmonyOS 原生版架构

## 技术栈

- ArkTS / ArkUI：声明式 UI、Stage 模型、单 UIAbility 入口；
- 本地存储：应用沙箱文件 `filesDir/studykit_data.json`（JSON），不依赖网络与账号；
- 海报：ArkUI `OffscreenCanvas` + 2D 上下文绘制，`toDataURL('image/png')` 后用
  `util.Base64Helper` 解码、`fs.writeSync` 写 PNG 到 `filesDir`；
- 无第三方运行时依赖：不引入 WebView、不做 Android 兼容层。

## 状态流

    EntryAbility
      → Index.aboutToAppear()
          → StudyStore.init(context)：读取本地 JSON（文件不存在则用空数据）
          → reload()：从 StudyStore 派生所有页面状态
              → SignInStats.summary(...)     签到汇总 / 连续 / 补签额度
              → Srs.dueCount（当前词书过滤）  今日待复习
              → 今日已复习、今日已答小测、错题数、累计复习次数、撤销可用性
              → @State → 5 个 Tab（学习 / 签到 / 单词 / 练习 / 我的）
                         + 海报页（Canvas 生成 PNG）

- 所有写操作（签到、补签、撤销补签、复习、撤销复习、小测答题、切词书、导入示例、重置）
  都先写 StudyStore，再 reload() 重算页面状态；
- SignInStats / Srs 是纯函数集合，不做 IO，可直接补 Hypium 单测。

## 数据模型（studykit_data.json）

    {
      "signIns":     [{ day, makeup }],                    // 签到记录
      "cards":       [{ word, box, dueDate, lastReviewed, correct, wrong }],  // Leitner 5 盒
      "reviews":     [{ date, word, remembered, at }],     // 复习日志（今日已复习、最近记录）
      "quizAnswers": [{ date, word, correct, at }],        // 小测逐题作答
      "quizSessions":[{ date, total, correct, at }],       // 每组小测结果
      "wrongs":      [{ word, date, wrongCount }],         // 真实错题本
      "studyDays":   ["YYYY-MM-DD"],                       // 有学习动作的日期
      "book":        "cet4",                               // 当前词书（basic/cet4/cet6/ky/all）
      "demoImported": false,                               // 是否已导入 3 道示例错题
      "reviewCountTotal": 0                                // 累计真实复习次数（个人记忆曲线）
    }

## 词书

- Words.ets 里每个词带 `book` 字段，四本：基础（basic）/ CET4 / CET6 / 考研（ky）；
- `wordsOfBook(bookId)` 返回词书词表，`BOOK_ALL='all'` 表示全部 66 词；
- 单词页/首页的词书切换写 `Store.setBook` 并落盘，重启后保留选择；
- 复习队列、今日待复习、小测出题都按当前词书过滤（“全部”时用全词库）。

## 口径

签到（沿用 Android v3.21 口径）：

- 连续：今天签了从今天数，没签但昨天签了从昨天数；
- 补签：最近 3 天（不含今天/未来）、每月最多 3 次，可撤销；
- 月度：当前月分母取“已过天数”，历史月取整月天数；
- 里程碑：3/7/14/30/66，按 longestStreak 常亮。

Leitner 5 盒复习：

- 盒 1 到期=当天，盒 2/3/4/5 间隔=1/2/4/7 天；
- “记住了”：盒 +1（最高 5），dueDate = 今天 + 新盒间隔，correct +1；
- “再学一次”：回到盒 1，dueDate = 今天，wrong +1；
- 今日待复习 = 当前词书里 dueDate ≤ 今天的卡片数；“已掌握” = 盒 5 的卡片数；
- 撤销上一步复习：恢复上一张卡片，删除对应复习日志，累计复习次数回退 1。

小测：

- 4 选 1，每组 5 题；正常小测只出当前词书的词，错题重练时错题优先；
- 答后即时反馈正确答案与例句；逐题落库，答错进错题本、答对从错题本移除；
- 一组结束写入 quizSessions；今日任务“小测 3 题”按 quizAnswers 当天条数计算。

示例错题（Demo.ets）：

- 预置 3 道示例：数学公式（圆面积）、英语（consistent 释义）、形近词（affect/effect）；
- 练习页或首页入口，导入后显示 3 条示例条目，点“开始 60 秒示例复习”进入倒计时；
- 60 秒倒计时、3 题、即时反馈与解释；答案不写 Store，不影响正确率/错题本/今日任务；
- 文案明确“示例 / 不计入统计 / 不是扫描识别”，不假装有相机识别能力。

个人记忆曲线进度：

- 累计复习次数 = Store.reviewCountTotal（每次复习 +1，撤销 -1），满 100 次解锁；
- 首页、学习报告、海报都用同一份真实计数；明确“只按真实次数计数，不做算法校准”，不冒充任何校准算法。

分享海报：

- 数据来源：当前签到 summary、学习报告统计、词书进度、累计复习次数；
- Canvas 尺寸 720×1040，暖纸底 + 墨色/金色文字 + StudyKit 水印；
- 生成后自动保存 PNG 到 `filesDir/StudyKit-report-<时间戳>.png` 或
  `StudyKit-signin-<时间戳>.png`，页面展示 Image 预览与保存路径；
- 文件只写应用沙箱，不需要相册权限，不上传。

## 页面

1. 学习：今日任务卡（签到 / 复习 5 词 / 小测 3 题）、词书切换、三项读数、签到快卡、
   示例错题入口、错题本入口、个人记忆曲线进度；
2. 签到：整月日历、周进度、里程碑、补签/撤销补签、月度统计、“签到海报”按钮；
3. 单词：当前词书复习卡（翻卡看释义与例句、记住/再学、撤销上一步复习）、词书切换、复习规则说明；
4. 练习：示例错题（导入/60 秒复习）、今日小测、错题本与重练、最近小测、答题/结果页；
5. 我的：个人记忆曲线、学习报告（含四本词书进度）、分享海报（报告/签到）、设计说明、二次确认重置；
6. 海报页：Canvas 生成结果预览、PNG 保存路径、重新生成。

## 设备与布局

- module.json5 的 deviceTypes 沿用上架设备类型 tablet（本机 API 15 SDK 的 device-define 只有
  default/tablet/wearable，无 phone，添加 phone 会导致 syscap 交集为空而构建失败）；
- 页面内容统一 constraintSize({ maxWidth: 760 }) 居中。

## 构建

构建工程不能放在含中文的路径，构建副本在 E:\dev\studykit-harmony；签名用 AGC 发布证书与 Profile，
见 README 与 docs/BUILD-EVIDENCE.md。
