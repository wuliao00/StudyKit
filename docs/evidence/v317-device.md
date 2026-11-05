# v3.17 每日签到与首页动效 真机验证

> 设备：vivo V2156A / Android 11 / 1080x2408。构建：assembleDebug，debug 签名与设备 3.16.0 一致。

## 1. 覆盖升级与数据

- 设备原版本：3.16.0 / 38。
- 安装 3.17.0 / 39 后：versionCode=39、versionName=3.17.0。
- Room v14 -> v15 自动迁移（新增 daily_sign_ins），原有数据保留：
  - questions 71 条、needs_review=1 为 0；
  - user_version=15，daily_sign_ins 表存在。

## 2. 签到走查

1. 首页签到卡未签状态：「每日签到 / 每天来一下，积少成多 / 签到」，7 天圆点全灰
   （v317-device-before-signin.png）；
2. 点「签到」：按钮切换为「✓ 已签到」，徽章转 goldSoft，最后一天圆点变 accent，
   全屏彩带（v317-device-confetti.png），文案变为「已连续签到 1 天」（v317-device-signed.png）；
3. 点卡片主体打开「签到日历」：累计 1 天 / 最长连续 1 天，14 天两行网格，
   今天 5 号描边 + 圆点，底部「今天已签到，明天记得再来。」（v317-device-calendar.png）；
4. 落库校验：daily_sign_ins 存在 ('2026-11-05', 1793892538177)，重复点击不会新增行（INSERT OR IGNORE）。

## 3. 首轮真机问题与修正

- 首轮截图发现按钮文案继承了 caption 的 secondaryText 灰色，压在 accent 实底上对比度不足；
  已改为显式 onAccent（浅色）/ successInk（已签到），复验截图为最终版本。
- 日历里从「今天签到」进入时先收起弹层，让首页彩带可见（卡片上的签到不收起）。

## 4. 其它回归

- OCR 主链抽查：clearPageIsRecognizedWithReport OK 3.76s（本版未动 OCR）。
- JVM 全量：198 测试类 / 1727 用例 / 0 失败（1 条真模型评测无数据集时跳过）。
- 动效收敛：签到按钮 pressScale / 卡片进度条不受影响；reduceMotion 开启时彩带与 press 回弹按既有组件契约收敛。
