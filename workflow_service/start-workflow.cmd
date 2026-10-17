@echo off
rem Compatibility entry point; start-service.cmd starts the complete workflow.
setlocal
call "%~dp0start-service.cmd" %*
exit /b %errorlevel%
