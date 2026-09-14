@echo off
rem NOTE: keep this file ASCII-only. cmd.exe parses .bat with the OEM codepage (936),
rem so UTF-8 Chinese text swallows the following characters and breaks commands.
setlocal
cd /d "%~dp0"
title Qiheng Contract Hub

echo [1/3] Checking Docker Desktop...
docker version >nul 2>&1
if errorlevel 1 (
  echo Docker Desktop is not running. Please start Docker Desktop first.
  pause
  exit /b 1
)

echo [2/3] Starting PostgreSQL...
rem Use "docker inspect" (not --filter with ^$ + findstr): the regex form is fragile in .bat quoting.
docker inspect qiheng-postgres >nul 2>&1
if errorlevel 1 (
  docker run -d --name qiheng-postgres ^
    -e POSTGRES_DB=qiheng ^
    -e POSTGRES_USER=qiheng ^
    -e POSTGRES_PASSWORD=QhPostgres-2026-Strong ^
    -p 127.0.0.1:5432:5432 ^
    -v qiheng-postgres:/var/lib/postgresql/data ^
    postgres:16
  if errorlevel 1 (
    echo Failed to create the PostgreSQL container.
    pause
    exit /b 1
  )
) else (
  docker start qiheng-postgres >nul 2>&1
)
rem Wait for a real connection to the qiheng database. Do not use pg_isready alone:
rem while the postgres image runs its first-time initdb, the temporary server answers
rem pg_isready but the "qiheng" database does not exist yet, so the app would exit.
for /l %%i in (1,1,90) do (
  docker exec -e PGCLIENTENCODING=UTF8 qiheng-postgres psql -U qiheng -d qiheng -c "SELECT 1" >nul 2>&1
  if not errorlevel 1 goto database_ready
  timeout /t 1 /nobreak >nul
)
echo PostgreSQL startup timeout. Please check Docker Desktop.
pause
exit /b 1

:database_ready
echo PostgreSQL is ready.
echo [3/3] Starting Qiheng Contract Hub...
set DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/qiheng
set DATABASE_USERNAME=qiheng
set DATABASE_PASSWORD=QhPostgres-2026-Strong
set STORAGE_ROOT=%~dp0data\files
set SECURE_COOKIE=false
rem Pin the port: the health checks and the browser URL below are hardcoded to 8080.
rem Without this, a stray PORT environment variable would move the app and break startup detection.
set "PORT=8080"
rem LibreOffice is required for DOCX to PDF conversion. Set LIBREOFFICE_PATH yourself
rem to override this default (for example on the intranet server).
if not defined LIBREOFFICE_PATH set "LIBREOFFICE_PATH=C:\Program Files\LibreOffice\program\soffice.exe"
if not exist "%~dp0data" mkdir "%~dp0data"
if not exist "%~dp0target\ContractHub-1.0.0.jar" (
  echo target\ContractHub-1.0.0.jar not found. Please run "mvn package" first.
  pause
  exit /b 1
)
curl.exe -fsS http://127.0.0.1:8080/api/v1/health >nul 2>&1
if errorlevel 1 start "Qiheng" /min cmd /c "set DATABASE_URL=%DATABASE_URL%&& set DATABASE_USERNAME=%DATABASE_USERNAME%&& set DATABASE_PASSWORD=%DATABASE_PASSWORD%&& set STORAGE_ROOT=%STORAGE_ROOT%&& set SECURE_COOKIE=%SECURE_COOKIE%&& set LIBREOFFICE_PATH=%LIBREOFFICE_PATH%&& java -jar "%~dp0target\ContractHub-1.0.0.jar" > "%~dp0data\application.log" 2>&1"
for /l %%i in (1,1,45) do (
  curl.exe -fsS http://127.0.0.1:8080/api/v1/health >nul 2>&1
  if not errorlevel 1 goto app_ready
  timeout /t 1 /nobreak >nul
)
echo Startup timed out. Check data\application.log or whether port 8080 is already in use.
pause
exit /b 1
:app_ready
start "" "http://127.0.0.1:8080/"
echo System started. Opening browser.
exit /b 0
