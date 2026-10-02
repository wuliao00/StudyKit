# StudyKit v2.7 设计：app.docx（学习科学证据×优化方案）全量对齐

- 日期：2026-10-02　基线：v2.6.0（versionCode 15，HEAD f3674f7）　目标发版：**v2.7.0 / versionCode 16**
- 需求权威：用户提供的 `app.docx` 全文（已原样存档于 `docs/evidence/2026-10-02-app-docx-content.md`）。
  **用户裁定：文档与现状冲突时以文档为主**；本文档负责把"以文档为主"翻译成可施工、可回退的决定。
- 差距审计底稿：本设计基于一次 18 项逐条只读审计（ALREADY 4 / PARTIAL 10 / LACKS 5），
  关键断言都带 `file:line`，审计引用的行号在写作时又逐条回读核对过。

## 0. 一句话目标

把 app.docx 的 P0/P1 全部落成代码，同时不弄丢任何用户数据、不引入后端、不对用户发布统计上站不住的数字。

## 1. 决策记录（均已获用户确认，2026-10-01/02）

| # | 决策 | 备选与否决理由 |
|---|---|---|
| D1 | **双内核，默认 FSRS**：新增 `SchedulingKernel` 接口，`FsrsKernel` 为默认，`HalfLifeKernel`（现 `MemoryModel`）保留为设置项可切回 | 全量替换要重写 13 处依赖（遗忘曲线看板/明日预告/按钮预览），回归风险不对等；"只借语义不换内核"等于不执行文档 P0 |
| D2 | **先建迁移测试网，再动 schema**：`exportSchema=true` + `app/schemas/` 快照 + Robolectric 下的 `MigrationTestHelper` 测试，全绿之后才允许 v6→v7 的 ALTER/CREATE | 仓内现状是"5 个 migration、0 个测试"（CHANGELOG 自认）；一次全上等于把用户数据押给真机抽检 |
| D3 | 模考（延迟反馈）与变式题**都做本地最小实现**：模考=一次性作答、交卷后逐题分层反馈；变式=`concept_tag` + 用户/导入关联同考点题，复习时换题面 | 文档写"AI 或题库自动生成变体题"——本 App 离线无 AI，宣称自动生成即虚假 |
| D4 | **文案按统计口径改写**：`d=0.65` 不写"成功率提升 65%"；FSRS 的 20%~30% 标注"其自测基准，非独立复现" | 文档照抄会在两处对用户说谎；用户已选此项 |
| D5 | 评分仍是 **3 档**（认识/模糊/忘记 → Again/Hard/Good），不加"简单"第 4 按钮；`easy_bonus(w[16])` 分支休眠 | 加按钮动所有已钉死文案与测试；且"点 Easy 送间隔"是诱导性 UI |
| D6 | 断签保护（每月 2 次）**纯推导、零 schema**：由现有 CheckIn 历史算出，不落列 | 落列需记"哪天被保护"，派生方案已满足展示稳定性（规则见 §7.2） |
| D7 | 文档的 A/B 北极星**降级为本地前后对照**：延迟后测保留率（7/30 天分桶）+ CSV 导出；不引入任何遥测 | 零后端是产品定位（基线 §2），A/B 平台需要埋点 |
| D8 | 文档中「蒙对也算数…多巴胺」一条**不照抄**（无可辩护引用），改写为"猜对也算一次成功提取；隔几天再见一次" | 神经机制声明超出证据 |

## 2. 排期层：一个接口，两个内核

### 2.1 接口

```kotlin
// data/memory/SchedulingKernel.kt（新）
interface SchedulingKernel {
    fun recall(s: KernelState, elapsedDays: Double): Double          // 预测可提取性 R∈[0,1]
    fun review(s: KernelState, elapsedDays: Double, rating: KernelRating,
               conf: Confidence?): KernelState                        // 评一次分 → 新状态
    fun nextIntervalDays(s: KernelState, desiredRetention: Double): Double
    fun seedFromHalfLife(halfLifeDays: Double): KernelState           // 迁移用（见 2.4）
    val id: KernelId                                                  // FSRS | HALF_LIFE
}
```

`KernelState` 是 `data class(stability: Double, difficulty: Double, state: CardState, dueAt: Long?)`；
`CardState ∈ {LEARNING, REVIEW, RELEARNING}`（对齐 py-fsrs 的 State 语义）。
旧内核以适配器包进来：`half_life_days→stability`、`ReviewGrade→KernelRating` 双向映射，行为与 v2.6 逐比特一致（由既有黄金轨迹测试守着）。

