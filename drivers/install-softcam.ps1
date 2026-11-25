$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$srcDll = Join-Path $root 'softcam\softcam.dll'
$destDir = 'C:\PhoneConsoleSoftcam'
$destDll = Join-Path $destDir 'softcam.dll'
$clsidPath = 'Registry::HKEY_CLASSES_ROOT\CLSID\{AEF3B972-5FA5-4647-9571-358EB472BC9E}\InprocServer32'

try {
    $admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    if (-not $admin) {
        Write-Host '[错误] 请以管理员权限运行本脚本。' -ForegroundColor Red
        exit 2
    }
    if (-not (Test-Path -LiteralPath $srcDll)) {
        Write-Host "[错误] 缺少驱动文件: $srcDll" -ForegroundColor Red
        exit 3
    }

    New-Item -ItemType Directory -Force -Path $destDir | Out-Null
    Copy-Item -LiteralPath $srcDll -Destination $destDll -Force

    $proc = Start-Process -FilePath (Join-Path $env:SystemRoot 'System32\regsvr32.exe') -ArgumentList '/s', "`"$destDll`"" -Wait -PassThru -NoNewWindow
    if ($proc.ExitCode -ne 0) {
        Write-Host "[错误] regsvr32 注册失败，退出码=$($proc.ExitCode)" -ForegroundColor Red
        exit 1
    }

    if (Test-Path $clsidPath) {
        $regVal = (Get-ItemProperty -Path $clsidPath).'(default)'
        Write-Host "[完成] 虚拟摄像头驱动已注册（DirectShow Softcam）：$regVal" -ForegroundColor Green
        Write-Host '请在微信视频 / 腾讯会议等软件的摄像头设置中选择 "DirectShow Softcam"。'
        exit 0
    }

    Write-Host '[警告] regsvr32 已执行，但未检测到 CLSID 注册项。' -ForegroundColor Yellow
    exit 1
} catch {
    Write-Host "[错误] $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}