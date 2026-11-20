@echo off

chcp 65001 >nul
cd /d "%~dp0"
title 手机控制台 - PC 服务端

echo ================================================================
echo  Hello~~~~~~
echo ================================================================
echo. 

echo [1/3] 结束旧实例...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ports=@(8500,8501); $ids = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $ports -contains $_.LocalPort } | Select-Object -ExpandProperty OwningProcess -Unique; foreach ($id in $ids) { $p = Get-Process -Id $id -ErrorAction SilentlyContinue; if ($p -and ($p.ProcessName -eq 'java' -or $p.ProcessName -eq 'javaw')) { Write-Host ('  结束旧实例 PID ' + $id); Stop-Process -Id $id -Force -ErrorAction SilentlyContinue } }"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$procs = Get-CimInstance Win32_Process -Filter \"Name='java.exe' OR Name='javaw.exe'\" | Where-Object { $_.CommandLine -like '*phone-console-pc.jar*' }; foreach ($p in $procs) { Write-Host ('  结束残留进程 PID ' + $p.ProcessId); Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue }"

rem 等端口真正释放，避免新旧实例抢端口
powershell -NoProfile -ExecutionPolicy Bypass -Command "for ($i=0; $i -lt 24; $i++) { $used = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -in 8500,8501 }; if (-not $used) { exit 0 }; Start-Sleep -Milliseconds 250 }; exit 1"
if errorlevel 1 (
    echo   端口 8500 / 8501 仍被占用，且占用者不是 java 进程。
    echo   请检查是否有其他程序占用了这两个端口后重试。
    pause
    
    exit /b 1
)
echo   旧实例已清理完成
echo.

echo [2/3] 检查 jar...
if not exist "target\phone-console-pc.jar" (
    echo   未找到 jar，正在打包...
    call mvn -q -DskipTests package
    if errorlevel 1 (
        echo   打包失败，请检查 Maven / JDK 环境。
        pause
        exit /b 1
    )
)
echo   jar 就绪
echo.

echo [3/3] 启动服务，就绪后将自动打开网页控制台...
echo.

rem 后台等待 8501 端口就绪后打开浏览器，不阻塞前台日志
start "" /b powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -Command "for ($i=0; $i -lt 120; $i++) { $c = New-Object Net.Sockets.TcpClient; try { $c.Connect('127.0.0.1', 8501); Start-Process 'http://127.0.0.1:8501/'; break } catch { Start-Sleep -Milliseconds 500 } finally { $c.Dispose() } }"

rem 优先用 JAVA_HOME 里的真实 JVM，避免经过 javapath 转接层多出一个进程
set "JAVA_CMD=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"

rem P0：限制堆与元空间上限，避免默认堆随物理内存膨胀；多端并发 OOM 时可把 -Xmx256m 调到 384m
"%JAVA_CMD%" -Xms64m -Xmx256m -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m -jar target\phone-console-pc.jar
set "EXITCODE=%errorlevel%"

echo.
echo 服务已退出（退出码 %EXITCODE%）。再次双击本脚本即可重启。
pause
