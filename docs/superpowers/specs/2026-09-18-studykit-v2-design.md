# StudyKit v2.0 设计：视觉升级 + 动效系统 + 录入自动化

日期：2026-09-18 ｜ 状态：已确认 ｜ 里程碑：M1 视觉/动效，M2 录入自动化

## 背景与目标

StudyKit 是本地优先的安卓学习应用（Kotlin + Compose + Room，零图片资源、全部系统字体，四 Tab：学习/习惯/读书/错题）。当前 UI 为单浅色「iOS 极简」主题，动画稀少且以 tween 为主，数据录入全靠逐条手工填写。

v2.0 目标：

1. 视觉与动效整体升级到「墨墨背单词式温暖纸感」为基底、关键节点叠加「小计划式庆祝动效」的融合风格，支持日/夜双主题。
2. 建立 spring 动效体系与高帧率（90/120Hz）渲染路径：跟手 3D 翻面、滑动评价卡片、粒子庆祝。
3. 录入自动化五件套：批量粘贴、文件导入（SAF）、在线词库（GitHub 开源词表）、截图分享进错题、OCR 文字提取（ML Kit bundled 中文模型）。

明确不做（YAGNI）：云同步、iPad 布局、Lottie/Vico 等图表动画库、云端 OCR、OCR 引擎抽象层（后续可替换 RapidOCR 时再抽象）。

## 设计约束（保持现有工程纪律）

- 颜色/字号/圆角/间距/阴影一律引用 `AppTheme`（令牌层唯一入口，`ui/theme/AppTheme.kt`），
  动效时长/缓动/spring 规格一律引用 `MotionSpec`（`ui/motion/MotionSpec.kt`），禁止硬编码。
  ~~原写「一律引用 `DesignTokens`」——该文件已在 T15 下线（见 CHANGELOG 2.0.0「变更」），
  幸存的间距/圆角/阴影度量收进 `AppTheme.space` / `.radius` / `.elevation`。~~
- 零图片/字体资源文件；庆祝动效为 Canvas 程序绘制，不引入素材。
- 边到边（`enableEdgeToEdge()` + targetSdk 35）下**窗口 insets 只有一个消费者**：
  `AppNav` 的 `Scaffold` 显式钉住 `contentWindowInsets = WindowInsets.safeDrawing`（含 ime），
  键盘高度因此直接流进 `innerPadding.bottom`，子树里那一枚 `Modifier.padding(innerPadding)`
  就是全仓唯一的补偿点。页面内不得再补 `.imePadding()` / `.safeDrawingPadding()`——
  `padding()` 不消费 insets，下游读到的仍是满值，叠加即双份键盘（波 4 真踩过，表单页塌成一条）。
  注意 M3 的默认值本来就是 `safeDrawing`，因此这条是**把隐式默认写成显式约定**，不是行为修复；
  键盘弹起是否真能滚到保存按钮仍待真机确认。
- 无障碍约定：装饰性元素（紧邻同义文字的图标、表情符号、对勾字符）不进朗读；
  同一事实只保留一份朗读；手写选择器一律 `selectableGroup` + `selectable(role = Role.RadioButton)`，
  `Surface(onClick)` 这类可点节点由外层组件交给读屏时走 `stateDescription`。
- 业务规则不变：SM-2 间隔重复、打卡 streak、答错自动进错题本。
- Room 只新增列/索引，不修改既有字段语义；所有新表带迁移。
- 本地无 JDK/Android SDK：编译与单测由 GitHub Actions 验证（见「构建与验证」）。

## 1. 主题系统（M1）

实际落地形态（原写「`ui/theme/DesignTokens.kt` 重写为日/夜两套令牌」—— 该文件已在 T15 **删除**）：

- `ui/theme/Palette.kt`：两套原始色值，是全仓唯一允许写死颜色的地方之一。
- `ui/theme/AppTheme.kt`：令牌层唯一入口。`AppColors`（双主题色）、`AppTexts`（文字档）、
  以及与主题无关的度量 `AppTheme.space` / `.radius` / `.elevation` / `.size`，
  经 `CompositionLocal` 下发；动效规格不在这里，在 `MotionSpec`。
- `ui/theme/Theme.kt`：按 `isSystemInDarkTheme()` 选 `LightColors` / `DarkColors`，
  Material3 动态取色保持关闭（保证品牌色稳定）。**注意**：只**部分**覆写了 `ColorScheme`
  （primary/background/surface/outline 等，secondary 与 container 系列仍是 M3 默认推导），
  因此页面与组件的填充色/文字色必须显式取 `AppTheme.colors.*`，不得依赖 `MaterialTheme.colorScheme.*`。

