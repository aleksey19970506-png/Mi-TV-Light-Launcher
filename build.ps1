<#
.SYNOPSIS
    Build TV Shell APK without Gradle.

.DESCRIPTION
    Requires: JDK 8+ (javac), Android SDK build-tools (aapt2, d8, zipalign, apksigner),
    android.jar from platforms/android-XX.

.EXAMPLE
    ./build.ps1 -AndroidJar "C:\Android\sdk\platforms\android-30\android.jar" -BuildTools "C:\Android\sdk\build-tools\34.0.0"
#>
param(
    [Parameter(Mandatory = $true)][string]$AndroidJar,
    [Parameter(Mandatory = $true)][string]$BuildTools,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Keystore = "keystore.jks",
    [string]$StorePass = "android",
    [string]$KeyPass = "android",
    [string]$Alias = "tvshell",
    [string]$OutApk = "TVShell.apk",
    [int]$MinSdk = 21,
    [int]$TargetSdk = 30
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$app = Join-Path $root "app"
$work = Join-Path $root "build"

if (-not (Test-Path $AndroidJar)) { throw "android.jar not found: $AndroidJar" }
if (-not (Test-Path (Join-Path $BuildTools "aapt2.exe"))) { throw "build-tools not found: $BuildTools" }

$javac = if ($JavaHome) { Join-Path $JavaHome "bin\javac.exe" } else { "javac" }
if ($JavaHome -and -not (Test-Path $javac)) { throw "javac not found: $javac" }

Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $work | Out-Null
New-Item -ItemType Directory -Path (Join-Path $work "classes") | Out-Null
New-Item -ItemType Directory -Path (Join-Path $work "dex") | Out-Null

Write-Host "[1/6] aapt2 compile"
& (Join-Path $BuildTools "aapt2.exe") compile --dir (Join-Path $app "res") -o (Join-Path $work "res.zip")
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "[2/6] aapt2 link"
$baseApk = Join-Path $work "base.apk"
& (Join-Path $BuildTools "aapt2.exe") link -o $baseApk -I $AndroidJar `
    --manifest (Join-Path $app "AndroidManifest.xml") `
    -A (Join-Path $app "assets") (Join-Path $work "res.zip") `
    --min-sdk-version $MinSdk --target-sdk-version $TargetSdk
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "[3/6] javac"
$sources = Get-ChildItem (Join-Path $app "src") -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
& $javac --release 8 -encoding UTF-8 -cp $AndroidJar -d (Join-Path $work "classes") $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "[4/6] d8"
$classes = Get-ChildItem (Join-Path $work "classes") -Recurse -Filter *.class | Select-Object -ExpandProperty FullName
& (Join-Path $BuildTools "d8.bat") --lib $AndroidJar --min-api $MinSdk --output (Join-Path $work "dex") $classes
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "[5/6] package + zipalign"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($baseApk, "Update")
$zip.Entries | Where-Object { $_.FullName -eq "classes.dex" } | ForEach-Object { $_.Delete() }
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
    $zip, (Join-Path $work "dex\classes.dex"), "classes.dex",
    [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
$zip.Dispose()

$aligned = Join-Path $work "aligned.apk"
& (Join-Path $BuildTools "zipalign.exe") -f 4 $baseApk $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "[6/6] sign"
if (-not (Test-Path $Keystore)) {
    Write-Host "  creating key $Keystore (alias $Alias)"
    $keytool = if ($JavaHome) { Join-Path $JavaHome "bin\keytool.exe" } else { "keytool" }
    & $keytool -genkeypair -keystore $Keystore -alias $Alias `
        -keyalg RSA -keysize 2048 -validity 10000 `
        -storepass $StorePass -keypass $KeyPass `
        -dname "CN=TV Shell, O=OpenSource, C=RU"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}
$out = Join-Path $root $OutApk
& (Join-Path $BuildTools "apksigner.bat") sign --ks $Keystore --ks-pass "pass:$StorePass" `
    --key-pass "pass:$KeyPass" --ks-key-alias $Alias --out $out $aligned
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

$size = [math]::Round((Get-Item $out).Length / 1MB, 2)
Write-Host "Done: $out ($size MB)"
