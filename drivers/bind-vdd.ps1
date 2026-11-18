$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$nef = Join-Path $root 'nefcon\x64\nefconc.exe'
$inf = 'C:\VirtualDisplayDriver\MttVDD.inf'
$log = Join-Path $root 'bind.log'

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

    $out = & $nef --install-driver --inf-path $inf 2>&1
    $out | ForEach-Object { Write-Log "nefcon install-driver: $_" }
    Write-Log "nefcon exit=$LASTEXITCODE"

    $out = & pnputil /add-driver $inf /install 2>&1
    $out | ForEach-Object { Write-Log "pnputil: $_" }
    Write-Log "pnputil exit=$LASTEXITCODE"

    Start-Sleep -Seconds 4
    Get-PnpDevice -Class Display -ErrorAction SilentlyContinue |
        Where-Object { $_.InstanceId -like 'ROOT\DISPLAY\0004' -or $_.InstanceId -like '*MTTVDD*' } |
        ForEach-Object { Write-Log ("device: {0} | {1} | {2}" -f $_.InstanceId, $_.FriendlyName, $_.Status) }

    Add-Type -AssemblyName System.Windows.Forms
    Write-Log ("screens=" + [System.Windows.Forms.Screen]::AllScreens.Count)
    exit 0
} catch {
    Write-Log "ERROR $($_.Exception.Message)"
    exit 1
}
