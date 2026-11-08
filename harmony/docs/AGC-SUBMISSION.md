# HarmonyOS 应用市场（AGC）上架记录

> 记录时间：2026-11-08。应用已在华为 HarmonyOS 开发者中心（原 AppGallery Connect）创建，
> 但**尚未提交审核**；真机安装/截图仍未完成，因此不能声称已上架。

## 应用信息

- 应用名称：StudyKit
- APP ID：6917621229664802173
- 应用包名：com.studykit.study
- 应用类型：HarmonyOS 应用
- 支持设备：平板
- 应用分类：应用
- 所属项目：StudyKit
- 版本状态：准备提交

## 签名材料（账户内生成，私钥在本机 E:\dev）

- 调试证书：StudyKit Debug（有效期至 2027-11-08）
- 调试设备：MatePad 11.5 S，UDID A3B9958BC64F7AD33CDDF9B11646FE98D20A502A94560EC240E9ADC1961A16D3
- 调试 Profile：StudyKit Debug
- 发布证书：StudyKit Release（有效期至 2029-11-08）
- 发布 Profile：StudyKit Release
- 私钥文件：E:\\dev\\studykit-debug.p12、E:\\dev\\studykit-release.p12
- 证书/Profile：E:\\dev\\studykit-debug.cer / studykit-debug-profile.p7b / studykit-release.cer / studykit-release-profile.p7b

## 已签出的 HAP

- 调试：release/StudyKit-Harmony-v1.0.0-debug-agc.hap
  - 大小 248808 B
  - sha256 a16c99efe1f2235a82ab9300803c31a86885421c580647ab6aa784f8e399abe1
- 发布：release/StudyKit-Harmony-v1.0.0-release.hap
  - 大小 248726 B
  - sha256 f92b4b92c12f2abb6b5befa36ae4bfd83a1538f17a14572ddbcf657823ef1c52

## 当前阻塞

- 2026-11-08 11:45 起 HDC list targets 为空（平板断开/未授权），无法安装调试 HAP；
- 应用市场提交还需要：应用图标、截图、隐私政策 URL、客服联系方式、分类标签等资料；
- 当前 AGC 页面停留在应用信息填写，尚未点“保存/下一步/提交”。

## 下一步

1. 平板重新连接并允许 HDC 调试；
2. hdc install -r release/StudyKit-Harmony-v1.0.0-debug-agc.hap
3. 启动应用截图（首页/签到/补签/单词/我的），补 AGC 应用信息；
4. 上传发布 HAP，填写隐私政策/客服信息后提交审核。
