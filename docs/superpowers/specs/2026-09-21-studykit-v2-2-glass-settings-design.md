# StudyKit v2.2 设计：玻璃材质 + 稳定满帧 + 设置与本地信息

日期：2026-09-21 ｜ 状态：已确认 ｜ 里程碑：M3（四批：词库修通 → 设置 → 材质 → 帧率）

## 背景与目标

v2.0/v2.1 已交付双主题令牌体系（`AppTheme`）、spring 动效体系（`MotionSpec`）与录入自动化四路入口。
本版的四个目标按用户原话是「美化 UI、增加美感、能进行高帧率、加入液态玻璃、再加入设置能储存用户的本地信息」，
拆成四批，并且顺带把 v2.1 遗留的「词库下载那一跳未验通」收口 —— 该项本版内必须给出定性结论，不允许继续挂着。

**本版一处实测改变了两件事的可行性**，先记在明处，免得后文被当成空头承诺：

| 实测项 | 结果 | 对设计的影响 |
|---|---|---|
| 测试机显示模式 | `dumpsys display` → `supportedModes [{id=1, 1080x2408, fps=60.0}]`，只有 60Hz 一档 | 「高帧率」在本机可兑现的上限是**稳定满 60 帧**，不是字面的 90/120Hz |
| 测试机系统版本 | `ro.build.version.release` = 11，SDK = 30 | 系统级背景模糊 `RenderEffect` 要 API 31+，Compose `Modifier.blur` 在 30 以下是空操作 → 「液态玻璃」只能**手绘**，不能依赖真模糊 |

据此，用户已选定：玻璃走**手绘材质**（不做 API 31+ 的真模糊叠加分支，因为那条分支在唯一能判观感的设备上永远走不到，写了就是无法验收的死代码），
覆盖范围是**浮层用玻璃、卡片做纸感**（不是满屏玻璃化）。

## 1. 词库下载：根因已定性，改走 HTTPS 镜像

### 1.1 根因（2026-09-21 实测，全部在真机 shell 里完成）

v2.1 的怀疑清单有四项（DNS / 明文策略 / 证书 / 404），实测全部排除，真凶是**网络路径不放行明文 HTTP**：

- **借道电脑（gnirehtet，`tun0` 接管默认路由）时**：明文 80 端口的响应**整体被吞**。
  `toybox nc -w 8 <host> 80 < 请求文件` 对 `www.baidu.com`、`github.com`、`ydschool-online.nos.netease.com`
  三个主机一律 `exit=0`（TCP 连上了）、**回包 0 字节**。同一时刻 443 正常（App 能在线拉到 81 本目录就是证据）。
- **关掉隧道走 WiFi 直连时**：80 端口活了，但词表主机被校园网门户劫持 ——
  返回 `HTTP/1.0 302 Moved Temporarily` + `Server: NetEngine Server 1.0` +
  `Location: http://100.100.9.2/gportal/web/login?wlanuserip=10.12.109.242&...`；
  而 `www.baidu.com:80` 同时能拿到 200（门户白名单只放了它）。
  `HttpURLConnection` 的 `instanceFollowRedirects = true` 会跟着跳进门户页，
  拿到一页 HTML 去喂 `ZipInputStream`，最终报成「压缩包里没有找到词表 JSON」。

所以：不是代码 bug，不是 UA，不是证书，不是 404。目录（HTTPS）通、词表（明文 HTTP）不通，正是这个形状。

### 1.2 修法

同一份对象存在 HTTPS 镜像，且该主机**已经在本仓用于封面图**：

```
http://ydschool-online.nos.netease.com/1523620217431_CET4luan_1.zip        ← 目录给的地址
https://nos.netease.com/ydschool-online/1523620217431_CET4luan_1.zip       ← 镜像，PC 直连实测 206
```

改写规则是机械的：`http://<bucket>.nos.netease.com/<object>` → `https://nos.netease.com/<bucket>/<object>`。
抽样 6 本跨类别验证全部 `206`（CET4luan、Level4luan、GRE、PEPXiaoXue5、PEPGaoZhong_11、reciteWord_BeiShiGaoZhong_11）。

落地四条：

1. **纯函数改写**放在 `util/importer/DictBookParser.kt` 旁边的新文件 `util/importer/DictMirror.kt`
   （零 Android 依赖，可 JVM 单测）：`httpsDownloadUrl(rawUrl)` 只在「host 以 `.nos.netease.com` 结尾且协议是 http」时改写，
   其余原样返回 —— 不做通用 URL 猜测，避免把非网易对象改成不存在的地址。
2. **先镜像、后原址**：`DictRemote.downloadBook` 依次尝试 `镜像 → 原明文地址`，两跳都失败才算失败；
   进度回调只由真正开始读流的那一跳驱动，避免「下载中 0%」卡死在探测阶段。
3. **门户重定向要说人话**：`open()` 里检测 `30x` 且 `Location` 的 host 不是预期主机（或是私有网段 IP）时，
   抛 `DictRemoteException("当前网络把明文请求重定向到了网页认证门户（<host>），请改用 WiFi 直连或关掉代理")`，
   不再让它伪装成 zip 结构错误。
