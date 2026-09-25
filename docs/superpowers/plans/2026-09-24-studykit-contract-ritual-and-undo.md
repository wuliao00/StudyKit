# StudyKit 契约收尾：达成仪式 + 可撤销

分支 `feat/contract-ritual-and-undo`，基线 `fb12c77`（= `main` = tag `v2.4.2`）。
仓库：`C:\Users\Administrator\Documents\QoderCN\2026-08-28\chat-1\StudyKit`

## 来由（规格欠账）

设计文档 `docs/superpowers/specs/2026-09-22-studykit-v2-4-habits-play-design.md` §6 明写：

> 到期自动对账：达成给一个**整页的仪式（不是 toast）**，未达成把当初自己写的后果原文摆出来。

2026-09-24 补走查（`Desktop\StudyKit-v2.3.0\v2.4真机走查报告.md` §9 待决 3、4）确认：
后半句实现了（未达成卡面摆后果原文），**前半句没有** —— 达成只有一枚金色药丸，
`ConfettiBurst` 组件在导入结果页和习惯打卡两处用了，契约这条线一次都没挂。
同轮还查出：`ContractDao` 只有 insert/update/observeAll/byId，**契约签了不能撤**，
填错目标次数或选错习惯，只能等它到期判完，或者用「清除学习数据」同归于尽。

这两个就是本计划的全部范围。

## Rulings（控制器已拍，实施者不必再问）

### R1 仪式的触发时机：绑"本次真的结了一次账"，不绑"看到一张达成的契约"

> **2026-09-25 合并前修正**：这一段原先的**前提写错了**，结论不受影响。原文写的是
> "对账是惰性的 —— 只有进契约页时 `ContractsViewModel.settleDue()` 才会把到期的 ACTIVE 判掉"。
> 不成立：`ContractsViewModel.init` 订阅的是源头 `observeAll()`、跑在 `viewModelScope` 里，
> 而这个 ViewModel 在 `ui/nav/AppNav.kt` 的根 composable 里就 `viewModel()` 出来了
> （`MainActivity` 一进来必定走到那里）—— 所以**结算跑在整个进程寿命里**，
> 用户停在别的页面时到期的契约一样会被判掉。代码注释与 CHANGELOG 里同样的话一并改掉。

如果仪式绑在"卡片状态是 ACHIEVED"上，它会在每次列表重放/重组时庆祝一遍，那是噪音不是仪式。

所以：仪式绑在 `settleDue` **实际写库成功**的那几张契约上。`settle()` 的防重
（只有 `ACTIVE && deadline < today` 才改状态）保证一张契约一生只被结算一次，
于是"结算发生了"天然就是 once-per-contract，**不需要加"是否看过"的列，不需要 Room v7 迁移**。

代价（按修正后的前提重说一遍，仍然可接受）：不在契约页时结算出来的那几张会**攒在队列里**
（仪式那扇窗口挂在契约页的组合里），等用户回到这一页才摆；进程在收下之前被杀掉，那一次庆祝就没了。
仍然可以接受 —— 卡面上的达成态摆着，而"庆祝"本来就没有第二个时刻能补。写进 KDoc 说清。

### R2 只给达成，不给未达成

未达成的处理是"把后果原文摆出来"，已经在卡面上（真机验过）。
给失败做一个整页仪式是另一种产品气质，不在规格里，不做。

### R3 撤销 = 物理删除，ACTIVE 与已结算都允许，文案分两种

不做"软删/归档"：契约本来就是一张凭据，留着一条被撤销的记录没有消费方，
而 `archived` 语义已经被习惯占用了。
已结算的契约是历史 —— 但历史的所有权属于用户，app 没有权利替人留着一条他自己要抹掉的记录。
两种状态用同一套确认框骨架，文案不同（见任务 1）。

### R4 版本号：本计划不碰

两个任务只做代码 + 测试 + CHANGELOG 草稿条目。
`versionCode/versionName`、tag、Release 是发布动作，留给用户点头后单独做。

### R6–R8（合并前整枝评审追加的裁决；原有 R1–R4 一字不动）

