# StudyKit HarmonyOS 原生版（MVP）

这是 StudyKit 的 HarmonyOS 5 / ArkTS + ArkUI 原生实现，不是 Android APK 的套壳。
当前是一期 MVP：首页 + 每日签到 + 补签/撤销 + 里程碑 + 本地词表复习。

> **构建状态（2026-11-07）**：源码已用 **OpenHarmony SDK 5.0.3（API 15）+ hvigor 6.26.9**
> 成功编译并签出 HAP（247,720 B），证明 ArkTS 代码可构建；但 HarmonyOS 5 真机安装仍受两点限制：
> ① HDC 当前显示设备 Unauthorized，需要在平板上点「允许」调试授权；
> ② 现有签名是 OpenHarmony 本地调试证书，HarmonyOS 5 正式安装通常要求华为开发者签名。
> 本机没有 HarmonyOS 5 SDK/华为账号，因此**尚未在 MatePad 上完成安装与截图**；不会把未安装说成已验机。

## 已实现

- 首页：暖纸感原生布局、今日待办卡、三项读数、签到卡、本周进度、入口卡；
- 签到：整月日历（周一开始、42 格）、今天描边、已签/补签状态、月度签到率；
- 补签：最近 3 天、每月最多 3 次；点补签格弹确认框；补签格金底金点；
- 撤销：最近 3 天内的补签可撤销，连续/本月统计立即刷新；
- 里程碑：3/7/14/30/66 天常亮徽章，首页显示已达成的最高里程碑；
- 本地持久化：签到记录、已掌握词存 HarmonyOS Preferences，不上传；
- 单词：24 个示例词、翻卡/再学/记住了、进度与重置；
- 平板适配：自适应宽度、Tabs 底栏、滚动页面，不使用 Android 资源。

## 目录

    harmony/
      AppScope/                 应用级配置、图标、字符串
      entry/                    entry HAP 模块
        src/main/ets/
          entryability/         UIAbility 入口
          model/                SignInStats / StudyStore / 示例词
          common/               暖纸感主题令牌
          pages/Index.ets       四个 Tab + 签到日历 + 单词页
        src/main/resources/
        src/main/module.json5
      build-profile.json5       HarmonyOS 5.0.0(12)
      hvigorfile.ts
      oh-package.json5

## 构建 / 运行

前置条件：

1. DevEco Studio 5.0+（支持 HarmonyOS 5.0.0 / API 12）；
2. HarmonyOS SDK 5.0.0(12)（DevEco → Settings → SDK 下载）；
3. 华为账号登录 DevEco，在 Project Structure → Signing Configs 勾选自动签名
   （HarmonyOS 真机必须使用华为签名）。

步骤：

1. DevEco Studio → Open → 选择本目录 harmony/；
2. 等待 hvigor sync 完成（首次会下载 ohpm 依赖）；
3. File → Project Structure → Signing Configs → 勾选 Automatically generate signature；
4. 运行设备选择已开启开发者模式的 MatePad，Run entry。

命令行构建（SDK/签名配置好后）：

    cd harmony
    ohpm install
    hvigorw assembleHap --mode module -p product=default -p buildMode=debug

产物：entry/build/default/outputs/default/entry-default-signed.hap。

## 与 Android 版的关系

Android v3.21 的功能不会因为 HarmonyOS 版出现而停止维护。HarmonyOS 版按模块重写，
签到算法口径与 Android 版一致：

- 连续签到允许“今天未签但昨天签过”保留；
- 补签窗口最近 3 天、每月 3 次；
- 签到/补签都计入连续、累计、本月统计；
- 里程碑按历史最长连续常亮。

## 后续路线

1. 词库导入 + FSRS 排期（对齐 Android 内存模型）；
2. 题库练习 / 错题本；
3. OCR 拍照识别（HarmonyOS 端侧模型/云侧可选）；
4. 数据导入导出（与 Android Room 迁移格式兼容）；
5. 习惯 / 读书 / 契约模块。

## 说明

- SignIn.ets / StudyStore.ets 是纯 ArkTS，不依赖 Android；
- Index.ets 是单页四 Tab，配色与 Android 暖纸感主题一致；
- 项目按 HarmonyOS 5 Stage 模型组织，API 12；低版本设备需要改 compatibleSdkVersion 与 API 调用。
