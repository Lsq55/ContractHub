@echo off
setlocal
cd /d "%~dp0"
title Qiheng Contract Hub
if not exist "%~dp0data" mkdir "%~dp0data"
docker version >nul 2>&1
if errorlevel 1 (echo Docker Desktop is not running.&pause&exit /b 1)
docker inspect qiheng-postgres >nul 2>&1
if errorlevel 1 docker run -d --name qiheng-postgres -e POSTGRES_DB=qiheng -e POSTGRES_USER=qiheng -e POSTGRES_PASSWORD=QhPostgres-2026-Strong -p 127.0.0.1:5432:5432 -v qiheng-postgres:/var/lib/postgresql/data postgres:16
if not errorlevel 1 docker start qiheng-postgres >nul 2>&1
rem Wait for a real connection to the qiheng database: while the postgres image runs
rem its first-time initdb, the temporary server answers pg_isready but the database
rem does not exist yet, and the app would fail to start.
for /l %%i in (1,1,90) do (docker exec -e PGCLIENTENCODING=UTF8 qiheng-postgres psql -U qiheng -d qiheng -c "SELECT 1" >nul 2>&1 && goto dbready&timeout /t 1 /nobreak >nul)
echo PostgreSQL startup timeout.&pause&exit /b 1
:dbready
if not exist "%~dp0target\ContractHub-1.0.0.jar" (echo target\ContractHub-1.0.0.jar not found.&pause&exit /b 1)
set DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/qiheng
set DATABASE_USERNAME=qiheng
set DATABASE_PASSWORD=QhPostgres-2026-Strong
set STORAGE_ROOT=%~dp0data\files
set "PORT=8080"
if not defined LIBREOFFICE_PATH set "LIBREOFFICE_PATH=C:\Program Files\LibreOffice\program\soffice.exe"
curl.exe -fsS http://127.0.0.1:8080/api/v1/health >nul 2>&1
if errorlevel 1 start "Qiheng" /min java -jar "%~dp0target\ContractHub-1.0.0.jar"
for /l %%i in (1,1,45) do (curl.exe -fsS http://127.0.0.1:8080/api/v1/health >nul 2>&1 && goto ready&timeout /t 1 /nobreak >nul)
echo Java startup failed. See data\application.log.&pause&exit /b 1
:ready
start "" "http://127.0.0.1:8080/"
exit /b 0
