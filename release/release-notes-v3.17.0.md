# StudyKit v3.17.0

> 版本号 3.17.0 / 39。Room v15（新增 daily_sign_ins，纯加表）。
> 这一版加了每日签到，并把首页按钮、入场和反馈动效统一了一轮。

## 每日签到

- 首页 Hero 下方新增「每日签到」卡：连续/累计天数 + 最近 7 天圆点 + 签到按钮；
- 点击卡片打开 14 天签到日历，今天签到后日历/圆点/文案同步更新；
- 一天一行、重复点击幂等；今天未签但昨天签过时保留连击，避免零点一过归零；
- 签到成功：轻触觉 + 全屏彩带，按钮切换为「✓ 已签到」。

## 交互与动效

- 首页设置 / 录题 / 批量录入三枚图标按钮统一回弹反馈（BouncyIconButton）；
- 待校对入口出现/消失改为展开 + 淡入淡出；
- 底栏选中 Tab 图标加 1.08 倍 spring 缩放；
- 所有动效沿用 reduceMotion 开关：开启后彩带与回弹收敛，不额外消耗帧。

## 数据迁移

Room v14 -> v15：CREATE TABLE daily_sign_ins（day 主键 + created_at），旧表与旧数据不动。

## 机检

198 测试类 / 1727 用例 / 0 失败（1 条真模型评测在未传 -PocrEvalDir 时跳过）。
  新增 SignInStatsTest（5 条）、DailySignInDaoTest（1 条）、Migration14To15Test（1 条）。

## 真机验证

## 真机验证（vivo V2156A / Android 11）

- **覆盖升级**：3.16.0/38 -> 3.17.0/39 直接覆盖安装成功；Room v14 -> v15 自动迁移，
  questions 71 条 / pending 0 保留，daily_sign_ins 表可写。
- **签到走查**：未签卡 -> 点签到 -> 按钮转「✓ 已签到」+ 全屏彩带 -> 文案变「已连续签到 1 天」；
  点卡片打开 14 天签到日历；数据库落一行 2026-11-05，重复点击幂等。
- **首轮问题**：按钮文案继承 caption 灰色，已改显式 onAccent/successInk 并复验。
- **OCR 抽查**：clearPageIsRecognizedWithReport OK 3.76s。
- 证据：docs/evidence/v317-device.md 与 4 张截图。

## 产物

| 文件 | 字节 | sha256 |
| --- | --- | --- |
| release/StudyKit-v3.17.0-debug.apk | 121444808 | 14eb87fb3d35e1418b9a5dc0586a3c45f2a2d94b5c4c22c1b4e854193bed89e0 |

## 已知边界

- 签到日期按设备本地时区；跨零点后需重新进入首页才会刷新（与既有连续学习天数一致）；
- 签到日历只展示最近 14 天，不做整月翻页；无积分/奖励体系；
- 其它 OCR/切题限制同 v3.16。
