@echo off
cd /d "%~dp0"
node --env-file=.env src/index.mjs
if errorlevel 1 pause
