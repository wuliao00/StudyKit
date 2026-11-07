# HarmonyOS HAP 构建证据（2026-11-07）

## 工具链

- 本机路径限定：hvigor 拒绝含中文的工程路径，构建副本在 E:\dev\studykit-harmony；
- 构建工具：hvigor 6.26.9 + @ohos/hvigor-ohos-plugin 6.26.9（HarmonyOS npm 源）；
- SDK：OpenHarmony SDK 5.0.3.135（API 15），布局 sdk/15/{ets,js,native,previewer,toolchains}；
- 编译命令：
  node node_modules/@ohos/hvigor/bin/hvigor.js assembleHap --mode module -p product=default -p buildMode=debug

## 产物

- 未签名：entry/build/default/outputs/default/entry-default-unsigned.hap（223,250 B）
- 签名：release/StudyKit-Harmony-v1.0.0-debug.hap（247,720 B）
  - sha256：a43c5dc9e0b48062be245918c536ab76f3cdf45cc45a216d870a0c4e2b8cfc20
- 签名方式：OpenHarmony SDK 自带 hap-sign-tool；
  - 生成 app 密钥/证书链（CA = OpenHarmony Application CA）；
  - 用 OpenHarmonyProfileDebug.pem 签出 debug profile；
  - sign-app localSign 签 HAP。

## 已验证

- assembleHap BUILD SUCCESSFUL，33 tasks，ArkTS CompileArkTS 通过；
- HAP 生成并可被 hap-sign-tool sign-app 签名成功。

## 未完成（阻塞）

1. HDC 设备 Unauthorized：MatePad 需要点「允许」调试授权；
2. HarmonyOS 5 通常只信任华为开发者签名；当前 OpenHarmony 本地证书可能被拒绝安装；
3. 因此尚未安装、未截图、未做真机交互验证。

## 下一步

- 用户在平板上允许 HDC 调试；
- 若签名被拒：登录华为账号，在 DevEco Studio 配置自动签名后重新签名；
- 安装后验证首页/签到/补签撤销/单词页/横竖屏，并补 docs/evidence。
