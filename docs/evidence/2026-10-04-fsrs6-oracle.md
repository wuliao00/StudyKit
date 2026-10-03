# FSRS v6 ORACLE — 预言机取证报告

日期：2026-10-04 ｜ 分支：`feat/v2.8-fsrs6` ｜ 基线：`f064360`

## 0. 结论（先看这里）

**预言机取自真实参考库，非规格手推。** 本机 `pip install fsrs` 成功，装上
`py-fsrs==6.3.2`（`open-spaced-repetition` 官方 Python 实现，纯 Python，含完整公式源码）。
本报告与 `fsrs6-golden.json` 里每一个数字都由 **调用该库自身的私有公式原语**
（`_initial_stability` / `_initial_difficulty` / `_next_difficulty` / `_next_stability` /
`_short_term_stability`）与库的 `DECAY=-parameters[20]`、`FACTOR=0.9^(1/DECAY)-1` 算出，
**不是**离线手算、**不是**推测。生成脚本随附于 `docs/evidence/fsrs6_oracle.py`，可复跑。

- Python：`3.14.5` ｜ pip：`26.1.1`
- `pip install fsrs` → `Successfully installed fsrs-6.3.2`
- 库源码路径：`C:\Python314\Lib\site-packages\fsrs\scheduler.py`（859 行，逐行核对）

## 1. 版本钉死（v5 vs v6 参数个数 / 公式差异）

社区与库源码一致确认参数个数谱系：

| 版本 | 参数个数 | DECAY 处理 | 备注 |
|---|---|---|---|
| FSRS-4.5 | 17 (`w[0..16]`) | 固定 `-0.5`，`FACTOR=19/81` | 引入 hard/easy `w[15]/w[16]` |
| FSRS-5 | 19 (`w[0..18]`) | 固定 `-0.5`，`FACTOR=19/81` | 新增短期稳定性 `w[17]/w[18]`（intra-day） |
| **FSRS-6** | **21 (`w[0..20]`)** | **`DECAY = -w[20]`（可学习参数，默认 `w[20]=0.1542`）** | `FACTOR=0.9^(1/DECAY)-1`；短期稳定性新增 `S^-w[19]` 指数项；lapse 新增 `S/e^(w17·w18)` 上限 |

> 证据：`scheduler.py:31` `FSRS_DEFAULT_DECAY = 0.1542`；`scheduler.py:32-54` `DEFAULT_PARAMETERS`
> 恰含 21 项；`scheduler.py:187-188` `self._DECAY = -self.parameters[20]` /
> `self._FACTOR = 0.9 ** (1 / self._DECAY) - 1`。论坛帖 `forums.ankiweb.net`（2025-04）列出
> FSRS-5 默认 `w[0..18]`＝19 项且 `baseDecay=-0.5, factor=19/81`，与上表一致。

**本仓基线（FsrsKernel.kt 的 16 项 `w[0..15]`）是"简化 FSRS-5"**：KDoc 自陈"本 App 不产生 EASY、
无第四档"，且只取默认表前 16 项。升级到真 v6 需要补齐 `w[16..20]` 并把 DECAY 变成 `w[20]`。
前 16 项数值（`0.212 … 0.6014`）与 v6 默认表**逐值相同**，漂移从 `w[16]` 起。

## 2. v6 默认参数向量（21 float，取自库 `DEFAULT_PARAMETERS`）

```
w[00]=0.212    w[01]=1.2931   w[02]=2.3065   w[03]=8.2956   w[04]=6.4133
w[05]=0.8334   w[06]=3.0194   w[07]=0.001    w[08]=1.8722   w[09]=0.1666
w[10]=0.796    w[11]=1.4835   w[12]=0.0614   w[13]=0.2629   w[14]=1.6483
w[15]=0.6014   w[16]=1.8729   w[17]=0.5425   w[18]=0.0912   w[19]=0.0658
w[20]=0.1542
```
`DECAY = -0.1542` ｜ `FACTOR = 0.9^(1/DECAY)-1 = 0.9803464944134797`

`w[16]`=easy_bonus ｜ `w[17]`,`w[18]`=短期稳定性 ｜ `w[19]`=短期稳定性 S 衰减指数（v6 新增）｜ `w[20]`=decay 可学习参数（v6 新增）

## 3. 公式（逐条对齐库源码）

- **retrievability**（`scheduler.py:234`）：`R = (1 + FACTOR·t/S)^DECAY`，`t=max(0,elapsed_days)`。
- **initial_stability**（`662`）：`S0(G) = w[G-1]`，`clamp_stability`（下限 0.001）。
- **initial_difficulty**（`669`）：`D0(G) = w[4] − e^(w[5]·(G−1)) + 1`，存储时夹 `[1,10]`。
- **next_difficulty**（`716`）：
  - `linear_damping = (10 − D)·ΔD/9`，`ΔD = −w[6]·(G−3)`
  - `mean_reversion = w[7]·arg1 + (1−w[7])·arg2`，其中 **`arg1 = D0(Easy) 不夹取`（= −4.771630703161737）**，`arg2 = D + linear_damping`，结果夹 `[1,10]`。
