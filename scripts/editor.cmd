@echo off
rem Opens the config editor (a separate window, not the game).
rem Save writes config.json in the repo folder, which the game loads at start and on R.
pushd "%~dp0.."
call sbt -batch editor/run
set RESULT=%ERRORLEVEL%
popd
exit /b %RESULT%
