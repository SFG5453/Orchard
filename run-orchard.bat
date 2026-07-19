@echo off
cd /d "%~dp0"
call npm install
if errorlevel 1 exit /b %errorlevel%
call npm run start
