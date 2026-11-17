@echo off
rem 一键打包签名 APK，产物：dist\phone-console-<版本>-release.apk
setlocal
cd /d "%~dp0"

if not defined ANDROID_HOME set "ANDROID_HOME=D:\android-sdk"
if not defined ANDROID_SDK_ROOT set "ANDROID_SDK_ROOT=%ANDROID_HOME%"

call gradlew.bat --no-daemon assembleRelease
if errorlevel 1 (
    echo [失败] 打包中断，请检查上方日志。
    exit /b 1
)

if not exist "..\dist" mkdir "..\dist"
copy /y "app\build\outputs\apk\release\phone-console-0.1.0-release.apk" "..\dist\" >nul
echo [完成] ..\dist\phone-console-0.1.0-release.apk
endlocal