**双写语义（消除切换内核时的状态歧义）**：每条目只有一个活跃内核（= 设置页当前内核，新条目创建时写入其 `kernel` 列）。
一次复习只**原生更新活跃内核的那份状态**，另一份用 §2.4 的换算式镜像回填（h=12.7895·S / S=h/12.7895），镜像值是近似值——
注释与设置页文案都要承认这一点："切回旧内核后，评分历史不丢，但记忆强度读数是换算近似"。不做双原生更新（两套模型对同一次评分各自演进会漂移，且用户无从理解）。

### 2.2 FSRS 公式与参数（幂律 v4.5/v5 族）

- 遗忘曲线：`R(t,S) = (1 + factor·t/S)^decay`，`decay = −0.5`，`factor = 0.9^(1/decay) − 1 = 19/81`。
- 下次间隔：`I = S/factor · (desiredRetention^(1/decay) − 1)`。
- 成功支 `S'`、失败支 `S'`、`D0`、`D'`（含 mean-reversion）、`hard_penalty(w[15])`：
  公式形式逐条对齐 `fsrs-rs src/model_v6.rs:28-88` 的**逐字锚点**（本库 `reverse-ref\竞品\02-Anki-FSRS\逆向报告.md` §已核实表，A 级）；
  21 个默认权重取 `open-spaced-repetition/py-fsrs@9446cb0 README「Custom parameters」`一节（0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666, 0.796, 1.4835, 0.0614, 0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542）。
  注意：该权重表是 **v6 默认**（`decay=−w[20]` 可学）；本实现**固定 decay=−0.5**，公式实际只引用 `w[0..15]`（初始 S 四支 w[0..3]、初始 D w[4..5]、均值回归与 Hard 倍率 w[6..7]、成功支 w[8..10]、失败支 w[11..14]、hard_penalty w[15]），
  `easy_bonus(w[16])` 与同日短支 `w[17..19]`、可学 decay `w[20]` 均不使用（D5 无 Easy、无优化器）——这 16 个数在 v5/v6 两代表里语义一致，混用风险只在这组参数本身的拟合语境（代码注释与本条声明同口径：不声称等于 Anki 的 v5 结果）。
- `desired_retention` 沿用现有"严格度/考试日期"体系：`MemoryScheduler.forSettings` 的 target 语义原样喂给两个内核，不新增用户概念。
- 不做的事：fuzzing（文档没要，且与"负载均衡"方向相反——见 00-对标矩阵 C1）、在线参数拟合（单用户样本不足以拟合 19 维）。

### 2.3 评分与信心映射

| 交互 | FSRS rating | 信心（新增） |
|---|---|---|
| 忘记 | Again(1) | 三档：瞎猜(1)/有点印象(2)/非常确定(3)，**翻面/提交前**采集，可空 |
| 模糊 | Hard(2) | 同上 |
| 认识 | Good(3) | 同上 |
| —（D5 无 Easy） | Easy(4) 分支休眠 | 同上 |

**超纠正规则（文档 §模块2 P1）**：`confidence==非常确定 且 答错/评忘记` → 该条目获得 `priority=1`：
当日 +10 分钟插入一次重测（阻断 Butler 2011 的回弹），并在队列排序中置顶；
`confidence` 落 `word_reviews.confidence`，规则是纯函数（独立单测）。

### 2.4 旧数据回填（唯一推导值，B 级标注）

`h`（半衰期，p=2^(−t/h)）与 `S`（R=90% 的间隔）在幂律族下：
`S = h / 12.7895`（即 `S = h·(0.5^(1/decay)−1)/(0.9^(1/decay)−1)`，decay=−0.5 代入）。
迁移 SQL 直接按此式填 `fsrs_stability`；`fsrs_difficulty = 5.0 + (difficulty−1)·0.5`（线性映射，同为推导，注释写明）。
注释里必须引用本库已修正的结论：**"6.57×"只属于指数族**（`reverse-ref\竞品\02-Anki-FSRS\evidence\引用复查台账.md §2`），不许再流通。

## 3. 数据层：Room v6→v7，测试网先行

### 3.1 顺序（TDD 硬约束，每一步先红后绿）

