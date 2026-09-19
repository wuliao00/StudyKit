# 更新日志

本项目遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)，格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [2.0.0] - 2026-09-19

### 新增
- **日/夜双主题**：`ui/theme` 重建为「暖纸感」双主题令牌层（`Palette` 原始色板 + `AppColors`/`AppTexts`
  经 `CompositionLocal` 下发），浅色暖纸 / 夜间暖黑各自达标，系统切换即时生效，状态栏跟着换
- **高帧率交互动效**：`MotionSpec` 统一 spring 规格（按压回弹 / 结算弹入 / 翻面 / 飞出），
  逐帧跟随 90/120Hz vsync；页面转场（淡入 + 短距位移）、底栏药丸指示、列表条目
  `animateItem()` 进出让位，全流程不再出现「整列瞬间跳一格」
- **滑动评价**：背单词卡片 3D 翻面 + 右滑「认识」/左滑「忘记」手势结算（阈值与甩速双判定，
  纯函数 `decideSwipe` 已补单测），答错选项抖动反馈
- **打卡热力图**：习惯页新增 GitHub 风格近 8 周热力图（`buildHeatmapCells` 跨月/跨年纯函数已补单测），
  打卡当日播彩带庆祝（`ConfettiBurst` 粒子合批），条目在待打卡/已打卡两区之间搬家带让位动画
- **达成与连续口径**：学习首页 hero 卡（今日待办进度环 + 连续学习火焰徽章）、正确率环形结算页、
  背单词小结卡数字滚动；连续天数由背单词与刷题共同驱动
- **墨水令牌**：`accentInk / successInk / goldInk / warningInk` 四个「文字与图标专用」变体
  （浅色压深后达 WCAG AA，夜间沿用提亮色），另有 `heatIdle`（热力图空格）与 `lightbox`（看图取景框）

### 变更
- **移除旧单主题令牌**：`DesignTokens` 整体下线，幸存的间距/圆角/阴影度量收进
  `AppTheme.space` / `AppTheme.radius` / `AppTheme.elevation`；颜色与文字样式一律取 `AppTheme`
- 错题本页新增「待复习 / 已掌握」两态筛选：标记掌握后条目从默认划线消失，已掌握清单仍可从上方 chip 回看
- 完成态、来源徽标等「色块 + 文字」处统一改为 `soft` 柔底 + `ink` 墨色（白字压实底只保留 accent 一处）
- 转屏不再重播已播放过的进场动画（`born` 等一次性标记改 `rememberSaveable`）

### 修复
- 刷题选项作答后仍可点击 / 触发 ripple 的锁定缺口；详情页返回出现「双弹」的转场竞态
- 品牌色作文字时不达 AA 的站点（判定文案、状态标签、百分比读数等）全部改用 ink 变体
- 夜间冷启动顶部「白条闪一下」：新增 `values-night/themes.xml`，窗口状态栏底色直接给夜间暖黑
  `#1C1B18`（浅色档同步从 v1 的 `#F8F8FA` 改为暖纸底 `#FAF8F2`；两档均仅 API < 35 生效，
  edge-to-edge 下由 Compose 承担）
- 启动图标残留的 iOS 蓝 `#007AFF` 改为品牌青绿 `#00A78E`

### 构建
- CI 单元测试 73 项（热力图网格、连续天数、滑动判定、书脊色带等纯函数）
- `versionCode = 2` / `versionName = "2.0.0"`
- 接入 `androidx.profileinstaller`，并把 `app/src/main/baseline-prof.txt` 重写为 **ART 文本 profile
  语法**（55 条规则，覆盖启动链、主题/动效令牌层、底栏导航、四 Tab 首帧与背单词热路径）；
  旧文件里 `Lcom/studykit/ui/theme/** { * }` 那类 R8 keep 写法会被 profile 解析器静默丢弃
- CI 新增 `release-build` job：`./gradlew --no-daemon assembleRelease` 校验 release（R8 混淆 +
  资源收缩）可编译，并用 `unzip -l` 断言产物内确有 `assets/dexopt/baseline.prof*`，缺失即 fail。
  仓库无 signingConfig，该 job 只产未签名包，**不签名、不上传、不发布**

## [1.0.0] - 2026-08-28

### 新增
- 四大功能模块：背单词 / 刷题、习惯打卡（含数量型目标与补卡）、读书笔记、错题整理
- 全局日历视图：聚合打卡记录与系统日历事件，按日查看明细
- 复习提醒：WorkManager 周期任务，到期错题与单词本地通知
- 学习数据导出：打卡记录 CSV 导出、成就文本分享
- Debug 构建首装自动播种演示数据（Release 构建不播种）

### 构建
- Kotlin 2.0.21 + Jetpack Compose（BOM 2024.12.01）+ Room 2.6.1（KSP）
- Gradle 依赖与插件版本统一收敛至 `gradle/libs.versions.toml` 版本目录
- CI：GitHub Actions（JDK 17 temurin），执行 lint / 单元测试 / Debug 构建
- Release 构建开启代码混淆与资源收缩（`isMinifyEnabled` / `isShrinkResources`）
  > 注意：混淆后的 release 包需在本地真机完整回归验证后方可发布。

### 修复
- 日历权限声明修正为官方 `READ_CALENDAR`（移除无效的 `READ_CALENDARS`）
- 删除错题时改为按 id 从数据库查询后再清理图片文件，不再依赖 UI 缓存
- 复习提醒的到期筛选下推至 SQL，避免全表载入内存过滤
