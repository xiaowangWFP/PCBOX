<#
.SYNOPSIS
    启动前环境检测：缺什么就提示什么，确认后从上游下载并解压到项目内的 deps\ 目录。

.DESCRIPTION
    检测三项：Java 运行时（17+）、ffmpeg、adb。
    已存在（系统 PATH 或 deps\ 内）则跳过；缺失时列出下载清单并询问 Y/N。

    所有依赖统一放在项目根目录的 deps\ 下，结构固定：
        deps\jre\bin\java.exe
        deps\ffmpeg\bin\ffmpeg.exe
        deps\platform-tools\adb.exe
        deps\VERSIONS.txt        ← 记录来源、版本、安装时间
        deps\.cache\             ← 下载临时文件，安装完成后自动删除
    deps\ 已加入 .gitignore，不会进入仓库，删除该目录即可完全恢复干净状态。

.PARAMETER CheckOnly
    只检测并打印结果，不提示、不下载。

.PARAMETER Yes
    跳过确认，直接下载缺失项（用于脚本调用）。

.EXAMPLE
    .\setup-env.ps1 -CheckOnly
    .\setup-env.ps1 -Yes
#>
param(
    [switch]$CheckOnly,
    [switch]$Yes
)

$ErrorActionPreference = "Stop"

$here = Split-Path -Parent $MyInvocation.MyCommand.Definition
$root = Split-Path -Parent $here
$deps = Join-Path $root "deps"
$cache = Join-Path $deps ".cache"
$manifest = Join-Path $deps "VERSIONS.txt"

$targets = @(
    @{
        Key    = "jre"
        Name   = "Java 运行时 21（JRE）"
        Size   = "约 45 MB"
        Dir    = Join-Path $deps "jre"
        Marker = "bin\java.exe"
        Url    = "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jre/hotspot/normal/eclipse"
        Source = "Eclipse Temurin (api.adoptium.net)"
    },
    @{
        Key    = "ffmpeg"
        Name   = "ffmpeg（屏幕推流）"
        Size   = "约 110 MB"
        Dir    = Join-Path $deps "ffmpeg"
        Marker = "bin\ffmpeg.exe"
        Url    = "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip"
        Source = "gyan.dev ffmpeg essentials"
    },
    @{
        Key    = "platform-tools"
        Name   = "platform-tools（adb）"
        Size   = "约 12 MB"
        Dir    = Join-Path $deps "platform-tools"
        Marker = "adb.exe"
        Url    = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip"
        Source = "Google Android platform-tools"
    }
)

function Test-JavaVersion {
    param([string]$JavaExe)
    # java -version 往 stderr 输出，$ErrorActionPreference=Stop 时会被当成终止错误，这里临时放宽
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $JavaExe -version 2>&1 | Out-String
        if ($out -match 'version "(\d+)(?:\.(\d+))?') {
            $major = [int]$Matches[1]
            if ($major -eq 1 -and $Matches[2]) { $major = [int]$Matches[2] }
            return $major -ge 17
        }
    } catch {
        # 视为不可用
    } finally {
        $ErrorActionPreference = $prev
    }
    return $false
}

function Resolve-Java {
    $local = Join-Path $deps "jre\bin\java.exe"
    if (Test-Path $local) { return $local }
    $cmd = Get-Command java -ErrorAction SilentlyContinue
    if ($cmd -and (Test-JavaVersion $cmd.Source)) { return $cmd.Source }
    return $null
}