4. `network_security_config.xml` 的明文例外**保留**（回落那一跳仍需要），但注释改写为「只服务回落路径，主路径已走 HTTPS」。

端到端判据：新包装上后真点一次「导入这本」，进度条走完并进结果页。隧道里到镜像主机 443 的 TCP 可达已实测，
TLS 层不单独造轮子验证 —— 新包就是那次验证。

## 2. 材质系统：浮层玻璃 + 卡片纸感

### 2.1 手绘玻璃

新增 `ui/material/Glass.kt`，对外只暴露一个修饰符入口：

```kotlin
Modifier.glassSurface(shape: Shape, pressed: Float = 0f, scrollPhase: Float = 0f)
```

绘制五层，全部在 `DrawScope` 里完成，不用系统模糊：

1. **半透明底**：主题底色叠一层 tint（浅色用压暗的暖白、夜间用提亮的暖黑，alpha 由令牌给）；
2. **顶亮底暗线性渐变**：模拟入射光，浅色主题弱、夜间强；
3. **1px 内描边高光**：上缘亮、下缘暗，玻璃边缘的那道"切割感"靠它，不靠阴影；
4. **外柔影**：沿用 `AppTheme.elevation`，不新造一档；
5. **流动亮带**：一条斜向高光带，位置由 `scrollPhase`（内容从底栏上滚过的距离归一化）与
   `pressed`（按压进度）驱动 —— 这是「湿感 / 液态」的来源，也是唯一每帧变化的层。

`scrollPhase` 与 `pressed` 都由调用方传入，`Glass.kt` 不自己订阅滚动状态：材质层不持有语义，
才能同时服务底栏（订阅 `LazyListState`）和弹层（订阅按压进度）。

**性能约束写进实现**：1-4 层用 `drawWithCache` 建一次、只在尺寸/主题/形状变化时重建；
每帧真正重算的只有第 5 层的亮带位置。渐变对象（`Brush`）一律 `remember`，禁止在 `onDraw` 里新建。

参数进 `AppTheme.glass`（新增 `object glass`：`tintAlpha`、`highlightAlpha`、`edgeWidth`、`bandWidth`），
`Palette` 补两枚玻璃专用原始色（`LightGlassTint` / `DarkGlassTint`），浓度是**单一可调常量**，设置页有开关与档位。

### 2.2 落地站点（只有四处，明确不扩散）

| 站点 | 现在 | 改成 |
|---|---|---|
| 底栏 `NavigationBar`（`AppNav.kt:591`） | 实底 `card` 色 | 玻璃 + 订阅当前 Tab 列表的滚动量驱动 `scrollPhase` |
| 打卡弹层 `ui/habit/CheckInSheet.kt` | 实底 | 玻璃，随弹入动画给 `pressed` 一条沉降曲线 |
| 词库商店导入条 `ui/study/DictStoreScreen.kt` | 实底 | 玻璃（它是唯一还带红色错误行的浮层） |
| 看图对话框 `ui/mistake/MistakeDetailScreen.kt` | 黑底灯箱 | 玻璃浮层 + 保留 `colors.lightbox` 取景底 |

`AppCard` **不做透明**，只把描边与阴影升级为「暖纸 + 微光」：保留实底填充，
因此 M1 那一整套 WCAG AA 实测数字（`accentInk` 5.81:1、`successInk` 5.39:1 等）**一个都不必重算**。
这条是刻意的：满屏玻璃会让文字压在对侧内容上，对比度全部失效，还要付 real-time 模糊的帧代价。

## 3. 稳定满帧

**能兑现的**：本机 60Hz 下三个重灾场景不掉帧；玻璃层带来的 overdraw 增量被测量并压回去。
**不兑现的（写清以免被误解）**：`View.setRequestedFrameRate` / `FRAME_RATE_CATEGORY_HIGH` 是 API 31+，
本机用不到也验不了，本版**不做**；`MotionSpec` 的 spring 本来就逐帧跟随 vsync，到高刷设备上自然按面板率渲染。

方法：先立基线再改，改完复测同一组数字。

- 测量：`dumpsys gfxinfo com.studykit framestat` 前后各取一次窗口，真实帧率用两次
  `Total frames rendered` 差 ÷ `date +%s%N` 实测墙钟算（**janky% 只当参考**，WebView 时代的老账，见验证报告）。
- 场景：①彩带爆发（`ConfettiBurst`，90 粒）；②长列表滚动（词库商店 81 行 / 单词列表）；③切卡（`CardStudyScreen` 的 `AnimatedContent`）。
- 已点名的三个嫌疑（按嫌疑度排序，逐条测完再动手）：
  1. `ConfettiBurst` / `RingGauge` / `HeatmapWeeks` 的 Canvas 每帧重建 Path 与 Brush → `drawWithCache` + 仅数据变化失效；
  2. 加玻璃后新增的半透明层带来的 overdraw；
  3. 错峰入场 `StaggeredIn` 在长列表快速滚动时随 `remember` 重建重播。
- 已排除：9 处 `items(` 全部带 `key`，不是无 key 重组问题。

## 4. 设置与本地信息

