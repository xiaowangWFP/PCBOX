@echo off
chcp 65001 >nul
title 手机控制台 - 停止服务

echo 正在查找运行中的服务端实例（8500 / 8501）...
powershell -NoProfile -Command "$pids = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -in 8500,8501 } | Select-Object -ExpandProperty OwningProcess -Unique; if ($pids) { foreach ($id in $pids) { Write-Host ('结束进程 PID ' + $id); Stop-Process -Id $id -Force -ErrorAction SilentlyContinue }; Start-Sleep -Seconds 2; $left = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -in 8500,8501 }; if ($left) { Write-Host '停止失败，仍有端口被占用' -ForegroundColor Red } else { Write-Host '服务已停止' -ForegroundColor Green } } else { Write-Host '没有正在运行的服务端实例' }"

echo.
ping -n 5 127.0.0.1 >nul
