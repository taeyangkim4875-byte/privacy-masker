@echo off
rem Double-click or run from cmd. Options pass through: run.cmd -NoDb / run.cmd -SkipUi
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run.ps1" %*
if errorlevel 1 (
  echo.
  echo [x] Stopped with an error. Read the message above.
)
echo.
pause
