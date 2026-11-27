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
echo   -- 虚拟摄像头 --
echo   [12] 注册虚拟摄像头驱动    (需管理员)
echo   [13] 卸载虚拟摄像头驱动    (需管理员)
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
echo   [14] 静默后台启动（无黑框，缩进系统托盘）
echo   [15] 绑定全局启动快捷键（如 Ctrl+Alt+P）
echo.
echo   -- 共享 --
echo   [10] 打开发布目录 dist
echo   [11] 打包为 zip
echo   [0]  退出
echo  ==========================================================
set "CH="
set /p "CH=请选择 [0-15]: "

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
if "!CH!"=="12" goto SOFTCAM_INSTALL
if "!CH!"=="13" goto SOFTCAM_UNINSTALL
if "!CH!"=="14" goto SRV_SILENT
if "!CH!"=="15" goto SRV_SHORTCUT
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

:SOFTCAM_INSTALL
cls
echo  ---- 注册虚拟摄像头驱动（需管理员）----
echo.
net session >nul 2>&1
if errorlevel 1 (
    echo 当前非管理员，正在弹出 UAC 提权窗口...
    powershell -NoProfile -Command "Start-Process powershell.exe -Verb RunAs -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-NoExit','-File','%DRV%\install-softcam.ps1'"
) else (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%DRV%\install-softcam.ps1"
    pause
)
goto MENU

:SOFTCAM_UNINSTALL
cls
echo  ---- 卸载虚拟摄像头驱动（需管理员）----
echo.
net session >nul 2>&1
if errorlevel 1 (
    echo 当前非管理员，正在弹出 UAC 提权窗口...
    powershell -NoProfile -Command "Start-Process powershell.exe -Verb RunAs -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-NoExit','-File','%DRV%\uninstall-softcam.ps1'"
) else (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%DRV%\uninstall-softcam.ps1"
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

:SRV_SILENT
cls
echo  ---- 静默后台启动 PC 服务端 ----
echo.
wscript.exe "%SRV%\launch-silent.vbs"
echo  [完成] 已在后台启动（若服务已在运行，则直接打开管理网页）。
echo  可在屏幕右下角系统托盘找到「手机控制台 PC 服务端」图标。
echo.
pause
goto MENU

:SRV_SHORTCUT
cls
echo  ---- 绑定全局启动快捷键 ----
echo.
powershell -NoProfile -ExecutionPolicy Bypass -Command "& { $raw = & '%SRV%\bind-shortcut.ps1' -Action status; $r = $raw | ConvertFrom-Json; if ($r.bound) { Write-Host ('  当前已绑定快捷键: ' + $r.hotkey) } else { Write-Host '  当前状态: 尚未绑定快捷键' }; if ($r.desktopShortcut) { Write-Host '  桌面快捷方式: 已生成' }; if ($r.autoStart) { Write-Host '  开机静默自启: 已开启' } }"
echo.
echo  说明: 绑定后在桌面按组合键即可直接无黑框启动服务；输入 unbind 可解除绑定。
set "HK="
set /p "HK=请输入快捷键（直接回车默认 Ctrl+Alt+P，输入 unbind 解除）: "
if /i "!HK!"=="unbind" goto SRV_SHORTCUT_UNBIND
if not defined HK set "HK=Ctrl+Alt+P"
set "DESK="
set /p "DESK=是否同时创建桌面快捷方式？[y/N]: "
set "AUTO="
set /p "AUTO=是否开启开机自动静默启动？[y/N]: "
set "DESK_FLAG="
if /i "!DESK!"=="y" set "DESK_FLAG=-Desktop"
set "AUTO_FLAG=-AutoStartMode disable"
if /i "!AUTO!"=="y" set "AUTO_FLAG=-AutoStartMode enable"
echo.
powershell -NoProfile -ExecutionPolicy Bypass -Command "& { $raw = & '%SRV%\bind-shortcut.ps1' -Action bind -Hotkey '!HK!' !DESK_FLAG! !AUTO_FLAG!; $r = $raw | ConvertFrom-Json; if ($r.ok) { Write-Host ('  [成功] 已绑定全局快捷键: ' + $r.hotkey); Write-Host ('  开始菜单捷径: ' + $r.startMenuPath) } else { Write-Host ('  [失败] ' + $r.error) } }"
echo.
pause
goto MENU

:SRV_SHORTCUT_UNBIND
echo.
powershell -NoProfile -ExecutionPolicy Bypass -Command "& { $raw = & '%SRV%\bind-shortcut.ps1' -Action unbind -RemoveDesktop -AutoStartMode disable; $r = $raw | ConvertFrom-Json; if ($r.ok) { Write-Host '  [成功] 已解除启动快捷键及相关快捷方式。' } else { Write-Host ('  [失败] ' + $r.error) } }"
echo.
pause
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
powershell -NoProfile -Command "$ErrorActionPreference='Stop'; $root='%ROOT%'; $stage=Join-Path $env:TEMP ('phone-console-pkg-' + [guid]::NewGuid().ToString('N').Substring(0,8)); New-Item -ItemType Directory -Path $stage | Out-Null; try { $dirs=@('android-client','pc-server','drivers','platform-tools','dist'); foreach ($d in $dirs) { $src=Join-Path $root $d; if (-not (Test-Path -LiteralPath $src)) { continue }; $dst=Join-Path $stage $d; & robocopy $src $dst /E /XD .gradle .idea build target keystore /XF local.properties keystore.properties config.json '*.log' '*.lnk' /NFL /NDL /NJH /NJS /NC /NS /NP | Out-Null; if ($LASTEXITCODE -ge 8) { throw ('robocopy failed: ' + $d) } }; if (Test-Path -LiteralPath (Join-Path $root '工具箱.cmd')) { Copy-Item -LiteralPath (Join-Path $root '工具箱.cmd') -Destination $stage }; if (Test-Path -LiteralPath '%OUT%') { Remove-Item -LiteralPath '%OUT%' -Force }; Compress-Archive -Path (Join-Path $stage '*') -DestinationPath '%OUT%' -Force -CompressionLevel Optimal; Write-Host ('[完成] ' + '%OUT%') } finally { if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force -ErrorAction SilentlyContinue } }"
echo.
echo 该文件包含源代码、工具、驱动与产物，可直接发给其他电脑使用。
echo 复制到新电脑后，重新运行 环境检查 确认 JDK / Maven / Android SDK 即可。
echo.
pause
goto MENU
