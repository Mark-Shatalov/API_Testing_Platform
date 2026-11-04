@echo off
rem Builds the project (tests skipped) and starts the JavaFX app from source with the Maven Wrapper.
setlocal
cd /d "%~dp0"
if not defined JAVA_HOME for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\jdk-25*") do set "JAVA_HOME=%%~fD"
echo Using JAVA_HOME=%JAVA_HOME%
call mvnw.cmd -B --no-transfer-progress -DskipTests install
if errorlevel 1 goto failed
call mvnw.cmd -B --no-transfer-progress -f app\pom.xml javafx:run
if errorlevel 1 goto failed
exit /b 0
:failed
echo.
echo Build or launch failed. See the messages above.
pause
exit /b 1
