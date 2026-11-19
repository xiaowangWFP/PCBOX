# 热重载验证：推流进行中通过 /api/config 修改分辨率与编码参数，
# 校验服务端是否自动重启编码器并按新参数（1080p / veryfast / -threads 2）出流。
# 用法：pwsh -File hotreload-test.ps1
param(
    [string]$Jar = 'd:\手机连接电脑方案\bench\artifacts\p0.jar',
    [string]$ConfigPath = 'd:\手机连接电脑方案\bench\artifacts\p0-config.json',
    [int]$WsPort = 18500,
    [int]$HttpPort = 18501,
    [string]$OutDir = 'd:\手机连接电脑方案\bench\raw'
)

$ErrorActionPreference = 'Stop'
$probePath = Join-Path $PSScriptRoot 'probe.mjs'
$probeOut = Join-Path $OutDir 'hotreload-probe.json'
$backup = "$ConfigPath.bak"
Copy-Item $ConfigPath $backup -Force
if (Test-Path $probeOut) { Remove-Item $probeOut -Force }

$tag = 'hotreload-' + (Get-Date -Format 'HHmmss')
$javaExe = 'D:\Java21\bin\java.exe'
$server = $null

try {
    $server = Start-Process -FilePath $javaExe -ArgumentList @("-Dbench.tag=$tag", "-Dconfig=$ConfigPath", '-jar', $Jar) `
        -WorkingDirectory 'd:\手机连接电脑方案\pc-server' -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $OutDir 'hotreload-server.out') `
        -RedirectStandardError (Join-Path $OutDir 'hotreload-server.err')

    $deadline = (Get-Date).AddSeconds(50)
    while ((Get-Date) -lt $deadline) {
        if (Get-NetTCPConnection -LocalPort $WsPort -State Listen -ErrorAction SilentlyContinue) { break }
        Start-Sleep -Milliseconds 300
    }

    $probe = Start-Process -FilePath 'node' -ArgumentList @($probePath, "ws://127.0.0.1:$WsPort", '0', '34', $probeOut) -PassThru -WindowStyle Hidden
    Start-Sleep -Seconds 10

    $before = (Get-CimInstance Win32_Process -Filter "Name='ffmpeg.exe'" |
            Where-Object { $_.ParentProcessId -eq $server.Id } |
            Select-Object -First 1).CommandLine

    $config = Get-Content $ConfigPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $config.screen.maxWidth = 1920
    $config.screen.maxHeight = 1080
    $config.screen.scaleFlags = 'bicubic'
    $config.screen.x264Preset = 'veryfast'
    $config.screen.maxBitrateKbps = 6000
    $config.screen.x264Threads = 2
    $body = $config | ConvertTo-Json -Depth 20
    $response = Invoke-RestMethod -Uri "http://127.0.0.1:$HttpPort/api/config" -Method Post -Body $body -ContentType 'application/json; charset=utf-8'
    Start-Sleep -Seconds 8

    $after = (Get-CimInstance Win32_Process -Filter "Name='ffmpeg.exe'" |
            Where-Object { $_.ParentProcessId -eq $server.Id } |
            Select-Object -First 1).CommandLine

    $probe.WaitForExit(30000) | Out-Null
    $probeData = Get-Content $probeOut -Raw -Encoding UTF8 | ConvertFrom-Json

    [pscustomobject]@{
        saveOk        = $response.ok
        warnings      = ($response.warnings -join '; ')
        beforeCommand = $before
        afterCommand  = $after
        stats         = $probeData.stats
        states        = $probeData.states
    } | ConvertTo-Json -Depth 8 | Tee-Object -Variable output | Set-Content (Join-Path $OutDir 'hotreload-result.json') -Encoding UTF8
    $output
}
finally {
    foreach ($p in @(Get-Process -Name ffmpeg -ErrorAction SilentlyContinue)) {
        Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
    }
    if ($server -and -not $server.HasExited) { Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue }
    if (Test-Path $backup) {
        Move-Item $backup $ConfigPath -Force
    }
}
