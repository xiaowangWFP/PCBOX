# 基准测量：启动指定 jar，采样空闲态与推流态的 java / ffmpeg CPU、内存，
# 以及全系统 CPU、NVIDIA GPU 利用率 / 显存 / 功耗（nvidia-smi 可用时）。
# 动效窗口两阶段都开着，保证桌面变化量一致，差值才纯粹是推流开销。
# 用法示例：
#   pwsh -File measure.ps1 -Label baseline
#   pwsh -File measure.ps1 -Label p0 -JvmArgs "-Xms64m -Xmx256m -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m"
#   pwsh -File measure.ps1 -Label gpu -NoGpu
param(
    [Parameter(Mandatory = $true)][string]$Label,
    [string]$Jar = 'd:\手机连接电脑方案\pc-server\target\phone-console-pc.jar',
    [string]$WorkDir = 'd:\手机连接电脑方案\pc-server',
    [string]$JvmArgs = '',
    [string]$ConfigPath = '',
    [int]$WsPort = 8500,
    [int]$HttpPort = 8501,
    [int]$Monitor = 0,
    [int]$IdleSeconds = 8,
    [int]$StreamSeconds = 30,
    [switch]$NoMotion,
    [switch]$NoGpu,
    [string]$OutDir = 'd:\手机连接电脑方案\bench\raw'
)

$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$cores = [int](Get-CimInstance Win32_Processor | Measure-Object -Property NumberOfLogicalProcessors -Sum).Sum
$nvidiaSmi = $null
if (-not $NoGpu) {
    $nvidiaSmi = (Get-Command nvidia-smi -ErrorAction SilentlyContinue).Source
}

# 单次 nvidia-smi 约 60ms，可以跟上 1 秒节拍；不可用时整列留空
function Get-GpuSnapshot {
    if (-not $nvidiaSmi) { return $null }
    try {
        $line = @(& $nvidiaSmi --query-gpu=utilization.gpu,memory.used,power.draw --format=csv,noheader,nounits 2>$null)[0]
        if (-not $line) { return $null }
        $parts = $line -split ','
        if ($parts.Count -lt 3) { return $null }
        [pscustomobject]@{
            UtilPct = $parts[0].Trim() -as [double]
            MemMB   = $parts[1].Trim() -as [double]
            PowerW  = $parts[2].Trim() -as [double]
        }
    } catch {
        return $null
    }
}

# 全适配器（含核显）的每进程 GPU 引擎占用，单次约 1.5 秒，只在阶段结束时抓一次
function Get-GpuEngineUsage {
    if ($NoGpu) { return $null }
    try {
        $samples = (Get-Counter '\GPU Engine(*)\Utilization Percentage' -ErrorAction Stop).CounterSamples
    } catch {
        return $null
    }
    $byEngine = @{}
    $byPid = @{}
    foreach ($s in $samples) {
        $value = [double]$s.CookedValue
        if ($value -le 0.05) { continue }
        $engine = 'other'
        if ($s.InstanceName -match 'engtype_([a-z0-9]+)') { $engine = $Matches[1] }
        if (-not $byEngine.ContainsKey($engine)) { $byEngine[$engine] = 0.0 }
        $byEngine[$engine] += $value
        if ($s.InstanceName -match '^pid_(\d+)_') {
            $enginePid = [int]$Matches[1]
            if ($enginePid -eq $PID) { continue }
            if (-not $byPid.ContainsKey($enginePid)) { $byPid[$enginePid] = 0.0 }
            $byPid[$enginePid] += $value
        }
    }
    $total = 0.0
    if ($byEngine.Count -gt 0) { $total = ($byEngine.Values | Measure-Object -Sum).Sum }
    $topPids = @()
    foreach ($entry in @($byPid.GetEnumerator() | Sort-Object Value -Descending | Select-Object -First 8)) {
        $procName = '?'
        try { $procName = (Get-Process -Id $entry.Key -ErrorAction Stop).ProcessName } catch { }
        $topPids += [pscustomobject]@{ pid = $entry.Key; process = $procName; pct = [math]::Round($entry.Value, 2) }
    }
    [pscustomobject]@{
        totalPct = [math]::Round($total, 1)
        byEngine = @($byEngine.GetEnumerator() | Sort-Object Value -Descending | ForEach-Object {
                [pscustomobject]@{ engine = $_.Key; pct = [math]::Round($_.Value, 2) }
            })
        topPids  = $topPids
    }
}

