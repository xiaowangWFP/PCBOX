param(
    [switch]$OpenBrowser,
    [int]$WaitPid = 0
)

$ErrorActionPreference = "Stop"
$srvDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$rootDir = Split-Path -Parent $srvDir
Set-Location $srvDir

# 读取当前配置中的端口（默认 ws=8500, http=8501）
$wsPort = 8500
$httpPort = 8501
$cfgPath = Join-Path $srvDir "config.json"
if (Test-Path -LiteralPath $cfgPath) {
    try {
        $cfg = Get-Content -Raw -Encoding UTF8 -LiteralPath $cfgPath | ConvertFrom-Json
        if ($cfg.network.wsPort) { $wsPort = [int]$cfg.network.wsPort }
        if ($cfg.network.httpPort) { $httpPort = [int]$cfg.network.httpPort }
    } catch { }
}

function Test-PortListening([int]$port) {
    try {
        $listeners = [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
        foreach ($ep in $listeners) {
            if ($ep.Port -eq $port) { return $true }
        }
    } catch { }
    return $false
}

if ($WaitPid -gt 0) {
    for ($w = 0; $w -lt 80; $w++) {
        $proc = Get-Process -Id $WaitPid -ErrorAction SilentlyContinue
        $busy = (Test-PortListening $httpPort) -or (Test-PortListening $wsPort)
        if (-not $proc -and -not $busy) { break }
        Start-Sleep -Milliseconds 150
    }
}

# 若服务端已在当前桌面运行，直接打开管理网页，不再重复拉起导致端口冲突弹窗
if (Test-PortListening $httpPort) {
    Start-Process ("http://127.0.0.1:" + $httpPort + "/")
    exit 0
}

# 把项目内置依赖加入 PATH，确保 adb / ffmpeg 可直接命中
$extraPaths = @(
    (Join-Path $rootDir "platform-tools"),
    (Join-Path $rootDir "deps\platform-tools"),
    (Join-Path $rootDir "deps\ffmpeg\bin"),
    (Join-Path $rootDir "deps\jre\bin")
) | Where-Object { Test-Path -LiteralPath $_ }
if ($extraPaths.Count -gt 0) {
    $env:PATH = ($extraPaths -join ";") + ";" + $env:PATH
}

# 若 jar 尚未打包则自动打包
$jarPath = Join-Path $srvDir "target\phone-console-pc.jar"
if (-not (Test-Path -LiteralPath $jarPath)) {
    & mvn -q -DskipTests package
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $jarPath)) {
        exit 1
    }
}

# 解析无黑框运行的 javaw.exe
$javawCandidates = @(
    (Join-Path $rootDir "deps\jre\bin\javaw.exe"),
    $(if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\javaw.exe" } else { "" }),
    "D:\Java21\bin\javaw.exe"
) | Where-Object { $_ -and (Test-Path -LiteralPath $_) }

$javaw = $javawCandidates | Select-Object -First 1
if (-not $javaw) {
    $cmd = Get-Command "javaw.exe" -ErrorAction SilentlyContinue
    if ($cmd) { $javaw = $cmd.Source }
}
if (-not $javaw) {
    $cmdJava = Get-Command "java.exe" -ErrorAction SilentlyContinue
    if ($cmdJava) { $javaw = $cmdJava.Source }
}
if (-not $javaw) {
    exit 2
}

$argList = @(
    "-Xms64m",
    "-Xmx256m",
    "-XX:+UseSerialGC",
    "-XX:MaxMetaspaceSize=128m",
    "-DtrayNotify=true",
    "-jar",
    $jarPath
)
Start-Process -FilePath $javaw -ArgumentList $argList -WorkingDirectory $srvDir -WindowStyle Hidden | Out-Null

if ($OpenBrowser) {
    for ($i = 0; $i -lt 40; $i++) {
        Start-Sleep -Milliseconds 300
        if (Test-PortListening $httpPort) {
            Start-Process ("http://127.0.0.1:" + $httpPort + "/")
            break
        }
    }
}
