@echo off
chcp 65001 >nul 2>&1
title API Viewer

:: ApiViewer 실행 스크립트 (Windows)
:: 빌드 후 실행: run.bat
:: 빌드 없이 실행: run.bat --no-build

set JAR=target\api-viewer-1.0.0.jar

cd /d "%~dp0"

if "%1"=="--no-build" goto skip_build

echo [INFO] 빌드 중...
if exist mvnw.cmd (
    call mvnw.cmd -q package -DskipTests
) else (
    call mvn -q package -DskipTests
)
if errorlevel 1 (
    echo [ERROR] 빌드 실패. 로그를 확인하세요.
    pause
    exit /b 1
)

:skip_build
if not exist "%JAR%" (
    echo [ERROR] JAR 파일이 없습니다.
    echo         run.bat           : 빌드 후 실행
    echo         run.bat --no-build: JAR만 실행
    pause
    exit /b 1
)

echo.
echo ================================================
echo   API Viewer 시작
echo ------------------------------------------------
echo   웹         : http://localhost
echo   분석현황   : http://localhost/viewer.html
echo   설정       : http://localhost/settings.html
echo ================================================
echo.
echo 종료: Ctrl+C
echo.

java -Djava.net.preferIPv4Stack=true -Dfile.encoding=UTF-8 -jar "%JAR%"

pause
