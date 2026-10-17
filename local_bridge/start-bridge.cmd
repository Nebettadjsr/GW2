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
rem The pipeline runs in its own worktree on its own branch (README, "Branches"),
rem so this script starts the bridge from there whichever checkout it is run from.
if not defined GW2_AGENT_WORKTREE set "GW2_AGENT_WORKTREE=%~dp0..\..\GW2-agent"
if exist "%GW2_AGENT_WORKTREE%\local_bridge\bridge.py" (cd /d "%GW2_AGENT_WORKTREE%") else (cd /d "%~dp0..")
echo Bridge repository: %CD%
if /i "%GW2_WORKFLOW_DRIVER%"=="python" if not exist "local_bridge\run_cycle.py" (
    echo This agent worktree does not contain the Python runner yet. Merge the workflow change before starting it.
    exit /b 1
)
:run
if /i "%GW2_WORKFLOW_DRIVER%"=="python" (
    python -m local_bridge.run_cycle %*
) else (
    python local_bridge\bridge.py
)
rem Exit code 3: the bridge merged new workflow code and asks to be started again.
if errorlevel 3 if not errorlevel 4 goto run
