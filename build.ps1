# CM Hook 构建脚本 (PowerShell 版) — 可复现全链
# 用法: powershell -ExecutionPolicy Bypass -File build.ps1 [-NoInstall]
# 关键约束:
#   1) classes.jar 只打 com/ 包 —— de/ 与 QOlJwxewM/ 是 LSPosed API stub, 进 dex 会被拒载
#   2) javac 失败必须中止(门禁), 不带病打包
#   3) resources.arsc / .so 用 ZIP_STORED, 最后 zipalign -p 4 页对齐(libdexkit.so mmap)
#   4) AndroidManifest 改动必须重编 base.apk 模板, 否则 versionCode 不生效
param([switch]$NoInstall, [string]$Device = "auto")

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$sdk = "..\sdk"
$tag = (Get-Date).ToString("HH:mm:ss")

function Step($n) { Write-Host "`n=== [$n] $tag" -ForegroundColor Cyan }

Step "1/7 javac (release 8, 门禁)"
Remove-Item -Recurse -Force obj, dexout, classes.jar -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path obj, dexout | Out-Null
$srcs = Get-ChildItem -Recurse -File -Path src -Filter *.java | ForEach-Object { $_.FullName }
$cp = "$sdk\android.jar;libs\dexkit.jar;libs\kotlin-stdlib.jar;libs\flatbuffers.jar"
& javac --release 8 -encoding UTF-8 -g:none "-J-Duser.language=en" "-J-Duser.country=US" -cp $cp -d obj @srcs
if ($LASTEXITCODE -ne 0) { throw "javac 失败, 终止构建" }
Write-Host "javac OK: $($srcs.Count) 个源文件"

Step "2/7 jar (只含 com/)"
& jar cf classes.jar -C obj com
if ($LASTEXITCODE -ne 0) { throw "jar 失败" }

Step "3/7 d8"
& java -cp "$sdk\r8.jar" com.android.tools.r8.D8 --output dexout --min-api 24 --lib "$sdk\android.jar" `
    classes.jar libs\dexkit.jar libs\kotlin-stdlib.jar libs\flatbuffers.jar
if ($LASTEXITCODE -ne 0) { throw "d8 失败" }
Write-Host ("classes.dex: {0:N2} MB" -f ((Get-Item dexout\classes.dex).Length / 1MB))

Step "4/7 aapt2 重编模板 (versionCode/Name/图标 生效)"
$resZip = "res-compiled.zip"
if (Test-Path res) {
    Remove-Item -Force $resZip -ErrorAction SilentlyContinue
    & "$sdk\android-14\aapt2.exe" compile --dir res -o $resZip
    if ($LASTEXITCODE -ne 0) { throw "aapt2 compile(res) 失败" }
    & "$sdk\android-14\aapt2.exe" link --manifest AndroidManifest.xml -I "$sdk\android.jar" `
        --min-sdk-version 24 --target-sdk-version 33 -o base.apk $resZip
} else {
    & "$sdk\android-14\aapt2.exe" link --manifest AndroidManifest.xml -I "$sdk\android.jar" `
        --min-sdk-version 24 --target-sdk-version 33 -o base.apk
}
if ($LASTEXITCODE -ne 0) { throw "aapt2 失败" }

Step "5/7 合并 APK (build_apk.py)"
& python build_apk.py
if ($LASTEXITCODE -ne 0) { throw "build_apk.py 失败" }

Step "6/7 zipalign -p 4 + apksigner"
& "$sdk\android-14\zipalign.exe" -f -p 4 cmhook-unsigned.apk cmhook-aligned.apk
if ($LASTEXITCODE -ne 0) { throw "zipalign 失败" }
& java -jar "$sdk\android-14\lib\apksigner.jar" sign --ks cmks.jks --ks-pass pass:cmhook123 `
    --key-pass pass:cmhook123 --out cmhook.apk cmhook-aligned.apk
if ($LASTEXITCODE -ne 0) { throw "apksigner 失败" }
$certInfo = $null
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'   # java 的 stderr WARNING 不当作致命错误
$certInfo = & java -jar "$sdk\android-14\lib\apksigner.jar" verify --print-certs cmhook.apk 2>$null
$ErrorActionPreference = $prevEap
if ($certInfo) { $certInfo | Select-String -Pattern 'certificate DN|SHA-256 digest' }
Write-Host ("cmhook.apk: {0:N2} MB" -f ((Get-Item cmhook.apk).Length / 1MB))

if ($NoInstall) { Write-Host "`n[跳过安装]" -ForegroundColor Yellow; exit 0 }

Step "7/7 安装 + 重启目标 APP"
if ([string]::IsNullOrWhiteSpace($Device) -or $Device -eq "auto") {
    $Device = (& adb devices) | Select-String -Pattern '^\S+\s+device$' | ForEach-Object { ($_.ToString() -split '\s+')[0] } | Select-Object -First 1
}
Write-Host "目标设备: [$Device]"
# 走 cmd /c 预拼命令行: PS5.1 下直接对 native 传 -r 会被吃掉
$adbLine = "adb -s $Device"
$r1 = cmd /c "$adbLine install -r cmhook.apk 2>&1"
$r1 | Select-Object -Last 4
if ($LASTEXITCODE -ne 0) { throw "安装失败" }
cmd /c "$adbLine shell am force-stop com.netease.cloudmusic" | Out-Null
cmd /c "$adbLine shell `"dumpsys package com.rev.cmhook | grep -E 'versionName|versionCode'`""
Write-Host "`n构建完成 → $PSScriptRoot\cmhook.apk" -ForegroundColor Green
