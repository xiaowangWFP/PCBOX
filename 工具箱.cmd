@echo off
chcp 65001 >nul
setlocal EnableExtensions EnableDelayedExpansion

rem 所有路径从脚本位置推导，整个工作区复制到任意目录后仍可使用
for %%i in ("%~dp0.") do set "ROOT=%%~fi"
set "DIST=%ROOT%\dist"
set "ADB=%ROOT%\platform-tools\adb.exe"
set "AND=%ROOT%\android-client"
set "SRV=%ROOT%\pc-server"
set "DRV=%ROOT%\drivers"

:MENU
cls
echo.
echo  ==========================================================
echo   手机连接电脑方案  -  工具箱
echo  ==========================================================
echo   工作区: %ROOT%
echo.
echo   [1]  环境检查 (JDK / Maven / Android SDK / ADB)
echo.
echo   -- 虚拟屏幕 --
echo   [2]  安装驱动              (需管理员)
echo   [3]  绑定/刷新驱动         (需管理员)
echo.
echo   -- 安卓端 --
echo   [4]  构建 Debug APK
echo   [5]  构建 Release APK
echo   [6]  安装 APK 到手机       (ADB)
echo   [7]  查看已连接设备        (ADB)
echo.
echo   -- PC 服务端 --
echo   [8]  构建 jar
echo   [9]  启动服务（自动重启并打开控制台）
echo.
echo   -- 共享 --
echo   [10] 打开发布目录 dist
echo   [11] 打包为 zip
echo   [0]  退出
echo  ==========================================================
set "CH="
set /p "CH=请选择 [0-11]: "

if "!CH!"=="0" exit /b 0
if "!CH!"=="1" goto ENV
if "!CH!"=="2" goto VDD_INSTALL
if "!CH!"=="3" goto VDD_BIND
if "!CH!"=="4" goto BLD_DEBUG
if "!CH!"=="5" goto BLD_RELEASE
if "!CH!"=="6" goto ADB_INSTALL
if "!CH!"=="7" goto ADB_DEVICES
if "!CH!"=="8" goto SRV_BUILD
if "!CH!"=="9" goto SRV_RUN
if "!CH!"=="10" goto OPEN_DIST
if "!CH!"=="11" goto PACKAGE
goto MENU

:ENV
cls
echo  ---- 环境检查 ----
echo.
echo  [Java / JDK]
where java >nul 2>&1
if errorlevel 1 (echo    [x] 未找到 java，请安装 JDK 17+ 并加入 PATH) else (java -version 2>&1 | findstr /i "version")
echo.
echo  [Maven]
where mvn >nul 2>&1
if errorlevel 1 (echo    [x] 未找到 mvn，请安装 Maven 并加入 PATH) else (call mvn -version 2>nul | findstr /i "Apache Maven")
echo.
echo  [Android SDK]
set "SDK=%ANDROID_HOME%"
if not defined SDK set "SDK=%ROOT%\sdk"
if exist "%SDK%\platforms\android-34" (echo    [v] %SDK%) else (echo    [x] 未找到 Android SDK: %SDK%  需 platforms\android-34)
echo.
echo  [ADB]
if exist "%ADB%" ("%ADB%" version | findstr /i "Android Debug Bridge") else (echo    [x] 未找到 %ADB%)
echo.
echo  [Gradle Wrapper]
if exist "%AND%\gradlew.bat" (echo    [v] %AND%\gradlew.bat) else (echo    [x] 缺少 %AND%\gradlew.bat)
echo.
echo  [PC 服务端 jar]
if exist "%SRV%\target\phone-console-pc.jar" (echo    [v] 已构建) else (echo    [.] 未构建，可用菜单 [8] 构建)
echo.
pause
goto MENU

:VDD_INSTALL
cls
echo  ---- 安装虚拟屏幕驱动（需管理员）----
echo.
net session >nul 2>&1
if errorlevel 1 (
    echo 当前非管理员，正在弹出 UAC 提权窗口...
    powershell -NoProfile -Command "Start-Process powershell.exe -Verb RunAs -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-NoExit','-File','%DRV%\install-vdd.ps1'"
) else (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%DRV%\install-vdd.ps1"
    pause
)
goto MENU