1. `exportSchema=true`：`ksp { arg("room.schemaLocation", "$projectDir/schemas") }`；`sourceSets["test"].assets.srcDirs += "$projectDir/schemas"`。
2. 提交 `app/schemas/com.studykit.data.AppDatabase/6.json`（v6 现状快照，生成后 diff 审读）。
3. `testImplementation(libs.androidx.room.testing)`，写 `Migration_6_7Test`（Robolectric + `MigrationTestHelper`）：
   先造 v6 库、插含中文/含 NULL 的样本行 → 跑 v7 迁移 → 断言行数、每列回填值、坏值不炸。**此时必红（无迁移）**。
4. 写迁移与实体 → 测试转绿 → 才允许接线业务代码。

### 3.2 列/表变更清单（一次 v7 全上）

| 表 | 变更 | 用途 |
|---|---|---|
| `words` | +`fsrs_stability REAL`、+`fsrs_difficulty REAL`、+`fsrs_state INTEGER DEFAULT 1`、+`kernel TEXT DEFAULT 'FSRS'` | 双内核并存与回填 |
| `word_reviews` | +`confidence INTEGER NULL`、+`rating INTEGER NULL`（FSRS 1..3；-1=旧数据） | 校准与超纠正的本钱 |
| `practice_records` | +`confidence INTEGER NULL` | 题库侧信心落库（与 word_reviews 同构，否则 §2.3 的超纠正规则在题库无处存） |
| `questions` | +`concept_tag TEXT DEFAULT ''` | 变式与按考点聚合（G7） |
| `mistakes` | +`fsrs_stability`、+`fsrs_difficulty`、+`fsrs_state`、+`correct_streak INTEGER DEFAULT 0`、+`review_count INTEGER DEFAULT 0`、+`priority INTEGER DEFAULT 0`；`review_at` 语义改为"手动覆盖，可空" | 错题算法排期（文档 §模块3 P0） |
| 新表 `mistake_redos` | `id, mistake_id FK, redone_at, correct, hints_used, had_note_rebuild` | 逐次重做历史（RedoFlow 自认缺的那张表） |
| 新表 `chapter_tests` | `id, book_id FK, chapter_label, question, expected_answer, passed, tested_at`——`question`/`expected_answer` 都是**用户自己写的文本**，App 不判题：作答时先回忆再展开对照，`passed` 由用户当场自评布尔（无 NLP、无 AI，D3 的"本地最小实现"口径） | 章节自测题（文档 §模块5 P2 的最小版） |
| `excerpts` | +`next_review_at INTEGER DEFAULT 0`、+`review_count INTEGER DEFAULT 0`、+`stability REAL` | 书摘入复习队列（文档 §模块5 P1） |
| `habits` | +`if_then TEXT DEFAULT ''` | 执行意图原文（when 复用现有 `category`，where/then 存句子） |

设置类新项（内核开关、模考默认、信心评级开关）**全部走 `app_settings` 键值表**，零 schema（该表设计初衷即此）。
迁移全部 `ALTER TABLE ADD COLUMN` + `CREATE TABLE`，无删列无改类型；回填在 Java 侧 `Migration` 里做（SQL 里算 `h/12.7895` 亦可，取 Java 侧以复用常量定义）。

## 4. 模块 1｜背单词（M1）

- **信心评级插入点**：闸门（`RecallGate`）产出答案后、翻面前，一行三档轻点选；可整卡跳过（记 NULL，不阻塞）。预测试（新词三选一）保持现状，预测试轮不采信心。
- 三档评分按钮保留并继续印预测间隔；数字来源从 `MemoryModel.preview` 改为"当前内核的 preview"（HalfLife 路径输出与 v2.6 完全一致，黄金轨迹测试不改断言仍须通过）。
- **目标梯度提示行（app.docx 模块1 P1，写作后补录）**：学习首页 hero 卡现有"今日待办 x/总数"区（`StudyHomeScreen.kt` 的 `val goal = AppSettings.dailyWordGoal` 附近），未达目标时追加一句"距今日目标还差 N 词"；只陈述既有事实、**不篡改任何数字、不给进度条做非线性缩放**（目标梯度效应 Kivetz 2006 利用的是"剩余量递减"的可见性，不是把环画得更满）。
- 首页"明天预计复习 N 词 · 到时候大约还记得 X%"文案不变，X 改由当前内核算。
- 超纠正置顶与当日 +10 分钟重测（§2.3）对词与题同一套实现。

