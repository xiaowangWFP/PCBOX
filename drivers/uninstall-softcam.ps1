$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$destDir = 'C:\PhoneConsoleSoftcam'
$destDll = Join-Path $destDir 'softcam.dll'
$srcDll = Join-Path $root 'softcam\softcam.dll'
$clsidPath = 'Registry::HKEY_CLASSES_ROOT\CLSID\{AEF3B972-5FA5-4647-9571-358EB472BC9E}\InprocServer32'

try {
    $admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    if (-not $admin) {
        Write-Host '[错误] 请以管理员权限运行本脚本。' -ForegroundColor Red
        exit 2
    }

    $targetDll = $destDll
    if (Test-Path $clsidPath) {
        $regVal = (Get-ItemProperty -Path $clsidPath -ErrorAction SilentlyContinue).'(default)'
        if ($regVal -and (Test-Path -LiteralPath $regVal)) { $targetDll = $regVal }
    } elseif (-not (Test-Path -LiteralPath $targetDll)) {
        $targetDll = $srcDll
    }

    if (Test-Path -LiteralPath $targetDll) {
        $proc = Start-Process -FilePath (Join-Path $env:SystemRoot 'System32\regsvr32.exe') -ArgumentList '/s', '/u', "`"$targetDll`"" -Wait -PassThru -NoNewWindow
        if ($proc.ExitCode -ne 0) {
            Write-Host "[错误] regsvr32 卸载失败，退出码=$($proc.ExitCode)" -ForegroundColor Red
            exit 1
        }
    }

    if (Test-Path -LiteralPath $destDir) {
        Remove-Item -LiteralPath $destDir -Recurse -Force -ErrorAction SilentlyContinue
    }
    Write-Host '[完成] 虚拟摄像头驱动已卸载。' -ForegroundColor Green
    exit 0
} catch {
    Write-Host "[错误] $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}