编号沿用控制器合并前那份评审清单（对应评审的 I2+I3 / I6+I7 / I4），代码注释里出现的
"裁决 R6/R7/R8"指的就是下面三条。

#### R6 「这张仪式放过没有」只记一处：`ContractsViewModel.dismissRitual`

页面侧那一份 `takenRituals`（连同它的 `TakenRitualsSaver`）删掉。同一件事记两处，
两处就会各说一套：VM 摘一次、页面写一次，谁漏了都不出错，于是没人能证明它挡过什么。
评审逐条追过"被收下的契约凭什么回来"，确认页面那道闸单独永远触发不了。

#### R7 仪式是一扇 `Dialog` 窗口，不是页面里的一层浮层

两条结构性的洞：Material3 的 `AlertDialog`（`ConfirmDialog` / `CreateContractDialog`）
各自在自己的窗口里、永远画在页面之上，浮层盖不住它，而浮层的吞点击与摘语义也伸不进那一层；
浮层又接不住系统返回 —— 返回直接弹栈，而队列活在 Activity 作用域的 VM 里，
"返回"于是悄悄等于"这次仪式无限期推迟"。`onDismissRequest` 与「收下」接同一个动作。
`clearAndSetSemantics` 留在内容 Column 上，但口径降为 belt-and-braces（窗口已经把焦点关住了）。
**窗口对读屏是否真的隔离没上过设备**，在控制器的真机清单里 —— 不许把它写成已验证。

#### R8 一批多张不设"一次最多看几张"的封顶，改成一行「第 k / 共 n 张」

一行理由：**封顶要凭空回答"剩下的什么时候算"**，而 n 的上限就是用户自己签下的契约数 ——
所以让出口可数，而不是给出口设限。（配合 R1 的修正看：结算跑在整个进程寿命里，攒一批是可达状态。）

## Global Constraints（每个任务都受这些约束）

- **构建/测试在本机跑**：`./gradlew testDebugUnitTest`（JDK 在 `/e/dev/jdk`，
  `ANDROID_HOME=E:\dev\android-sdk`）。路径是纯 ASCII，不会触发非 ASCII 路径的
  `ClassNotFoundException` 老坑。**每个任务收尾必须跑一次全量 `testDebugUnitTest` + `lint`**，
  不能只跑自己新加的那几条。
- **lint 是 CI 门禁**：删了代码就要顺手删掉因此变成未使用的 import，否则 CI 红。
- **KDoc/块注释里绝不能出现 `/*`**（Kotlin 块注释可嵌套，一个 `path/*.jpg` 会毁掉整段编译）。
- **Compose 陷阱**：`TextStyle` / `animateFloatAsState` / `tween` / `spring` 一律**命名参数**；
  本仓 lint 禁 `produceState`。
- **Robolectric 的四个硬限制**（`CheckInSheetRenderTest` 是现成参照）：
  `ui-test-manifest` 必须 `debugImplementation`；一个测试方法里 `setContent` 只能一次；
  同类测试互相污染（未捕获异常会毒死同 JVM 的后一条）；dialog 收不到点击。
  新写的渲染测试**优先单方法多阶段 + `mutableStateOf` 驱动重组**，不要开第二条 Robolectric 测试方法。
- **纯函数优先**：能从 Composable 里抽出来的判定，抽成 `internal fun` 放同文件，
  配 JVM 单测（照 `contractHabitName` / `statValueFontSizeSp` / `isMakeUpEligible` 的既有写法）。
- **文案纪律**：不许过度承诺；限制要画在被限制的对象上；任何拒绝用户动作的分支必须说话
  （`HabitViewModel.rejectWithToast` 是同仓先例）。
- **只用 `AppTheme` tokens**（颜色 `AppTheme.colors`、字号 `AppTheme.texts`、
  间距 `AppTheme.space`、圆角 `AppTheme.radius`），不写字面 dp/颜色，除非是在定义组件本身。
- **提交信息**：多行中文提交信息一律先写文件再 `git commit -F <file>`。
  直接在 `-m "..."` 里放反引号会被 shell 命令替换吃掉（本仓踩过）。
