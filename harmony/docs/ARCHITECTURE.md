# HarmonyOS 原生版架构

## 技术栈

- ArkTS / ArkUI：声明式 UI、Stage 模型、单 UIAbility 入口；
- 本地存储：应用沙箱文件 `filesDir/studykit_data.json`（JSON），不依赖网络与账号；
- 无第三方运行时依赖：不引入 WebView、不做 Android 兼容层。

## 状态流

    EntryAbility
      → Index.aboutToAppear()
          → StudyStore.init(context)：读取本地 JSON（文件不存在则用空数据）
          → reload()：从 StudyStore 派生所有页面状态
              → SignInStats.summary(...)     签到汇总 / 连续 / 补签额度
              → Srs.dueCount / masteredCount 今日待复习 / 已掌握
              → 今日已复习、今日已答小测、错题数、撤销可用性
              → @State → 5 个 Tab（学习 / 签到 / 单词 / 练习 / 我的）

- 所有写操作（签到、补签、撤销补签、复习、撤销复习、小测答题、重置）都先写 StudyStore，
  再 reload() 重算页面状态，避免多个 Tab 读数不一致；
- SignInStats / Srs 是纯函数集合，不做 IO，可直接补 Hypium 单测。

## 数据模型（studykit_data.json）

    {
      "signIns":    [{ day, makeup }],                    // 签到记录
      "cards":      [{ word, box, dueDate, lastReviewed, correct, wrong }],  // Leitner 5 盒
      "reviews":    [{ date, word, remembered, at }],     // 复习日志（今日已复习、累计正确率）
      "quizAnswers":[{ date, word, correct, at }],        // 小测逐题作答（今日题数、总正确率）
      "quizSessions":[{ date, total, correct, at }],      // 每组小测结果
      "wrongs":     [{ word, date, wrongCount }],         // 错题本，答对后自动移除
      "studyDays":  ["YYYY-MM-DD"]                        // 有学习动作的日期（连续 / 累计）
    }

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
- 今日待复习 = dueDate <= 今天的卡片数量；“已掌握” = 盒 5 的卡片数；
- 撤销上一步复习：恢复上一张卡片的盒子/到期/对错计数，删除对应复习日志，
  若当天没有其它学习动作则一并撤回学习天数。

小测：

- 从 66 词词库生成 4 选 1，每组 5 题；优先出今日到期词；错题重练时错题优先；
- 答后即时反馈正确答案与例句；逐题落库，答错进错题本、答对从错题本移除；
- 一组结束写入 quizSessions；今日任务“小测 3 题”按 quizAnswers 当天条数计算。

学习报告：

- 正确率 =（复习答对 + 小测答对）/（复习总作答 + 小测总作答）；
- 连续学习 / 累计学习按 studyDays 计算（签到、复习、小测任一动作即记一天）；
- 重置：二次确认后清空全部学习数据（词库本身不变）。

## 页面

1. 学习：今日任务卡（签到 / 复习 5 词 / 小测 3 题，0/3 真实进度、可点、完成态、3/3 奖励文案）、
   今日待复习/已掌握/正确率、签到快卡、错题快捷入口；
2. 签到：整月日历、周进度、里程碑、补签/撤销补签、月度统计；
3. 单词：Leitner 复习卡（翻卡看释义与例句、记住/再学、撤销上一步复习）、复习规则说明；
4. 练习：今日小测入口、错题本列表与错题重练、最近小测记录、答题流程与结果页；
5. 我的：学习报告（已掌握/待复习/正确率/连续/累计/签到）、设计说明、二次确认重置。

## 设备与布局

- module.json5 的 deviceTypes 沿用上架设备类型 tablet（本机 API 15 SDK 的 device-define 只有
  default/tablet/wearable，无 phone，添加 phone 会导致 syscap 交集为空而构建失败）；
- 页面内容统一 constraintSize({ maxWidth: 760 }) 居中，手机宽度下自动收窄，平板与手机均可读。

## 构建

构建工程不能放在含中文的路径，构建副本在 E:\dev\studykit-harmony；签名用 AGC 发布证书与 Profile，
见 README 与 docs/BUILD-EVIDENCE.md。