- **next_recall_stability**（Hard/Good/Easy，`785`）：
  `S·(1 + e^w[8]·(11−D)·S^−w[9]·(e^((1−R)·w[10])−1)·hard_penalty·easy_bonus)`；
  `hard_penalty=w[15]`（仅 Hard）｜ `easy_bonus=w[16]`（仅 Easy）。
- **next_forget_stability**（Again，`766`，v6 关键改动）：
  - 长期项 `L = w[11]·D^−w[12]·((S+1)^w[13]−1)·e^((1−R)·w[14])`
  - 短期上限 `M = S / e^(w[17]·w[18])`
  - `S'_f = min(L, M)`，再 `clamp_stability`（下限 0.001）。**不再像 v5 那样"地板 1 天 / 上限=当前 S"。**
- **short_term_stability**（intra-day，`elapsed_days<1`，`697`）：
  `increase = e^(w[17]·(G−3+w[18])) · S^−w[19]`；`G∈{Hard,Good,Easy}` 时 `increase=max(increase,1)`；
  `S'_short = clamp_stability(S·increase)`（下限 0.001）。
- **next_interval**（`679`）：`I = S/FACTOR·(desired_retention^(1/DECAY) − 1)`（库会 round 到整天并夹 `[1,maximum_interval]`；**本仓 `nextIntervalDays` 保留连续 Double 契约，不 round**）。
- `STABILITY_MIN = 0.001`（`scheduler.py:56`）。

## 4. StudyKit 装配口径（保持公共契约 + 尽量贴库）

`FsrsKernel.review(state, elapsedDays, rating, conf)` 的分支装配：

1. `firstTime = state.stability == null` → **直接落 `S0(G)` + `D0(G)`（夹取）**，不套增长公式。
   —— 与 py-fsrs 首次评分"存初始值、不生长"一致；也让"新词首次 AGAIN = w[0] = 0.212"的既有断言自然成立。
2. 非首次且 `elapsedDays < 1` → `short_term_stability` + `next_difficulty`。
3. 非首次且 `elapsedDays ≥ 1` → 先算 `R`，再 `next_stability`（Again→forget / 其余→recall）+ `next_difficulty`。
4. `cardState` 映射沿用现状：`AGAIN→RELEARNING`，其余 `→REVIEW`（不改，避免接线破坏）。

**刻意不改的 StudyKit 侧约定（改它会破坏"其它测试必须原样绿"）：**
- `FSRS_HALF_OVER_S = 243/19`（`hDays↔stability` 镜像读数比）：**不动**。它是本仓 A-T2 双审定调的
  **跨内核展示镜像启发常数**，被 `HalfLifeKernel`、`kernelStateOf(mistake)`、`MIGRATION_6_7` 与
  `SchedulingKernelTest` 共用，**不是** FSRS 内部的 decay/factor。v6 的 decay 变化改的是
  `FsrsKernel` 内的 `DECAY/FACTOR`，与该镜像比无耦合。
- 难度镜像 `fsrsDifficultyFromHalfLife` / `halfDifficultyFromFsrs`（线性 `fsrsD=5+(halfD−1)·0.5`）：**不动**。
  v6 未改难度定义域 `[1,10]`，往返测试仍成立。
- 连续间隔（不 round）、`MAX_STABILITY=36500` 上限、`MAX_INTERVAL_FALLBACK=365`、AGAIN→10 分钟地板、
  NaN/脏值 sanitize：**保留**（StudyKit 安全网，对黄金值无影响）。
- `MIN_STABILITY_FLOOR` 由 `0.01` 对齐为库的 `0.001`（sanitize 下限，仅影响 NaN 兜底路径，测试按符号引用自动跟随）。

## 5. 黄金值（`fsrs6-golden.json` 摘录，容差 1e-4）

### 5.1 初始值（firstTime 落库）
| rating | S0 | D0(夹取) |
|---|---|---|
| Again | 0.212 | 6.4133 |
| Hard | 1.2931 | 5.112170705601056 |
| Good | 2.3065 | 2.118103970459016 |
| Easy | 8.2956 | 1.0（未夹取 −4.771630703161737 → 夹到 1） |

均值回归目标 `arg1 = D0(Easy) 未夹取 = -4.771630703161737`。

### 5.2 next_difficulty（从 D=5.0 出发）
Again `8.341762369296838` ｜ Hard `6.665995369296838` ｜ Good `4.9902283692968386` ｜ Easy `3.3144613692968385`