## 5. 模块 2｜题库练习（M2）

- 提交前信心三档（同上）；答错且"非常确定"→ 走 §2.3 规则。
- **模考模式**（新）：题目选择页新增入口「模考」。行为：顺序作答、不显示对错、不弹反馈、不触发提示阶梯；顶栏"交卷"；交卷后逐题走现有 `QuizFeedback` 三层 + 完整解析；错题自动入错题本并标 `priority`。练习模式行为一字不改（即时反馈，文档口径）。
- 交错开关从题目选择页**同时**镜像进设置页（文档没要求，但 CHANGELOG 自认的别扭点，属低成本修正）。

## 6. 模块 3｜错题本（M3）

- **排期接管**：进入错题本不再要求手选"明天/三天后/一周后"；默认由内核排（首次入本=当天+10 分钟优先级同 §2.3）。"手动定时间"降级为详情页次级菜单"覆盖排期"。UI 文案「复习时间由你自己定，这里没有算法排期。」（`MistakeDetailScreen.kt:386`）改写为"系统按遗忘曲线排期，你可以覆盖"。
- **重做式复习**：`RedoFlow` 现有"先拿纸重做"门保留；重做提交后写 `mistake_redos`；重做流程接 `hintTiers`（3 档挤牙膏，题库那套复用）；OCR 拍照题在重做通过前，解析区保持折叠（文档"强制重建"的最小实现）。
- **掌握标准**：`mastered` 由"最近两次判对之间隔 ≥3 天，且连续两次判对"自动达成（2 次，不用 3——工作量减半且文档写"2–3"取小；此取舍在代码注释与 CHANGELOG 声明）；自动达成后仍可手动标记/取消，取消即回算法接管。
- **变式**：`concept_tag` 非空的错题，复习页出现"换一道同考点的"按钮（从同 tag 的 `questions` 里抽未做过本条的题）；无同 tag 题时按钮不出现（不造假）。

## 7. 模块 4/5｜习惯与读书（M4/M5）

### 7.1 习惯

- 创建页新增「执行意图」区块：引导拼出"当【时段/地点】，我就【行为】"，产物存 `habits.if_then`，列表页副标题优先展示它；`category` 选择器复用为 when 的时段维度（Gardner 2021 绑例程的既有决定不动）。
- **断签保护**（D6 纯推导）：某习惯的自然月内，单日照缺且该缺失是本月的第 1、2 次时，"连续天数"跨它延续、热力图上该日记为"已保护"（灰圈），每日志气泡文案如实写"未打卡（已用断签保护）"。第 3 次起照常断链。规则是 `HabitGuard` 纯函数 + 独立单测。
- 66 天文案现状保持；Tip 接线见 §8（漏打卡当晚弹 MISS_ONE_DAY、连续 21 天弹 SIXTY_SIX 两个触发点现在根本没接）。

### 7.2 读书

- 首页主指标从「书摘总数」改为「**检索练习次数**」（书摘自测 + 章节自测 + 合书回忆 的完成总数）；书摘总数降为次级 tile。
- 书摘入队：新摘录默认 `next_review_at = now + 1d`；"复习书摘"页按曲线出摘，展示原文前半 → 用户回忆后半/出处 → 自评三档 → 内核重排。摘录编辑页可关（该摘不复习）。
- 章节自测：读至某章末（现有页数进度驱动）出现"给这章出 2–3 道自测题"入口，存 `chapter_tests`；合书回忆按钮同页。`EXPLAIN_WHY` Tip 挂在"刚完成一章"事件上。

## 8. 提示语（StudyTips）

- 接线 6 条死码：`NewCardFirstLook→PRETEST`、`AboutToFlip→RECALL_FIRST`、`GapDay→MISS_ONE_DAY`、`StreakReached(21)→SIXTY_SIX`、`ChapterFinished→EXPLAIN_WHY`、`ExcerptOnlyNoRecall→RECALL_NOTES`，触发点逐条与 §4–§7 对应；事件源仍是交互事实，不做随机弹。
- 新增 2 条：`IF_THEN`（习惯创建页首次保存时；文案按 D4："把习惯绑到具体时间地点，元分析效应量 d=0.65，属中到大"）、`PRODUCTIVE_STRUGGLE`（错题重做第一次点"给点提示"时；依据 D8 口径）。
- 「蒙对也算数」不入库（D8）。所有 Tip 继续带 `[科学验证]` 徽标 + evidence 字段。

