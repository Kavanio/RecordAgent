@echo off
setlocal
chcp 65001 >nul
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0backend.ps1" -Action Start
set "BACKEND_RESULT=%ERRORLEVEL%"
if not "%BACKEND_RESULT%"=="0" echo Operation failed. Read the message above.
pause
exit /b %BACKEND_RESULT%
