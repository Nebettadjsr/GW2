@echo off
rem Starts the Python workflow service in its dedicated worktree.
setlocal
set "SERVICE_DIR=%USERPROFILE%\.gw2-workflow-service"
set "PREVIOUS_DIR=%USERPROFILE%\.gw2-claude-bridge"
if not defined GW2_WORKFLOW_TOKEN if defined GW2_BRIDGE_TOKEN set "GW2_WORKFLOW_TOKEN=%GW2_BRIDGE_TOKEN%"
if not defined GW2_WORKFLOW_TOKEN (
    if exist "%SERVICE_DIR%\token.txt" (
        set /p GW2_WORKFLOW_TOKEN=<"%SERVICE_DIR%\token.txt"
    ) else if exist "%PREVIOUS_DIR%\token.txt" (
        set /p GW2_WORKFLOW_TOKEN=<"%PREVIOUS_DIR%\token.txt"
    ) else (
        echo Create a token using workflow_service\README.md before starting the service.
        exit /b 1
    )
)
if not defined GW2_WORKFLOW_HOST if defined GW2_BRIDGE_HOST set "GW2_WORKFLOW_HOST=%GW2_BRIDGE_HOST%"
if not defined GW2_WORKFLOW_HOST set "GW2_WORKFLOW_HOST=172.29.240.1"
rem service.py accepts the previous state setting/path without copying it.
if not defined GW2_AGENT_WORKTREE set "GW2_AGENT_WORKTREE=%~dp0..\..\GW2-agent"
if not exist "%GW2_AGENT_WORKTREE%\workflow_service\control.py" (
    echo The agent worktree does not contain the workflow service yet. Deploy this change before starting it.
    exit /b 1
)
cd /d "%GW2_AGENT_WORKTREE%"
echo Workflow service repository: %CD%
:run
if /i "%GW2_WORKFLOW_DRIVER%"=="legacy" (
    python -m workflow_service.service
) else (
    python -m workflow_service.control %*
)
rem Exit code 3 requests a restart after merged Python workflow changes.
if errorlevel 3 if not errorlevel 4 goto run
exit /b %errorlevel%
