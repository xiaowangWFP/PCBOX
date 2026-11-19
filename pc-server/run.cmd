@echo off
chcp 65001 >nul
cd /d "%~dp0"
title 手机控制台 PC 服务端

if not exist "target\phone-console-pc.jar" (
    echo [1/2] 未找到 jar，正在打包...
    call mvn -q -DskipTests package
    if errorlevel 1 (
        echo 打包失败，请检查 Maven / JDK 环境。
        pause
        exit /b 1
    )
)

echo ================================================================
echo  手机控制台 PC 服务端
echo.
echo  * 本窗口开着 = 服务在跑；关闭本窗口 = 服务停止
echo  * 也可以右键右下角托盘图标 -^> 退出
echo  * 如果不想占用窗口，用 start.cmd 后台启动
echo ================================================================
echo.
echo [2/2] 正在启动，日志如下：
echo.

rem P0：限制堆与元空间上限，避免默认堆随物理内存膨胀；多端并发 OOM 时可把 -Xmx256m 调到 384m
java -Xms64m -Xmx256m -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m -jar target\phone-console-pc.jar
set "EXITCODE=%errorlevel%"

echo.
if "%EXITCODE%"=="2" (
    echo 启动被拒绝（通常是端口已被占用，已有实例在运行）。
    echo 请检查右下角托盘图标，先退出旧实例再启动。
) else (
    echo 服务已退出，退出码 %EXITCODE%。
)
pause