### 4.1 持久化选型：Room，不加 DataStore

新增 `app_settings` 表（`key` 主键 + `value` 文本 + `updated_at`），版本 `3 → 4`，手写 `MIGRATION_3_4`。

理由三条，都记下来免得下次重议：①零新依赖（本仓纪律是「不新增运行时依赖」，M2 唯一例外是已批准的 ML Kit）；
②备份/导出只管一个 DB 文件；③本仓已有 Room 迁移的真机升级存活验证套路（建表 SQL 与实体逐字对齐），复用现成。

`SettingsRepository` 走 `Flow<Map<String,String>>` + 类型化访问器（`themeMode`、`glassEnabled`、`dailyWordGoal` …），
读侧 `StateFlow` 缓存一份，写侧 `suspend`；不引入 DataStore 也不引入 SharedPreferences。

### 4.2 三类内容

1. **外观与动效**：主题三态（跟随系统 / 浅色 / 深色）、玻璃开关、玻璃浓度档、减弱动效（关彩带 + 关错峰 + 关按压缩放）、
   字号缩放档、列表密度档。
   —— 今天 `MainActivity` 里是 `StudyKitTheme { ... }`，`darkTheme` 走默认 `isSystemInDarkTheme()`，
   用户根本没有入口，这是设置页天然的第一格。
2. **个人档案与目标**：昵称、头像色（从既有 `SpinePalette` 的色族里选，不新造色）、每日目标词数 / 题数、
   考试倒计时日期、提醒时间。
   —— 提醒现在是 `ReminderScheduler` 里写死的 `PeriodicWorkRequestBuilder(6, TimeUnit.HOURS)`，
   本版把它读设置项（具体推送时刻受 WorkManager 周期约束，界面上如实写「约每 N 小时提醒一次」，不承诺准点）。
3. **数据管理**：一键导出（SAF `CreateDocument`，zip = `studykit.db` + 错题图片 + `manifest.json` 含条数与校验和）、
   恢复（同 zip 校验后覆盖，成功即重启到首页）、占用统计（词/题/错题/笔记/照片体积）、清除数据（二次确认 + 只清业务表不动设置）、
   诊断入口（版本、词库源与上次失败原因 —— 本轮排查的直接产物）。

   这一档不是装饰：`res/xml/backup_rules.xml` 现在把 `domain="database"` 整个排除在系统备份之外，
   也就是说用户的词库、错题与照片目前**没有任何退路**。

### 4.3 入口与导航

顶栏齿轮（学习首页右上角），路由 `settings` 挂在 `AppNav` 的 `SettingsRoutes.SETTINGS`，
下设「外观与动效」「档案与目标」「数据管理」三段同页滚动 —— 不拆三个子路由，页少时拆路由只会让返回栈变复杂。
底栏四 Tab 骨架不动。

## 明确不做（YAGNI）

- API 31+ 的真背景模糊叠加分支（无法在判观感的设备上验收）。
- 满屏玻璃化（对比度重算 + overdraw 与满帧目标直接冲突）。
- 120Hz 请求、`setRequestedFrameRate`、帧率档位开关。
- 云同步、账号、DataStore、图标字体/图片资源、动效库（Lottie）。
- 设置项的搜索与分组自定义。

## 设计约束（沿用 v2.0/v2.1 全部工程纪律）

令牌唯一入口、边到边 insets 单消费者、品牌色与 `*Ink` 的分工、`TextStyle`/`spring`/`tween` 一律命名参、
lint 禁 `produceState`、`OneShotGate` 管一次性提交门、中文文案直接写字面量、
本机无 JDK/Android SDK（编译与单测只在 GitHub Actions）、合并 main / 打 tag / 发 Release / force push 必须先问。
新增一条：**材质层不得在 `onDraw` 内新建 `Brush`/`Path`/`Shader`**，一律 `remember` 或 `drawWithCache`。

## 测试策略

- **JVM 单测**：`DictMirror` 改写规则（含非 http、非 nos.netease.com、已带端口、路径含多级四案）；
  门户重定向判定；`SettingsRepository` 的类型化读写与默认值；导出 manifest 的校验和计算。
- **CI**：两个 job 全绿（lint + testDebugUnitTest + assembleDebug；assembleRelease + baseline profile 断言），
  按 **head SHA** 复核，不信报告文字。
- **真机**：词库整本下载走通（隧道开与关各一次）；玻璃观感与「减弱动效」开关；设置项冷启动后仍在（持久化）；
  DB v3→v4 在装有 v2.1 数据的机器上升级不丢数据；三个场景帧率前后对比。

## 已知未验通 / 未做（本版交付时逐项回收）

| 项 | 状态 |
|---|---|
| 词库下载那一跳 | 本版内验通，否则结论必须写进报告 |
| 系统分享进错题 | v2.1 未验通，本版顺带复验（intent 已在 manifest） |
| 详情页长按识别 | 未做，不在本版 |
| 摘录批量导入 | 未做，等用户定「导进哪本书 + 感想存哪」 |
| 预测式返回手势 | `enableOnBackInvokedCallback` 已开，但 Android 11 观测不到，维持不可证状态 |