| 令牌 | 浅色 | 深色 |
|---|---|---|
| 背景 | 暖米白纸感 `#FAF8F2` | 暖黑 `#1C1B18`（非纯黑，减 OLED 刺眼） |
| 卡片 | 暖白 `#FFFFFF` | `#26241F` |
| 主色 | 青绿 `#00A78E`（墨墨式低饱和） | 提亮 `#33C7AB` |
| 达成金 | `#FFB300` | `#FFC94D` |
| 错误色 | 珊瑚红 `#FF5A52` | `#FF7A73` |

上表是**基础色**（填充/描边/色点/彩带用）。M1 收尾另加了一批不在表里的档位：
每个品牌色的「文字专用」墨水变体 `accentInk / successInk / goldInk / warningInk`
（品牌色作纯文字在浅色卡面只有 1.79–3.07:1，不达 WCAG AA）、实底上的文字色 `onAccent`、
热力图空格色 `heatIdle`、看图取景框 `lightbox`。规则是「色块 + 文字」优先 **soft 柔底 + ink 字**。

排版与形状升级：

- 统计数字：Bold 34–40sp、紧字距；标签 Caption 级。
- 圆角按用途分三档（不是一律 20dp）：卡片 20dp（`radius.lg`，即 `AppCard`）、
  **可点** chip / 磁贴 16dp（`radius.md`）、不可点的状态药丸 8dp（`radius.sm`）；
  主按钮胶囊形（`radius.xl` = `size.pill` 52dp 的一半）。
- 阴影改「低 elevation + 柔和描边」双层观感：`AppCard` 取 `elevation.hairline`（1dp）
  配 1dp `divider` 描边，分层主要由描边负责，抬升只留一丝。
- 页面留白遵循 8dp 网格，页边距统一 20dp（`space.pageH`），卡片内边距 16dp（`space.card`）。

## 2. 动效系统（M1）

新增 `ui/motion/MotionSpec.kt`：集中定义 spring 规格（按压 `press`、回弹 `snap`、翻面 `flip`、
环 `ring`、错峰 `stagger`、切卡 `cardSwitch`、飞出 `flyOut()`）、时长常量
（`FadeMs` / `StaggerMs` / `NavExitFadeMs` / `CardSwitchInMs` / `CardSwitchOutMs` / `ShakeMs` /
`CountUpMs`）与统一缓动 `Easing`，以及成对的淡入淡出工厂 `fadeEnter()` / `fadeExit()`
和四个导航转场工厂。spring 逐帧跟随 vsync；**「在高刷屏上自然按 90/120Hz 渲染」这句仍是
真机核验项，未在设备上复测过**（见「已知限制」）。

- **`tween` 的实际口径**：原写「替换全部 `tween(...)`」——收口的是**淡入淡出**这一类，
  页面/内容转场一律走 `fadeEnter/fadeExit`，于是曲线由结构保证而不是靠注释宣称。
  两类分支仍直接写 `tween`：**颜色交叉补间**（筛选 chip、学科选项、两页日历日格的
  `animateColorAsState`）与**翻月的水平滑入**（动的是位移 lambda，工厂只管淡）；
  它们的时长都取自 `MotionSpec` 常量。`app/src` 内调用点已无任何字面量毫秒
  （唯一例外是 `ConfettiBurst` 的公开参数默认值 `durationMillis: Int = 1100`，那是组件 API 不是页面外流）。
- **全局转场**：NavHost 进子页 = 新页自右侧推入、当前页向左让位（`MotionSpec.navEnter/navExit`），
  返回 = 当前页向右滑出、上一页自左侧滑入（`navPopEnter/navPopExit`），
  成对的 `slideIn/OutHorizontally` + 淡入淡出组合而成；底部 Tab 之间只做淡入淡出（无方向语义）。
  ~~原写「`slideIntoContainer`/`material3` 转场」——实际未用那两个 API。~~
  **预测式返回手势（原写「兼容」，实为 M2 待办）**：M1 只做了普通 pop 转场，未接
  `predictivePopSpec`、manifest 也未加 `enableOnBackInvokedCallback`（navigation-compose 2.8.5
  的该参数与系统左缘手势必须真机验证，本机无 SDK 无法核验，加 flag 的风险大于收益；
  清单见「7. 构建与验证」）。
