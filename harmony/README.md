# StudyKit HarmonyOS 原生版

这是 StudyKit 的 HarmonyOS 5 / ArkTS + ArkUI 原生实现，不是 Android APK 的套壳。
当前版本把学习闭环做完整：66 词本地词库、Leitner 5 盒复习排期、4 选 1 小测与错题本、
今日 0/3 任务卡、签到与补签、学习报告与本地数据重置。所有数据只存在本机。

## 已实现

- 词库：内置 66 个常用词，word / phonetic / meaning / example 四字段完整，完全离线；
- 复习：Leitner 5 盒 SRS，记录每个词的 box、dueDate、lastReviewed、correct、wrong；
  今日待复习按到期日真实计算；“记住了”升一盒（1/2/4/7 天后复习），“再学一次”退回第 1 盒；
  单词页可以撤销上一步复习；
- 小测：从词库生成 4 选 1，每组 5 题；作答后立刻反馈正确答案与例句；逐题落库，
  结果记录当日答题数/正确数；答错自动进错题本，错题可以重练，答对后自动移出；
- 今日任务卡：首页“今天从哪开始”按真实数据算 0/3（签到、复习 5 词、小测 3 题），
  每一步可点、有完成态，全部完成显示“今日已起飞 3/3”和下一步鼓励；
- 签到：整月日历（周一开始）、今天描边、已签/补签状态、月度签到率；
  补签最近 3 天、每月最多 3 次；补签可撤销，撤销后连续/本月统计立即刷新；
  里程碑 3/7/14/30/66 天常亮；
- 学习报告：已掌握、待复习、正确率、连续学习、累计学习、签到累计；
  本地数据可重置，二次确认后清空；
- 导航：学习 / 签到 / 单词 / 练习 / 我的 共 5 个 Tab；暖纸感配色、ArkUI 原生组件；
- 持久化：应用沙箱 `filesDir/studykit_data.json`，不需要账号，不联网。

## 目录

    harmony/
      AppScope/                 应用级配置、图标、字符串
      entry/                    entry HAP 模块
        src/main/ets/
          entryability/         UIAbility 入口
          common/Theme.ets      暖纸感主题令牌
          model/
            Words.ets           66 词词库（word/phonetic/meaning/example）
            Srs.ets             Leitner 5 盒排期与统计
            Quiz.ets            4 选 1 出题、错题记录
            Store.ets           本地 JSON 持久化与撤销栈
            SignIn.ets          签到/补签/连续/里程碑口径
          pages/Index.ets       5 Tab 页面与全部交互
        src/main/resources/
        src/main/module.json5
      build-profile.json5
      hvigorfile.ts
      oh-package.json5
      docs/                     架构、构建证据、AGC 上架记录

## 构建 / 签名

前置条件：

1. DevEco Studio 5.0+ 或 hvigor 6.26+；
2. OpenHarmony / HarmonyOS SDK API 15（本机：E:\dev\ohos-sdk\sdk\15）；
3. 华为签名材料（发布证书 .cer、Profile .p7b、私钥 .p12）。

构建工程不能放在含中文的路径，本项目源码在 `harmony/`，构建副本在 `E:\dev\studykit-harmony`：

    cd E:\dev\studykit-harmony
    node node_modules/@ohos/hvigor/bin/hvigor.js assembleHap --mode module -p product=default -p buildMode=release

产物：`entry/build/default/outputs/default/entry-default-unsigned.hap`

签名（hap-sign-tool，release 证书 + profile）：

    java -jar <SDK>/toolchains/lib/hap-sign-tool.jar sign-app \
      -mode localSign -keyAlias studykit-release -keyPwd <p12密码> \
      -appCertFile studykit-release.cer -profileFile studykit-release-profile.p7b \
      -inFile entry-default-unsigned.hap -signAlg SHA256withECDSA \
      -keystoreFile studykit-release.p12 -keystorePwd <p12密码> \
      -outFile StudyKit-Harmony-v1.0.0-release.hap -compatibleVersion 15 -signCode 1

具体构建/签名结果与哈希见 `docs/BUILD-EVIDENCE.md`。

## 与 Android 版的关系

Android v3.21 的功能不会因为 HarmonyOS 版出现而停止维护。HarmonyOS 版按模块重写，
签到算法口径与 Android 版一致。当前已覆盖：签到/补签/撤销、本地词库、Leitner 复习、小测与错题、
今日任务卡、学习报告；数据模型集中在一个 JSON 文件里，后续可以按需迁移到 RelationalStore。

## 说明

- Words / Srs / Quiz / Store / SignIn 都是纯 ArkTS，不依赖 Android；
- Index.ets 是单页 5 Tab，配色与 Android 暖纸感主题一致；
- module.json5 的 deviceTypes 为上架设备类型 tablet（API 15 SDK 的 device-define 没有 phone，
  加入 phone 会导致 syscap 校验失败）；页面内容按 maxWidth 760 居中，手机/平板宽度都能正常显示。
