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
  - 大小 256190 B
  - sha256 40110f25f27e7ec1077d9815e47f3cedac2f5dcbe6691abdfb7b22c36844b5bd
- 发布：release/StudyKit-Harmony-v1.0.0-release.hap
  - 大小 256103 B
  - sha256 3f68fb09215338df55fa60dac2cd3433652fb333bcc49e651498302fd5ff38e1

## 真机验证（2026-11-09）

- 平板重新连接后 HDC 正常，调试 HAP 安装成功并启动；
- 已验证：首页、整月签到日历、补签确认、撤销补签、单词翻卡/记住、我的页；
- 修复了补签重复入口、撤销后统计/日历不刷新、首页已掌握计数不刷新等 ArkUI 状态缓存问题；
- 证据截图：docs/evidence/harmony/01-home.jpeg 至 06-profile.jpeg。

## 当前阻塞

- 应用市场提交还需要：应用图标已准备（harmony/store/app_icon_1024.png）、
  截图已准备、隐私政策 URL、客服联系方式、分类标签等资料；
- 当前 AGC 页面停留在应用信息填写，尚未点“保存/下一步/提交”。

## 下一步

1. 平板重新连接并允许 HDC 调试；
2. hdc install -r release/StudyKit-Harmony-v1.0.0-debug-agc.hap
3. 启动应用截图（首页/签到/补签/单词/我的），补 AGC 应用信息；
4. 上传发布 HAP，填写隐私政策/客服信息后提交审核。


## 应用市场资料（2026-11-09 更新）

- 客服邮箱：kino114514@hotmail.com
- 隐私政策：https://wuliao00.github.io/StudyKit/privacy-policy.html
- 应用图标：harmony/store/app_icon_1024.png（已上传）
- 应用分类：教育 / 应用
- 应用标签：学习、语言学习、备考（主标签：学习）
- 截图：docs/evidence/harmony/01-home.jpeg 至 06-profile.jpeg

## 最终提交阻塞

华为开发者中心登录会话在 2026-11-09 13:20 左右过期，页面跳转
id1.cloud.huawei.com 登录页，无法完成“保存应用信息 / 下一步 / 提交审核”。
需要重新登录华为账号后继续；应用本身已安装并在 MatePad 上验证。


## 2026-11-09 13:58 上架包已接收

- 重新以 release 模式构建 HAP（去掉 debug:true），生成并签名 .app：StudyKit-release-app3.app
- AGC 软件包管理显示合法性「已达标」；
- 版本信息页已选择 1.0.0（1）版本并保存草稿；
- 应用信息已保存：应用名称、图标、教育分类、标签（学习主标签/语言学习/备考）、客服邮箱。

## 提交审核仍需用户提供（平台强制）

1. 应用负责人手机号 + 短信验证码；
2. APP 备案信息：主体类型、主办单位名称、主办单位证件号；
3. 版权信息：软件著作权/电子版权证书（或授权材料）；
4. 其余可代填：应用介绍、3~10 张截图、年龄分级问卷、隐私政策 URL、隐私标签。

隐私政策：https://wuliao00.github.io/StudyKit/privacy-policy.html
