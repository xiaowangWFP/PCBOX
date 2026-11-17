@echo off
chcp 65001 >nul
cd /d "%~dp0"
title 手机控制台 - 后台启动

if not exist "target\phone-console-pc.jar" (
    echo 未找到 jar，正在打包...
    call mvn -q -DskipTests package
    if errorlevel 1 (
        echo 打包失败，请检查 Maven / JDK 环境。
        pause
        exit /b 1
    )
)

powershell -NoProfile -Command "if (Get-NetTCPConnection -LocalPort 8500 -State Listen -ErrorAction SilentlyContinue) { exit 1 } else { exit 0 }"
if errorlevel 1 (
    echo 已有服务端实例在运行（8500 端口被占用），无需重复启动。
    echo 如需重启，请先运行 stop.cmd。
    ping -n 6 127.0.0.1 >nul
    exit /b 1
)

echo 正在后台启动（无控制台窗口）...
start "" javaw -jar target\phone-console-pc.jar

ping -n 10 127.0.0.1 >nul
powershell -NoProfile -Command "if (Get-NetTCPConnection -LocalPort 8500 -State Listen -ErrorAction SilentlyContinue) { Write-Host '启动成功：服务已在后台运行' -ForegroundColor Green } else { Write-Host '启动失败：请检查日志或端口占用' -ForegroundColor Red }"
echo.
echo 托盘图标在屏幕右下角：
echo   * 双击图标 - 打开调试页面
echo   * 右键菜单 - 打开调试页面 / 退出
echo.
echo 本窗口可以关闭，不影响后台服务。
ping -n 7 127.0.0.1 >nul
