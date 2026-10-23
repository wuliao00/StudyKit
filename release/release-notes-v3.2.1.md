# StudyKit v3.2.1：更新下载页切到 Gitee Releases

> **状态：v3.2.1 发版说明（2026-10-23）。** 这一版只改「去下载」的去向与版本号，
> **不改功能、数据与调度**；Room 仍是 `version = 9`，从 3.2.0 覆盖升级不迁移。

## 改了什么

- `UpdateChecker.DOWNLOAD_PAGE` 从夸克文件夹分享换成 **Gitee Releases**：
  https://gitee.com/wuliao11541/StudyKit/releases
- 版本号升到 **3.2.1 / 23**（`ReleaseVersionTest` 钉着）。
- 版本判断仍只读 **Gitee tags**；Gitee 发行版附件、夸克、蓝奏云、GitHub Releases 都是下载渠道，
  不参与「有没有新版」的判断。
- 为什么 Gitee 附件是 `.7z`：Gitee 单附件上限 **100M**，debug APK 约 110.8MB；7z 压缩后约 69.8MB，
  解压后就是原始 APK。

## 装机与验证

- 发版包：本地 debug 3.2.1/23，证书 SHA-256 `f86c8a85…`（与公开 v2.5.0 / v3.0.0 / 3.2.0 同一把钥匙）；
  `adb install -r` 可覆盖升级，数据保留。
- 真机：2026-10-22 在 vivo V2156A / Android 11 上从 3.2.0/22 覆盖安装；
  核对 `versionName=3.2.1` / `versionCode=23`，存量 1165 词 / 13 题等未丢。
- 机检：CI 全绿（tag 提交）；159 类 / 1496 测 / 0 失败；lint 0 error（42 warning）。

## 诚实边界

- 这一版没有功能改动，3.2.0 的边界原样保留：3.1 语义交错的设备延迟/相邻率、拍照书页转错题本的
  完整真机串测、OLED 暗部观感仍未验。
- Gitee 附件是 `.7z`，需要先解压；想要裸 APK 用夸克网盘或 GitHub Releases。
- 这是本地 debug 包，不是应用商店包；安装占用与下载体积不是一回事。

## 下载渠道

| 渠道 | 位置 | 说明 |
| --- | --- | --- |
| Gitee Releases | [gitee.com/wuliao11541/StudyKit/releases](https://gitee.com/wuliao11541/StudyKit/releases) | 发行版附件 `.7z`（约 69.8MB），解压后安装；更新检查读的也是 Gitee tags |
| 夸克网盘 | [pan.quark.cn/s/b1029385f1b5?pwd=z4Yk](https://pan.quark.cn/s/b1029385f1b5?pwd=z4Yk) | 文件夹分享，提取码 `z4Yk`；裸 APK 在 `StudyKit` 目录 |
| 蓝奏云 | [ilanzou.com/s/nIqyzX3Ec](https://www.ilanzou.com/s/nIqyzX3Ec) | `StudyKit-v3.2.1-local-debug.7z`（69,808,023 B，永久有效），需先解压 |
| GitHub Releases | [github.com/wuliao00/StudyKit/releases](https://github.com/wuliao00/StudyKit/releases) | 原始 APK |

## 校验

| 项 | 值 |
| --- | --- |
| 版本 | `3.2.1` / `23` |
| 文件 | `StudyKit-v3.2.1-local-debug.apk` |
| 大小 | `110,804,175` B |
| SHA-256 | `1d10e6dc8f544735e42d62d3c7a63d3478ce5fee411482bb71bed177cf1748d8` |
| Gitee 7z | `69,808,023` B / sha256 `55219046dc14bb5d14bc19b0fd0065f6ab9833a8f53534cbd8bdc6722be917b3` |
| 证书 SHA-256 | `f86c8a854723cd50231a3173f80f96e0f32db3d0bb3d7047379c0ba75f5d61f5` |
| Room | `version = 9`（与 3.2.0 相同，无迁移） |
| 构建源码 | tag 提交（只改 `UpdateChecker`、版本号与文档） |
