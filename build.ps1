# build.ps1 - 编译打包 APK（aapt2 + javac + d8 + zipalign + apksigner）
$ErrorActionPreference = 'Stop'
$ws = $PSScriptRoot
# SDK 路径按本机位置修改；也可用环境变量 ANDROID_SDK_ROOT
$SDK = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { 'C:\android-build\sdk' }
$BT = "$SDK\build-tools\34.0.0"
$PLATFORM = "$SDK\platforms\android-34\android.jar"
$OUT = Join-Path $ws 'apk\build'
New-Item -ItemType Directory -Force -Path $OUT | Out-Null

function Check($step) {
    if ($LASTEXITCODE -ne 0) { throw "FAILED at step: $step" }
}

Write-Host '== 1. aapt2 compile =='
& "$BT\aapt2.exe" compile --dir (Join-Path $ws 'apk\res') -o (Join-Path $OUT 'res.zip')
Check 'aapt2 compile'

Write-Host '== 2. aapt2 link =='
& "$BT\aapt2.exe" link -o (Join-Path $OUT 'base.apk') `
    -I $PLATFORM `
    --manifest (Join-Path $ws 'apk\AndroidManifest.xml') `
    -A (Join-Path $ws 'apk\assets') `
    --java (Join-Path $ws 'apk\gen') `
    --min-sdk-version 23 --target-sdk-version 34 `
    --version-code 5 --version-name '1.2.0' `
    --auto-add-overlay `
    (Join-Path $OUT 'res.zip')
Check 'aapt2 link'

Write-Host '== 3. javac =='
New-Item -ItemType Directory -Force -Path (Join-Path $ws 'apk\classes') | Out-Null
& javac -encoding UTF-8 -source 8 -target 8 -nowarn `
    -classpath $PLATFORM `
    -d (Join-Path $ws 'apk\classes') `
    (Join-Path $ws 'apk\gen\com\gugu\jingqi\R.java') `
    (Join-Path $ws 'apk\src\MainActivity.java')
Check 'javac'

Write-Host '== 4. d8 dex =='
New-Item -ItemType Directory -Force -Path (Join-Path $ws 'apk\dex') | Out-Null
& "$BT\d8.bat" --release --lib $PLATFORM --min-api 23 `
    --output (Join-Path $ws 'apk\dex') `
    (Get-ChildItem (Join-Path $ws 'apk\classes\com\gugu\jingqi') -Filter *.class | ForEach-Object { $_.FullName })
Check 'd8'

Write-Host '== 5. merge dex into apk =='
Copy-Item (Join-Path $ws 'apk\dex\classes.dex') (Join-Path $OUT 'classes.dex') -Force
Push-Location $OUT
& "$BT\aapt.exe" add base.apk classes.dex | Out-Null
Pop-Location
Check 'aapt add'

Write-Host '== 6. zipalign =='
& "$BT\zipalign.exe" -f 4 (Join-Path $OUT 'base.apk') (Join-Path $OUT 'aligned.apk')
Check 'zipalign'

Write-Host '== 7. keystore =='
$ks = Join-Path $ws 'apk\release.keystore'
if (-not (Test-Path $ks)) {
    & keytool -genkeypair -v -keystore $ks -alias jingqi -keyalg RSA -keysize 2048 `
        -validity 10000 -storepass jingqi123 -keypass jingqi123 `
        -dname 'CN=JingqiReader,O=Gugu,C=CN'
    Check 'keytool'
}

Write-Host '== 8. sign =='
$final = Join-Path $ws 'jingqi.apk'
if (Test-Path $final) { Remove-Item $final -Force }
& "$BT\apksigner.bat" sign --ks $ks --ks-pass pass:jingqi123 --key-pass pass:jingqi123 `
    --out $final (Join-Path $OUT 'aligned.apk')
Check 'apksigner sign'

& "$BT\apksigner.bat" verify --print-certs $final
Check 'apksigner verify'

Write-Host "DONE: $final  ($([Math]::Round((Get-Item $final).Length/1KB)) KB)"
