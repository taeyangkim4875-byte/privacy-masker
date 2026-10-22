@echo off
rem Double-click or run from cmd. Passes options through: run.cmd -NoDb / run.cmd -SkipUi
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run.ps1" %*
