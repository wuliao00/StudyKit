# HarmonyOS 原生版架构（一期）

## 技术栈

- ArkTS / ArkUI：声明式 UI、Stage 模型；
- Preferences：签到记录与已掌握词，本地 JSON；
- UIAbility：单 Ability 入口，pages/Index 承载四个 Tab；
- 无第三方依赖：不引入 WebView、不做 Android 兼容层。

## 状态流

    EntryAbility
      → Index.aboutToAppear()
          → StudyStore.init(context)
          → StudyStore.loadSignIns() / loadMastered()
          → SignInStats.summary(records, today)
          → @State summary / @State mastered
              → Home / SignIn / Words / Profile 四个 @Builder 页面

- 签到/补签/撤销/记词动作都经过 StudyStore 写 Preferences 后 reload()；
- SignInStats 是纯函数集合，不做 IO，后续可直接补 Hypium 单测；
- 首页与签到页共用同一份 summary，避免两个 Tab 读数不一致。

## 口径

与 Android v3.21 对齐：

- 连续：今天签了从今天数，没签但昨天签了从昨天数；
- 补签：最近 3 天（不含今天/未来）、每月最多 3 次；
- 月度：当前月分母取“已过天数”，历史月取整月天数；
- 里程碑：3/7/14/30/66，按 longestStreak 常亮。

## 为什么先做 MVP

Android 版是一个已迭代 30+ 个版本的完整应用。直接一次性搬全部模块会稀释验证质量；
一期先选「首页 + 签到 + 本地词表」这条最高频、最少系统依赖的闭环，在 MatePad 上验证
ArkTS UI、Preferences、平板布局与生命周期，再按模块接入题库/OCR/FSRS。

## 待办

- Hypium 单测：把 SignInStats 的窗口/配额/里程碑口径钉死；
- 设备验证：HAP 安装、切页、签到/补签/撤销、横竖屏；
- 数据导入：与 Android 端签到 JSON 对齐；
- 性能：Preferences 全量 JSON 读写对签到数据足够，词表规模大后换 RelationalStore。
