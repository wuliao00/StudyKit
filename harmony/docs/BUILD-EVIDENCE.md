# HarmonyOS HAP 构建证据（2026-11-07）

## 工具链

- 本机路径限定：hvigor 拒绝含中文的工程路径，构建副本在 E:\dev\studykit-harmony；
- 构建工具：hvigor 6.26.9 + @ohos/hvigor-ohos-plugin 6.26.9（HarmonyOS npm 源）；
- SDK：OpenHarmony SDK 5.0.3.135（API 15），布局 sdk/15/{ets,js,native,previewer,toolchains}；
- 编译命令：
  node node_modules/@ohos/hvigor/bin/hvigor.js assembleHap --mode module -p product=default -p buildMode=debug

## 产物

- 未签名：entry/build/default/outputs/default/entry-default-unsigned.hap（223,250 B）
- 签名：release/StudyKit-Harmony-v1.0.0-debug.hap（247653 B）
  - sha256：fb666f91eed09a9a3445c9ec0b773cfdcd555451a8ae4fc3005f4e5a848e5f69
  - profile 已加入 MatePad UDID A3B9958BC64F7AD33CDDF9B11646FE98D20A502A94560EC240E9ADC1961A16D3
- 签名方式：OpenHarmony SDK 自带 hap-sign-tool；
  - 生成 app 密钥/证书链（CA = OpenHarmony Application CA）；
  - 用 OpenHarmonyProfileDebug.pem 签出 debug profile；
  - sign-app localSign 签 HAP。

## 已验证

- assembleHap BUILD SUCCESSFUL，33 tasks，ArkTS CompileArkTS 通过；
- HAP 生成并可被 hap-sign-tool sign-app 签名成功。

## 真机安装结果（2026-11-08 07:50）

HDC 已恢复授权（删除主机 .hdc 旧密钥后重新生成；设备 udid 已读取）。

执行：

    hdc -t 8ZQUN26602G03715 install -r entry-default-signed-udid.hap

返回：

    error: failed to install bundle. code:9568257 error: fail to verify pkcs7 file.

说明：设备为 HarmonyOS 7.0.0.111（型号 SLG-W60），不信任 OpenHarmony 本地调试证书。
这不是 HDC 授权问题，而是华为签名信任链问题。

## 未完成（阻塞）

1. HarmonyOS 7 真机安装要求华为开发者签名；当前 OpenHarmony 本地证书被拒绝；
2. 需要登录华为账号，在 DevEco Studio 5/6 中配置自动签名后重新签 HAP；
3. 因此尚未安装、未截图、未做真机交互验证。

## 下一步

- 用户在平板上允许 HDC 调试；
- 若签名被拒：登录华为账号，在 DevEco Studio 配置自动签名后重新签名；
- 安装后验证首页/签到/补签撤销/单词页/横竖屏，并补 docs/evidence。
