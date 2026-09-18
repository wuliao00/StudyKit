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

- 颜色/字号/圆角/动效参数一律引用 DesignTokens，禁止硬编码。
- 零图片/字体资源文件；庆祝动效为 Canvas 程序绘制，不引入素材。
- 业务规则不变：SM-2 间隔重复、打卡 streak、答错自动进错题本。
- Room 只新增列/索引，不修改既有字段语义；所有新表带迁移。
- 本地无 JDK/Android SDK：编译与单测由 GitHub Actions 验证（见「构建与验证」）。

## 1. 主题系统（M1）

`ui/theme/DesignTokens.kt` 重写为日/夜两套令牌，`Theme.kt` 按 `isSystemInDarkTheme()` 选择两套 `ColorScheme`，Material3 动态取色保持关闭（保证品牌色稳定）。

| 令牌 | 浅色 | 深色 |
|---|---|---|
| 背景 | 暖米白纸感 `#FAF8F2` | 暖黑 `#1C1B18`（非纯黑，减 OLED 刺眼） |
| 卡片 | 暖白 `#FFFFFF` | `#26241F` |
| 主色 | 青绿 `#00A78E`（墨墨式低饱和） | 提亮 `#33C7AB` |
| 达成金 | `#FFB300` | `#FFC94D` |
| 错误色 | 珊瑚红 `#FF5A52` | `#FF7A73` |

排版与形状升级：

- 统计数字：Bold 34–40sp、紧字距；标签 Caption 级。
- 卡片圆角 20dp；主按钮胶囊形；阴影改「低 elevation + 柔和描边」双层观感。
- 页面留白遵循 8dp 网格，页边距统一 20dp。

## 2. 动效系统（M1）

新增 `ui/motion/MotionSpec.kt`：集中定义 spring 规格（按压、回弹、进入、退场、飞出）与时长常量，替换全部 `tween(DesignTokens.AnimDurationMs)`。spring 逐帧跟随 vsync，在高刷屏上自然按 90/120Hz 渲染。

- **全局转场**：NavHost 子页 push/slide 进、pop 出（`slideIntoContainer`/`material3` 转场）；底部 Tab 切换淡入；与预测式返回手势兼容。
- **背单词卡片（重点）**：真 3D 翻面（`graphicsLayer` rotationY + cameraDistance，spring 驱动）；滑动评价——右滑=认识、左滑=不认识，拖拽时卡片倾斜、彩色遮罩与文字透明度跟手，越阈值松手飞出，未到位回弹；底部按钮保留（可达性与替代输入）。
- **庆祝时刻**：一轮复习结算、习惯目标达成时播放 Canvas 粒子彩带 + 数字滚动（IntState 动画）。
- **微交互**：列表项 staggered 淡入上移；打卡按钮、折叠箭头、选项点击全部换 spring；按压 0.96 回弹。

## 3. 页面清单（M1）

| 页面 | 改动 |
|---|---|
| 学习 Tab 首页 | 新增「今日任务」hero 卡：环形进度（到期/完成）+ 连续学习天数火焰徽章；入口卡渐变图标底；统计磁贴大数字重排 |
| 背单词卡片页 | 上述 3D + 滑动评价 + 结算庆祝页 |
| 单词列表 | 新卡片样式、staggered 进入、熟练度色点 |
| 刷题 | 选项 spring 缩放；对=绿圈描边、错=卡片 shake + 红描边；交卷结果页环形正确率 |
| 习惯列表 | 顶部新增 GitHub 式打卡热力图（Canvas 自绘）；打卡瞬间环形填充 + 光点粒子；达成金色态保留 |
| 日历页 | 热力配色跟随双主题；格子点按水波反馈 |
| 读书 | 书脊色带封面占位卡；摘录引用样式（大引号 + 色条） |
| 错题列表/详情 | 图片卡圆角升级；标记已掌握后划线消失动画 |
| 底部导航 | 选中项药丸指示器 + 图标 spring 缩放 |
| 通用 | EmptyState 升级为插画符号 + 引导按钮；全部页面对齐新令牌 |

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
- 里程碑顺序：M1 合入并出 APK → 用户真机确认观感 → M2 开发（导入页面直接使用 M1 新设计语言）。
- 文档同步：README 三语 + CHANGELOG 记 v2.0；词库商店需补「数据来源与许可」说明（kajweb/dict 词表许可核对）。

## 8. 测试策略

- 解析器、间隔重复、streak：JVM 单测（CI 全绿）。
- 动效/主题：APK 真机走查（用户执行，交付检查单：翻面跟手、滑动评价、深浅色切换、120Hz 设备观感）。
- OCR/分享/SAF：真机手工用例（无 CI 模拟器依赖）。
