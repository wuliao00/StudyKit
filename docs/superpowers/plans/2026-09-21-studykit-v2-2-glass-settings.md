# StudyKit v2.2 玻璃材质 + 稳定满帧 + 设置 实施计划

> **执行状态（2026-09-21 收尾）**：T1–T10 全部落地，head `e0bb5dc` CI 两 job 绿，v2.2.0 debug APK 已真机走查
> （交付目录 `C:\Users\Administrator\Desktop\StudyKit-v2.2.0\`，证据与未验通项见其中 `真机验证报告.md`）。
> 三处偏差按裁定改过并已写进 spec：①玻璃浮层站点从四处收成三处（看图对话框那条等于新造 UI，放弃）；
> ②字号缩放/列表密度两项**本版不做**（会重开 M1 逐屏真机验收过的像素预算）；
> ③T11–T13 的帧率只做到"立了一组基线 + RingGauge 分配修复"，底栏玻璃开/关那一对数字
> （p50 10ms vs 6ms）**滚动距离不等、不足以归因**，账留给下一版用等距脚本滚动重测。
> 下面勾选框保留原样作为任务清单，不再逐格回勾。

**Goal:** 收口词库下载那一跳（根因已定性为明文 HTTP 被网络路径阻断），加上手绘玻璃材质与纸感卡，建立帧率基线并把重灾场景压回满帧，最后交付一个能把本地信息存下来的设置页。

**Architecture:** 四条互不遮挡的轨道。①**网络修正**在 `data/remote/` 与 `util/importer/` 一处（纯函数改写 + 两跳尝试 + 门户判定）；②**设置**是新的纵切：Room 新增 `app_settings` 键值表 → `SettingsRepository`（Flow）→ `SettingsViewModel` → 一段三节滚动页；主题三态由 `MainActivity` 把 `SettingsRepository` 的值喂给 `StudyKitTheme(darkTheme=…)`；③**材质**是新的横切 `ui/material/Glass.kt`，只挂在四个浮层站点，`AppCard` 仅升级描边阴影不改填充；④**帧率**先测后改，改动集中在 `ui/components/` 的三枚 Canvas 组件。

**Tech Stack:** Kotlin 2.0.21、Compose BOM 2024.12.01（Material3 1.3.1）、Room 2.6.1 + KSP、`HttpURLConnection`、`java.util.zip`、WorkManager 2.10.0、SAF (`ACTION_CREATE_DOCUMENT` / `OPEN_DOCUMENT`)、JUnit 4.13.2。**不新增任何依赖。**

**Spec:** `docs/superpowers/specs/2026-09-21-studykit-v2-2-glass-settings-design.md`

---

## Global Constraints

沿用 v2.0/v2.1 全部纪律，逐条对所有任务隐含生效：

- 分支 `feat/v2-visual-motion` 继续（v2.0/v2.1 都在这条上、未合 main）。**禁止**合并 main、打 tag、发 Release、force push —— 需先问用户。
- **本机无 JDK、无 Android SDK**：不得在本地跑 `gradlew`。编译与单测唯一执行器是 GitHub Actions（`.github/workflows/ci.yml`）。TDD 用「先推测试看红、再推实现看绿」双推证据，run id 记进报告；**子代理回报的"CI 全绿"必须按 head SHA 自己复核**。
- 真机：`/e/tools/adb/adb.exe`（`export PATH="/e/tools/adb:$PATH"`），序列号 `35152127910030J`，vivo V2156A / **Android 11（SDK 30）** / 1080×2408 / **只有 60Hz 一档**。覆盖安装先 `adb uninstall com.studykit`（CI 每次 runner 的 debug keystore 不同）。
- 手机的网络靠 gnirehtet 借道电脑（校园网门户未认证）。**动它之前先问，用完必须恢复**，恢复前注意僵尸 `gnirehtet.exe` 会占住 31416 端口。
- 令牌唯一入口 `AppTheme.colors/texts/space/radius/size/elevation`（本版新增 `.glass`），动效参数唯一入口 `MotionSpec`。禁止 `MaterialTheme.colorScheme.*` 与 `Color(0x…)` 字面量（例外：`ui/theme/Palette.kt`、`ui/book/BookSpine.kt`）。
- 品牌色只作填充/描边/色点/粒子；文字与图标位用同族 `*Ink`。
- 边到边：`AppNav` 根 `Scaffold` 的 `innerPadding` 是全仓唯一 insets 消费者，新页面禁止 `.imePadding()` / `.safeDrawingPadding()` / `.windowInsetsPadding()`。
- 组合卫生：`TextStyle` / `animateFloatAsState` / `animateColorAsState` / `tween` / `spring` 一律命名参（例外：`CornerRadius(radiusX = …)` 必须位置参）。lint 禁 `produceState`。一次性提交门用 `util/OneShotGate`。
- **本版新增一条硬约束**：材质层与 Canvas 组件不得在 `onDraw` / 每帧 lambda 里新建 `Brush` / `Path` / `Shader` / `LinearGradient`，一律 `remember` 或 `Modifier.drawWithCache`。
- 文案直接写中文字面量；提交信息用中文 conventional commits。
- 不新增运行时依赖（包括 DataStore）。

---

## 批次一：词库下载改走 HTTPS 镜像

- [ ] **T1 改写规则（纯函数 + 测试先行）**
  新建 `util/importer/DictMirror.kt`：`fun httpsUrlOf(raw: String): String`，仅当协议为 `http` 且 host 形如 `<bucket>.nos.netease.com` 时
  改写为 `https://nos.netease.com/<bucket>/<path>`；其余原样返回。先推 `test/.../DictMirrorTest.kt`（六案：标准改写、
  https 不动、非 nos 主机不动、带查询串保留、多级路径、空/畸形输入不抛）看红，再推实现看绿。
