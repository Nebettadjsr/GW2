@echo off
rem Starts the n8n pipeline bridge with its settings; see local_bridge/README.md.
rem The token stays outside the repository in %USERPROFILE%\.gw2-claude-bridge\token.txt.
setlocal
set "SECRET_DIR=%USERPROFILE%\.gw2-claude-bridge"
if not exist "%SECRET_DIR%\token.txt" (
    echo Missing %SECRET_DIR%\token.txt. Create it with the PowerShell block in local_bridge\README.md.
    exit /b 1
)
set /p GW2_BRIDGE_TOKEN=<"%SECRET_DIR%\token.txt"
if not defined GW2_BRIDGE_HOST set "GW2_BRIDGE_HOST=172.29.240.1"
set "GW2_BRIDGE_STATE=%SECRET_DIR%\tasks.json"
set "GW2_N8N_CALLBACK_ORIGIN=http://localhost:5678"
cd /d "%~dp0.."
python local_bridge\bridge.py
