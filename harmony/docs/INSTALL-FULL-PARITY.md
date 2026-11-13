# StudyKit 完全移植安装说明（2026-11-13 真机验证）

## 推荐安装

当前 MatePad 11.5 S（API 26 / HarmonyOS 7.0.0）真机已验证：

- 包：StudyKit-Harmony-v1.0.0-full-parity-api26-ocr-debug.hap
- 真机结果：安装成功；完整功能入口可打开；习惯示例 + 打卡成功；题库答题 + 解析正常；拍照/相册选图后 HMS OCR 识别 61 行文本成功。
- 发布签名包（同一份代码）：StudyKit-Harmony-v1.0.0-full-parity-api26-ocr.hap；本地 HDC 会因 AGC 发布证书“not trusted app source”拒绝，需走 AppGallery/正式签名渠道。

## 夸克网盘

https://pan.quark.cn/s/62ae851310a0

包含：
- 鸿蒙 API26 调试包（真机验证用）
- 鸿蒙 API26 发布包
- 鸿蒙 API15 调试包（旧设备兜底）
- Android v3.22.0 release APK

GitHub：
https://github.com/wuliao00/StudyKit/releases/tag/v3.22.0

## 已验证

- 首页、我的、完整功能入口
- 学习计划页 6 模板
- 习惯页：示例习惯、打卡、连续 1 天、月历/补卡入口
- 题库练习：答题、即时判分、解析
- 拍照/相册 OCR：真实 HMS Core OCR 返回 61 行

## 仍有限制

- 习惯打卡、题库只做了主流程真机点验；读书/统计/设置/备份尚未逐项点验。
- 提醒仍是页内本地提醒，不是系统通知；Android 的复杂模考等边界功能未逐项复刻。
- API15 包 OCR 依赖运行期 HMS Core 模块，当前设备未能加载，会回退手动粘贴；真正的 OCR 用 API26 包。
