@echo off
rem HANARE demo: BE (profile demo, PostgreSQL nhung, khong can Docker, cong 8080) o cua so rieng, roi FE (cong 5173).
rem Tai khoan: xem http://localhost:8080/api/v1/demo/accounts (mat khau chung Demo@1234). Dong cua so "HANARE BE demo" de tat BE.
setlocal
cd /d "%~dp0"

start "HANARE BE demo" /D "%~dp0BE" cmd /c ".\mvnw.cmd -B spring-boot:test-run & pause"

echo Dang cho BE nap du lieu mau (lan dau co the mat 1-2 phut)...
set /a tries=0
:wait
curl -s -f -o nul http://localhost:8080/api/v1/demo/accounts && goto ready
set /a tries+=1
if %tries% GEQ 90 (
  echo BE chua san sang sau 3 phut - xem cua so "HANARE BE demo". Van mo FE.
  goto fe
)
timeout /t 2 /nobreak >nul
goto wait

:ready
echo BE san sang.
:fe
cd /d "%~dp0FE"
if not exist node_modules call npm install
call npm run dev
