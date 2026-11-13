# StudyKit HarmonyOS 完全功能对齐计划

目标：把 Android 端五大模块与学习能力迁到 HarmonyOS 原生 ArkUI，不引入 WebView/伪入口。

## 页面与模块

| Android 能力 | HarmonyOS 落地 | 文件 |
|---|---|---|
| 首页 / 今日任务 / 签到 / 词卡 / 小测 | 已有 | pages/Index.ets |
| 题库练习、即时判分、错题自动入库 | 新增独立页 | pages/QuestionPracticePage.ets |
| 错题本、筛选、掌握、删除、导出 | 新增独立页 | pages/MistakesPage.ets |
| 拍照/相册 OCR 录题、识别文本校对导入 | 新增独立页，HMS Core Vision + 手动兜底 | pages/OcrPage.ets + model/OcrImport.ets |
| 习惯 CRUD、每日/补卡、连续、月历 | 新增独立页 | pages/HabitsPage.ets |
| 书架、阅读进度、摘录/笔记 | 新增独立页 | pages/BooksPage.ets |
| 学习计划模板、编辑、预览、保存 | 新增独立页 | pages/PlanPage.ets |
| 学习统计、记忆报告、分享海报 | 新增独立页 | pages/StatsPage.ets |
| 设置、每日目标、提醒、zip 备份/恢复、清空 | 新增独立页 | pages/SettingsPage.ets |
| 单词库（内置四书/自定义导入） | 现有四书 + 后续 WordManage | pages/WordManagePage.ets（待补） |

## 约束

- 新页面全部 @Entry @Component，router.back() 返回；独立 JSON store 放 filesDir，不互相踩文件。
- 不修改现有 Index/Store 的写范围；Lead 最后统一加路由和入口。
- HMS OCR 需要 build-profile 的 runtimeOS 改为 HarmonyOS；若设备/SDK 不可用，OCR 页仍可手动粘贴文本导入。
- 每个模块 release HAP 构建通过后在真机走查；没有真机时至少 compile + HAP verify，不伪造截图。

## 验收

1. 五个 Android 顶层 tab（学习/计划/习惯/读书/错题）都能打开真实功能页；
2. OCR 可拍照/选图识别并导入题库或错题；
3. 题库→答题→错题本→重做闭环；
4. 习惯/书籍/计划数据重启后仍在；
5. 统计与备份可用；
6. release HAP 构建/签名通过。


## 2026-11-13 构建状态

- 已新增并注册：QuestionPracticePage、MistakesPage、OcrPage、WordManagePage、HabitsPage、BooksPage、PlanPage、StatsPage、SettingsPage。
- build-profile 改为 HarmonyOS runtimeOS / API 26，启用 HMS Core Vision OCR 动态导入；DEVECO_SDK_HOME=E:/dev/DevEcoStudio26b/sdk 构建。
- release HAP BUILD SUCCESSFUL，ArkTS 0 ERROR（只有 deprecated/throws WARN）；HAP 已用 AGC release 证书 sign-app，verify-app 通过。
- 未完成真机验证：当前 hdc 只有 COM3/COM4，无设备；API 26 兼容性/OCR 运行时/文件选择器/zlib/日历需真机走查。
- 仍需说明：Android 的提醒通知、复杂考试/模考、完整 OCR 版面校正等边界功能是简化实现，不是逐像素/逐算法 1:1。
