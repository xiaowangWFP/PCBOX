param(
    [int]$Monitors = 2,
    [string[]]$Modes = @('2360x1640', '2732x2048', '2266x1488', '2048x1536')
)

$ErrorActionPreference = 'Stop'
$settings = 'C:\VirtualDisplayDriver\vdd_settings.xml'
$log = Join-Path $PSScriptRoot 'apply-ipad-screen.log'

function Write-Log([string]$message) {
    $line = "$(Get-Date -Format 'HH:mm:ss') $message"
    Add-Content -LiteralPath $log -Value $line -Encoding UTF8
    Write-Host $line
}

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Log '需要管理员权限，正在以管理员身份重新启动本脚本（会弹 UAC）'
    # 只转发屏数；分辨率用脚本默认值，避免命令行把数组拆成多个位置参数导致只生效第一个
    $argList = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$PSCommandPath`"", '-Monitors', "$Monitors")
    try {
        Start-Process -FilePath 'powershell.exe' -Verb RunAs -ArgumentList $argList | Out-Null
    } catch {
        Write-Log '已取消管理员授权，本次未做任何修改'
        exit 0
    }
    exit 0
}

if (-not (Test-Path -LiteralPath $settings)) {
    Write-Log "ERROR 找不到 $settings，请先运行 install-vdd.ps1"
    exit 1
}

# 1. 备份
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backup = "$settings.bak-$stamp"
Copy-Item -LiteralPath $settings -Destination $backup -Force
Write-Log "已备份配置文件：$backup"

# 2. 生成新配置：保留 1920x1080 作为原屏模式，追加 iPad 分辨率
$all = @('1920x1080') + $Modes
$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine("<?xml version='1.0' encoding='utf-8'?>")
[void]$sb.AppendLine('<vdd_settings>')
[void]$sb.AppendLine('    <monitors>')
[void]$sb.AppendLine("        <count>$Monitors</count>")
[void]$sb.AppendLine('    </monitors>')
[void]$sb.AppendLine('    <gpu>')
[void]$sb.AppendLine('        <friendlyname>default</friendlyname>')
[void]$sb.AppendLine('    </gpu>')
[void]$sb.AppendLine('    <global>')
[void]$sb.AppendLine('        <g_refresh_rate>60</g_refresh_rate>')
[void]$sb.AppendLine('    </global>')
[void]$sb.AppendLine('    <resolutions>')
foreach ($mode in $all) {
    $parts = $mode.Split('x')
    if ($parts.Count -ne 2) { continue }
    [void]$sb.AppendLine('        <resolution>')
    [void]$sb.AppendLine("            <width>$($parts[0])</width>")
    [void]$sb.AppendLine("            <height>$($parts[1])</height>")
    [void]$sb.AppendLine('            <refresh_rate>60</refresh_rate>')
    [void]$sb.AppendLine('        </resolution>')
}
[void]$sb.AppendLine('    </resolutions>')
[void]$sb.AppendLine('    <options>')
[void]$sb.AppendLine('        <CustomEdid>false</CustomEdid>')
[void]$sb.AppendLine('        <PreventSpoof>false</PreventSpoof>')
[void]$sb.AppendLine('        <EdidCeaOverride>false</EdidCeaOverride>')
[void]$sb.AppendLine('        <HardwareCursor>true</HardwareCursor>')
[void]$sb.AppendLine('        <SDR10bit>false</SDR10bit>')
[void]$sb.AppendLine('        <HDRPlus>false</HDRPlus>')
[void]$sb.AppendLine('        <logging>false</logging>')
[void]$sb.AppendLine('        <debuglogging>false</debuglogging>')
[void]$sb.AppendLine('    </options>')
[void]$sb.AppendLine('</vdd_settings>')

Set-Content -LiteralPath $settings -Value $sb.ToString() -Encoding UTF8
Write-Log "已写入新配置：显示器数量=$Monitors，分辨率=$($all -join ', ')"

# 3. 重启虚拟显示设备节点使配置生效
# 只认 MttVDD 这个设备（"Virtual Display Driver" 是 INF 里的 DeviceName），
# 不能按 "*Virtual Display*" 模糊匹配，否则会误命中 MuMu / GameViewer / ToDesk 的虚拟屏
$device = Get-PnpDevice -Class Display -ErrorAction SilentlyContinue |
    Where-Object { $_.FriendlyName -eq 'Virtual Display Driver' } |
    Select-Object -First 1
if (-not $device) {
    $device = Get-PnpDevice -InstanceId 'ROOT\DISPLAY\0004' -ErrorAction SilentlyContinue
}
if (-not $device) {
    Write-Log 'WARN 未找到虚拟显示设备节点，请手动在设备管理器里禁用/启用一次'
} else {
    Write-Log "重启设备：$($device.InstanceId)（$($device.FriendlyName)）"
    $out = & pnputil /restart-device "$($device.InstanceId)" 2>&1
    $out | ForEach-Object { Write-Log "pnputil: $_" }
    Write-Log "pnputil exit=$LASTEXITCODE"
}

Start-Sleep -Seconds 6

# 4. 汇报当前屏幕
Add-Type -AssemblyName System.Windows.Forms
$screens = [System.Windows.Forms.Screen]::AllScreens
Write-Log "当前屏幕数量：$($screens.Count)"
foreach ($s in $screens) {
    Write-Log ("  {0} primary={1} bounds={2}" -f $s.DeviceName, $s.Primary, $s.Bounds)
}

Write-Log '完成。请在「设置 → 系统 → 显示」里把新增的虚拟屏设为“扩展这些显示器”，'
Write-Log '分辨率选 2360×1640（11 寸 iPad）或 2732×2048（12.9/13 寸）等对应比例。'
Start-Process 'ms-settings:display'
