<#
.SYNOPSIS
    启动前环境检测：缺什么就提示什么，确认后从上游自动下载并解压到 tools\。

.DESCRIPTION
    检测三项：Java 运行时（17+）、ffmpeg、adb。
    已存在（系统 PATH 或 tools\ 内）则跳过；缺失时列出下载清单并向用户确认（Y/N）。
    所有依赖都装在仓库的 tools\ 目录，不污染系统、不进 git。

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
$tools = Join-Path $root "tools"
$temp = Join-Path $env:TEMP ("phone-console-setup-" + [Guid]::NewGuid().ToString("N").Substring(0, 8))

$targets = @(
    @{
        Key   = "java"
        Name  = "Java 运行时 21（JRE）"
        Size  = "约 45 MB"
        Local = Join-Path $tools "jre\bin\java.exe"
        Url   = "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jre/hotspot/normal/eclipse"
        Dir   = Join-Path $tools "jre"
        Mark  = "bin\java.exe"
    },
    @{
        Key   = "ffmpeg"
        Name  = "ffmpeg（屏幕推流）"
        Size  = "约 110 MB"
        Local = Join-Path $tools "ffmpeg\bin\ffmpeg.exe"
        Url   = "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip"
        Dir   = Join-Path $tools "ffmpeg"
        Mark  = "bin\ffmpeg.exe"
    },
    @{
        Key   = "adb"
        Name  = "adb（USB 通道）"
        Size  = "约 12 MB"
        Local = Join-Path $tools "platform-tools\adb.exe"
        Url   = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip"
        Dir   = Join-Path $tools "platform-tools"
        Mark  = "adb.exe"
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
    if (Test-Path (Join-Path $tools "jre\bin\java.exe")) {
        return (Join-Path $tools "jre\bin\java.exe")
    }
    $cmd = Get-Command java -ErrorAction SilentlyContinue
    if ($cmd -and (Test-JavaVersion $cmd.Source)) {
        return $cmd.Source
    }
    return $null
}

function Resolve-Ffmpeg {
    if (Test-Path (Join-Path $tools "ffmpeg\bin\ffmpeg.exe")) {
        return (Join-Path $tools "ffmpeg\bin\ffmpeg.exe")
    }
    if (Test-Path (Join-Path $root "bin\ffmpeg.exe")) {
        return (Join-Path $root "bin\ffmpeg.exe")
    }
    $cmd = Get-Command ffmpeg -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

function Resolve-Adb {
    $bundled = Join-Path $root "platform-tools\adb.exe"
    if (Test-Path $bundled) { return $bundled }
    if (Test-Path (Join-Path $tools "platform-tools\adb.exe")) {
        return (Join-Path $tools "platform-tools\adb.exe")
    }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

function Unpack {
    param([string]$Zip, [string]$Dest, [string]$Marker)

    $stage = Join-Path $temp "unpack"
    if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
    New-Item -ItemType Directory -Path $stage | Out-Null
    Expand-Archive -LiteralPath $Zip -DestinationPath $stage -Force

    $hit = Get-ChildItem $stage -Recurse -Filter (Split-Path -Leaf $Marker) -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName.Replace($stage, "").TrimStart("\") -match ("(^|\\)" + [regex]::Escape($Marker) + "$") } |
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

    New-Item -ItemType Directory -Path $temp -Force | Out-Null
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

if ($missing.Count -eq 0) {
    Write-Host ""
    Write-Host "环境已就绪，无需下载。" -ForegroundColor Green
    exit 0
}

if ($CheckOnly) {
    Write-Host ""
    Write-Host ("缺少 {0} 项依赖，可执行 setup-env.ps1 自动下载。" -f $missing.Count) -ForegroundColor Yellow
    exit 1
}

Write-Host ""
Write-Host "以下依赖缺失，需要从官方上游下载并解压到 tools\ 目录：" -ForegroundColor Yellow
foreach ($item in $missing) {
    Write-Host ("  - {0}  {1}" -f $item.Name, $item.Size)
}
Write-Host "  下载位置：$tools"
Write-Host ""

if (-not $Yes) {
    $answer = Read-Host "是否现在自动下载？[Y/n]"
    if ($answer -and $answer.Trim().ToLower() -notin @('y', 'yes', '')) {
        Write-Host "已取消。可稍后手动执行 setup-env.ps1。" -ForegroundColor Yellow
        exit 1
    }
}

New-Item -ItemType Directory -Path $tools -Force | Out-Null
New-Item -ItemType Directory -Path $temp -Force | Out-Null

$failed = @()
foreach ($item in $missing) {
    $zip = Join-Path $temp ($item.Key + ".zip")
    try {
        Download -Url $item.Url -OutFile $zip -Label $item.Name
        Write-Host ("  正在解压到 {0} ..." -f $item.Dir) -ForegroundColor Cyan
        Unpack -Zip $zip -Dest $item.Dir -Marker $item.Mark
        Write-Host ("  [完成] " + $item.Name) -ForegroundColor Green
    } catch {
        Write-Host ("  [失败] " + $item.Name + "：" + $_.Exception.Message) -ForegroundColor Red
        $failed += $item.Name
    }
}

Remove-Item $temp -Recurse -Force -ErrorAction SilentlyContinue

Write-Host ""
if ($failed.Count -gt 0) {
    Write-Host ("以下依赖安装失败：" + ($failed -join '、')) -ForegroundColor Red
    Write-Host "可手动下载后解压到 tools\ 对应目录，或检查网络后重试。" -ForegroundColor Yellow
    exit 2
}

Write-Host "依赖已安装到 $tools" -ForegroundColor Green
Write-Host "提示：这些文件已被 .gitignore 忽略，不会进入仓库。" -ForegroundColor DarkGray
exit 0
