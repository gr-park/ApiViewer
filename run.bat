@echo off
chcp 65001 >nul 2>&1
title API Viewer

:: ApiViewer 실행 스크립트 (Windows)
:: 빌드 후 실행: run.bat
:: 빌드 없이 실행: run.bat --no-build

set JAR=target\api-viewer-1.0.0.jar

cd /d "%~dp0"

:: 기본은 빌드 없이 실행 (운영 환경 — 빌드 환경 미지원)
:: 빌드까지 같이 하려면: run.bat --build
if not "%1"=="--build" goto skip_build

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
    echo [ERROR] JAR 파일이 없습니다: %JAR%
    echo         run.bat         : JAR만 실행 (기본)
    echo         run.bat --build : 빌드 후 실행
    pause
    exit /b 1
)

echo.
echo ================================================
echo   API Viewer 시작
echo ------------------------------------------------
echo   [대시보드]      http://localhost/
echo   [URL분석현황]   http://localhost/viewer.html
echo   [URL호출현황]   http://localhost/call-stats.html
echo   [현업검토]     http://localhost/review.html
echo   [설정]         http://localhost/settings.html
echo   [URL분석추출]  http://localhost/extract.html
echo ------------------------------------------------
echo   [H2 콘솔]      http://localhost/h2-console
echo     JDBC URL    : jdbc:h2:file:./data/api-viewer-db
echo     User / Pass : sa / (없음)
echo ================================================
echo   종료: Ctrl+C
echo ================================================
echo.

java -Djava.net.preferIPv4Stack=true -Dfile.encoding=UTF-8 -jar "%JAR%"