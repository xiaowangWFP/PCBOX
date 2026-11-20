@echo off

chcp 65001 >nul
cd /d "%~dp0"
title PC 服务端

echo ================================================================
echo  * 首次启动可能会慢一点
echo  * 启动完成后会自动打开网页控制台
echo ================================================================
echo.

echo [1/4] 结束旧实例...
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

echo [2/4] 检查运行环境...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup-env.ps1"
if errorlevel 1 (
    echo.
    echo 环境未就绪，已中止启动。补齐依赖后重新双击本脚本即可。
    pause
    exit /b 1
)
echo.

rem 把本地依赖目录加入 PATH，服务端默认配置里的 adb / ffmpeg 即可直接命中
set "DEPS=%~dp0..\deps"
if exist "%~dp0..\platform-tools\adb.exe" set "PATH=%~dp0..\platform-tools;%PATH%"
if exist "%DEPS%\platform-tools\adb.exe" set "PATH=%DEPS%\platform-tools;%PATH%"
if exist "%DEPS%\ffmpeg\bin\ffmpeg.exe" set "PATH=%DEPS%\ffmpeg\bin;%PATH%"
if exist "%DEPS%\jre\bin\java.exe" set "PATH=%DEPS%\jre\bin;%PATH%"
echo.

echo [3/4] 检查 jar...
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

echo [4/4] 启动服务，就绪后将自动打开网页控制台...
echo.

rem 后台等待 8501 端口就绪后打开浏览器，不阻塞前台日志
start "" /b powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -Command "for ($i=0; $i -lt 120; $i++) { $c = New-Object Net.Sockets.TcpClient; try { $c.Connect('127.0.0.1', 8501); Start-Process 'http://127.0.0.1:8501/'; break } catch { Start-Sleep -Milliseconds 500 } finally { $c.Dispose() } }"

rem 优先用本地下载的 JRE，其次 JAVA_HOME，最后 PATH 里的 java
set "JAVA_CMD=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
if exist "%DEPS%\jre\bin\java.exe" set "JAVA_CMD=%DEPS%\jre\bin\java.exe"

rem P0：限制堆与元空间上限，避免默认堆随物理内存膨胀；多端并发 OOM 时可把 -Xmx256m 调到 384m
"%JAVA_CMD%" -Xms64m -Xmx256m -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m -jar target\phone-console-pc.jar
set "EXITCODE=%errorlevel%"

echo.
echo 服务已退出（退出码 %EXITCODE%）。再次双击本脚本即可重启。
pause
