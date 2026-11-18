@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo 将把虚拟显示驱动改为 2 块屏，并补充 iPad 常见分辨率。
echo 需要管理员权限，UAC 弹窗请点“是”。
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0apply-ipad-screen.ps1"
echo.
pause
