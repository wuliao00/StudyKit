param(
  [string]$ProjectPath = "E:\dev\studykit-harmony",
  [string]$SdkDir = "E:\dev\ohos-sdk\sdk"
)
$ErrorActionPreference = 'Stop'
if ($ProjectPath -match '[^\x00-\x7F]') {
  throw 'hvigor 不接受含中文的工程路径，请把工程复制到纯 ASCII 路径再构建。'
}
Set-Content -Path (Join-Path $ProjectPath 'local.properties') -Value ("sdk.dir=" + ($SdkDir -replace '\\','/')) -Encoding utf8
Push-Location $ProjectPath
try {
  node node_modules\@ohos\hvigor\bin\hvigor.js assembleHap --mode module -p product=default -p buildMode=debug
} finally {
  Pop-Location
}
