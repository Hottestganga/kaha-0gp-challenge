@echo off
setlocal
cd /d "%~dp0"
if not exist out mkdir out

echo Compiling 0GP Race local multiplayer server...
javac --release 11 -d out LocalRaceServer.java
if errorlevel 1 (
  echo.
  echo Server compile failed. Make sure JDK 11 is installed and javac is available.
  pause
  exit /b 1
)

echo.
echo Starting server. Keep this window open while testing.
echo.
java -cp out LocalRaceServer
pause
