# HarmonyOS HAP 构建 / 签名证据（2026-11-12 功能完善版）

## 工具链

- 源码：E:\项目\studykit\harmony；构建副本：E:\dev\studykit-harmony（hvigor 拒绝含中文路径）
- hvigor 6.26.9 + @ohos/hvigor-ohos-plugin 6.26.9
- SDK：OpenHarmony SDK 5.0.3.135（API 15，E:\dev\ohos-sdk\sdk）
- Node：v24.18.0

## 构建命令

    cd E:\dev\studykit-harmony
    node node_modules/@ohos/hvigor/bin/hvigor.js assembleHap --mode module -p product=default -p buildMode=release

结果：**BUILD SUCCESSFUL in 4 min 24 s 532 ms**（33 tasks）。

- 首次全量构建（18 min 20 s）日志：build-release-2.log（同 BUILD SUCCESSFUL）；
- 最终增量构建日志：build-release-3.log / build-release-final.log；
- ArkTS 编译（CompileArkTS）通过，日志中 0 条 ERROR，仅 “skip sign” 提示（本项目用 hap-sign-tool 单独签名）。

## 未签名产物

    entry/build/default/outputs/default/entry-default-unsigned.hap
    大小：244,500 B

## 签名（hap-sign-tool，AGC 发布证书 + 发布 Profile）

    E:/dev/jdk/bin/java.exe -jar E:/dev/ohos-sdk/sdk/15/toolchains/lib/hap-sign-tool.jar sign-app \
      -mode localSign -keyAlias studykit-release -keyPwd 123456 \
      -appCertFile E:/dev/studykit-release.cer \
      -profileFile E:/dev/studykit-release-profile.p7b \
      -inFile entry-default-unsigned.hap -signAlg SHA256withECDSA \
      -keystoreFile E:/dev/studykit-release.p12 -keystorePwd 123456 \
      -outFile StudyKit-Harmony-v1.0.0-release.hap \
      -compatibleVersion 15 -signCode 1

- profile 内 bundle-name = com.studykit.study，type = release，distribution-type = app_gallery；
- 证书：莫松钧(2079002878751576897) / Release（Huawei CBG Developer Relations CA G2 签发）；
- 结果：sign-app success（sign-release-3.log）。

## 最终 HAP

    E:\dev\studykit-harmony\release\StudyKit-Harmony-v1.0.0-release.hap
    大小：268,867 B
    sha256：50056954C07CF78A1D79D58F2F444378EFC4E651B30842E12DE94C60D6AD4F75
    证据副本：E:\dev\harmony-evidence\StudyKit-Harmony-v1.0.0-release.hap

verify-app 校验：verify exit 0，“Digest verify result: true”，“verify: Verify success”
（verify-app.log / verify-certchain.cer / verify-profile.p7b）。

## 真机状态（2026-11-12 19:50）

- 主机 hdc 此时看不到设备：USB 只有 WPD/MTP（VID_12D1&PID_1101），无 HDC Interface，
  `hdc list targets` 为空（轮询日志 hdc-poll.log）；
- 已通知 Lead 让用户重新插线并在平板允许调试；设备恢复后执行：
  `hdc -t 8ZQUN26602G03715 install -r StudyKit-Harmony-v1.0.0-release.hap` 并走验收流程。

## 历史（2026-11-07/09，MVP 版本）

- 旧版 24 示例词 MVP 曾用 OpenHarmony 本地证书签名（设备拒绝），后改用 AGC 调试证书安装成功；
- 旧构建证据见 git 历史中的本文件。


## 2026-11-12 第一眼价值版（task-8：四书/示例错题/海报/记忆曲线）

构建命令不变（E:\dev\studykit-harmony，release）：

    node node_modules/@ohos/hvigor/bin/hvigor.js assembleHap --mode module -p product=default -p buildMode=release

- 首次构建（全量）：BUILD SUCCESSFUL in 8 min 12 s，CompileArkTS 3m10s，0 ERROR；
  日志：E:\dev\product-evidence\build-release-task8-1.log
- 最终构建（清理文案后增量）：BUILD SUCCESSFUL in 2 min 25 s，CompileArkTS 1m20s，0 ERROR；
  日志：E:\dev\product-evidence\build-release-task8-final.log

签名（与之前一致：AGC 发布证书 + 发布 Profile localSign）：

    java -jar E:/dev/ohos-sdk/sdk/15/toolchains/lib/hap-sign-tool.jar sign-app \
      -mode localSign -keyAlias studykit-release -keyPwd 123456 \
      -appCertFile E:/dev/studykit-release.cer \
      -profileFile E:/dev/studykit-release-profile.p7b \
      -inFile entry-default-unsigned.hap -signAlg SHA256withECDSA \
      -keystoreFile E:/dev/studykit-release.p12 -keystorePwd 123456 \
      -outFile StudyKit-Harmony-v1.0.0-task8-release.hap \
      -compatibleVersion 15 -signCode 1

- 结果：sign-app success；verify-app：Digest verify true / Verify success；
- 最终 HAP：E:\dev\product-evidence\StudyKit-Harmony-v1.0.0-task8-release.hap
  （349,133 B，sha256 B29728BDA33513B38E46E1F7A298D23B15F5A855D488B15D0172F73ED83D05FF）
- 副本：E:\dev\StudyKit-Harmony-task8-final.hap
- 未签名产物：E:\dev\product-evidence\StudyKit-Harmony-v1.0.0-task8-unsigned.hap（319,884 B）
- 日志/校验/源码自检/内容扫描：E:\dev\product-evidence

task-8 功能点（源码级自检见 source-feature-check.txt）：66 词分四本词书、3 道示例错题（60 秒倒计时）、
Canvas 海报（OffscreenCanvas + toDataURL + Base64 解码 + fs 写 PNG）、“再复习 X 次解锁个人记忆曲线”、
副标题“StudyKit · 错题本 · 背单词 · 打卡”；死入口/未完成关键词扫描为 0 命中。

## 真机状态（2026-11-12 22:42）

- 22:41 执行 `hdc list targets -v` 时曾短暂出现 `8ZQUN26602G03715 USB Offline`，
  随后 hdc 重启（kill/start）后又只剩 COM3/COM4 UART Ready，设备未就绪，安装未执行；
- 已放置自动衔接脚本：E:\dev\product-evidence\device-auto-install.ps1
  （最多轮询 20 分钟，设备 Ready 后自动 install + 启动 + 截图 + dumpLayout + bm dump，
  日志写 E:\dev\product-evidence\device-auto-install.log）；
- 设备恢复后可继续按 E:\dev\harmony-evidence\device-walkthrough.md 做 5 tab 走查、示例 60 秒复习、
  海报 PNG 生成、重启持久化验证。