- **不碰**：`app/build.gradle.kts` 的版本号、`.github/workflows/ci.yml`、签名相关任何配置。

---

## Task 1: 契约可以撤销/删除

### 要做的

1. `data/dao/ContractDao.kt`：加
   `@Query("DELETE FROM contracts WHERE id = :id") suspend fun deleteById(id: Long)`，
   KDoc 说明为什么是物理删除（R3）。
2. `data/repository/ContractRepository.kt`：薄包装 `suspend fun deleteById(id: Long)`。
3. `ui/habit/ContractsViewModel.kt`：加 `fun deleteContract(id: Long)`。
   - 用 `OneShotGate`（`com.studykit.util.OneShotGate`，同仓 `creatingContract` 的写法）
     防连点双删。
   - 删除是 suspend DAO，`viewModelScope.launch` 里 await 即可；
     Room 的 flow 会自动重放，列表随之刷新，**不要**手动改本地 StateFlow。
4. `ui/habit/ContractsScreen.kt`：
   - `ContractCard` 加一个删除入口。**必须放在卡片自己的作用域里**，
     不要放在页面级 `Column` 的循环外面（会删错张）。
   - 用现成的 `ConfirmDialog`（`ui/settings/SettingsScreen.kt` 里那个组件，
     如果它是 private 就在 `ui/components/` 找同名公共组件；两处都没有的话，
     用 `AlertDialog` 按 `CreateContractDialog` 的写法新建一个，不要改 settings 页的可见性）。
   - 文案按状态分两种：
     - ACTIVE：标题「撤销这份契约？」，正文要点明
       "撤销后这条契约连同它的判定一起消失，不会留下记录；已打的打卡不受影响"，
       确认按钮「撤销契约」，`danger = true`。
     - ACHIEVED / FAILED：标题「删除这条记录？」，正文点明
       "这是已经判完的历史，删了就找不回来"，确认按钮「删除记录」，`danger = true`。
   - 入口控件本身：用一个小的 `TextButton`（照 `OrganizeScreen` 里「归档」那一枚的写法与位置），
     标签 ACTIVE 显示「撤销」、已结算显示「删除」。**不要**用图标按钮加 `contentDescription`
     了事 —— 那正是本仓被反复要求"限制画在被限制的对象上"的反面。
5. 测试：
   - `deleteContract` 的门是 `OneShotGate`，`OneShotGateTest` 已有覆盖，不重复。
   - 抽一个纯函数 `internal fun contractDeleteLabel(status: String): String`
     （ACTIVE → "撤销"，其他 → "删除"）与
     `internal fun contractDeleteConfirmText(status: String): Pair<String, String>`
     （标题、正文），配 JVM 单测钉住三种状态。这样"文案随状态分叉"这条可验证的逻辑
     不留在 Composable 里。
   - 新建 `ContractDeleteCopyTest.kt`（放 `app/src/test/java/com/studykit/ui/habit/`）。

### 验收

- 全量 `testDebugUnitTest` 与 `lint` 绿。
- 手工路径（实施者如能跑本机 gradle 就只跑测试；真机由控制器负责）：
  契约页每张卡右下角有入口，点它出确认框，确认后卡片消失，重进页面不复活。

### 不要做

- 不做"批量撤销"、不做"撤销后恢复"、不加回收站。
- 不动 `clearBusinessData`（它已经会清整表）。
- 不引入 Room 新版本。

---

## Task 2: 达成给一个整页的仪式

### 要做的

1. `ui/habit/ContractSettle.kt` 或 ViewModel 层：让 `settleDue` 把**本次真的被结算**的契约收集出来。
   判定用现成的纯函数语义：`settle(c, n, today).status != c.status` 即为"这张被结算了"。
   抽成 `internal fun newlySettledBeforeFailure(all: List<Contract>, ...)` 之类**不要**发明 ——
   具体形态让实施者按现有代码定，但必须满足：
   - 有一个纯函数负责"从这批契约里挑出本次被结算成 ACHIEVED 的"，可单测；
   - `settleDue` 用它，不重复实现判定。