:VDD_BIND
cls
echo  ---- 绑定/刷新虚拟屏幕驱动（需管理员）----
echo.
net session >nul 2>&1
if errorlevel 1 (
    echo 当前非管理员，正在弹出 UAC 提权窗口...
    powershell -NoProfile -Command "Start-Process powershell.exe -Verb RunAs -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-NoExit','-File','%DRV%\bind-vdd.ps1'"
) else (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%DRV%\bind-vdd.ps1"
    pause
)
goto MENU

:BLD_DEBUG
cls
echo  ---- 构建 Debug APK ----
echo.
pushd "%AND%"
call gradlew.bat --no-daemon assembleDebug
set "RC=%errorlevel%"
popd
if "%RC%"=="0" (
    if not exist "%DIST%" mkdir "%DIST%"
    copy /y "%AND%\app\build\outputs\apk\debug\*.apk" "%DIST%\" >nul
    echo  [完成] APK 已输出到 %DIST%
) else (
    echo  [失败] 构建失败，请查看上方日志。
)
pause
goto MENU

:BLD_RELEASE
cls
echo  ---- 构建 Release APK ----
echo.
if not exist "%AND%\keystore.properties" echo  [提示] 未配置 keystore.properties，产物将是未签名包。
echo.
pushd "%AND%"
call gradlew.bat --no-daemon assembleRelease
set "RC=%errorlevel%"
popd
if "%RC%"=="0" (
    if not exist "%DIST%" mkdir "%DIST%"
    copy /y "%AND%\app\build\outputs\apk\release\*.apk" "%DIST%\" >nul
    echo  [完成] APK 已输出到 %DIST%
) else (
    echo  [失败] 构建失败，请查看上方日志。
)
pause
goto MENU

:ADB_INSTALL
cls
echo  ---- 安装 APK 到手机 ----
echo.
if not exist "%ADB%" (
    echo  [失败] 找不到 %ADB%
    pause
    goto MENU
)
"%ADB%" devices
echo.
set "SEL="
set /p "SEL=输入 APK 路径（直接回车 = 使用 dist 中最新的 release 包）: "
if "!SEL!"=="" (
    for /f "delims=" %%f in ('dir /b /o-d "%DIST%\*release*.apk" 2^>nul') do (
        if not defined SEL set "SEL=%DIST%\%%f"
    )
)
if not defined SEL (
    echo  [失败] 未找到可安装的 APK。
    pause
    goto MENU
)
echo.
echo 正在安装: !SEL!
"%ADB%" install -r "!SEL!"
pause
goto MENU

:ADB_DEVICES
cls
echo  ---- ADB 设备列表 ----
echo.
if not exist "%ADB%" (
    echo  [失败] 找不到 %ADB%
) else (
    "%ADB%" devices -l
)
pause
goto MENU

:SRV_BUILD
cls
echo  ---- 构建 PC 服务端 jar ----
echo.
pushd "%SRV%"
call mvn -q -DskipTests package
set "RC=%errorlevel%"
popd
if "%RC%"=="0" (echo  [完成] %SRV%\target\phone-console-pc.jar) else (echo  [失败] 请检查 Maven / JDK 环境。)
pause
goto MENU

:SRV_RUN
cls
call "%SRV%\run.cmd"
goto MENU

:OPEN_DIST
if not exist "%DIST%" mkdir "%DIST%"
start "" explorer "%DIST%"
goto MENU

:PACKAGE
cls
echo  ---- 打包分享 ----
echo.
set "OUT=%ROOT%\分享包.zip"
powershell -NoProfile -Command "$src=@('android-client','pc-server','drivers','platform-tools','dist','工具箱.cmd') | ForEach-Object { Join-Path '%ROOT%' $_ } | Where-Object { Test-Path -LiteralPath $_ }; if ($src) { Compress-Archive -LiteralPath $src -DestinationPath '%OUT%' -Force -CompressionLevel Optimal; Write-Host ('[完成] ' + '%OUT%') } else { Write-Host '[失败] 没有可打包的内容' }"
echo.
echo 该文件包含源代码、工具、驱动与产物，可直接发给其他电脑使用。
echo 复制到新电脑后，重新运行 环境检查 确认 JDK / Maven / Android SDK 即可。
echo.
pause
goto MENU
