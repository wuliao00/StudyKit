# 更新日志

本项目遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)，格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [1.0.0] - 2026-08-28

### 新增
- 四大功能模块：背单词 / 刷题、习惯打卡（含数量型目标与补卡）、读书笔记、错题整理
- 全局日历视图：聚合打卡记录与系统日历事件，按日查看明细
- 复习提醒：WorkManager 周期任务，到期错题与单词本地通知
- 学习数据导出：打卡记录 CSV 导出、成就文本分享
- Debug 构建首装自动播种演示数据（Release 构建不播种）

### 构建
- Kotlin 2.0.21 + Jetpack Compose（BOM 2024.12.01）+ Room 2.6.1（KSP）
- Gradle 依赖与插件版本统一收敛至 `gradle/libs.versions.toml` 版本目录
- CI：GitHub Actions（JDK 17 temurin），执行 lint / 单元测试 / Debug 构建
- Release 构建开启代码混淆与资源收缩（`isMinifyEnabled` / `isShrinkResources`）
  > 注意：混淆后的 release 包需在本地真机完整回归验证后方可发布。

### 修复
- 日历权限声明修正为官方 `READ_CALENDAR`（移除无效的 `READ_CALENDARS`）
- 删除错题时改为按 id 从数据库查询后再清理图片文件，不再依赖 UI 缓存
- 复习提醒的到期筛选下推至 SQL，避免全表载入内存过滤
