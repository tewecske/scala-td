@echo off
rem Opens the simulator window: set up a start point (level, unlocked towers,
rem talents, draft picks, lives lost), let a bot play it and save it as a setup file.
pushd "%~dp0.."
call sbt -batch "editor/runMain td.SimulatorApp"
set RESULT=%ERRORLEVEL%
popd
exit /b %RESULT%
