# StudyKit 完全移植安装说明（2026-11-13）

## 包选择

| 文件 | 适用 | OCR |
|---|---|---|
| StudyKit-Harmony-v1.0.0-full-parity-api15-ocr.hap | 当前 MatePad / HarmonyOS 5（API15+），推荐先装这个 | 运行时尝试加载 HMS Core OCR；不可用时页面自动切手动粘贴 |
| StudyKit-Harmony-v1.0.0-full-parity-api26-ocr.hap | HarmonyOS API26 设备 | 编译期 HMS Core Vision OCR |
| StudyKit-v3.22.0-release.apk | Android | Android 原生 PP-OCR |

## 网盘

夸克：https://pan.quark.cn/s/49651acdcc99

GitHub：https://github.com/wuliao00/StudyKit/releases/tag/v3.22.0

## 已移植模块

- 学习首页 / 60 秒开始 / 3 道示例错题 / 签到 / 词书 / 小测
- FSRS v6 公式（21 参默认参数）与 忘记/模糊/记住了 三档评分
- 自定义单词库（增删改、批量导入、导出）
- 题库练习 → 判分 → 解析 → 错题自动入库 → 错题重做
- 错题本：筛选、笔记、标记掌握、导出
- 拍照/相册 OCR：HMS Core Vision（API26 编译期 / API15 运行期）+ 手动粘贴兜底
- 习惯：CRUD、每日/补卡、连续天数、7 日/月历、数量型
- 读书：书架、阅读进度、摘录/笔记
- 学习计划：6 模板、编辑、实时预览、保存/删除
- 统计：真实数据、记忆保持率、分享海报
- 设置：每日目标、本地提醒、zip 导出/导入、清空二次确认

## 边界

- 当前没有 HDC 设备连接，未做真机点验；API26 包是否能在具体平板安装取决于设备 API 版本。
- 提醒是页内本地提醒，不是系统通知；Android 的复杂模考/完整通知调度没有逐项复刻。
- OCR 是否真的识别取决于设备是否带 HMS Core OCR；API15 包会失败回退手动粘贴，不伪造识别成功。
