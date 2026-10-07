@echo off
REM Keep the Soda Music daemon connected to the relay.
REM
REM Configure through environment variables or by editing the values below:
REM   RELAY_AGENT_URL   wss:// URL of the relay's agent endpoint
REM   SODA_SHARED_SECRET  shared secret, must match the relay's relay.env
REM
REM Paths are resolved from the script's own location, so the package runs from any directory.
setlocal
cd /d "%~dp0"

if "%RELAY_AGENT_URL%"=="" set RELAY_AGENT_URL=ws://127.0.0.1:8765/agent
if "%NODE_NAME%"=="" set NODE_NAME=home-pc

:loop
echo [%date% %time%] starting daemon >> daemon.log
python soda_daemon.py --relay "%RELAY_AGENT_URL%" --node "%NODE_NAME%" >> daemon.log 2>&1
echo [%date% %time%] daemon exited, restarting in 10s >> daemon.log
timeout /t 10 /nobreak >nul
goto loop
