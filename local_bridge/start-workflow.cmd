@echo off
rem Uses the same worktree, credentials, state and restart handling as the bridge.
setlocal
set "GW2_WORKFLOW_DRIVER=python"
call "%~dp0start-bridge.cmd" %*
exit /b %errorlevel%