- **背单词卡片（重点）**：真 3D 翻面（`graphicsLayer` rotationY + cameraDistance，`flip` spring 驱动）；
  滑动评价——右滑=认识、左滑=不认识，拖拽时卡片**倾斜 + 微缩放**跟手，同时两枚 ✓/✗ 角标
  **只有图形、按位移线性渐显**（刻意不放「认识/忘记」文字：17sp 文案压在拖动中的卡片上会抢注意力，
  文字语义由下方两颗按钮承担），越阈值松手飞出（`flyOut`）、未到位回弹（`snap`）。
  ~~原写「彩色遮罩与文字透明度跟手」——这两样都没做，也不在该页形态里。~~
  底部按钮保留（可达性与替代输入）。
- **庆祝时刻**：一轮卡片复习结算、以及某个习惯在**本帧变成「今日已打卡」**时播放 Canvas 粒子彩带
  （`ConfettiBurst`，粒子合批）+ 数字滚动（`animateIntAsState`）。习惯侧的触发条件是
  「`checkedInToday` 集合新增的那个习惯」，不是目标达成那一刻；冷启动首帧先用快照挡掉早已打好的卡，
  避免一进页面就放一遍。
- **微交互**：按压 0.96 回弹（`rememberPressScale`）；打卡按钮、折叠箭头、刷题选项、底栏图标
  全部换 spring；刷题答错抖动 420ms（`ShakeMs`）。
  **列表项 staggered 淡入上移只做了 4 处调用点**：学习首页三张入口卡、书架列表、
  题目录入分块、单词录入分块（下标一律 `minOf(index, StaggerIndexCap)` 限幅）。
  三个 `LazyColumn` 列表页（单词库 / 习惯 / 错题）走的是条目级 `animateItem()` 进出让位，
  **尚未接错峰入场** —— 列为 M2/设备走查项，不在 M1 已交付里。

## 3. 页面清单（M1）

| 页面 | 改动 |
|---|---|
| 学习 Tab 首页 | 新增「今日待办」hero 卡：88dp 环形进度（`todayDone / (todayDone + dueCount)`，达成转 `goldInk`）+ 连续学习天数火焰徽章；入口卡图标是 **44dp 纯色 `*Soft` 圆底 + ink 图标**（~~原写「渐变图标底」——未做渐变，全仓零渐变~~）；统计磁贴大数字重排 |
| 背单词卡片页 | 上述 3D + 滑动评价 + 结算庆祝页 |
| 单词列表 | 新卡片样式、熟练度色点；条目走 `animateItem()`（错峰入场未接，见 §2） |
| 刷题 | 选项 spring 缩放；对=绿圈描边、错=卡片 shake + 红描边；交卷结果页环形正确率 |
| 习惯列表 | 顶部新增 GitHub 式打卡热力图（Canvas 自绘，单 Canvas 一次画完）；打卡瞬间环形填充 + 彩带粒子；达成金色态保留 |
| 日历页 | 两页日历配色跟随双主题；日格打卡 **1.08 弹跳后回落** + 默认 ripple（~~原写「水波反馈」——没有水波这类效果~~）；翻月淡入 + 短距滑入 |
| 读书 | 书脊色带封面占位卡；摘录引用样式（大引号 + 色条） |
| 错题列表/详情 | 图片卡圆角升级；标记已掌握后条目从默认列表退场（`animateItem()`），已掌握侧由 chip 进入 |
| 底部导航 | 选中项药丸指示器 + 图标**按压** spring 缩放（~~原写「图标 spring 缩放」易读成选中态动画：缩放吃的是 pressed，不是 selected~~） |
| 通用 | EmptyState 升级为 72dp 圆形柔底**图标符号** + 标题 + 说明；**组件本身不含按钮**，引导按钮由各调用方在空态下方自行放 `AppButton`（书架 / 习惯 / 卡片学习 / 错题等）。~~原写「插画符号 + 引导按钮」：零图片资源的约束下没有插画，组件内也没有按钮槽~~ |
| 通用·无障碍（波 4） | 装饰性元素不再被读：底栏图标、习惯表情位、达成「✓」、火焰徽章、错题缩略图；翻面卡任一刻只读朝前那一面；手写选择器挂 `selectableGroup` + `Role.RadioButton`，刷题砖块挂 `stateDescription`；热力图给整块概览朗读（**概览语义，非逐格**）。48dp 可点槽位见 §7 设备清单 |
| 通用·边到边（波 4） | `enableEdgeToEdge()` 下窗口 insets 单一消费者：`AppNav` 的 `Scaffold` 钉住 `WindowInsets.safeDrawing`，系统栏/刘海/底栏/键盘一并流进 `innerPadding`，全仓不再出现 `.imePadding()`；七张表单页根 `Column` 均 `verticalScroll`，键盘弹起可滚到保存按钮（此条待真机确认） |

## 4. 性能（M1）