## 9. 度量（D7）

- 统计页新增「延迟后测」卡：按 7/30 天桶输出"到期条目实测回忆成功率 vs 模型预测 R"（数据源 `word_reviews`，含迁移前的 grade 回填 -1 桶单列不计入）。
- 导出：习惯/复习 CSV 并入现有 zip 备份通道（新增 entries，格式在 spec 附录 A 定，导入端不认这些新文件——只增不改）。
- 明确无网络、无埋点、无用户级 A/B 分流（文档的 A/B 只落成"上线前后自比"）。

## 10. 不做清单（防范围蔓延）

AI 生成变体题、在线参数拟合/优化器、云同步、第四评分按钮、fuzzing、学习风格（文档亦证伪）、"21 天"回潮文案、任何遥测。

## 11. 测试与验收

1. **TDD**：本文件每一节对应的测试先写、先跑红、再实现转绿；FSRS 用 py-fsrs 的公式手算 3 条黄金轨迹（Good/Good/Again 与 Hard 支各一）钉死；`seedFromHalfLife` 用 §2.4 系数断言；HalfLife 回归 = 既有 41 个 JVM 测试零修改通过（除新增列默认值）。
2. **迁移测试**（§3.1 第 3 步）含：空库、纯旧行、含中文、`h=0.5` 新词、脏 `h≤0` 行（必须降级不崩）。
3. 全量：`gradlew test` + `gradlew assembleDebug` 绿；真机（vivo 35152127910030J）`adb install -r` 覆盖升级**旧数据库**实测迁移，再截图 6 处新界面（词卡信心条、模考交卷页、错题排期接管、执行意图创建页、书摘复习页、延迟后测卡），截图存 `docs/screenshots/` 并更新 README 与 CHANGELOG（发版记录含"参考应用来源声明"沿用 reverse-ref §6.6 口径）。
4. 回滚预案：内核切回 Half-Life 即回到 v2.6 行为面；数据层不回滚（迁移只增不删，可前向兼容）。

## 12. 风险登记

| 风险 | 等级 | 缓解 |
|---|---|---|
| h→S 回填系数是推导值，回填后首周排期整体偏移 | 中 | 设置页可见"排期内核"与"切回"；看板对照卡暴露预测 vs 实测 |
| v7 迁移破坏老用户数据 | 高 | D2 的测试网 + 真机覆盖安装实测 + 只增列不删列；备份 zip 现成逃生通道 |
| 模考与闸门/提示/信心的组合状态机出错 | 中 | 模考为独立会话模式位，`QuizScreen` 只加一条分支；渲染守卫测试（Robolectric createComposeRule）照 CheckInSheet 先例写 |
| py-fsrs 权重与固定 decay=−0.5 的混用不是任何官方组合 | 低 | 代码注释 + CHANGELOG 明写；黄金轨迹钉的是"本实现的定义"，不声称等于 Anki |

## 13. 来源

- app.docx：用户 2026-10-01 提供全文（本仓 `docs/evidence/2026-10-02-app-docx-content.md`）。
- py-fsrs README（21 权重、desired_retention、learning/relearning steps）：`github.com/open-spaced-repetition/py-fsrs` @ commit `9446cb06605c597a063aeee49f7d188d42e34dc2`（经 GitHub API 取回，MIT）。
- FSRS v6 公式逐字锚点：本库 `reverse-ref\竞品\02-Anki-FSRS\逆向报告.md`（`fsrs-rs src/model_v6.rs:28-88`，A 级、可复算）。
- 幂律族换算与"6.57× 仅指数族"的更正：`reverse-ref\竞品\02-Anki-FSRS\evidence\引用复查台账.md §2`。
- 墨墨 DHP 参数与半衰期内核：`MemoryModel.kt` 头注（KDD 2022 SSP 解析版）。

## 附录 A（导出 CSV 格式）
- `review_history.csv`：`item_kind(word|mistake|excerpt), item_id, reviewed_at_epoch_ms, gap_days, confidence, grade, priority, elapsed_bucket(7|30|other)`
- 编码 UTF-8 带表头；文件名进 zip 根；解析端只需容忍性读，不做回导。
