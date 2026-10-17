@echo off
rem Packages the game for Windows into target\dist (see project\Dist.scala).
rem Run from a plain cmd window. Needs JDK 17+ (with jpackage) and sbt on the PATH.
pushd "%~dp0.."
call sbt -batch dist
set RESULT=%ERRORLEVEL%
popd
exit /b %RESULT%