2. `ui/habit/ContractsViewModel.kt`：暴露一次性事件流。
   - 用 `Channel`/`SharedFlow` 或本仓既有模式（`util/OneShotGate` 旁边有没有事件载体，
     先读 `ui/` 下现有 `LaunchedEffect` + StateFlow 的用法，**跟着既有写法走**，
     不要为这一个功能引入新库）。
   - 事件内容：达成契约的展示所需字段（习惯名、承诺原文、进度 N/goal、署名、对账日）。
     只给 ACHIEVED，R2。
3. `ui/habit/ContractsScreen.kt`：全屏仪式层。

   > **合并前修正（见 R6/R7）**：这一条里"仪式是页面根 Box 上的一层浮层"与"用 `rememberSaveable`
   > 在页面侧记住这一张已经放过"两处已按裁决改掉：这不是实施者做多了，是计划写错了。
   > 现在：仪式是它自己那一扇 `Dialog` 窗口（R7），"放过没有"只由 `dismissRitual` 记一处（R6）；
   > `Box` + `matchParentSize` 那条要求原样成立，只是那棵根 Box 搬到了窗口里面。

   - 结构照 `ConfettiBurst` 的 KDoc 推荐写法：
     `Box(Modifier.fillMaxSize()) { 页面内容(); 仪式层() }`，
     彩带用 `Modifier.matchParentSize()` 挂在**根 Box 之上**，
     不要挂在 `AppCard` 里（卡片 Surface 有圆角裁剪，会把粒子切成方框 —— 组件 KDoc 明写）。
   - 仪式层内容（自上而下）：一句"契约达成"、习惯名、承诺原文（引号包裹，同卡面）、
     进度 `N/goal 次`、`署名：X · 对账于 yyyy-MM-dd`、一枚「收下」按钮。
   - 配色只用 gold 系 tokens（`goldSoft` 底 / `goldInk` 字 / `gold` 描边），
     与卡面达成态同族。**品牌色 `gold` 做非文本用途只允许描边这一处**（仓内既有纪律）。
   - 「减弱动效」开着时不放彩带（`ConfettiBurst` 自己会早退，但入场缩放/位移也要跳过 ——
     照 `HabitListScreen` 里 `celebration` 那段的既有做法）。
   - 旋转/重组不能重放：用 `rememberSaveable` 记住"这一张已经放过"，key 用契约 id。
     `ImportResultScreen` 里对 `ConfettiBurst` 防重放的处理是先例，照它做。
4. 测试：
   - 纯函数那条（"从一批里挑出本次被结算成 ACHIEVED 的"）配 JVM 单测：
     未到期不选、到期但判 FAILED 不选、已结算过的不选（防重）、一次多张全选中。
   - 渲染守卫**可选**：如果加 Robolectric 测试，必须遵守 Global Constraints 里那四条硬限制，
     且**只加一个测试方法**、用 `mutableStateOf` 驱动阶段切换。
     若实施者判断这条在 Robolectric 下不可靠（dialog/全屏层的 `assertIsAdd` 语义有坑），
     **宁可不写**，在报告里说明为什么 —— 本仓有过一次"为了有测试而写了条不可复现的测试"最后撤回的教训
     （见走查报告 §6）。

### 验收

- 全量 `testDebugUnitTest` 与 `lint` 绿。
- 逻辑自证：把一张契约的 `deadline_epoch_day` 改成过去（控制器会用真机做，实施者只需保证
  纯函数层有测试覆盖）。

### 不要做

- 不做未达成的仪式（R2）。
- 不加"是否看过"的数据库列，不做 Room 迁移（R1 的核心就是不需要它）。
- 不做音效、不做震动、不做分享图（设计文档 §8「明确不做」里列了贪吃蛇的音效/震动/道具，
  同一气质适用于此）。

---

## 交付顺序

任务 1 → 任务 2（两个任务都改 `ContractsScreen.kt` 与 `ContractsViewModel.kt`，**严格串行**，
不并行派发）。每个任务：实施 → 独立评审（规格 + 质量）→ 修复轮 → 记账。
全部完成后做一次整枝评审。真机复验与发布由控制器（主会话）负责，不在任务内。