- [ ] **T2 两跳下载 + 门户判定**
  `DictRemote.downloadBook` 改为 `镜像 → 原址` 顺序尝试，第一跳失败只记原因、第二跳仍失败才抛；
  `open()` 增门户检测（30x 且 `Location` host 非预期或落在私有网段 → 专属 `DictRemoteException` 文案）。
  `DictStoreViewModel` 的错误链保持把最深 cause 透出（v2.1 已做）。
- [ ] **T3 明文例外降级为回落专用**：`res/xml/network_security_config.xml` 注释改写，配置本身保留。

## 批次二：设置持久化与设置页

- [ ] **T4 Room v3→v4**：`entity/AppSetting.kt`（`@Entity("app_settings")`：`key` PK、`value`、`updatedAt`）、
  `dao/AppSettingDao.kt`（`Flow<List<AppSetting>>` + `@Upsert`/`@Insert(onConflict=REPLACE)` + 单键删）、
  `AppDatabase` 注册 + `MIGRATION_3_4`（建表 SQL 与 Room 生成的逐字对齐）+ `addMigrations`。
- [ ] **T5 `SettingsRepository` + 类型化访问器**（含 `AppSettings` 数据类与默认值）、`AppContainer` 挂载；
  JVM 单测覆盖默认值、覆盖写、部分缺键。
- [ ] **T6 主题三态接上**：`MainActivity` 取 `AppSettings.themeMode` → `StudyKitTheme(darkTheme = …)`；
  `Theme.kt` 参数化（`ThemeMode.{SYSTEM,LIGHT,DARK}`，SYSTEM 仍走 `isSystemInDarkTheme()`）。
- [ ] **T7 设置页**：`ui/settings/SettingsScreen.kt` + `SettingsViewModel.kt`，三段（外观与动效 / 档案与目标 / 数据管理），
  `SettingsRoutes.SETTINGS = "settings"`，顶栏齿轮入口；档案字段与提醒周期写回仓储。

## 批次三：材质

- [ ] **T8 `Palette` + `AppTheme.glass` 令牌**，`ui/material/Glass.kt` 的 `Modifier.glassSurface(shape, pressed, scrollPhase)`
  五层绘制，1-4 层 `drawWithCache` 缓存、每帧只动亮带。
- [ ] **T9 四个浮层站点**：底栏（订阅当前 Tab 列表滚动量）、`CheckInSheet`、`DictStoreScreen` 导入条、`MistakeDetailScreen` 对话框；
  玻璃开关与浓度档在此接上 T5。
- [ ] **T10 `AppCard` 纸感升级**：只改描边/阴影/渐变，填充保持实底；`减弱动效` 开关接进 `MotionSpec` 消费点（彩带、错峰、按压缩放）。

## 批次四：帧率

- [ ] **T11 基线**：三场景各测一组 `gfxinfo framestat` + 双取样帧率，数字进台账。
- [ ] **T12 修复**：`ConfettiBurst` / `RingGauge` / `HeatmapWeeks` 的每帧重建收进 `drawWithCache`；`StaggeredIn` 快速滚动重播按 T11 证据决定是否处理；玻璃 overdraw 复测。
- [ ] **T13 复测出前后对比表**。

## 收口

- [ ] **T14** versionName `2.1.0 → 2.2.0`、CHANGELOG、CI 按 head SHA 复核、debug APK + `auto-install.sh` + `更新说明.md` 落
  `C:\Users\Administrator\Desktop\StudyKit-v2.2.0\`；真机走查（词库下载、玻璃观感、设置冷启动存活、v3→v4 升级不丢数据、帧率），
  出 `真机验证报告.md`；系统分享进错题顺带复验。
