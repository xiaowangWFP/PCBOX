@echo off
chcp 65001 >nul
cd /d "%~dp0"
if not exist "工具箱\工具箱.cmd" (
    echo [错误] 找不到 工具箱\工具箱.cmd，请保持目录结构完整。
    pause
    exit /b 1
)
call "工具箱\工具箱.cmd" %*
