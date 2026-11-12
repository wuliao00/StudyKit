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
