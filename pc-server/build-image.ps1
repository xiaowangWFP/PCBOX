<#
.SYNOPSIS
    构建自带 JRE 的 PC 服务端 app-image（免安装、目标机无需装 Java）。

.DESCRIPTION
    - 全量构建：mvn package + jpackage，输出 dist\PhoneConsole\
    - 日常更新：-JarOnly 只把新 jar 覆盖进已有镜像，不重新封包
    - 已有 jar 只想重打包：-SkipBuild

.EXAMPLE
    .\build-image.ps1
    .\build-image.ps1 -JarOnly
#>
param(
    [string]$AppName = "PhoneConsole",
    [string]$AppVersion = "0.2.0",
    [switch]$JarOnly,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

$here = Split-Path -Parent $MyInvocation.MyCommand.Definition
$root = Split-Path -Parent $here
$distRoot = Join-Path $root "dist"
$imageDir = Join-Path $distRoot $AppName
$targetJar = Join-Path $imageDir "app\phone-console-pc.jar"
$builtJar = Join-Path $here "target\phone-console-pc.jar"

function Find-JdkTools {
    $candidates = New-Object System.Collections.Generic.List[string]

    foreach ($name in @('JAVA_HOME', 'JDK_HOME')) {
        $value = [Environment]::GetEnvironmentVariable($name)
        if ($value) { $candidates.Add($value) }
    }

    # java 可能是 javapath 转接层，真实 JDK 位置看 java.home
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $props = (& java -XshowSettings:properties -version 2>&1 | Out-String)
        if ($props -match 'java\.home = ([^\r\n]+)') { $candidates.Add($Matches[1].Trim()) }
    } catch {
        # 忽略，下面还有兜底目录
    }
    $ErrorActionPreference = $previous

    foreach ($base in @("$env:ProgramFiles\Java", "${env:ProgramFiles}\Eclipse Adoptium", 'D:\Java21', 'D:\Java', 'C:\Java')) {
        if (-not (Test-Path $base)) { continue }
        $candidates.Add($base)
        Get-ChildItem $base -Directory -ErrorAction SilentlyContinue | ForEach-Object { $candidates.Add($_.FullName) }
    }

    foreach ($dir in $candidates) {
        if (-not $dir) { continue }
        $jp = Join-Path $dir 'bin\jpackage.exe'
        if (Test-Path $jp) { return $jp }
    }

    throw "未找到 jpackage.exe。请安装 JDK 17+（JRE 不含 jpackage），并设置 JAVA_HOME 或把 JDK\bin 加入 PATH。"
}

if (-not $SkipBuild) {
    Write-Host "[1/2] 打包 jar..." -ForegroundColor Cyan
    Push-Location $here
    try {
        & mvn -q -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw "mvn package 失败，请检查 Maven / JDK 环境。" }
    } finally {
        Pop-Location
    }
    Write-Host "      $builtJar" -ForegroundColor DarkGray
}

if (-not (Test-Path $builtJar)) {
    throw "未找到 $builtJar，请先执行 mvn package。"
}

if ($JarOnly) {
    if (-not (Test-Path $imageDir)) {
        throw "镜像目录不存在：$imageDir，请先执行不带 -JarOnly 的完整构建。"
    }
    Copy-Item $builtJar $targetJar -Force
    Write-Host "[完成] 已更新镜像内的 jar（无需重新封包）" -ForegroundColor Green
    Write-Host "      $targetJar" -ForegroundColor DarkGray
    exit 0
}

Write-Host "[2/2] 生成 app-image..." -ForegroundColor Cyan
$jpackage = Find-JdkTools

# 只把最终 shaded jar 交给 jpackage，避免 target 下的 classes / 中间产物被打进镜像
$staging = Join-Path $here "target\jpackage-input"
if (Test-Path $staging) { Remove-Item $staging -Recurse -Force }
New-Item -ItemType Directory -Path $staging | Out-Null
Copy-Item $builtJar (Join-Path $staging "phone-console-pc.jar") -Force

if (-not (Test-Path $distRoot)) { New-Item -ItemType Directory -Path $distRoot | Out-Null }
if (Test-Path $imageDir) { Remove-Item $imageDir -Recurse -Force }

Push-Location $here
try {
    & $jpackage `
        --type app-image `
        --name $AppName `
        --app-version $AppVersion `
        --input $staging `
        --main-jar "phone-console-pc.jar" `
        --main-class "com.phoneconsole.pc.Main" `
        --jlink-options "--strip-debug --no-header-files --no-man-pages --compress=zip-6" `
        --java-options "-Xms64m" `
        --java-options "-Xmx256m" `
        --java-options "-XX:+UseSerialGC" `
        --java-options "-XX:MaxMetaspaceSize=128m" `
        --java-options "-Dfile.encoding=UTF-8" `
        --dest $distRoot
    if ($LASTEXITCODE -ne 0) { throw "jpackage 失败（退出码 $LASTEXITCODE）。" }
} finally {
    Pop-Location
}

# 附带 adb 与可选的 ffmpeg，让镜像在干净机器上也能直接使用
$platformTools = Join-Path $root "platform-tools"
if (Test-Path $platformTools) {
    Copy-Item $platformTools (Join-Path $imageDir "platform-tools") -Recurse -Force
    Write-Host "      已内置 platform-tools（adb）" -ForegroundColor DarkGray
}

$ffmpegSource = @((Join-Path $root "bin\ffmpeg.exe"), (Join-Path $root "tools\ffmpeg.exe")) |
    Where-Object { Test-Path $_ } | Select-Object -First 1
if ($ffmpegSource) {
    $binDir = Join-Path $imageDir "bin"
    if (-not (Test-Path $binDir)) { New-Item -ItemType Directory -Path $binDir | Out-Null }
    Copy-Item $ffmpegSource (Join-Path $binDir "ffmpeg.exe") -Force
    Write-Host "      已内置 ffmpeg" -ForegroundColor DarkGray
} else {
    Write-Host "      未找到 ffmpeg.exe：屏幕推流需目标机安装 ffmpeg，或把 ffmpeg.exe 放到镜像 bin 目录" -ForegroundColor Yellow
}

$exe = Join-Path $imageDir "$AppName.exe"
Write-Host ""
Write-Host "[完成] 镜像目录：$imageDir" -ForegroundColor Green
Write-Host "      启动程序：$exe" -ForegroundColor DarkGray
Write-Host "      配置文件：$(Join-Path $imageDir 'config.json')（首次运行自动生成）" -ForegroundColor DarkGray
Write-Host ""
Write-Host "日常更新只需：" -ForegroundColor Cyan
Write-Host "  .\build-image.ps1 -JarOnly" -ForegroundColor DarkGray
