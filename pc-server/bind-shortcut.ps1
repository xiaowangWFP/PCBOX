param(
    [ValidateSet("status", "bind", "unbind")]
    [string]$Action = "status",
    [string]$Hotkey = "",
    [switch]$Desktop,
    [switch]$RemoveDesktop,
    [switch]$AutoStart,
    [string]$AutoStartMode = "keep"
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$srvDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$vbsPath = Join-Path $srvDir "launch-silent.vbs"
$wscriptPath = Join-Path $env:SystemRoot "System32\wscript.exe"
$shortcutName = "手机控制台服务端.lnk"

$startMenuDir = [Environment]::GetFolderPath("Programs")
$startupDir = [Environment]::GetFolderPath("Startup")
$desktopDir = [Environment]::GetFolderPath("Desktop")

$startMenuLnk = Join-Path $startMenuDir $shortcutName
$startupLnk = Join-Path $startupDir $shortcutName
$desktopLnk = Join-Path $desktopDir $shortcutName

function Normalize-Hotkey([string]$raw) {
    if ([string]::IsNullOrWhiteSpace($raw)) { return "" }
    $parts = $raw -split "\+" | ForEach-Object { $_.Trim() } | Where-Object { $_ }
    $hasCtrl = $false
    $hasAlt = $false
    $hasShift = $false
    $key = ""
    foreach ($p in $parts) {
        switch -Regex ($p.ToUpperInvariant()) {
            "^(CTRL|CONTROL)$" { $hasCtrl = $true }
            "^ALT$" { $hasAlt = $true }
            "^SHIFT$" { $hasShift = $true }
            default { $key = $p.ToUpperInvariant() }
        }
    }
    if (-not $key) { return "" }
    $out = New-Object System.Collections.Generic.List[string]
    if ($hasCtrl) { $out.Add("Ctrl") }
    if ($hasAlt) { $out.Add("Alt") }
    if ($hasShift) { $out.Add("Shift") }
    $out.Add($key)
    return ($out -join "+")
}

function Is-ValidHotkey([string]$hk) {
    if ([string]::IsNullOrWhiteSpace($hk)) { return $false }
    return ($hk -match "^(?:Ctrl\+Alt\+Shift|Ctrl\+Alt|Ctrl\+Shift|Alt\+Shift)\+(?:[A-Z0-9]|F(?:[1-9]|1[0-2]))$") -or ($hk -match "^F(?:[9]|1[0-2])$")
}

function Find-IconLocation {
    $candidates = @(
        (Join-Path $env:SystemRoot "System32\shell32.dll"),
        (Join-Path $env:SystemRoot "System32\imageres.dll")
    )
    foreach ($c in $candidates) {
        if (Test-Path -LiteralPath $c) { return "$c,17" }
    }
    return "$wscriptPath,0"
}

function Write-Lnk([string]$path, [string]$hotkeyValue) {
    $dir = Split-Path -Parent $path
    if (-not (Test-Path -LiteralPath $dir)) {
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
    }
    $ws = New-Object -ComObject WScript.Shell
    $sc = $ws.CreateShortcut($path)
    $sc.TargetPath = $wscriptPath
    $sc.Arguments = '"' + $vbsPath + '"'
    $sc.WorkingDirectory = $srvDir
    $sc.WindowStyle = 7
    $sc.Description = "手机控制台 PC 服务端（静默启动）"
    $sc.IconLocation = Find-IconLocation
    if ($hotkeyValue) {
        $sc.Hotkey = $hotkeyValue
    } else {
        $sc.Hotkey = ""
    }
    $sc.Save()
}

if ($Action -eq "bind") {
    $norm = Normalize-Hotkey $Hotkey
    if (-not (Is-ValidHotkey $norm)) {
        [PSCustomObject]@{
            ok = $false
            error = "无效快捷键，请使用如 Ctrl+Alt+P、Ctrl+Shift+K 或 F9~F12（需包含两个修饰键或功能键）"
        } | ConvertTo-Json -Compress
        exit 0
    }
    Write-Lnk $startMenuLnk $norm
    if ($Desktop) {
        # 桌面快捷方式不重复注册 Hotkey，避免与开始菜单捷径触发冲突
        Write-Lnk $desktopLnk ""
    } elseif ($RemoveDesktop -and (Test-Path -LiteralPath $desktopLnk)) {
        Remove-Item -LiteralPath $desktopLnk -Force -ErrorAction SilentlyContinue
    }
    if ($AutoStartMode -eq "enable" -or $AutoStart) {
        Write-Lnk $startupLnk ""
    } elseif ($AutoStartMode -eq "disable" -and (Test-Path -LiteralPath $startupLnk)) {
        Remove-Item -LiteralPath $startupLnk -Force -ErrorAction SilentlyContinue
    }
} elseif ($Action -eq "unbind") {
    if (Test-Path -LiteralPath $startMenuLnk) {
        Remove-Item -LiteralPath $startMenuLnk -Force -ErrorAction SilentlyContinue
    }
    if ($RemoveDesktop -and (Test-Path -LiteralPath $desktopLnk)) {
        Remove-Item -LiteralPath $desktopLnk -Force -ErrorAction SilentlyContinue
    }
    if ($AutoStartMode -eq "disable" -and (Test-Path -LiteralPath $startupLnk)) {
        Remove-Item -LiteralPath $startupLnk -Force -ErrorAction SilentlyContinue
    }
}

$bound = Test-Path -LiteralPath $startMenuLnk
$detectedHotkey = ""
if ($bound) {
    try {
        $ws = New-Object -ComObject WScript.Shell
        $sc = $ws.CreateShortcut($startMenuLnk)
        $detectedHotkey = Normalize-Hotkey $sc.Hotkey
    } catch { }
}
$hasDesktop = Test-Path -LiteralPath $desktopLnk
$hasAutoStart = Test-Path -LiteralPath $startupLnk

[PSCustomObject]@{
    ok = $true
    bound = [bool]($bound -and $detectedHotkey)
    hotkey = $detectedHotkey
    desktopShortcut = $hasDesktop
    autoStart = $hasAutoStart
    startMenuPath = $startMenuLnk
    desktopPath = $desktopLnk
    startupPath = $startupLnk
} | ConvertTo-Json -Compress
