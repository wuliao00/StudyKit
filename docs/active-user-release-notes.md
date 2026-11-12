# StudyKit 主动用户版更新说明（审核修复）

## 为什么改

华为审核反馈“功能未完善”。论文《Paradox of the Active User》指出：用户是来完成任务的，不是来先学系统的；产品应把学习藏进可完成的小动作，并让每一步可撤销、可理解。

## HarmonyOS 1.0.0（重新提交）

- 66 个真实词条（word / phonetic / meaning / example），不再使用示例词；
- 本地 Leitner 5 盒复习排期：box、dueDate、correct/wrong 全部落盘；“记住了/再学一次”真实更新，可撤销上一步；
- 4 选 1 小测：5 题一组、即时反馈、正确答案 + 例句、错题本、错题优先重练；
- 首页「今天从哪开始」真实 0/3：签到 / 复习 5 词 / 小测 3 题，可点、完成态、3/3 奖励；
- 练习 tab：小测、错题本、最近小测；
- 我的：已掌握 / 待复习 / 正确率 / 连续 / 累计 / 签到报告，二次确认重置全部本地数据；
- 删除“示例词 / MVP / 后续接入 / 题库练习 / 拍照识别”等未完成文案与死入口；
- 5 tab：学习 / 签到 / 单词 / 练习 / 我的；暖纸感原生 ArkUI。

## Android 3.21 补丁

- 首页 hero 上方新增「今天从哪开始」三步引导卡（签到 / 复习 / 小测，真实数据、每日重置、可收起）；
- 新增「为什么这样安排」底部说明（本地优先 / 可撤销 / 无账号 / 三步设计）；
- 模考 / 错题本 / 待校对收进「更多学习方式」折叠区，首屏保留题库练习主入口；
- 不改既有功能、包名与签名。

## 验证

- Harmony HAP：release 构建成功，CompileArkTS 0 ERROR；hap-sign-tool verify-app 通过；`release/StudyKit-Harmony-v1.0.0-active-user.hap`（268,445 B，sha256 2F636A24…570D5C）；
- Android：assembleDebug BUILD SUCCESSFUL（8m13s）；APK `release/StudyKit-v3.21.0-active-user-debug.apk`（121,517,050 B，sha256 4bd8b8b4…37ed6ac）；
- 真机：MatePad 当前未连接（USB 仅 WPD/MTP），恢复后补安装走查与截图。

## 交付物

- [Android APK](../release/StudyKit-v3.21.0-active-user-debug.apk)
- [HarmonyOS HAP](../release/StudyKit-Harmony-v1.0.0-active-user.hap)
- [视觉稿首页](../design/active-user/home.png)、[完成态](../design/active-user/done.png)、[小测](../design/active-user/quiz.png)

## 2026-11-12 真机修复

- 真机发现首页任务行/指标在 Tab 间返回时不刷新（数字 3/3 但行仍显示“去完成”），已给任务卡、任务行、指标行加随 `viewVersion`/完成态变化的 `.id`，强制重建。
- 重新构建、签名、安装到 MatePad 11.5 S；实测签到→复习 5 词→小测 5 题→3/3、正确率 100%，重启后数据仍在。
