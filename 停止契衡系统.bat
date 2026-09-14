@echo off
rem NOTE: keep this file ASCII-only (see the launcher script for the reason).
setlocal
echo Stopping Qiheng Contract Hub...
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":8080 .*LISTENING"') do taskkill /PID %%p /T /F >nul 2>&1
echo Application stopped. The PostgreSQL container is kept running to protect data.
pause