### 5.3 retrievability（S=10）
t=0→1.0 ｜ t=5→0.9403442888929227 ｜ **t=10→0.9（R(t=S) 锚点成立）** ｜ t=20→0.8458846451494336 ｜ t=40→0.782132114874225

### 5.4 间隔恒等式
`I(S=10, desired=0.9) = 10.0`（≡ S，恒等，与 decay 无关）｜ `I(S=10, desired=0.88) = 13.169322520007132`

### 5.5 长期轨迹（t=2.0，起点 S=2.3065 / D=2.118103970459016）
| rating | R | S' | D' | I@0.9 | I@0.88 |
|---|---|---|---|---|---|
| Good | 0.9094932559773545 | **10.964332335820698** | **2.111214235785395** | 10.964332335820698 | **14.439282874696593** |
| Hard | 0.9094932559773545 | **7.513320366762569** | **4.752858488532557** | 7.513320366762569 | 9.894533910603455 |
| Easy | 0.9094932559773545 | **18.52175418175859** | **1.0** | 18.52175418175859 | 24.391895445586968 |
| Again | 0.9094932559773545 | **0.6075801062519337** | **7.394502741279718** | 0.6075801062519337 | — |

> 对比基线（简化 FSRS-5）GOOD 曾为 `S'=10.757465 / D'=2.116986`、AGAIN 曾被夹到 `1.0`：v6 下
> DECAY/factor、均值回归未夹取目标、forget 的 `min(L,M)` 三者都变，故黄金值**必然改变**（见 §5.5 新值）。

### 5.6 镜像/半衰期路径的 AGAIN（防误判首次评分）
| 场景 | S | D | S'（forget） | D' |
|---|---|---|---|---|
| mirrored LEARNING（h=30 回填）AGAIN t=2 | 2.345679012345679 | 3.0 | **0.6004989018212185** | 7.6843759692968385 |
| seeded halfLife(30) AGAIN t=2 | 2.345679012345679 | 2.0 | **0.6156363020281342** | 7.3556827692968385 |

两值均 `>0.3` 且 `≠0.212`（S0），既有"未被误判成首次评分 / lapse 只降不升"防呆断言在 v6 下仍成立。

### 5.7 短期（intra-day，t=0.5，起点 S=2.3065 / D=2.118103970459016）
| rating | S'（short） | D' |
|---|---|---|
| Good | 2.3065（increase<1 被抬到 1，S 不变） | 2.111214235785395 |
| Hard | 2.3065（同上） | 4.752858488532557 |
| Easy | 3.946054067969477 | 1.0 |
| Again | 0.7750839828558984（Again 不抬地板） | 7.394502741279718 |

## 6. 端到端 `review_card` 轨迹（真实库集成证据，fuzzing 关）

新卡 Good→Good→Good→Again→Good→Easy，日间隔 (0,1,3,0,6,12)：

| # | rating | Δ天 | state | stability | difficulty |
|---|---|---|---|---|---|
| 1 | Good | 0 | Learning | 2.3065 | 2.118103970459016 |
| 2 | Good | 1 | Review | 7.31530074407728 | 2.111214235785395 |
| 3 | Good | 3 | Review | 19.833473465759585 | 2.1043313908464483 |
| 4 | Again | 0 | Relearning | 5.785056178601778 | 7.389975788014609 |
| 5 | Good | 6 | Review | 14.404750930188623 | 7.377814181523433 |
| 6 | Easy | 12 | Review | 43.95121765303967 | 6.486830244144543 |

> 第 1 步是库自身的学习步状态机（Learning，落 S0），第 2 步进入 Review 后才生长——印证 §4"首次落 S0、
> 之后才生长"。本仓无学习步状态机，`review()` 一次调用即"落 S0 / 生长"二选一，故本仓的黄金测试用
> **非空的 S/D** 喂"第 2 次及以后"的生长路径（§5.5），与库语义等价。

## 7. 未能核验 / 偏差声明

1. **未与 Anki 桌面端逐比特比对**：本轮预言机=py-fsrs 6.3.2 源码直算；Anki 内置 fsrs-rs 与 py-fsrs
   应同标准，但本机未装 Anki，不声称"= Anki 结果"，只声称"= 官方 py-fsrs 结果"。
2. `next_interval` 库侧 round 成整天 + 夹 `[1,maximum_interval]`；本仓 `nextIntervalDays` 按既有契约
   返回**连续 Double**（不 round），这是契约层差异，非公式差异。
3. 任务书把均值回归写成"w[17] forget 守卫 w[17·18]"等索引为记忆近似；实际以库为准：
   **均值回归系数 = `w[7]`**，forget 短期上限 = `S/e^(w[17]·w[18])`，短期稳定性 = `w[17]/w[18]/w[19]`。
   本报告一律采用库的真实下标。
