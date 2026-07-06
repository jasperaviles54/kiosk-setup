@echo off
REM Double-click to open the Kiosk Manager window.
powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "%~dp0KioskManager.ps1"