- 新增 `androidx.profileinstaller` + 手写 `baseline-prof.txt`（启动路径、四 Tab 首帧、卡片翻面/滑动手势路径）。
- 重组卫生：列表 item 稳定 key；动效状态不高于消费组件；`derivedStateOf` 收敛高频读取；协程回放避免组合期副作用。
- 验证口径：CI 编译 + 单测全绿为门槛；真机帧率/观感由用户安装 debug APK 反馈迭代。

## 5. 录入自动化（M2）

### 5.1 批量粘贴与文件导入

- 各模块「添加」页升级为智能录入页：粘贴多行文本，纯 Kotlin 解析器实时产出预览列表（可逐行修正/剔除），确认后批量入库。
- 行格式：单词 `word \t 释义 [\t 例句]`（Tab/逗号/连续空格自适应分列）；题目 `题干 | 选项A | 选项B … | 答案`；笔记 `书名 | 摘录 | 感想`。解析失败行进「待修正」区而非丢弃。
- CSV/TXT 经 SAF（`ACTION_OPEN_DOCUMENT`）读取，走同一解析器；编码 UTF-8，BOM 容错。
- 解析器为独立纯函数类，配 JUnit 参数化单测（CI 验证）。

### 5.2 在线词库

- 新页面「词库商店」：浏览 `kajweb/dict` 同步而来的开源词表（四六级/考研/雅思/TOEFL/TED 等），分类 + 搜索 + 逐词预览 + 一键导入。
- 网络层零新依赖：`HttpURLConnection` + `org.json`（Android 内置），协程调度；失败展示可重试空态。
- 词表元数据打包进 assets 内置一份（版本随 APK 发布），列表 JSON 在线刷新；词汇数据仅导入时下载，落库后完全离线。
- 表数据含 `sourceListId`，支持整表删除；批量导入走 Room 事务。

### 5.3 截图分享进错题

- MainActivity 注册 `SEND`/`SEND_MULTIPLE`（image/*）intent-filter → 携图直达预填的错题录入页。
- 图片入库复用 `MistakeImageStore` 既有私有目录存储。

### 5.4 OCR（ML Kit bundled 中文）

- 依赖 `com.google.mlkit:text-recognition-chinese`（bundled，模型进 APK，全离线、无需 GMS）。
- 入口三处：①录错题「从图片提取文字」→ 识别题干填入文本框，人工修正后保存；②分享进来的图片自动预识别（失败静默降级为手动）；③错题详情页长按图片「识别文字」补录。
- 复用同一能力做「单词截图取词」：识别文本按行喂给 5.1 解析器，批量入词。

### 5.5 导入结果小结

所有导入完成统一跳「导入结果」卡：成功 N / 跳过 M / 待修正 K，可展开逐行原因；样式复用 v2.0 视觉。

## 6. 数据变更汇总

- `Word` 增列 `sourceListId`（可空）；新表 `word_list_meta`（在线词库导入来源）。
- 统计页所需「连续学习天数」由现有 `word_review`/`practice_record` 聚合得出，不落新表。
- Room schema 版本递增，提供迁移；`DemoSeeder` 不受影响。

## 7. 构建与验证

- 复用现有 `.github/workflows/ci.yml`（push/PR 触发 lint + `testDebugUnitTest` + `assembleDebug`），仅补一步 `actions/upload-artifact` 上传 debug APK，供真机验证；Release 签名不在本次范围。
- M1 收尾实际口径（2026-09-19 更正）：CI 为两个 job —— `build`（lint / 单测 / debug，并上传 debug 产物，`if-no-files-found: error`）与 `release-build`（`assembleRelease` 只编译不签名、不上传，并用 `unzip -l` 断言 baseline profile 已进 APK）。§2 的「与预测式返回手势兼容」已按此降级。
- **M2 待办清单（M1 遗留，需真机）**：①预测式返回手势（`predictivePopSpec` + manifest `enableOnBackInvokedCallback`，要验证系统左缘手势与本页横滑/纵滚的边界）；②§5 录入自动化五件套；③baseline profile 的真机收益复测（安装期 AOT 后重测冷启动与翻卡帧率）。
- 里程碑顺序：M1 合入并出 APK → 用户真机确认观感 → M2 开发（导入页面直接使用 M1 新设计语言）。
- 文档同步：README 三语 + CHANGELOG 记 v2.0；词库商店需补「数据来源与许可」说明（kajweb/dict 词表许可核对）。

## 8. 测试策略

- 解析器、间隔重复、streak：JVM 单测（CI 全绿）。
- 动效/主题：APK 真机走查（用户执行，交付检查单：翻面跟手、滑动评价、深浅色切换、120Hz 设备观感）。
- OCR/分享/SAF：真机手工用例（无 CI 模拟器依赖）。