function Resolve-Ffmpeg {
    $local = Join-Path $deps "ffmpeg\bin\ffmpeg.exe"
    if (Test-Path $local) { return $local }
    $cmd = Get-Command ffmpeg -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

function Resolve-Adb {
    $local = Join-Path $deps "platform-tools\adb.exe"
    if (Test-Path $local) { return $local }
    $bundled = Join-Path $root "platform-tools\adb.exe"
    if (Test-Path $bundled) { return $bundled }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

function Unpack {
    param([string]$Zip, [string]$Dest, [string]$Marker, [string]$Stage)

    if (Test-Path $Stage) { Remove-Item $Stage -Recurse -Force }
    New-Item -ItemType Directory -Path $Stage | Out-Null
    Expand-Archive -LiteralPath $Zip -DestinationPath $Stage -Force

    $leaf = Split-Path -Leaf $Marker
    $hit = Get-ChildItem $Stage -Recurse -Filter $leaf -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName.Replace($Stage, "").TrimStart("\") -match ("(^|\\)" + [regex]::Escape($Marker) + "$") } |
        Select-Object -First 1
    if (-not $hit) {
        throw "压缩包内未找到 $Marker"
    }

    $sourceDir = $hit.FullName
    $segments = $Marker.Split("\")
    for ($i = 0; $i -lt $segments.Count; $i++) { $sourceDir = Split-Path -Parent $sourceDir }

    if (Test-Path $Dest) { Remove-Item $Dest -Recurse -Force }
    Move-Item $sourceDir $Dest
}

function Download {
    param([string]$Url, [string]$OutFile, [string]$Label)

    Write-Host ("  正在下载 {0} ..." -f $Label) -ForegroundColor Cyan
    $prev = $ProgressPreference
    $ProgressPreference = 'SilentlyContinue'
    try {
        Invoke-WebRequest -Uri $Url -OutFile $OutFile -UseBasicParsing -TimeoutSec 1800
    } finally {
        $ProgressPreference = $prev
    }
    $mb = [math]::Round((Get-Item $OutFile).Length / 1MB, 1)
    Write-Host ("  下载完成：{0} MB" -f $mb) -ForegroundColor Green
}

function Write-Manifest {
    param([array]$Items)

    $stamp = (Get-Date -Format "yyyy-MM-dd HH:mm:ss")
    $lines = @()
    $lines += "手机控制台 · 本地依赖清单"
    $lines += ("生成时间：" + $stamp)
    $lines += "说明：本目录由 setup-env.ps1 自动维护，已被 .gitignore 忽略，删除即恢复干净状态。"
    $lines += ""
    foreach ($item in $Items) {
        $lines += ("[" + $item.Key + "]")
        $lines += ("  名称：" + $item.Name)
        $lines += ("  路径：" + $item.Dir)
        $lines += ("  来源：" + $item.Source)
        $lines += ("  地址：" + $item.Url)
        $lines += ""
    }
    [System.IO.File]::WriteAllLines($manifest, [string[]]$lines, (New-Object System.Text.UTF8Encoding($false)))
}

# ---------------- 检测 ----------------
$java = Resolve-Java
$ffmpeg = Resolve-Ffmpeg
$adb = Resolve-Adb

Write-Host ""
Write-Host "==================== 环境检测 ====================" -ForegroundColor Cyan
if ($java) { Write-Host ("  [OK] Java   " + $java) -ForegroundColor Green }
else { Write-Host ("  [缺失] Java   " + $targets[0].Name) -ForegroundColor Yellow }

if ($ffmpeg) { Write-Host ("  [OK] ffmpeg " + $ffmpeg) -ForegroundColor Green }
else { Write-Host ("  [缺失] ffmpeg " + $targets[1].Name) -ForegroundColor Yellow }

if ($adb) { Write-Host ("  [OK] adb    " + $adb) -ForegroundColor Green }
else { Write-Host ("  [缺失] adb    " + $targets[2].Name) -ForegroundColor Yellow }
Write-Host "=================================================" -ForegroundColor Cyan

$missing = @()
if (-not $java) { $missing += $targets[0] }
if (-not $ffmpeg) { $missing += $targets[1] }
if (-not $adb) { $missing += $targets[2] }

$present = @()
foreach ($item in $targets) {
    if (Test-Path (Join-Path $item.Dir $item.Marker)) { $present += $item }
}

if ($missing.Count -eq 0) {
    if ($present.Count -gt 0 -and (Test-Path $deps)) {
        Write-Manifest -Items $present
    }
    Write-Host ""
    Write-Host "环境已就绪，无需下载。" -ForegroundColor Green
    if (Test-Path $deps) {
        Write-Host ("本地依赖目录：{0}" -f $deps) -ForegroundColor DarkGray
    }
    exit 0
}

if ($CheckOnly) {
    Write-Host ""
    Write-Host ("缺少 {0} 项依赖，可执行 setup-env.ps1 自动下载到 deps\\。" -f $missing.Count) -ForegroundColor Yellow
    exit 1
}

Write-Host ""
Write-Host "以下依赖缺失，将统一安装到项目内的 deps\ 目录：" -ForegroundColor Yellow
foreach ($item in $missing) {
    Write-Host ("  - {0}  {1}" -f $item.Name, $item.Size)
}
Write-Host ("  安装位置：{0}" -f $deps)
Write-Host "  下载缓存：deps\.cache（安装完成后自动删除）"
Write-Host ""

if (-not $Yes) {
    $answer = Read-Host "是否现在自动下载？[Y/n]"
    if ($answer -and $answer.Trim().ToLower() -notin @('y', 'yes', '')) {
        Write-Host "已取消。可稍后手动执行 setup-env.ps1。" -ForegroundColor Yellow
        exit 1
    }
}

New-Item -ItemType Directory -Path $deps -Force | Out-Null
New-Item -ItemType Directory -Path $cache -Force | Out-Null

$installed = @()
$failed = @()
$index = 0
foreach ($item in $missing) {
    $index++
    $zip = Join-Path $cache ($item.Key + ".zip")
    $stage = Join-Path $cache ("unpack-" + $item.Key)
    Write-Host ("[{0}/{1}] {2}" -f $index, $missing.Count, $item.Name) -ForegroundColor Cyan
    try {
        Download -Url $item.Url -OutFile $zip -Label $item.Name
        Write-Host ("  正在解压到 {0} ..." -f $item.Dir) -ForegroundColor Cyan
        Unpack -Zip $zip -Dest $item.Dir -Marker $item.Marker -Stage $stage
        Write-Host ("  [完成] " + $item.Name) -ForegroundColor Green
        $installed += $item
        Remove-Item $zip, $stage -Recurse -Force -ErrorAction SilentlyContinue
    } catch {
        Write-Host ("  [失败] " + $item.Name + "：" + $_.Exception.Message) -ForegroundColor Red
        $failed += $item.Name
    }
    Write-Host ""
}

if (Test-Path $cache) {
    Remove-Item $cache -Recurse -Force -ErrorAction SilentlyContinue
}

$allPresent = @()
foreach ($item in $targets) {
    if (Test-Path (Join-Path $item.Dir $item.Marker)) { $allPresent += $item }
}
if ($allPresent.Count -gt 0 -and (Test-Path $deps)) {
    Write-Manifest -Items $allPresent
}

Write-Host ""
if ($failed.Count -gt 0) {
    Write-Host ("以下依赖安装失败：" + ($failed -join '、')) -ForegroundColor Red
    Write-Host "可检查网络后重试，或手动下载并解压到 deps\ 对应子目录。" -ForegroundColor Yellow
    exit 2
}

Write-Host ("依赖已就绪：{0}" -f $deps) -ForegroundColor Green
Write-Host ("清单文件：{0}" -f $manifest) -ForegroundColor DarkGray
Write-Host "该目录已被 .gitignore 忽略，不会进入仓库。" -ForegroundColor DarkGray
exit 0