foreach ($port in $WsPort, $HttpPort) {
    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {
        throw "端口 $port 已被占用，请先关闭现有服务端实例"
    }
}

$probePath = Join-Path $PSScriptRoot 'probe.mjs'
$probeOut = Join-Path $OutDir "$Label-probe.json"
foreach ($file in $probeOut, (Join-Path $OutDir "$Label-server.out"), (Join-Path $OutDir "$Label-server.err")) {
    if (Test-Path $file) { Remove-Item $file -Force }
}

# javapath 下是 Oracle 启动器壳进程（会立刻退出并 spawn 真实 JVM），必须直连真实 java.exe
$javaExe = 'D:\Java21\bin\java.exe'
if (-not (Test-Path $javaExe)) {
    $javaExe = (Get-Command java).Source
}

$tag = "bench-$Label-" + (Get-Date -Format 'HHmmss')
$argList = @("-Dbench.tag=$tag")
if ($JvmArgs.Trim().Length -gt 0) {
    $argList += $JvmArgs.Trim() -split '\s+'
}
if ($ConfigPath.Trim().Length -gt 0) {
    $argList += "-Dconfig=$ConfigPath"
}
$argList += @('-jar', $Jar)

$startedAt = Get-Date
$server = Start-Process -FilePath $javaExe -ArgumentList $argList -WorkingDirectory $WorkDir -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $OutDir "$Label-server.out") `
    -RedirectStandardError (Join-Path $OutDir "$Label-server.err")

$probe = $null
$motion = $null
$rows = @()
$javaPid = 0

function Get-Snapshot {
    $java = Get-Process -Id $javaPid -ErrorAction SilentlyContinue
    if (-not $java) { return $null }
    $ffCpu = 0.0
    $ffRss = 0.0
    $ffCount = 0
    foreach ($p in @(Get-Process -Name ffmpeg -ErrorAction SilentlyContinue)) {
        try {
            if ($p.StartTime -lt $startedAt) { continue }
            $ffCpu += $p.TotalProcessorTime.TotalMilliseconds
            $ffRss += $p.WorkingSet64
            $ffCount++
        } catch {
            # 进程已退出
        }
    }
    # 动效播放器与 WebSocket 探针都是测试脚手架，单独记账，便于从系统 CPU 里扣掉
    $motionCpu = $null
    if ($motion) {
        $motionProc = Get-Process -Id $motion.Id -ErrorAction SilentlyContinue
        if ($motionProc) { $motionCpu = $motionProc.TotalProcessorTime.TotalMilliseconds }
    }
    $probeCpu = $null
    if ($probe) {
        $probeProc = Get-Process -Id $probe.Id -ErrorAction SilentlyContinue
        if ($probeProc) { $probeCpu = $probeProc.TotalProcessorTime.TotalMilliseconds }
    }
    $gpuUtil = $null
    $gpuMem = $null
    $gpuPower = $null
    $gpu = Get-GpuSnapshot
    if ($gpu) {
        $gpuUtil = $gpu.UtilPct
        $gpuMem = $gpu.MemMB
        $gpuPower = $gpu.PowerW
    }
    [pscustomobject]@{
        JavaCpuMs   = $java.TotalProcessorTime.TotalMilliseconds
        JavaRssMB   = [math]::Round($java.WorkingSet64 / 1MB, 1)
        FfmpegCpuMs = $ffCpu
        FfmpegRssMB = [math]::Round($ffRss / 1MB, 1)
        FfmpegCount = $ffCount
        MotionCpuMs = $motionCpu
        ProbeCpuMs  = $probeCpu
        GpuUtilPct  = $gpuUtil
        GpuMemMB    = $gpuMem
        GpuPowerW   = $gpuPower
    }
}

function Invoke-Sampling([int]$Seconds, [string]$Phase) {
    $result = @()
    $prev = Get-Snapshot
    if ($null -eq $prev) { return $result }
    $prevTime = Get-Date
    for ($i = 0; $i -lt $Seconds; $i++) {
        # Get-Counter 自身要跑满 1 秒采样窗口，正好当作本次循环的节拍
        $sysCpu = $null
        $tick = Get-Date
        try {
            $sysCpu = [math]::Round((Get-Counter '\Processor(_Total)\% Processor Time' -ErrorAction Stop).CounterSamples[0].CookedValue, 1)
        } catch {
            $sysCpu = $null
        }
        $tickMs = ((Get-Date) - $tick).TotalMilliseconds
        if ($tickMs -lt 900) { Start-Sleep -Milliseconds ([int](900 - $tickMs)) }
        $cur = Get-Snapshot
        if ($null -eq $cur) { break }
        $now = Get-Date
        $elapsedMs = ($now - $prevTime).TotalMilliseconds
        if ($elapsedMs -le 0) { continue }
        $motionPct = $null
        if ($null -ne $cur.MotionCpuMs -and $null -ne $prev.MotionCpuMs) {
            $motionPct = [math]::Round(($cur.MotionCpuMs - $prev.MotionCpuMs) / ($elapsedMs * $cores) * 100, 1)
        }
        $probePct = $null
        if ($null -ne $cur.ProbeCpuMs -and $null -ne $prev.ProbeCpuMs) {
            $probePct = [math]::Round(($cur.ProbeCpuMs - $prev.ProbeCpuMs) / ($elapsedMs * $cores) * 100, 1)
        }
        $sysExcl = $null
        if ($null -ne $sysCpu) {
            $deduct = 0.0
            if ($null -ne $motionPct) { $deduct += $motionPct }
            if ($null -ne $probePct) { $deduct += $probePct }
            $sysExcl = [math]::Round($sysCpu - $deduct, 1)
        }
        $result += [pscustomobject]@{
            phase            = $Phase
            second           = $i
            javaCpuPct       = [math]::Round(($cur.JavaCpuMs - $prev.JavaCpuMs) / ($elapsedMs * $cores) * 100, 1)
            ffmpegCpuPct     = [math]::Round(($cur.FfmpegCpuMs - $prev.FfmpegCpuMs) / ($elapsedMs * $cores) * 100, 1)
            motionCpuPct     = $motionPct
            probeCpuPct      = $probePct
            sysCpuPct        = $sysCpu
            sysCpuExclHarness = $sysExcl
            gpuUtilPct       = $cur.GpuUtilPct
            gpuMemMB         = $cur.GpuMemMB
            gpuPowerW        = $cur.GpuPowerW
            javaRssMB        = $cur.JavaRssMB
            ffmpegRssMB      = $cur.FfmpegRssMB
            ffmpegCount      = $cur.FfmpegCount
        }
        $prev = $cur
        $prevTime = $now
    }
    return $result
}

function Get-Stat($items, [string]$Name) {
    $values = @($items | ForEach-Object { $_.$Name } | Where-Object { $null -ne $_ })
    if ($values.Count -eq 0) {
        return [pscustomobject]@{ avg = $null; max = $null }
    }
    [pscustomobject]@{
        avg = [math]::Round(($values | Measure-Object -Average).Average, 1)
        max = [math]::Round(($values | Measure-Object -Maximum).Maximum, 1)
    }
}

function Get-Summary($items, [int]$Skip) {
    $steady = @($items | Select-Object -Skip $Skip)
    if ($steady.Count -eq 0) { $steady = @($items) }
    if ($steady.Count -eq 0) { return $null }
    $totals = @($steady | ForEach-Object { $_.javaCpuPct + $_.ffmpegCpuPct })
    $sysCpu = Get-Stat -items $steady -Name 'sysCpuPct'
    $sysExcl = Get-Stat -items $steady -Name 'sysCpuExclHarness'
    $gpuUtil = Get-Stat -items $steady -Name 'gpuUtilPct'
    $gpuMem = Get-Stat -items $steady -Name 'gpuMemMB'
    $gpuPower = Get-Stat -items $steady -Name 'gpuPowerW'
    $motionCpu = Get-Stat -items $steady -Name 'motionCpuPct'
    $probeCpu = Get-Stat -items $steady -Name 'probeCpuPct'
    [pscustomobject]@{
        samples         = $steady.Count
        javaCpuPctAvg   = [math]::Round(($steady | Measure-Object javaCpuPct -Average).Average, 1)
        javaCpuPctMax   = [math]::Round(($steady | Measure-Object javaCpuPct -Maximum).Maximum, 1)
        ffmpegCpuPctAvg = [math]::Round(($steady | Measure-Object ffmpegCpuPct -Average).Average, 1)
        ffmpegCpuPctMax = [math]::Round(($steady | Measure-Object ffmpegCpuPct -Maximum).Maximum, 1)
        totalCpuPctAvg  = [math]::Round(($totals | Measure-Object -Average).Average, 1)
        totalCpuPctMax  = [math]::Round(($totals | Measure-Object -Maximum).Maximum, 1)
        javaRssMBAvg    = [math]::Round(($steady | Measure-Object javaRssMB -Average).Average, 1)
        javaRssMBMax    = [math]::Round(($steady | Measure-Object javaRssMB -Maximum).Maximum, 1)
        ffmpegRssMBAvg  = [math]::Round(($steady | Measure-Object ffmpegRssMB -Average).Average, 1)
        ffmpegRssMBMax  = [math]::Round(($steady | Measure-Object ffmpegRssMB -Maximum).Maximum, 1)
        ffmpegCountMax  = [math]::Round(($steady | Measure-Object ffmpegCount -Maximum).Maximum, 0)
        totalRssMBAvg   = [math]::Round((($steady | Measure-Object javaRssMB -Average).Average) + (($steady | Measure-Object ffmpegRssMB -Average).Average), 1)
        motionCpuPctAvg = $motionCpu.avg
        probeCpuPctAvg  = $probeCpu.avg
        sysCpuPctAvg    = $sysCpu.avg
        sysCpuPctMax    = $sysCpu.max
        sysCpuExclHarnessAvg = $sysExcl.avg
        sysCpuExclHarnessMax = $sysExcl.max
        gpuUtilPctAvg   = $gpuUtil.avg
        gpuUtilPctMax   = $gpuUtil.max
        gpuMemMBAvg     = $gpuMem.avg
        gpuMemMBMax     = $gpuMem.max
        gpuPowerWAvg    = $gpuPower.avg
        gpuPowerWMax    = $gpuPower.max
    }
}

try {
    $deadline = (Get-Date).AddSeconds(50)
    $ready = $false
    while ((Get-Date) -lt $deadline) {
        if ($server.HasExited) { throw "服务端启动失败，退出码 $($server.ExitCode)，详见 $OutDir\$Label-server.err" }
        if (Get-NetTCPConnection -LocalPort $WsPort -State Listen -ErrorAction SilentlyContinue) {
            $ready = $true
            break
        }
        Start-Sleep -Milliseconds 300
    }
    if (-not $ready) { throw "服务端 50 秒内未监听 $WsPort 端口" }

    $javaPid = [int](Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
            Where-Object { $_.CommandLine -and $_.CommandLine.Contains($tag) } |
            Select-Object -First 1 -ExpandProperty ProcessId)
    if ($javaPid -eq 0) { throw '未找到本次启动的 JVM 进程' }

    # 动效窗口在空闲阶段就打开：两阶段桌面变化量一致，差值才是纯粹的推流开销
    if (-not $NoMotion) {
        # 用 30fps 动态彩条覆盖主屏中部，制造接近真实使用的画面变化量（该进程 CPU 不计入统计）
        $motion = Start-Process -FilePath 'ffplay' -ArgumentList @(
            '-hide_banner', '-loglevel', 'error',
            '-f', 'lavfi', '-i', 'testsrc2=size=1280x720:rate=30',
            '-noborder', '-x', '1280', '-y', '720', '-left', '80', '-top', '80'
        ) -PassThru -WindowStyle Normal
        Start-Sleep -Seconds 2
        if ($motion.HasExited) { Write-Warning '动态画面窗口启动失败，本次采集为静态桌面负载' }
    }
    Start-Sleep -Seconds 2
    $rows += Invoke-Sampling -Seconds $IdleSeconds -Phase 'idle'
    $gpuIdle = Get-GpuEngineUsage

    # 探针比采样多跑 20 秒，保证采样窗口内编码器不会被主动关闭
    $probeSeconds = $StreamSeconds + 20
    $probe = Start-Process -FilePath 'node' -ArgumentList @($probePath, "ws://127.0.0.1:$WsPort", "$Monitor", "$probeSeconds", $probeOut) -PassThru -WindowStyle Hidden
    Start-Sleep -Seconds 3
    # 采样刚开始时编码器一定还活着，先记下命令行
    try {
        $ffmpegCommand = (Get-CimInstance Win32_Process -Filter "Name='ffmpeg.exe'" |
                Where-Object { $_.ParentProcessId -eq $javaPid } |
                Select-Object -First 1).CommandLine
    } catch {
        $ffmpegCommand = ''
    }
    $rows += Invoke-Sampling -Seconds $StreamSeconds -Phase 'stream'
    $gpuStream = Get-GpuEngineUsage

    $jvmHeap = ''
    try {
        $jcmdExe = 'D:\Java21\bin\jcmd.exe'
        if (-not (Test-Path $jcmdExe)) { $jcmdExe = 'jcmd' }
        $jvmHeap = (& $jcmdExe $javaPid GC.heap_info 2>&1 | Out-String)
    } catch {
        $jvmHeap = "jcmd 不可用：$($_.Exception.Message)"
    }
    $probe.WaitForExit(20000) | Out-Null
    if (-not $probe.HasExited) { Stop-Process -Id $probe.Id -Force -ErrorAction SilentlyContinue }

    $idleSummary = Get-Summary -items @($rows | Where-Object phase -eq 'idle') -Skip 2
    $streamSummary = Get-Summary -items @($rows | Where-Object { $_.phase -eq 'stream' -and $_.ffmpegCount -gt 0 }) -Skip 8

    $probeData = $null
    if (Test-Path $probeOut) {
        $probeData = Get-Content $probeOut -Raw -Encoding UTF8 | ConvertFrom-Json
    }

    $gpuAdapterName = $null
    if ($nvidiaSmi) {
        $gpuAdapterName = (& $nvidiaSmi --query-gpu=name --format=csv,noheader 2>$null | Select-Object -First 1)
    }

    $result = [pscustomobject]@{
        label     = $Label
        jar       = $Jar
        jvmArgs   = $JvmArgs
        configPath = $ConfigPath
        cores     = $cores
        monitor   = $Monitor
        motion    = (-not $NoMotion)
        startedAt = $startedAt.ToString('s')
        idle      = $idleSummary
        stream    = $streamSummary
        jvmHeap   = $jvmHeap
        ffmpegCommand = $ffmpegCommand
        gpuAdapter = $gpuAdapterName
        gpuEngine = [pscustomobject]@{ idle = $gpuIdle; stream = $gpuStream }
        probe     = $probeData
    }
    $result | ConvertTo-Json -Depth 8 | Set-Content -Path (Join-Path $OutDir "$Label-summary.json") -Encoding UTF8
    $rows | ConvertTo-Json -Depth 5 | Set-Content -Path (Join-Path $OutDir "$Label-samples.json") -Encoding UTF8
    $result | ConvertTo-Json -Depth 8
}
finally {
    if ($probe -and -not $probe.HasExited) { Stop-Process -Id $probe.Id -Force -ErrorAction SilentlyContinue }
    if ($motion -and -not $motion.HasExited) { Stop-Process -Id $motion.Id -Force -ErrorAction SilentlyContinue }
    # 只清理本次 JVM 的子进程，避免误杀本机其它实例正在使用的编码器
    if ($javaPid -gt 0) {
        foreach ($p in @(Get-CimInstance Win32_Process -Filter "Name='ffmpeg.exe'" -ErrorAction SilentlyContinue |
                Where-Object { $_.ParentProcessId -eq $javaPid })) {
            Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
        }
    }
    if (-not $server.HasExited) { Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue }
}
