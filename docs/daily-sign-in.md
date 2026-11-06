# 每日签到与首页交互（v3.17）

## 1. 数据模型

- 表：daily_sign_ins，一天一行，主键 day（本地日期 ISO 字符串 yyyy-MM-dd），created_at 毫秒时间戳。
- Room：v14 -> v15 纯加表迁移（MIGRATION_14_15），旧表一列不动。
- DAO：INSERT OR IGNORE（重复签到幂等，返回 -1）；observeDays / allDays / deleteAll。
- 仓储：DailySignInRepository.signIn(today) 只负责落库；连击与日历全部由纯函数现算。

## 2. 统计口径（SignInStats）

- 今天已签：从今天往前数连续天数；
- 今天未签、昨天签过：从昨天往前数，首页显示「已连续 N 天，今天签到续上」，避免零点一过就归零；
- 今天和昨天都没签：0；
- 未来日期、无法解析的脏字符串直接忽略；
- 最长连续：所有签到日期排序后扫描间隔；
- 最近 14 天：从旧到新，最后一项是今天（保留给统计/兼容）；
- 本周：周一到周日 7 格，未来日期不涂色，供签到卡圆点与「本周 X/7」使用；
- 整月网格：周一开始、月初补空、固定 42 格（6 行），供底部日历翻月使用；
- 里程碑：连续 3/7/14/30/66 天当天由 ViewModel 发一次提示。

## 3. UI 与动效

- 首页「每日签到」卡（Hero 与统计带之后、入口卡之前）：
  - 左：签到徽章（未签 accentSoft，已签 goldSoft，颜色与缩放 spring）；
  - 中：连续文案 + 本周 7 天圆点 + 「本周 X/7」（已签 accent 且 1.0 缩放，今天未签 accent 35%，其余 divider 0.78）；
  - 右：签到按钮，AnimatedContent 在「签到 / ✓ 已签到」之间淡入 + 缩放切换；
  - 卡片主体点击打开整月签到日历（ModalBottomSheet，可翻历史月）；按钮只签到，不打开日历。
- 签到成功：
  - 轻触觉反馈（HapticFeedbackType.LongPress，只在这一下）；
  - 首页外层 ConfettiBurst（90 粒 / 1600ms），trigger 为事件号，只有事件发生后才挂载，冷启动不空放；
  - reduceMotion 开启时 ConfettiBurst 与 press 回弹自动收敛（沿用现有组件契约）。
- 顺手优化：
  - 首页设置/录题/批量录入三枚 IconButton 换成 BouncyIconButton（IconButton + pressScale，触摸目标仍 48dp）；
  - 待校对入口用 AnimatedVisibility（expand + fade）出现/消失，批量确认后不再瞬间跳变；
  - 底栏选中 Tab 图标叠加 1.08 倍 spring 缩放（与按压回弹叠乘）。

## 4. 测试

- SignInStatsTest：空库、昨天连击、今天续上、断档最长、未来/脏数据。
- DailySignInDaoTest：重复签到幂等 + Flow 能读到日期。
- Migration14To15Test：v14 旧库升 v15，题目不丢，签到表可写可读。

## 5. 已知边界

- 日期口径跟随设备本地时区；跨零点时已组合的首页不会自动重算，重新进入首页/重新订阅后刷新（与既有 StudyStreak 一致）。
- 日历只翻历史月、不翻未来；没有补签功能；历史数据仍在库中，累计/最长按全量计算。
- 没有积分/奖励体系，签到只记录行为与连击。
