@echo off
chcp 65001
cls

set "TOOL_DIR=%~dp0tools"
set "JAVA_BIN=%TOOL_DIR%\jdk\bin\java.exe"
set "PATH=%TOOL_DIR%\jdk\bin;%PATH%"

set "JAR_FILE=%~dp0api-viewer-1.0.0.jar"
set "JAVA_OPTS=-Xms2g -Xmx4g -XX:+UseG1GC -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"

echo ====================
echo [RUN] api-viewer v1.0.0
echo [JAR] %JAR_FILE%
echo ====================

"%JAVA_BIN%" %JAVA_OPTS% -jar "%JAR_FILE%"

echo.
echo 완료되었습니다.
pause
