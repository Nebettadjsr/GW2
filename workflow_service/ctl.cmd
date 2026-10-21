@echo off
rem Operator commands for the workflow service: ctl status|pause|resume|start|abort|auto|manual|kill-leftovers
setlocal
if not defined GW2_AGENT_WORKTREE set "GW2_AGENT_WORKTREE=%~dp0..\..\GW2-agent"
cd /d "%GW2_AGENT_WORKTREE%"
python -m workflow_service.ctl %*
exit /b %errorlevel%
