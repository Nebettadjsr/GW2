@echo off
rem Starts the tiny launcher (port 8766) that lets the Grafana "Open workflow window" button start the service.
rem Put a shortcut to this file in shell:startup to have it available after every login.
setlocal
if not defined GW2_AGENT_WORKTREE set "GW2_AGENT_WORKTREE=%~dp0..\..\GW2-agent"
cd /d "%GW2_AGENT_WORKTREE%"
python -m workflow_service.supervisor
