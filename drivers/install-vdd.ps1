$ErrorActionPreference = 'Stop'
$root = 'd:\手机连接电脑方案\drivers'
$src = Join-Path $root 'vdd\VirtualDisplayDriver'
$nef = Join-Path $root 'nefcon\x64\nefconc.exe'
$dest = 'C:\VirtualDisplayDriver'
$inf = Join-Path $dest 'MttVDD.inf'
$hwid = 'Root\MttVDD'
$classGuid = '{4D36E968-E325-11CE-BFC1-08002BE10318}'
$log = Join-Path $root 'install.log'

function Write-Log([string]$message) {
    $line = "$(Get-Date -Format 'HH:mm:ss') $message"
    Add-Content -LiteralPath $log -Value $line -Encoding UTF8
    Write-Host $line
}

try {
    $admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    if (-not $admin) {
        Write-Log 'ERROR 未以管理员权限运行'
        exit 2
    }
    if (-not (Test-Path -LiteralPath $nef)) {
        Write-Log "ERROR 缺少 nefcon：$nef"
        exit 3
    }
    if (-not (Test-Path -LiteralPath (Join-Path $src 'MttVDD.inf'))) {
        Write-Log "ERROR 缺少驱动文件：$src"
        exit 3
    }

    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    Copy-Item -Path (Join-Path $src '*') -Destination $dest -Recurse -Force
    Write-Log "已复制驱动到 $dest"

    $out = & $nef --install-driver --inf-path $inf 2>&1
    $out | ForEach-Object { Write-Log "install-driver: $_" }
    Write-Log "install-driver exit=$LASTEXITCODE"

    $out = & $nef --create-device-node --hardware-id $hwid --class-name Display --class-guid $classGuid --no-duplicates 2>&1
    $out | ForEach-Object { Write-Log "create-device-node: $_" }
    Write-Log "create-device-node exit=$LASTEXITCODE"

    Start-Sleep -Seconds 3
    $devices = Get-PnpDevice -Class Display -ErrorAction SilentlyContinue |
        Where-Object { $_.InstanceId -like 'ROOT\MTTVDD*' -or $_.FriendlyName -like '*Virtual Display*' }
    foreach ($device in $devices) {
        Write-Log ("device: {0} | {1} | {2}" -f $device.InstanceId, $device.FriendlyName, $device.Status)
    }
    Add-Type -AssemblyName System.Windows.Forms
    Write-Log ("screens=" + [System.Windows.Forms.Screen]::AllScreens.Count)
    exit 0
} catch {
    Write-Log "ERROR $($_.Exception.Message)"
    exit 1
}
