# v3.21 看板签到 / 补签配额与撤销 真机验证

> 设备：vivo V2156A / Android 11 / 1080x2408。构建：assembleDebug，debug 签名与设备 3.20.0 一致。

## 1. 覆盖升级

- 设备原版本：3.20.0 / 42（Room v16）。
- 安装 3.21.0 / 43 后：versionCode=43、versionName=3.21.0；Room 仍 v16（无 schema 变更）。

## 2. 补签配额与撤销走查

1. 打开签到日历，标题下显示「本月补签 0/3 次」，11-04~11-06 可补签；
2. 补签 11-06：确认后格子变 goldSoft 底 + 金点，显示「本月补签 1/3 次」，连续 2 天、
   本月 2/7（28%）（v321-device-calendar-makeup.png）；
3. 再点 11-06 补签格子：弹「撤销补签」确认框（v321-device-undo-dialog.png）；
4. 确认撤销：格子回到「可补签」浅描边，标题变「本月补签 0/3 次」、连续 1 天、本月 1/7（14%）
   （v321-device-calendar-undone.png）；
5. 数据库核对：撤销后 daily_sign_ins 仅 ('2026-11-07', makeup=0)。

## 3. 记忆看板签到卡

- 返回首页点「记忆看板 ›」：顶部新增「签到」卡，显示连续签到 / 最长连续 / 本月签到（1/7）
  三个读数、累计/本周文案与 3/7/14/30/66 徽章（v321-device-stats.png）。

## 4. 首轮真机问题与修正

- 日历格一度仍按「已签到」渲染（teal 底），且补签格无法点开撤销：原因是 monthGrid 没把
  makeupDays 传进去；改为 monthGrid(month, signedDays, today, makeupDays) 后，补签格正确
  goldSoft + 金点，撤销弹层可打开，复验截图即修正后版本。
- 看板签到卡最初用三个嵌套 StatTile，窄列把「连续签到」等标签挤成两行；改为轻量 SignInStat
  三列后，标签/数值均单行显示。

## 5. 回归

- JVM：199 测试类 / 1737 用例 / 0 失败（1 条真模型评测跳过）；
- 新增 2 条 SignInStatsTest（月度配额、撤销窗口）+ DAO 撤销测试；
- 本版无 schema 变更，旧数据/71 题/pending 0 全部保留；验证后未留测试补签。
